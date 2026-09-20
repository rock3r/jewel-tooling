package dev.sebastiano.jewel.tooling

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import dev.sebastiano.jewel.tooling.mcp.bootstrap.Discovery
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.types.Variance

internal class McpAnalysisFacade(
    private val project: Project,
    private val projectId: String,
    private val generation: String,
    private val root: Path,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val isEnabled: () -> Boolean,
) {
    suspend fun inspect(tool: String, arguments: JsonObject): McpSnapshot {
        ready()
        val requested = arguments.string("file") ?: throw McpFailure("INVALID_ARGUMENT")
        val path = withContext(ioDispatcher) { resolveFile(requested) }
        val canonicalPath = root.relativize(path).joinToString("/") { it.toString() }
        val document = loadDocument(path)
        withContext(Dispatchers.EDT) {
            ready()
            val manager = PsiDocumentManager.getInstance(project)
            if (!manager.isCommitted(document)) manager.commitDocument(document)
        }
        val snapshot = readAction {
            ready()
            val manager = PsiDocumentManager.getInstance(project)
            McpSourceInput.require(manager.isCommitted(document), "STALE_LOCATION")
            val psi =
                manager.getPsiFile(document) as? KtFile ?: throw McpFailure("UNSUPPORTED_FILE")
            McpSourceInput.require(psi.isValid && !psi.isCompiled, "UNSUPPORTED_FILE")
            snapshot(psi, document, canonicalPath, tool, arguments)
        }
        validateRevision(snapshot, document)
        return snapshot
    }

    private suspend fun loadDocument(path: Path): Document {
        val file =
            LocalFileSystem.getInstance().findFileByNioFile(path)
                ?: throw McpFailure("UNSUPPORTED_FILE")
        return readAction {
            ready()
            McpSourceInput.require(
                ProjectFileIndex.getInstance(project).isInContent(file),
                "OUTSIDE_PROJECT",
            )
            val document =
                FileDocumentManager.getInstance().getDocument(file)
                    ?: throw McpFailure("UNSUPPORTED_FILE")
            McpSourceInput.require(document.textLength <= MAX_BYTES, "LIMIT_EXCEEDED")
            document
        }
    }

    private suspend fun validateRevision(snapshot: McpSnapshot, document: Document) = readAction {
        ready()
        McpSourceInput.require(
            snapshot.documentRevision == document.modificationStamp.toString() &&
                snapshot.psiRevision ==
                    PsiModificationTracker.getInstance(project).modificationCount.toString(),
            "STALE_LOCATION",
        )
    }

    private fun resolveFile(requested: String): Path {
        val relative = Path.of(requested)
        McpSourceInput.require(
            !relative.isAbsolute &&
                relative.none { it.toString() == ".." } &&
                requested.endsWith(".kt"),
            "UNSUPPORTED_FILE",
        )
        val path = root.resolve(relative).toRealPath()
        McpSourceInput.require(path.startsWith(root), "OUTSIDE_PROJECT")
        McpSourceInput.require(Files.isRegularFile(path), "UNSUPPORTED_FILE")
        McpSourceInput.require(Files.size(path) <= MAX_BYTES, "LIMIT_EXCEEDED")
        return path
    }

    private fun snapshot(
        file: KtFile,
        document: Document,
        path: String,
        tool: String,
        args: JsonObject,
    ): McpSnapshot {
        val hash = McpSourceInput.documentHash(document, args.string("expectedHash"))
        val fileHash = Discovery.digest(path.toByteArray(Charsets.UTF_8))
        val prefix = "$generation:$fileHash:$hash:"
        val selection = McpSourceSelection.parse(document, prefix, tool, args)
        val builder = McpDeclarationBuilder(project, prefix, document, path)
        val declarations = mutableListOf<McpDeclaration>()
        var parameterCount = 0
        for (function in selection.functions(file)) {
            ProgressManager.checkCanceled()
            val report = StabilityAnalysis.inspect(function, navigation = true) ?: continue
            McpSourceInput.require(declarations.size < MAX_DECLARATIONS, "LIMIT_EXCEEDED")
            parameterCount += report.parameters.size
            McpSourceInput.require(parameterCount <= MAX_PARAMETERS, "LIMIT_EXCEEDED")
            declarations += builder.declaration(function, report)
        }
        val results = selection.results(declarations)
        return McpSnapshot(
            projectId,
            generation,
            path,
            document.modificationStamp.toString(),
            hash,
            PsiModificationTracker.getInstance(project).modificationCount.toString(),
            results,
            results.flatMap { it.parameters }.flatMap { it.incomplete }.distinct(),
        )
    }

    private fun ready() {
        ProgressManager.checkCanceled()
        if (!isEnabled() || project.isDisposed) throw McpFailure("PROJECT_UNAVAILABLE")
        if (DumbService.isDumb(project)) throw McpFailure("INDEXING")
    }

    companion object {
        const val MAX_BYTES = 1024 * 1024
        private const val MAX_DECLARATIONS = 128
        private const val MAX_PARAMETERS = 256
        val LIMITS =
            mapOf(
                "sourceBytes" to MAX_BYTES,
                "resultBytes" to MAX_BYTES,
                "declarations" to MAX_DECLARATIONS,
                "parameters" to MAX_PARAMETERS,
                "concurrency" to 2,
                "timeoutSeconds" to 10,
            )
    }
}

