package dev.sebastiano.jewel.tooling.e2e

import com.intellij.ide.plugins.DynamicPlugins
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import dev.sebastiano.spectre.core.RobotDriver
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.text.JTextComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Imports actual target recordings through the production service in a disposable IDE. */
@Suppress("TooManyFunctions")
internal class RecordingScenario(private val project: Project, private val scope: CoroutineScope) {
  private fun loader(pluginId: String): ClassLoader {
    val descriptor = checkNotNull(PluginManagerCore.getPlugin(PluginId.getId(pluginId)))
    val loader = checkNotNull(descriptor.pluginClassLoader)
    check(loader.javaClass.name.contains("PluginClassLoader"))
    val version =
      loader
        .loadClass("com.fasterxml.jackson.core.json.PackageVersion")
        .getField("VERSION")
        .get(null)
    check(version.toString() == "2.19.0")
    val constraints = loader.loadClass("com.fasterxml.jackson.core.StreamReadConstraints")
    val builder = constraints.getMethod("builder").invoke(null)
    builder.javaClass
      .getMethod("maxNestingDepth", Int::class.javaPrimitiveType)
      .invoke(builder, MAX_JSON_DEPTH)
    checkNotNull(builder.javaClass.getMethod("build").invoke(builder))
    return loader
  }

  private fun targetControl(): Any {
    val type = loader("dev.sebastiano.jewel.tooling.fixture").loadClass("example.FixtureRecording")
    return type.getField("INSTANCE").get(null)
  }

  fun startTarget() {
    val target = targetControl()
    target.javaClass.getMethod("start").invoke(target)
  }

  fun stopTarget() {
    val target = targetControl()
    target.javaClass.getMethod("stop").invoke(target)
  }

  fun exportTarget(path: Path) {
    val target = targetControl()
    target.javaClass.getMethod("export", Path::class.java).invoke(target, path)
  }

  @Suppress("LongMethod")
  suspend fun inspect(path: Path, robot: RobotDriver, output: Path) {
    check(Files.size(path) > 0)
    val loaderName = openReport(path)
    await("initial report opened") { edt { reportWindow() != null } }
    val window = edt { checkNotNull(reportWindow()) }
    val evidence = edt {
      val children = descendants(window)
      val table = children.filterIsInstance<JTable>().single { it.name == "jewel-recording-sites" }
      check(table.rowCount > 0)
      val executions =
        (0 until table.rowCount).sumOf { (table.getValueAt(it, 1) as Number).toInt() }
      check(executions > 0)
      check(
        (0 until table.rowCount).any {
          table.getValueAt(it, 0).toString().contains("example.GreetingRow")
        }
      )
      val text = children.filterIsInstance<JTextComponent>().joinToString("\n") { it.text }
      check(text.contains("Stopped by the host")) { text }
      check(text.contains("Abandoned starts: 0") && text.contains("Discarded pairs: 0")) { text }
      check(text.contains("not frame times") && text.contains("Missing trace markers")) { text }
      check(text.contains(System.getProperty("jewel.test.captureId", "development"))) { text }
      "executions=$executions; sites=${table.rowCount}; pluginLoader=$loaderName\n$text"
    }
    Files.writeString(output.resolve("recording-evidence.txt"), evidence)
    val totalSites = edt {
      val children = descendants(window)
      val table = children.filterIsInstance<JTable>().single { it.name == "jewel-recording-sites" }
      val total = table.rowCount
      children.filterIsInstance<JTextField>().single { it.name == "jewel-recording-filter" }.text =
        "example.GreetingRow"
      check(table.rowCount == 1)
      check(table.getValueAt(0, 0).toString().contains("example.GreetingRow"))
      check(
        children.filterIsInstance<JTextComponent>().any {
          it.text.startsWith("example.GreetingRow") && it.text.contains("Session site")
        }
      )
      total
    }
    delay(PAINT_SETTLE_MS)
    val region = edt {
      check(window.height <= MAX_REPORT_HEIGHT) {
        "Report exceeds its intended initial height: ${window.size}"
      }
      Rectangle(window.contentPane.locationOnScreen, window.contentPane.size)
    }
    val transform = edt { window.graphicsConfiguration.defaultTransform }
    val image = WindowCapture.capture(window, region, robot)
    check(image.width == (region.width * transform.scaleX).toInt())
    check(image.height == (region.height * transform.scaleY).toInt())
    ImageIO.write(image, "png", output.resolve("recording.png").toFile())
    Files.writeString(
      output.resolve("recording-capture.json"),
      """{"captureId":"${System.getProperty("jewel.test.captureId", "development")}",
        "logicalWidth":${region.width},"logicalHeight":${region.height},
        "scaleX":${transform.scaleX},"scaleY":${transform.scaleY}}""",
    )
    val clear = edt {
      descendants(window).filterIsInstance<JButton>().single {
        it.name == "jewel-recording-filter-clear"
      }
    }
    val point = edt {
      clear.locationOnScreen.apply { translate(clear.width / 2, clear.height / 2) }
    }
    robot.click(point.x, point.y)
    await("filter cleared") {
      edt {
        descendants(window)
          .filterIsInstance<JTable>()
          .single { it.name == "jewel-recording-sites" }
          .rowCount == totalSites
      }
    }
    Files.writeString(
      output.resolve("recording-filter.txt"),
      "PASS: literal filter and Clear restored $totalSites sites",
    )
    for (id in listOf(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED)) {
      java.awt.Toolkit.getDefaultToolkit()
        .systemEventQueue
        .postEvent(
          KeyEvent(
            window,
            id,
            System.currentTimeMillis(),
            0,
            KeyEvent.VK_ESCAPE,
            KeyEvent.CHAR_UNDEFINED,
          )
        )
    }
    await("Escape closed the initial report") { edt { reportWindow() == null } }
  }

