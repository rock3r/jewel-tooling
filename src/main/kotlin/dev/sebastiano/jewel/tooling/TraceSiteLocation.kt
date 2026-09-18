package dev.sebastiano.jewel.tooling

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import dev.sebastiano.jewel.tooling.recording.SiteSummary
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

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
    val simpleName = if (separator > 0) qualifiedName.substring(separator + 1) else qualifiedName
    return TraceSiteLocation(qualifiedName, packageName, simpleName, fileName, line)
  }

  @Suppress("ReturnCount") // A missing file or PSI cannot open a descriptor.
  fun resolve(project: Project, location: TraceSiteLocation): OpenFileDescriptor? {
    val virtual = findFile(project, location) ?: return null
    val file = PsiManager.getInstance(project).findFile(virtual) as? KtFile ?: return null
    return OpenFileDescriptor(project, virtual, lineInFile(file, location), 0)
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
    val file = PsiManager.getInstance(project).findFile(virtual) as? KtFile ?: return
    val line = lineInFile(file, location)
    grouped.getOrPut(virtual.url) { LinkedHashMap() }.getOrPut(line) { mutableListOf() }.add(site)
  }

  private fun hint(sites: List<SiteSummary>, totalNs: Long): LiveEditorHint {
    val executions = sites.sumOf { it.executions }
    val inclusiveNs = sites.sumOf { it.totalNs }
    val hottest = sites.maxBy { it.totalNs }
    val percent = ((inclusiveNs * 100.0) / totalNs).toInt()
    val duration = LiveInspectionFormat.integer(inclusiveNs / NANOS)
    return LiveEditorHint(
      siteId = hottest.site.id,
      text = JewelToolingBundle.message("live.editor.gutter", duration),
      tooltip = LiveInspectionGutter.tooltip(percent, executions),
      accessible =
        JewelToolingBundle.message("live.editor.accessible", duration, percent, executions),
      hot = percent >= HOT_PERCENT,
    )
  }

  @Suppress("ReturnCount") // Indexing and ambiguity mean no target.
  private fun findFile(project: Project, location: TraceSiteLocation): VirtualFile? {
    if (project.isDisposed || DumbService.isDumb(project)) return null
    return choose(location, matches(project, location, GlobalSearchScope.projectScope(project)))
      ?: choose(location, matches(project, location, GlobalSearchScope.allScope(project)))
  }

  private fun choose(location: TraceSiteLocation, files: List<KtFile>): VirtualFile? {
    fun pick(candidates: List<KtFile>): VirtualFile? {
      if (candidates.size <= 1) return candidates.singleOrNull()?.virtualFile
      return candidates.singleOrNull { !it.isCompiled }?.virtualFile
    }
    val exact = files.filter {
      location.packageName.isEmpty() || it.packageFqName.asString() == location.packageName
    }
    val nested = files.filter {
      val pkg = it.packageFqName.asString()
      pkg.isNotEmpty() && location.packageName.startsWith("$pkg.")
    }
    return pick(exact) ?: pick(nested)
  }

  private fun matches(
    project: Project,
    location: TraceSiteLocation,
    scope: GlobalSearchScope,
  ): List<KtFile> =
    FilenameIndex.getVirtualFilesByName(project, location.fileName, scope).mapNotNull { virtual ->
      PsiManager.getInstance(project).findFile(virtual) as? KtFile
    }

  @Suppress("ReturnCount") // Prefer the compiler line; otherwise a unique function name.
  private fun lineInFile(file: KtFile, location: TraceSiteLocation): Int {
    val recorded = location.line - 1
    val lineCount = lineCount(file.text)
    if (lineCount <= 0) return 0
    if (recorded in 0 until lineCount) return recorded
    val function =
      file.declarations.filterIsInstance<KtNamedFunction>().singleOrNull {
        it.name == location.simpleName
      } ?: return 0
    val offset = function.nameIdentifier?.textOffset ?: function.textOffset
    return file.text.substring(0, offset.coerceIn(0, file.text.length)).count { it == '\n' }
  }

  private fun lineCount(text: String): Int =
    if (text.isEmpty()) 0 else text.count { it == '\n' } + 1

  private const val HOT_PERCENT = 10
  private const val NANOS = 1_000_000.0
}