private class McpSourceSelection(
    private val prefix: String,
    private val id: String?,
    private val range: Pair<Int, Int>?,
    private val parameter: String?,
) {
    fun functions(file: KtFile): List<KtNamedFunction> {
        val all = PsiTreeUtil.collectElementsOfType(file, KtNamedFunction::class.java)
        val point = range?.takeIf { it.first == it.second }?.first
        val atPoint = point?.let { position ->
            all.filter { it.textRange.containsOffset(position) }.minByOrNull { it.textLength }
        }
        return all.filter { function ->
                ProgressManager.checkCanceled()
                when {
                    id != null -> id == "$prefix${function.textOffset}"
                    point != null -> function === atPoint
                    range != null ->
                        (function.nameIdentifier?.textOffset ?: function.textOffset) in
                            range.first until range.second
                    else -> true
                }
            }
            .sortedBy { it.textOffset }
    }

    fun results(declarations: List<McpDeclaration>): List<McpDeclaration> {
        McpSourceInput.require(id == null || declarations.isNotEmpty(), "STALE_LOCATION")
        if (parameter == null) return declarations
        val declaration = declarations.single()
        val selected =
            declaration.parameters.singleOrNull { it.name == parameter }
                ?: throw McpFailure("INVALID_ARGUMENT")
        return listOf(declaration.copy(parameters = listOf(selected)))
    }

    companion object {
        fun parse(
            document: Document,
            prefix: String,
            tool: String,
            args: JsonObject,
        ): McpSourceSelection {
            val id = args.string("declarationId")
            McpSourceInput.require(id == null || id.startsWith(prefix), "STALE_LOCATION")
            McpSourceInput.require(id == null || !args.has("range"), "INVALID_ARGUMENT")
            val parameter = if (tool == "jewel_explain") args.string("parameter") else null
            McpSourceInput.require(
                tool != "jewel_explain" || (id != null && parameter != null),
                "INVALID_ARGUMENT",
            )
            val range = McpSourceInput.range(document, args)
            return McpSourceSelection(prefix, id, range, parameter)
        }
    }
}

