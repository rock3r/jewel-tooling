package example

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.testing.runSpectreTest
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.minutes

/** Test-only child process. Its output is a private pipe, never the build console. */
object LiveStandaloneTarget {
  @JvmStatic
  fun main(arguments: Array<String>) {
    check(arguments.isEmpty())
    runSpectreTest(timeout = 20.minutes) {
      lateinit var window: androidx.compose.ui.awt.ComposeWindow
      SwingUtilities.invokeAndWait { window = showApplication() }
      val automator =
        ComposeAutomator.inProcess(robotDriver = RobotDriver.synthetic(rootWindow = window))
      try {
        automator.waitForNode(tag = "add-item")
        lateinit var endpoint: String
        SwingUtilities.invokeAndWait { endpoint = FixtureRecording.openLiveConnection() }
        println("JEWEL_ENDPOINT=$endpoint")
        System.out.flush()
        while (true) {
          when (readlnOrNull()) {
            "CLICK" -> {
              automator.click(checkNotNull(automator.findOneByTestTag("add-item")))
              automator.waitForVisualIdle()
              println("JEWEL_CLICKED")
            }
            "CHECK" -> println("JEWEL_STATE=${FixtureRecording.isLiveCapturing()}")
            else -> break
          }
          System.out.flush()
        }
      } finally {
        SwingUtilities.invokeAndWait { window.dispose() }
      }
    }
  }
}
