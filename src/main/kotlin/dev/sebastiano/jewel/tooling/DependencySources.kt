package dev.sebastiano.jewel.tooling

import com.intellij.openapi.roots.ProjectFileIndex
import org.jetbrains.kotlin.psi.KtFile

internal fun KtFile.isDependencySource(): Boolean {
    val file = virtualFile
    if (isCompiled || file == null) return false
    val index = ProjectFileIndex.getInstance(project)
    return index.isInLibrary(file) && !index.isInContent(file)
}
