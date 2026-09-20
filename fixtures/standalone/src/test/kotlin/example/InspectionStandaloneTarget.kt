package example

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.testing.runSpectreTest
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay

/** Controls the application without installing a recording host. */
object InspectionStandaloneTarget {
    @JvmStatic
    fun main(arguments: Array<String>) {
        check(arguments.isEmpty())
        val directory = Path.of(System.getProperty("jewel.test.commands"))
        runSpectreTest(timeout = 10.minutes) {
            lateinit var window: androidx.compose.ui.awt.ComposeWindow
            SwingUtilities.invokeAndWait { window = showApplication(manualRecording = false) }
            val automator =
                ComposeAutomator.inProcess(robotDriver = RobotDriver.synthetic(rootWindow = window))
            try {
                automator.waitForNode(tag = "add-item")
                Files.writeString(
                    directory.resolve("target-ready"),
                    ProcessHandle.current().pid().toString(),
                )
                var previous = ""
                while (!Files.exists(directory.resolve("target-stop"))) {
                    val request = directory.resolve("target-click")
                    val next = if (Files.exists(request)) Files.readString(request) else ""
                    if (next.isNotEmpty() && next != previous) {
                        automator.click(checkNotNull(automator.findOneByTestTag("add-item")))
                        automator.waitForVisualIdle()
                        Files.writeString(directory.resolve("target-clicked"), next)
                        previous = next
                    }
                    delay(100)
                }
            } finally {
                SwingUtilities.invokeAndWait { window.dispose() }
            }
        }
    }
}