  private fun openReport(path: Path): String {
    val loader = loader("dev.sebastiano.jewel.tooling")
    val type = loader.loadClass("dev.sebastiano.jewel.tooling.RecordingReportService")
    edt {
      val service = project.getService(type)
      type
        .getMethod("open", Path::class.java, CoroutineScope::class.java)
        .invoke(service, path, scope)
    }
    return loader.javaClass.name
  }

  suspend fun verifyUnloadReload(path: Path, output: Path) {
    openReport(path)
    await("report opened before unload") { edt { reportWindow() != null } }
    val id = PluginId.getId("dev.sebastiano.jewel.tooling")
    val descriptor = checkNotNull(PluginManagerCore.getPluginSet().findInstalledPlugin(id))
    Files.writeString(output.resolve("recording-lifecycle-stage.txt"), "unload started")
    check(edt { DynamicPlugins.unloadPlugin(descriptor) }) { "Plugin requires restart to unload" }
    await("unload closed the report") { edt { reportWindow() == null } }
    check(edt { ActionManager.getInstance().getAction("JewelTooling.OpenRecording") == null })
    Files.writeString(output.resolve("recording-lifecycle-stage.txt"), "reload started")
    check(edt { DynamicPlugins.loadPlugin(descriptor, project) }) { "Plugin reload failed" }
    await("reload restored the action") {
      edt { ActionManager.getInstance().getAction("JewelTooling.OpenRecording") != null }
    }
    openReport(path)
    await("report opened after reload") { edt { reportWindow() != null } }
    val window = edt { checkNotNull(reportWindow()) }
    check(edt { descendants(window).filterIsInstance<JTable>().any { it.rowCount > 0 } })
    edt {
      window.dispatchEvent(
        java.awt.event.WindowEvent(window, java.awt.event.WindowEvent.WINDOW_CLOSING)
      )
    }
    await("window close dismissed the reloaded report") { edt { reportWindow() == null } }
    Files.writeString(
      output.resolve("recording-lifecycle.txt"),
      "PASS: unload closed the report; reload restored the action; import worked after reload",
    )
  }

  private fun reportWindow(): JDialog? =
    Window.getWindows().filterIsInstance<JDialog>().singleOrNull {
      it.isShowing && it.title == "Compose Recording"
    }

  private fun descendants(component: Component): List<Component> =
    listOf(component) +
      ((component as? Container)?.components?.flatMap { descendants(it) } ?: emptyList())

  private suspend fun await(stage: String, condition: () -> Boolean) {
    check(
      withTimeoutOrNull(CONDITION_TIMEOUT_MS) {
        while (!condition()) delay(POLL_INTERVAL_MS)
        true
      } == true
    ) {
      "Recording scenario timed out: $stage"
    }
  }

  @Suppress("TooGenericExceptionCaught")
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
    private const val MAX_REPORT_HEIGHT = 800
    private const val MAX_JSON_DEPTH = 8
    private const val PAINT_SETTLE_MS = 500L
    private const val CONDITION_TIMEOUT_MS = 60_000L
    private const val POLL_INTERVAL_MS = 100L
  }
}
