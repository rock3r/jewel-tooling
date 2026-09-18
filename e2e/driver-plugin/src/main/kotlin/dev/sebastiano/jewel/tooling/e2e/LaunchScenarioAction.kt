package dev.sebastiano.jewel.tooling.e2e

import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.execution.ParametersListUtil
import dev.sebastiano.spectre.core.RobotDriver
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JLabel
import javax.swing.JTable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType
import org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration

/** Exercises the production launch controls in a disposable authoring IDE. */
@Suppress(
  "LongMethod",
  "TooGenericExceptionCaught",
  "MagicNumber",
) // Keep the bounded E2E sequence and report every test failure.
class LaunchScenarioAction : AnAction() {
  override fun actionPerformed(event: AnActionEvent) {
    val project = checkNotNull(event.project)
    val output = Path.of(System.getProperty("jewel.test.output"))
    ApplicationManager.getApplication().executeOnPooledThread {
      try {
        val native = System.getProperty("jewel.test.target") == "ijpl"
        if (!native) EditorScenarioAction().importGradle(project)
        val configuration =
          if (native) edt { NativeInspectionConfiguration.create(project, output) }
          else
            edt {
              val manager = RunManager.getInstance(project)
              manager
                .createConfiguration(
                  "Standalone inspection",
                  GradleExternalTaskConfigurationType.getInstance().configurationFactories.single(),
                )
                .also { settings ->
                  val config = settings.configuration as GradleRunConfiguration
                  config.settings.externalProjectPath = project.basePath
                  config.settings.taskNames = listOf(":inspectionE2E")
                  config.settings.scriptParameters =
                    ParametersListUtil.join(listOf("-PinspectionCommands=$output"))
                  manager.addConfiguration(settings)
                  manager.selectedConfiguration = settings
                }
            }
        val original = edt { serialize(configuration.configuration) }
        var component: Component = edt {
          checkNotNull(ToolWindowManager.getInstance(project).getToolWindow("Compose Inspection"))
            .also { it.show() }
            .component
        }
        runBlocking {
          withTimeout(600_000) {
            await { edt { component.isShowing } }
            capture(output.resolve("one-click-install.png"))
            val executor =
              checkNotNull(ExecutorRegistry.getInstance().getExecutorById("JewelComposeInspection"))
            Files.writeString(
              output.resolve("launch-stage.txt"),
              "executor registered; launch selected configuration",
            )
            edt { ProgramRunnerUtil.executeConfiguration(configuration, executor) }
            component = showInspection()
            await {
              val observed = edt {
                val current =
                  checkNotNull(
                      ToolWindowManager.getInstance(project).getToolWindow("Compose Inspection")
                    )
                    .component
                val labels = descendants(current).filterIsInstance<JLabel>()
                val phase = labels.singleOrNull { it.name == "jewel-live-status" }?.text.orEmpty()
                phase to
                  (labels.map { it.text } +
                    descendants(current).filterIsInstance<javax.swing.JTextArea>().map { it.text })
              }
              Files.writeString(
                output.resolve("launch-connection-state.txt"),
                observed.second.joinToString("\n").take(4000),
              )
              check(!observed.first.contains("unavailable", ignoreCase = true)) {
                observed.second.joinToString("; ")
              }
              observed.first == "Capturing"
            }
            component = showInspection()
            await { Files.exists(output.resolve("target-ready")) }
            val before = edt { executions(component) }
            Files.writeString(output.resolve("target-click"), "1")
            withTimeout(30_000) { await { Files.exists(output.resolve("target-clicked")) } }
            withTimeout(30_000) {
              await {
                val observed = edt {
                  val rows =
                    descendants(component).filterIsInstance<JTable>().flatMap { table ->
                      (0 until table.rowCount).take(40).map { row ->
                        (0 until table.columnCount).joinToString(" | ") {
                          table.getValueAt(row, it).toString()
                        }
                      }
                    }
                  val labels = descendants(component).filterIsInstance<JLabel>().map { it.text }
                  val details =
                    descendants(component).filterIsInstance<javax.swing.JTextArea>().map { it.text }
                  Triple(executions(component), labels + details, rows)
                }
                Files.writeString(
                  output.resolve("launch-live-state.txt"),
                  "before=$before current=${observed.first}\n" +
                    (observed.second + observed.third).joinToString("\n").take(8000),
                )
                observed.first > before
              }
            }
            capture(output.resolve("one-click-live.png"))
            Files.writeString(output.resolve("launch-stage.txt"), "counts increased; stop capture")
            click("Stop Capture")
            await {
              edt {
                descendants(component).filterIsInstance<JLabel>().any {
                  it.name == "jewel-live-status" && it.text.startsWith("Capture ended")
                }
              }
            }
            check(edt { serialize(configuration.configuration) == original }) {
              "Saved configuration changed"
            }
            val pid = Files.readString(output.resolve("target-ready")).toLong()
            check(ProcessHandle.of(pid).orElseThrow().isAlive)
            capture(output.resolve("one-click-stopped.png"))
            click("Disconnect Target")
            check(ProcessHandle.of(pid).orElseThrow().isAlive)
            Files.writeString(
              output.resolve("result.txt"),
              "PASS: production executor launch, automatic capture, Spectre interaction, " +
                "stop, disconnect, saved configuration preserved",
            )
          }
        }
      } catch (failure: Throwable) {
        Files.writeString(output.resolve("result.txt"), "FAIL: ${failure.stackTraceToString()}")
      } finally {
        Files.writeString(output.resolve("target-stop"), "stop")
      }
    }
  }

