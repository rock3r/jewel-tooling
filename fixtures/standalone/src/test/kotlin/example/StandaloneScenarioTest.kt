package example

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.recording.AutoScreenshotter
import dev.sebastiano.spectre.recording.screencapturekit.asTitledWindow
import dev.sebastiano.spectre.testing.runSpectreTest
import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Test

class StandaloneScenarioTest {
  @Test
  fun realJewelInteractionAndCapture(): Unit = runSpectreTest {
    lateinit var window: androidx.compose.ui.awt.ComposeWindow
    SwingUtilities.invokeAndWait { window = showApplication() }
    val robot = RobotDriver.synthetic(rootWindow = window)
    val automator = ComposeAutomator.inProcess(robotDriver = robot)
    val output = Files.createDirectories(Path.of(System.getProperty("fixture.output")))
    try {
      automator.waitForNode(tag = "items-count")
      automator.waitForVisualIdle()
      check(automator.findOneByTestTag("items-count")?.text == "Items: 1")
      SwingUtilities.invokeAndWait { FixtureRecording.start() }
      automator.click(requireNotNull(automator.findOneByTestTag("add-item")))
      automator.waitForVisualIdle()
      check(automator.findOneByTestTag("items-count")?.text == "Items: 2")
      SwingUtilities.invokeAndWait { FixtureRecording.stop() }
      val recording = output.resolve("recording.json")
      Files.deleteIfExists(recording)
      FixtureRecording.export(recording)
      lateinit var region: Rectangle
      var scaleX = 0.0
      var scaleY = 0.0
      SwingUtilities.invokeAndWait {
        region = Rectangle(window.contentPane.locationOnScreen, window.contentPane.size)
        scaleX = window.graphicsConfiguration.defaultTransform.scaleX
        scaleY = window.graphicsConfiguration.defaultTransform.scaleY
      }
      val image =
        if (System.getProperty("os.name").startsWith("Mac")) {
          val captured = AutoScreenshotter().captureWindow(window.asTitledWindow())
          captured.getSubimage(
            ((region.x - window.x) * scaleX).toInt(),
            ((region.y - window.y) * scaleY).toInt(),
            (region.width * scaleX).toInt(),
            (region.height * scaleY).toInt(),
          )
        } else robot.screenshotAtDeviceScale(region)
      check(image.width == (region.width * scaleX).toInt())
      check(image.height == (region.height * scaleY).toInt())
      if (java.lang.Boolean.getBoolean("jewel.test.retina")) check(scaleX == 2.0 && scaleY == 2.0)
      ImageIO.write(image, "png", output.resolve("standalone-ui.png").toFile())
      Files.writeString(
        output.resolve("capture.json"),
        """{"captureId":"${System.getProperty("jewel.test.captureId", "development")}",
          "logicalWidth":${region.width},
          "logicalHeight":${region.height},
          "scaleX":$scaleX,
          "scaleY":$scaleY}""",
      )
    } finally {
      SwingUtilities.invokeAndWait { window.dispose() }
    }
  }
}
