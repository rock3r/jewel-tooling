package dev.sebastiano.jewel.tooling

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import dev.sebastiano.jewel.tooling.recording.SiteSummary
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile

internal data class TraceSiteLocation(
    val qualifiedName: String,
    val packageName: String,
    val simpleName: String,
    val fileName: String,
    val line: Int,
)

internal data class LiveEditorHint(
    val siteId: Int,
    val text: String,
    val tooltip: String,
    val accessible: String,
    val hot: Boolean,
)

internal data class TraceSiteLinks(
    val openable: Set<TraceSiteLocation>,
    val projectSiteIds: Set<Int>?,
)

internal object TraceSiteLocations {
    private val PATTERN = Regex("""^(.*) \(([^():]+):(\d+)\)$""")

    @Suppress("ReturnCount") // Each missing compiler field is an unmatched location.
    fun parse(info: String): TraceSiteLocation? {
        val match = PATTERN.matchEntire(info) ?: return null
        val qualifiedName = match.groupValues[1]
        val fileName = match.groupValues[2]
        val line = match.groupValues[3].toIntOrNull() ?: return null
        if (qualifiedName.isBlank() || fileName.isBlank() || line <= 0) return null
        val separator = qualifiedName.lastIndexOf('.')
        val packageName = if (separator > 0) qualifiedName.substring(0, separator) else ""
        val simpleName =
            if (separator > 0) qualifiedName.substring(separator + 1) else qualifiedName
        return TraceSiteLocation(qualifiedName, packageName, simpleName, fileName, line)
    }

    @Suppress("ReturnCount") // A missing file or PSI cannot open a descriptor.
    fun resolve(project: Project, location: TraceSiteLocation): OpenFileDescriptor? {
        val virtual = findFile(project, location) ?: return null
        val file = PsiManager.getInstance(project).findFile(virtual) as? KtFile
        val line =
            if (file != null) TraceSiteLines.lineInFile(file, location)
            else (location.line - 1).coerceAtLeast(0)
        return OpenFileDescriptor(project, virtual, line, 0)
    }

    fun links(project: Project, data: RecordingReportData): TraceSiteLinks {
        val openable = LinkedHashSet<TraceSiteLocation>()
        val inProject = LinkedHashSet<Int>()
        val indexable = !project.isDisposed && !DumbService.isDumb(project)
        if (indexable) {
            val projectScope = GlobalSearchScope.projectScope(project)
            for (site in data.sites) {
                val location = parse(site.site.info)
                if (location != null && findFile(project, location) != null) {
                    openable.add(location)
                    if (choose(project, location, projectScope) != null) {
                        inProject.add(site.site.id)
                    }
                }
            }
        }
        return TraceSiteLinks(openable, if (indexable) inProject else null)
    }

    fun map(project: Project, data: RecordingReportData): Map<String, Map<Int, LiveEditorHint>> {
        val totalNs = data.sites.sumOf { it.totalNs }.coerceAtLeast(1L)
        val grouped = LinkedHashMap<String, MutableMap<Int, MutableList<SiteSummary>>>()
        for (site in data.sites) record(project, grouped, site)
        return grouped.mapValues { (_, lines) ->
            lines.mapValues { (_, sites) -> hint(sites, totalNs) }
        }
    }

    @Suppress("ReturnCount") // Unparsed or unresolved compiler text cannot be mapped.
    private fun record(
        project: Project,
        grouped: MutableMap<String, MutableMap<Int, MutableList<SiteSummary>>>,
        site: SiteSummary,
    ) {
        val location = parse(site.site.info) ?: return
        val virtual = findFile(project, location) ?: return
        val file = PsiManager.getInstance(project).findFile(virtual) as? KtFile
        val line =
            if (file != null) TraceSiteLines.lineInFile(file, location)
            else (location.line - 1).coerceAtLeast(0)
        grouped
            .getOrPut(virtual.url) { LinkedHashMap() }
            .getOrPut(line) { mutableListOf() }
            .add(site)
    }

    private fun hint(sites: List<SiteSummary>, totalNs: Long): LiveEditorHint {
        val executions = sites.sumOf { it.executions }
        val inclusiveNs = sites.sumOf { it.totalNs }
        val hottest = sites.maxBy { it.totalNs }
        val percent = ((inclusiveNs * 100.0) / totalNs).toInt()
        val duration = LiveInspectionFormat.milliseconds(inclusiveNs / NANOS)
        return LiveEditorHint(
            siteId = hottest.site.id,
            text = JewelToolingBundle.message("live.editor.gutter", duration),
            tooltip = LiveInspectionGutter.tooltip(percent, executions),
            accessible =
                JewelToolingBundle.message("live.editor.accessible", duration, percent, executions),
            hot = percent >= HOT_PERCENT,
        )
    }

    @Suppress("ReturnCount") // Indexing and missing library classes mean no target.
    private fun findFile(project: Project, location: TraceSiteLocation): VirtualFile? {
        if (project.isDisposed || DumbService.isDumb(project)) return null
        choose(project, location, GlobalSearchScope.projectScope(project))?.let {
            return it
        }
        choose(project, location, GlobalSearchScope.allScope(project))?.let {
            return it
        }
        val pkg = location.packageName
        val base = location.fileName.substringBeforeLast('.')
        if (pkg.isEmpty() || base.isEmpty()) return null
        val scope = GlobalSearchScope.allScope(project)
        val facade = JavaPsiFacade.getInstance(project)
        return sequenceOf("$pkg.${base}Kt", "$pkg.$base")
            .mapNotNull { facade.findClass(it, scope) }
            .mapNotNull { cls ->
                cls.navigationElement.containingFile?.virtualFile ?: cls.containingFile?.virtualFile
            }
            .firstOrNull()
    }

    @Suppress("ReturnCount") // Indexing and ambiguity mean no target.
    private fun choose(
        project: Project,
        location: TraceSiteLocation,
        scope: GlobalSearchScope,
    ): VirtualFile? {
        val files = FilenameIndex.getVirtualFilesByName(location.fileName, scope)
        if (files.isEmpty()) return null
        val manager = PsiManager.getInstance(project)
        val ktFiles = files.mapNotNull { manager.findFile(it) as? KtFile }
        if (ktFiles.isEmpty()) return files.minByOrNull { it.path }
        fun named(file: KtFile): Boolean {
            val name = TraceSiteLines.declarationName(location.simpleName)
            val declarations = file.declarations
            return name.isNotEmpty() &&
                (declarations.any { it.name == name } ||
                    declarations.filterIsInstance<KtClassOrObject>().any { owner ->
                        owner.declarations.any { it.name == name }
                    })
        }
        fun pick(candidates: List<KtFile>): VirtualFile? {
            if (candidates.isEmpty()) return null
            val pool = candidates.filter(::named).ifEmpty { candidates }
            val preferred = pool.filter { !it.isCompiled }.ifEmpty { pool }
            return preferred.minByOrNull { it.virtualFile.path }?.virtualFile
        }
        val exact = ktFiles.filter {
            location.packageName.isEmpty() || it.packageFqName.asString() == location.packageName
        }
        val nested = ktFiles.filter {
            val pkg = it.packageFqName.asString()
            pkg.isNotEmpty() && location.packageName.startsWith("$pkg.")
        }
        return pick(exact) ?: pick(nested) ?: pick(ktFiles)
    }

    private const val HOT_PERCENT = 10
    private const val NANOS = 1_000_000.0
}
