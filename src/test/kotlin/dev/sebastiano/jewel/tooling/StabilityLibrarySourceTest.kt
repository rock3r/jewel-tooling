package dev.sebastiano.jewel.tooling

import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.psi.KtFile

class StabilityLibrarySourceTest : BasePlatformTestCase() {
    fun testLibraryKotlinFilesAreDependencySources() {
        val dir = FileUtil.createTempDirectory("compose-runtime", "src")
        com.intellij.openapi.util.Disposer.register(testRootDisposable) { FileUtil.delete(dir) }
        FileUtil.writeToFile(
            java.io.File(dir, "Effects.kt"),
            """
            package androidx.compose.runtime
            annotation class Composable
            @Composable fun LaunchedEffect(key: String) {}
            """
                .trimIndent(),
        )
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!
        ModuleRootModificationUtil.addModuleLibrary(
            myFixture.module,
            "compose-runtime",
            emptyList(),
            listOf(root.url),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val library =
            checkNotNull(
                PsiManager.getInstance(project).findFile(root.findChild("Effects.kt")!!) as? KtFile
            )
        assertTrue(library.isDependencySource())
        val projectFile =
            myFixture.configureByText(
                "Greeting.kt",
                """
                package example
                fun Greeting() {}
                """
                    .trimIndent(),
            ) as KtFile
        assertFalse(projectFile.isDependencySource())
    }
}