  private suspend fun capture(path: Path) {
    val component = showInspection()
    edt {
      val project = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.single()
      val manager = ToolWindowManager.getInstance(project)
      manager.setMaximized(checkNotNull(manager.getToolWindow("Compose Inspection")), true)
    }
    val window = edt { javax.swing.SwingUtilities.getWindowAncestor(component) }
    WindowCapture.activate(window)
    val region = edt { Rectangle(component.locationOnScreen, component.size) }
    val image = WindowCapture.capture(window, region, RobotDriver.synthetic(rootWindow = window))
    val scale = edt { window.graphicsConfiguration.defaultTransform }
    check(image.width == (region.width * scale.scaleX).toInt())
    check(image.height == (region.height * scale.scaleY).toInt())
    if (java.lang.Boolean.getBoolean("jewel.test.retina"))
      check(scale.scaleX == 2.0 && scale.scaleY == 2.0)
    ImageIO.write(image, "png", path.toFile())
    Files.writeString(
      path.resolveSibling(path.fileName.toString().removeSuffix(".png") + "-capture.json"),
      """{"captureId":"${System.getProperty("jewel.test.captureId", "development")}",
        "logicalWidth":${region.width},"logicalHeight":${region.height},
        "scaleX":${scale.scaleX},"scaleY":${scale.scaleY}}""",
    )
    Files.writeString(
      path.parent.resolve("one-click-target.txt"),
      System.getProperty("jewel.test.target"),
    )
  }

  private suspend fun click(text: String) {
    val component = showInspection()
    val window = edt { javax.swing.SwingUtilities.getWindowAncestor(component) }
    WindowCapture.activate(window)
    withTimeout(30_000) {
      await {
        edt {
          descendants(component).filterIsInstance<ActionButton>().any {
            it.action.templatePresentation.text == text && it.isEnabled
          }
        }
      }
    }
    edt {
      descendants(component)
        .filterIsInstance<ActionButton>()
        .single { it.action.templatePresentation.text == text }
        .click()
    }
  }

  private suspend fun showInspection(): Component {
    val project =
      checkNotNull(
        com.intellij.openapi.project.ProjectManager.getInstance().openProjects.singleOrNull()
      )
    val tool = edt {
      checkNotNull(ToolWindowManager.getInstance(project).getToolWindow("Compose Inspection"))
        .also { it.show() }
    }
    withTimeout(30_000) { await { edt { tool.component.isShowing } } }
    return edt { tool.component }
  }

  private fun serialize(
    configuration: com.intellij.execution.configurations.RunConfiguration
  ): String {
    val element = org.jdom.Element("configuration")
    configuration.writeExternal(element)
    return com.intellij.openapi.util.JDOMUtil.writeElement(element)
  }

  private fun executions(component: Component): Long {
    val table = descendants(component).filterIsInstance<JTable>().singleOrNull() ?: return 0
    return (0 until table.rowCount)
      .filter { table.getValueAt(it, 0).toString().contains("example.GreetingRow") }
      .sumOf { (table.getValueAt(it, 1) as Number).toLong() }
  }

  private suspend fun await(condition: () -> Boolean) {
    while (!condition()) delay(100)
  }

  private fun descendants(component: Component): List<Component> =
    listOf(component) +
      if (component is Container) component.components.flatMap(::descendants) else emptyList()

  private fun <T> edt(action: () -> T): T {
    val result = java.util.concurrent.CompletableFuture<T>()
    ApplicationManager.getApplication().invokeAndWait {
      try {
        result.complete(action())
      } catch (failure: Throwable) {
        result.completeExceptionally(failure)
      }
    }
    return result.get()
  }
}