private class McpDeclarationBuilder(
    private val project: Project,
    private val prefix: String,
    private val document: Document,
    private val path: String,
) {
    fun declaration(function: KtNamedFunction, report: FunctionStability): McpDeclaration {
        val resolved = resolvedTypes(function)
        val parameters = report.parameters.map { hint -> parameter(function, hint, resolved) }
        return McpDeclaration(
            "$prefix${function.textOffset}",
            report.name,
            location(document, path, function.textRange.startOffset, function.textRange.endOffset),
            parameters,
            Stability.entries.associate { state ->
                state.name to parameters.count { it.stability == state.name }
            },
        )
    }

    @OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)
    private fun resolvedTypes(function: KtNamedFunction): Map<Int, String> =
        analyze(function) {
            val symbol = function.symbol as? KaNamedFunctionSymbol
            val types = mutableMapOf<Int, String>()
            function.receiverTypeReference?.let {
                types[it.textRange.endOffset] =
                    it.type.render(KaTypeRendererForSource.WITH_QUALIFIED_NAMES, Variance.INVARIANT)
            }
            if (symbol != null) {
                for ((parameter, parameterSymbol) in
                    function.valueParameters.zip(symbol.valueParameters)) {
                    parameter.typeReference?.let {
                        types[it.textRange.endOffset] =
                            parameterSymbol.returnType.render(
                                KaTypeRendererForSource.WITH_QUALIFIED_NAMES,
                                Variance.INVARIANT,
                            )
                    }
                }
            }
            types.toMap()
        }

    private fun parameter(
        function: KtNamedFunction,
        hint: ParameterHint,
        resolved: Map<Int, String>,
    ): McpParameter {
        ProgressManager.checkCanceled()
        val receiver =
            function.receiverTypeReference?.takeIf { it.textRange.endOffset == hint.offset }
        val source =
            receiver
                ?: function.valueParameters
                    .asSequence()
                    .mapNotNull { it.typeReference }
                    .firstOrNull { it.textRange.endOffset == hint.offset }
                ?: throw McpFailure("UNSUPPORTED_FILE")
        val assessment = hint.assessment
        return McpParameter(
            if (receiver != null) "<receiver>" else hint.name,
            hint.typeText,
            resolved[hint.offset] ?: "<unresolved>",
            location(document, path, source.textRange.startOffset, source.textRange.endOffset),
            assessment.stability.name,
            assessment.evidence.map { McpEvidence(it.name, provenance(it)) },
            listOf(
                McpReason(assessment.reasonCode, assessment.reason, evidenceLocations(assessment))
            ),
            incomplete(assessment),
        )
    }

    private fun evidenceLocations(assessment: StabilityAssessment): List<McpLocation> {
        val target = assessment.sourceTarget?.element ?: return emptyList()
        val targetFile = target.containingFile.virtualFile
        val index = ProjectFileIndex.getInstance(project)
        return if (
            targetFile != null &&
                (index.isInContent(targetFile) || index.isInLibrarySource(targetFile))
        ) {
            val targetDocument =
                PsiDocumentManager.getInstance(project).getDocument(target.containingFile)
            if (targetDocument == null) emptyList()
            else
                listOf(
                    location(
                        targetDocument,
                        targetFile.url,
                        target.textRange.startOffset,
                        target.textRange.endOffset,
                    )
                )
        } else emptyList()
    }

    private fun incomplete(assessment: StabilityAssessment): List<String> =
        when {
            assessment.stability != Stability.UNKNOWN -> emptyList()
            assessment.reasonCode == "reason.external" -> listOf("METADATA_UNAVAILABLE")
            assessment.reasonCode == "reason.unresolved" -> listOf("UNRESOLVED_TYPE")
            assessment.reasonCode == "reason.bounded" -> listOf("ANALYSIS_LIMIT")
            else -> listOf("UNSUPPORTED_EVIDENCE")
        }

    private fun provenance(evidence: Evidence): String =
        when (evidence) {
            Evidence.BUILTIN -> "builtin"
            Evidence.DECLARED_CONTRACT -> "resolved_annotation"
            Evidence.SOURCE -> "source_inference"
            Evidence.COMPILER_METADATA -> "compiler_metadata"
            Evidence.UNSUPPORTED -> "unknown"
        }

    private fun position(document: Document, offset: Int): McpPosition {
        val line = document.getLineNumber(offset)
        return McpPosition(line + 1, offset - document.getLineStartOffset(line) + 1)
    }

    private fun location(document: Document, path: String, start: Int, end: Int) =
        McpLocation(path, position(document, start), position(document, end))
}

private object McpSourceInput {
    fun require(condition: Boolean, code: String) {
        if (!condition) throw McpFailure(code)
    }

    fun documentHash(document: Document, expected: String?): String {
        require(document.textLength <= McpAnalysisFacade.MAX_BYTES, "LIMIT_EXCEEDED")
        val text = document.immutableCharSequence.toString().toByteArray(Charsets.UTF_8)
        require(text.size <= McpAnalysisFacade.MAX_BYTES, "LIMIT_EXCEEDED")
        val hash = Discovery.digest(text)
        require(expected == null || expected == hash, "STALE_LOCATION")
        return hash
    }

    fun range(document: Document, arguments: JsonObject): Pair<Int, Int>? {
        val value = arguments.get("range") ?: return null
        require(value.isJsonObject, "INVALID_ARGUMENT")
        val range = value.asJsonObject
        require(range.keySet() == setOf("start", "end"), "INVALID_ARGUMENT")
        val start = offset(document, range, "start")
        val end = offset(document, range, "end")
        require(start <= end, "STALE_LOCATION")
        return start to end
    }

    private fun offset(document: Document, range: JsonObject, name: String): Int {
        val value = range.get(name) ?: throw McpFailure("INVALID_ARGUMENT")
        require(value.isJsonObject, "INVALID_ARGUMENT")
        val point = value.asJsonObject
        require(point.keySet() == setOf("line", "column"), "INVALID_ARGUMENT")
        val line = coordinate(point.get("line"))
        val column = coordinate(point.get("column"))
        require(line in 1..document.lineCount && column >= 1, "STALE_LOCATION")
        val start = document.getLineStartOffset(line - 1)
        require(column <= document.getLineEndOffset(line - 1) - start + 1, "STALE_LOCATION")
        return start + column - 1
    }

    private fun coordinate(value: JsonElement): Int {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber, "INVALID_ARGUMENT")
        return try {
            value.asBigDecimal.intValueExact()
        } catch (_: ArithmeticException) {
            throw McpFailure("INVALID_ARGUMENT")
        } catch (_: NumberFormatException) {
            throw McpFailure("INVALID_ARGUMENT")
        }
    }
}

internal fun JsonObject.string(name: String): String? {
    val value = get(name) ?: return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString)
        throw McpFailure("INVALID_ARGUMENT")
    return value.asString
}
