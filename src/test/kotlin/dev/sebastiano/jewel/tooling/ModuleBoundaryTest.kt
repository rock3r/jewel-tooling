package dev.sebastiano.jewel.tooling

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase
import java.io.File

class ModuleBoundaryTest : JavaCodeInsightFixtureTestCase() {
    private lateinit var stdlib: File

    override fun tuneFixture(
        builder: com.intellij.testFramework.builders.JavaModuleFixtureBuilder<*>
    ) {
        builder.addJdk(System.getProperty("java.home"))
    }

    override fun setUp() {
        super.setUp()
        stdlib = kotlinStdlibJar(testRootDisposable)
        PsiTestUtil.addLibrary(module, "kotlin-stdlib", stdlib.parent, stdlib.name)
    }

    fun testOtherModuleSourceIsUnknown() {
        val root =
            com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(
                    java.nio.file.Files.createTempDirectory("jewel-module-").toRealPath()
                )!!
        val other =
            PsiTestUtil.addModule(
                project,
                com.intellij.openapi.module.JavaModuleType.getModuleType(),
                "dependency",
                root,
            )
        try {
            PsiTestUtil.addLibrary(other, "kotlin-stdlib", stdlib.parent, stdlib.name)
            WriteCommandAction.runWriteCommandAction(project) {
                val file = root.createChildData(this, "ExternalModel.kt")
                file.setBinaryContent(
                    ("package external; class ExternalModel(val title: String); " +
                            "@androidx.compose.runtime.Stable class ContractModel(var title: String)")
                        .toByteArray()
                )
            }
            WriteCommandAction.runWriteCommandAction(project) {
                root
                    .createChildData(this, "Stable.kt")
                    .setBinaryContent(
                        "package androidx.compose.runtime; annotation class Stable".toByteArray()
                    )
            }
            com.intellij.openapi.roots.ModuleRootModificationUtil.addDependency(module, other)
            com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
            assertBoundaryVerdicts()
        } finally {
            WriteCommandAction.runWriteCommandAction(project) {
                com.intellij.openapi.roots.ModuleRootModificationUtil.updateModel(module) { model ->
                    model.orderEntries
                        .filterIsInstance<com.intellij.openapi.roots.ModuleOrderEntry>()
                        .filter { it.module == other }
                        .forEach { model.removeOrderEntry(it) }
                }
                com.intellij.openapi.module.ModuleManager.getInstance(project).disposeModule(other)
                root.delete(this)
            }
        }
    }

    private fun assertBoundaryVerdicts() {
        myFixture.addFileToProject(
            "androidx/compose/runtime/Composable.kt",
            "package androidx.compose.runtime; annotation class Composable",
        )
        val file =
            myFixture.configureByText(
                "Example.kt",
                "import androidx.compose.runtime.Composable; @Composable fun Demo(value: external.ExternalModel) {}",
            ) as org.jetbrains.kotlin.psi.KtFile
        val result = assessment(file)
        assertEquals(Stability.UNKNOWN, result.stability)
        assertEquals(JewelToolingBundle.message("reason.external"), result.reason)
        val contracted =
            myFixture.configureByText(
                "Contract.kt",
                "import androidx.compose.runtime.Composable; @Composable fun Demo(value: external.ContractModel) {}",
            ) as org.jetbrains.kotlin.psi.KtFile
        val contractResult = assessment(contracted)
        assertEquals(Stability.STABLE, contractResult.stability)
        assertEquals(JewelToolingBundle.message("reason.contract", "Stable"), contractResult.reason)
    }

    private fun assessment(file: org.jetbrains.kotlin.psi.KtFile): StabilityAssessment {
        return com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService()
            .submit(
                java.util.concurrent.Callable {
                    com.intellij.openapi.application.runReadActionBlocking {
                        StabilityAnalysis.hints(
                                file.declarations
                                    .filterIsInstance<org.jetbrains.kotlin.psi.KtNamedFunction>()
                                    .single()
                            )
                            .single()
                            .assessment
                    }
                }
            )
            .get(30, java.util.concurrent.TimeUnit.SECONDS)
    }
}
