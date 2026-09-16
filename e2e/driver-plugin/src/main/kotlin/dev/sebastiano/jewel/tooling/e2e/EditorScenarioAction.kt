package dev.sebastiano.jewel.tooling.e2e

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.hints.declarative.DeclarativeInlayHintsSettings
import com.intellij.codeInsight.hints.declarative.impl.DeclarativeInlayHintsPassFactory
import com.intellij.codeInsight.hints.declarative.impl.inlayRenderer.DeclarativeInlayRenderer
import com.intellij.ide.ui.LafManager
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemJdkUtil
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.PsiDocumentManager
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JLabel
import javax.swing.text.JTextComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.plugins.gradle.settings.DistributionType
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import org.jetbrains.plugins.gradle.util.GradleConstants

/** Installed only in disposable test IDEs; assertions inspect rendered platform inlays. */
class EditorScenarioAction : AnAction() {
  // Keep the ordered UI scenario together; failures cross the test-process boundary as evidence.
  @Suppress("LongMethod", "TooGenericExceptionCaught", "CyclomaticComplexMethod")
  override fun actionPerformed(event: AnActionEvent) {
    val project = requireNotNull(event.project)
    val output = Path.of(System.getProperty("jewel.test.output"))
    Files.createDirectories(output)
    ApplicationManager.getApplication().executeOnPooledThread {
      try {
        runBlocking {
          withTimeout(SCENARIO_TIMEOUT_MS) {
            edt {
              WriteCommandAction.runWriteCommandAction(project) {
                val sdk =
                  ProjectJdkTable.getInstance().findJdk("Fixture JBR")
                    ?: JavaSdk.getInstance()
                      .createJdk("Fixture JBR", System.getProperty("java.home"))
                      .also { ProjectJdkTable.getInstance().addJdk(it) }
                ProjectRootManager.getInstance(project).projectSdk = sdk
              }
            }
            if (java.lang.Boolean.getBoolean("jewel.test.gradle")) importGradle(project)
            DumbService.getInstance(project).waitForSmartMode()
            val path =
              Path.of(
                requireNotNull(project.basePath),
                System.getProperty("jewel.test.file", "src/Example.kt"),
              )
            val editor = edt {
              val file =
                requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
              requireNotNull(
                FileEditorManager.getInstance(project)
                  .openTextEditor(OpenFileDescriptor(project, file), true)
              )
            }
            if (System.getProperty("jewel.test.target") != null)
              edt {
                val laf = LafManager.getInstance()
                laf.setCurrentUIThemeLookAndFeel(requireNotNull(laf.defaultDarkLaf))
                laf.updateUI()
              }
            val expected = System.getProperty("jewel.test.expected", "stable,unstable").split(",")
            if (System.getProperty("jewel.test.target") != null) {
              Files.writeString(
                output.resolve("imported-model.txt"),
                runReadActionBlocking {
                  "file=$path; canonical=${path.toRealPath()}\n" +
                    ModuleManager.getInstance(project).modules.joinToString("\n") {
                      val roots = ModuleRootManager.getInstance(it)
                      "${it.name}: content=${roots.contentRoots.toList()}; sources=${roots.sourceRoots.toList()}"
                    }
                },
              )
              val evidence = runReadActionBlocking {
                val psi =
                  requireNotNull(
                    PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
                  )
                val module = requireNotNull(ModuleUtilCore.findModuleForPsiElement(psi))
                val roots = ModuleRootManager.getInstance(module)
                check(roots.sourceRoots.any { it.path.endsWith("src/main/kotlin") })
                val libraries = roots.orderEntries().classes().urls.toList()
                check(libraries.any { it.contains("jewel", ignoreCase = true) }) {
                  "Missing real Jewel dependency: $libraries"
                }
                "module=${module.name}\nsourceRoots=${roots.sourceRoots.toList()}\nclassRoots=$libraries"
              }
              Files.writeString(output.resolve("model-roots.txt"), evidence)
            }
            await { labels(editor) == expected }
            val frame = edt { requireNotNull(WindowManager.getInstance().getFrame(project)) }
            val robot = RobotDriver.synthetic(rootWindow = frame)
            await {
              edt {
                FileEditorManager.getInstance(project).selectedEditor?.let {
                  DaemonCodeAnalyzerEx.isHighlightingCompleted(it, project)
                } == true
              }
            }
            delay(PAINT_SETTLE_MS)
            val errors = runReadActionBlocking {
              buildList {
                DaemonCodeAnalyzerEx.processHighlights(
                  editor.document,
                  project,
                  HighlightSeverity.ERROR,
                  0,
                  editor.document.textLength,
                ) {
                  add("${it.startOffset}..${it.endOffset}: ${it.description}")
                  true
                }
              }
            }
            Files.writeString(output.resolve("diagnostics.txt"), errors.joinToString("\n"))
            check(errors.isEmpty()) { "Editor errors: $errors" }
            val region = edt {
              Rectangle(editor.component.locationOnScreen, editor.component.size).apply {
                height = minOf(height, EDITOR_CAPTURE_HEIGHT)
              }
            }
            val transform = edt { editor.contentComponent.graphicsConfiguration.defaultTransform }
            val image = WindowCapture.capture(frame, region, robot)
            check(image.width == (region.width * transform.scaleX).toInt())
            check(image.height == (region.height * transform.scaleY).toInt())
            if (java.lang.Boolean.getBoolean("jewel.test.retina"))
              check(transform.scaleX == 2.0 && transform.scaleY == 2.0)
            ImageIO.write(image, "png", output.resolve("editor-before.png").toFile())
            Files.writeString(
              output.resolve("capture.json"),
              """{"captureId":"${System.getProperty("jewel.test.captureId", "development")}",
                "logicalWidth":${region.width},
                "logicalHeight":${region.height},
                "pixelWidth":${image.width},
                "pixelHeight":${image.height},
                "scaleX":${transform.scaleX},
                "scaleY":${transform.scaleY}}""",
            )
            val explanations = edt {
              inlays(editor).map { it.renderer.toInlayData().single().tooltip.orEmpty() }
            }
            check(explanations[0].contains("All stored properties"))
            check(explanations[1].contains("collections and arrays"))
            if (java.lang.Boolean.getBoolean("jewel.test.hover")) {
              val point = edt {
                val bounds = requireNotNull(inlays(editor).first().bounds)
                val origin = editor.contentComponent.locationOnScreen
                java.awt.Point(
                  origin.x + bounds.x + bounds.width / 2,
                  origin.y + bounds.y + bounds.height / 2,
                )
              }
              robot.moveTo(point.x, point.y)
              await {
                edt {
                  Window.getWindows()
                    .filter { it.isShowing }
                    .flatMap { visibleText(it) }
                    .any { it.contains("All stored properties") }
                }
              }
              ImageIO.write(
                WindowCapture.capture(frame, region, robot),
                "png",
                output.resolve("explanation.png").toFile(),
              )
            }
            if (System.getProperty("jewel.test.target") != null) {
              DetailsScenario().inspect(robot, editor, project, output)
            }
            edt {
              WriteCommandAction.runWriteCommandAction(project) {
                val start = editor.document.text.indexOf("val title")
                check(start >= 0)
                editor.document.replaceString(start, start + "val".length, "var")
                PsiDocumentManager.getInstance(project).commitAllDocuments()
              }
            }
            await { labels(editor) == (listOf("unstable") + expected.drop(1)) }
            edt {
              DeclarativeInlayHintsSettings.getInstance().setProviderEnabled(PROVIDER, false)
              DeclarativeInlayHintsPassFactory.scheduleRecompute(editor, project)
            }
            await { labels(editor).isEmpty() }
            edt {
              DeclarativeInlayHintsSettings.getInstance().setProviderEnabled(PROVIDER, true)
              DeclarativeInlayHintsPassFactory.scheduleRecompute(editor, project)
            }
            await { labels(editor) == (listOf("unstable") + expected.drop(1)) }
            if (System.getProperty("jewel.test.target") == "ijpl") {
              edt {
                requireNotNull(
                    ToolWindowManager.getInstance(project).getToolWindow("Jewel Fixture")
                  )
                  .show()
              }
              val automator = ComposeAutomator.inProcess(robotDriver = robot)
              automator.waitForNode(tag = "items-count")
              automator.waitForVisualIdle()
              check(automator.findOneByTestTag("items-count")?.text == "Items: 1")
              automator.click(requireNotNull(automator.findOneByTestTag("add-item")))
              automator.waitForVisualIdle()
              check(automator.findOneByTestTag("items-count")?.text == "Items: 2")
              val toolRegion = edt {
                val component =
                  requireNotNull(
                      ToolWindowManager.getInstance(project).getToolWindow("Jewel Fixture")
                    )
                    .component
                Rectangle(component.locationOnScreen, component.size).apply {
                  height = minOf(height, TOOL_CAPTURE_HEIGHT)
                }
              }
              val toolImage = WindowCapture.capture(frame, toolRegion, robot)
              ImageIO.write(toolImage, "png", output.resolve("ijpl-ui.png").toFile())
              Files.writeString(
                output.resolve("ijpl-capture.json"),
                """{"captureId":"${System.getProperty("jewel.test.captureId", "development")}",
                "logicalWidth":${toolRegion.width},
                "logicalHeight":${toolRegion.height},
                "scaleX":${transform.scaleX},
                "scaleY":${transform.scaleY}}""",
              )
            }
            Files.writeString(
              output.resolve("result.txt"),
              "PASS: rendered labels, explanations, edit invalidation, provider toggle, Spectre device-scale capture",
            )
          }
        }
      } catch (failure: Throwable) {
        Files.writeString(output.resolve("result.txt"), "FAIL: " + failure.stackTraceToString())
      }
    }
  }

