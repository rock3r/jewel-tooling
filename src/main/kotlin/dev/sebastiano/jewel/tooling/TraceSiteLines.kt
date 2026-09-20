package dev.sebastiano.jewel.tooling

import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

internal object TraceSiteLines {
    fun lineInFile(file: KtFile, location: TraceSiteLocation): Int {
        val recorded = location.line - 1
        val lineCount = lineCount(file.text)
        if (lineCount <= 0) return 0
        namedMatch(file, location, recorded)?.let { match ->
            return offsetLine(file, match.nameIdentifier?.textOffset ?: match.textOffset)
        }
        return if (recorded in 0 until lineCount) recorded else 0
    }

    private fun namedMatch(
        file: KtFile,
        location: TraceSiteLocation,
        recorded: Int,
    ): KtNamedDeclaration? {
        val name = declarationName(location.simpleName)
        if (name.isEmpty() || name.startsWith("<")) return null
        val declarations =
            PsiTreeUtil.collectElementsOfType(file, KtNamedDeclaration::class.java).filter {
                declaration ->
                declaration.name == name &&
                    (declaration is KtNamedFunction || declaration is KtProperty)
            }
        return declarations.filter { covers(file, it, recorded) }.minByOrNull { it.textLength }
            ?: declarations.singleOrNull()
    }

    private fun covers(file: KtFile, declaration: KtNamedDeclaration, recorded: Int): Boolean {
        val range = declaration.textRange
        val start = offsetLine(file, range.startOffset)
        val end = offsetLine(file, (range.endOffset - 1).coerceAtLeast(range.startOffset))
        return recorded in start..end
    }

    private fun offsetLine(file: KtFile, offset: Int): Int {
        val document = file.viewProvider.document
        if (document != null && document.textLength > 0) {
            return document.getLineNumber(offset.coerceIn(0, document.textLength - 1))
        }
        if (offset <= 0 || file.text.isEmpty()) return 0
        return file.text.substring(0, offset.coerceAtMost(file.text.length)).count { it == '\n' }
    }

    internal fun declarationName(simpleName: String): String =
        when {
            simpleName.startsWith("<get-") && simpleName.endsWith(">") ->
                simpleName.removePrefix("<get-").removeSuffix(">")
            simpleName.startsWith("<set-") && simpleName.endsWith(">") ->
                simpleName.removePrefix("<set-").removeSuffix(">")
            else -> simpleName
        }

    private fun lineCount(text: String): Int =
        if (text.isEmpty()) 0 else text.count { it == '\n' } + 1
}
