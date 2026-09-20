package dev.sebastiano.jewel.tooling.e2e

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Drives a separate target IDE without installing a recorder host. */
class InspectionTargetActivity
@JvmOverloads
constructor(private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) : ProjectActivity {
    private companion object {
        const val COMMAND_POLL_MS = 100L
    }

    override suspend fun execute(project: Project) {
        if (System.getProperty("jewel.test.agentTarget") != "true") return
        val output = Path.of(System.getProperty("jewel.test.commands"))
        val window =
            withContext(Dispatchers.EDT) {
                checkNotNull(ToolWindowManager.getInstance(project).getToolWindow("Jewel Fixture"))
                    .show()
                checkNotNull(WindowManager.getInstance().getFrame(project))
            }
        val automator =
            ComposeAutomator.inProcess(robotDriver = RobotDriver.synthetic(rootWindow = window))
        automator.waitForNode(tag = "add-item")
        withContext(ioDispatcher) {
            Files.writeString(
                output.resolve("target-ready"),
                ProcessHandle.current().pid().toString(),
            )
        }
        var previous = ""
        while (!project.isDisposed) {
            if (withContext(ioDispatcher) { Files.exists(output.resolve("target-stop")) }) {
                withContext(Dispatchers.EDT) {
                    com.intellij.openapi.application.ApplicationManager.getApplication()
                        .exit(true, true, false)
                }
                return
            }
            val command =
                withContext(ioDispatcher) {
                    val request = output.resolve("target-click")
                    if (Files.exists(request)) Files.readString(request) else ""
                }
            if (command.isNotEmpty() && command != previous) {
                automator.click(checkNotNull(automator.findOneByTestTag("add-item")))
                automator.waitForIdle()
                withContext(ioDispatcher) {
                    Files.writeString(output.resolve("target-clicked"), command)
                }
                previous = command
            }
            delay(COMMAND_POLL_MS)
        }
    }
}