  @Suppress("TooGenericExceptionCaught") // Forward all callback failures to the waiting test.
  private fun importGradle(project: Project) {
    val completed = CompletableFuture<Unit>()
    edt {
      val settings = GradleSettings.getInstance(project)
      if (settings.linkedProjectsSettings.none { it.externalProjectPath == project.basePath }) {
        settings.linkProject(
          GradleProjectSettings().apply {
            externalProjectPath = project.basePath
            distributionType = DistributionType.DEFAULT_WRAPPED
            gradleJvm = ExternalSystemJdkUtil.USE_INTERNAL_JAVA
          }
        )
      }
      ExternalSystemUtil.refreshProject(
        requireNotNull(project.basePath),
        ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
          .use(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
          .withImportProjectData(true)
          .withCallback(
            object : ExternalProjectRefreshCallback {
              override fun onSuccess(externalProject: DataNode<ProjectData>?) {
                try {
                  check(externalProject != null) { "Gradle sync returned no project model" }
                  completed.complete(Unit)
                } catch (failure: Throwable) {
                  completed.completeExceptionally(failure)
                }
              }

              override fun onFailure(errorMessage: String, errorDetails: String?) {
                completed.completeExceptionally(
                  IllegalStateException("Gradle sync failed: $errorMessage\n$errorDetails")
                )
              }
            }
          ),
      )
    }
    completed.get(IMPORT_TIMEOUT_MINUTES, TimeUnit.MINUTES)
  }

  private fun visibleText(component: Component): List<String> {
    if (!component.isShowing) return emptyList()
    val own =
      when (component) {
        is JLabel -> listOf(component.text.orEmpty())
        is JTextComponent -> listOf(component.text.orEmpty())
        else -> emptyList()
      }
    return own +
      if (component is Container) component.components.flatMap { visibleText(it) } else emptyList()
  }

  private fun inlays(editor: Editor) =
    editor.inlayModel
      .getInlineElementsInRange(0, editor.document.textLength, DeclarativeInlayRenderer::class.java)
      .filter { it.renderer.providerId == PROVIDER }
      .sortedBy { it.offset }

  private fun labels(editor: Editor): List<String> = edt {
    inlays(editor).map {
      it.renderer.toInlayData().single().toString().removePrefix("<# ").removeSuffix(" #>")
    }
  }

  private suspend fun await(condition: () -> Boolean) {
    withTimeout(CONDITION_TIMEOUT_MS) { while (!condition()) delay(POLL_INTERVAL_MS) }
  }

  @Suppress(
    "TooGenericExceptionCaught"
  ) // Forward EDT failures, including assertions, to the caller.
  private fun <T> edt(block: () -> T): T {
    val result = CompletableFuture<T>()
    ApplicationManager.getApplication().invokeAndWait {
      try {
        result.complete(block())
      } catch (failure: Throwable) {
        result.completeExceptionally(failure)
      }
    }
    return result.get()
  }

  companion object {
    private const val SCENARIO_TIMEOUT_MS = 900_000L
    private const val PAINT_SETTLE_MS = 500L
    private const val EDITOR_CAPTURE_HEIGHT = 620
    private const val TOOL_CAPTURE_HEIGHT = 240
    private const val IMPORT_TIMEOUT_MINUTES = 10L
    private const val CONDITION_TIMEOUT_MS = 60_000L
    private const val POLL_INTERVAL_MS = 100L
    private const val PROVIDER = "jewel.compose.stability"
  }
}
