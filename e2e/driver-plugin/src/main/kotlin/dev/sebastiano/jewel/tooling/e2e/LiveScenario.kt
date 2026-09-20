package dev.sebastiano.jewel.tooling.e2e

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import javax.swing.JLabel
import javax.swing.JTable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

@Suppress("TooManyFunctions")
internal class LiveScenario(private val project: Project) {
    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val POLL_MS = 100L
    }

    @Suppress("LongMethod")
    suspend fun run(robot: RobotDriver, output: Path) {
        val isIjpl = System.getProperty("jewel.test.target") == "ijpl"
        val endpoint =
            if (isIjpl)
                edt {
                    val target = fixture()
                    target.javaClass.getMethod("openLiveConnection").invoke(target) as String
                }
            else {
                val path = Path.of(System.getProperty("jewel.test.liveEndpoint"))
                Files.writeString(output.resolve("live-start-request"), "start")
                await { Files.exists(path) && Files.size(path) > 0 }
                try {
                    Files.readString(path)
                } finally {
                    Files.deleteIfExists(path)
                }
            }
        val loader =
            checkNotNull(
                PluginManagerCore.getPlugin(PluginId.getId("dev.sebastiano.jewel.tooling"))
                    ?.pluginClassLoader
            )
        val type = loader.loadClass("dev.sebastiano.jewel.tooling.LiveInspectionService")
        val service = project.getService(type)
        val tool = edt {
            checkNotNull(ToolWindowManager.getInstance(project).getToolWindow("Compose Inspection"))
                .also { it.show() }
        }
        Files.writeString(output.resolve("live-stage.txt"), "connection dialog")
        connectThroughDialog(tool.component, endpoint)
        Files.writeString(output.resolve("live-stage.txt"), "wait for Ready")
        await { edt { status(tool.component) == "Ready" } }
        Files.writeString(output.resolve("live-stage.txt"), "start another capture")
        click("Start Capture")
        Files.writeString(output.resolve("live-stage.txt"), "wait for another capture")
        await { edt { status(tool.component) == "Capturing" } }
        Files.writeString(output.resolve("live-stage.txt"), "first interaction")
        val before = edt { executions(tool.component) }
        interact(isIjpl, robot, output, 1)
        Files.writeString(output.resolve("live-stage.txt"), "wait for first count above $before")
        await { edt { executions(tool.component) > before } }
        val first = edt { executions(tool.component) }
        interact(isIjpl, robot, output, 2)
        Files.writeString(output.resolve("live-stage.txt"), "wait for second count above $first")
        await { edt { executions(tool.component) > first } }
        Files.writeString(output.resolve("live-stage.txt"), "stop capture")
        click("Stop Capture")
        Files.writeString(output.resolve("live-stage.txt"), "wait for Capture ended")
        await {
            val observed = edt {
                status(tool.component) to
                    descendants(tool.component)
                        .filterIsInstance<javax.swing.JTextArea>()
                        .joinToString("\n") { it.text }
            }
            Files.writeString(
                output.resolve("live-stop-state.txt"),
                "${observed.first}\n${observed.second}",
            )
            observed.first.startsWith("Capture ended")
        }
        Files.writeString(output.resolve("live-stage.txt"), "capture stopped report")
        val stopped = edt { executions(tool.component) }
        val region = edt { Rectangle(tool.component.locationOnScreen, tool.component.size) }
        val window = edt { javax.swing.SwingUtilities.getWindowAncestor(tool.component) }
        val image = WindowCapture.capture(window, region, robot)
        ImageIO.write(image, "png", output.resolve("live-recording.png").toFile())
        val scale = edt { window.graphicsConfiguration.defaultTransform }
        check(image.width == (region.width * scale.scaleX).toInt())
        if (java.lang.Boolean.getBoolean("jewel.test.retina"))
            check(scale.scaleX == 2.0 && scale.scaleY == 2.0)
        Files.writeString(
            output.resolve("live-capture.json"),
            """{"captureId":"${System.getProperty("jewel.test.captureId", "development")}",""" +
                """"logicalWidth":${region.width},"logicalHeight":${region.height},""" +
                """"scaleX":${scale.scaleX},"scaleY":${scale.scaleY}}""",
        )
        // Read the confirmed model through the test driver; use the production export operation.
        val stateField = type.getDeclaredField("state").apply { isAccessible = true }
        val recording = edt {
            val state = stateField.get(service)
            val data = state.javaClass.getMethod("getData").invoke(state)
            data.javaClass.getMethod("getRecording").invoke(data)
        }
        val saved = output.resolve("live-recording.json")
        edt {
            type
                .getMethod("export", Path::class.java, recording.javaClass)
                .invoke(service, saved, recording)
        }
        Files.writeString(output.resolve("live-stage.txt"), "wait for export file")
        await { Files.exists(saved) }
        val codecType = loader.loadClass("dev.sebastiano.jewel.tooling.recording.RecordingCodec")
        val codec = codecType.getField("INSTANCE").get(null)
        val decoded =
            codecType
                .getMethod("read", java.io.InputStream::class.java, kotlin.Function0::class.java)
                .invoke(codec, Files.newInputStream(saved), { Unit })
        check(decoded == recording)
        val exportedStatus = recording.javaClass.getMethod("getStatus").invoke(recording).toString()
        val exportedReason =
            recording.javaClass.getMethod("getStopReason").invoke(recording).toString()
        val exportedEvents = recording.javaClass.getMethod("getEvents").invoke(recording) as List<*>
        check(exportedStatus == "STOPPED") {
            "Live export must be a complete recording, not $exportedStatus " +
                "($exportedReason), events=${exportedEvents.size}"
        }
        check(exportedEvents.isNotEmpty()) {
            "Live export contained no events ($exportedStatus $exportedReason)"
        }
        check(stopped > 0) { "Live table showed no completed GreetingRow executions" }
        Files.writeString(output.resolve("live-stage.txt"), "wait for export confirmation")
        await {
            val notifications = edt {
                NotificationsManager.getNotificationsManager()
                    .getNotificationsOfType(Notification::class.java, project)
                    .filter { it.content.startsWith("The recording was exported.") }
            }
            Files.writeString(
                output.resolve("live-export-state.txt"),
                notifications
                    .joinToString("\n") { it.content }
                    .ifEmpty { "no export notification" },
            )
            notifications.isNotEmpty()
        }
        Files.writeString(output.resolve("live-stage.txt"), "start another capture")
        click("Start Capture")
        Files.writeString(output.resolve("live-stage.txt"), "wait for another capture")
        await { edt { status(tool.component) == "Capturing" } }
        Files.writeString(
            output.resolve("live-evidence.txt"),
            "PASS: live counts increased twice before stop; final export matches; " +
                "new capture active for unload; executions=$stopped status=$exportedStatus reason=$exportedReason",
        )
    }

    suspend fun verifyTargetStopped(output: Path) {
        if (System.getProperty("jewel.test.target") == "ijpl") {
            await {
                edt {
                    val target = fixture()
                    target.javaClass.getMethod("isLiveCapturing").invoke(target) == false
                }
            }
            edt {
                val target = fixture()
                target.javaClass.getMethod("closeLiveConnection").invoke(target)
            }
        } else {
            await {
                val path = output.resolve("live-target-state")
                Files.exists(path) && Files.readString(path) == "false"
            }
        }
        Files.writeString(
            output.resolve("live-unload.txt"),
            "PASS: unloading the authoring plugin stopped target capture",
        )
    }

    private suspend fun interact(ijpl: Boolean, robot: RobotDriver, output: Path, sequence: Int) {
        if (ijpl) {
            edt {
                checkNotNull(ToolWindowManager.getInstance(project).getToolWindow("Jewel Fixture"))
                    .show()
            }
            val automator = ComposeAutomator.inProcess(robotDriver = robot)
            Files.writeString(
                output.resolve("live-stage.txt"),
                "wait for add-item node ($sequence)",
            )
            automator.waitForNode(tag = "add-item")
            Files.writeString(output.resolve("live-stage.txt"), "click add-item ($sequence)")
            automator.click(checkNotNull(automator.findOneByTestTag("add-item")))
            automator.waitForIdle()
        } else {
            Files.writeString(output.resolve("live-click-request"), sequence.toString())
            await {
                val path = output.resolve("live-click-ack")
                Files.exists(path) && Files.readString(path) == sequence.toString()
            }
        }
    }

    private fun fixture(): Any {
        val loader =
            checkNotNull(
                PluginManagerCore.getPlugin(PluginId.getId("dev.sebastiano.jewel.tooling.fixture"))
                    ?.pluginClassLoader
            )
        val type = loader.loadClass("example.FixtureRecording")
        return type.getField("INSTANCE").get(null)
    }

    private suspend fun connectThroughDialog(component: Component, endpoint: String) {
        await {
            edt {
                descendants(component).filterIsInstance<ActionButton>().any {
                    it.action.templatePresentation.text == "Connect Target"
                }
            }
        }
        ApplicationManager.getApplication().invokeLater {
            descendants(component)
                .filterIsInstance<ActionButton>()
                .single { it.action.templatePresentation.text == "Connect Target" }
                .click()
        }
        await { edt(ModalityState.any()) { connectionDialog() != null } }
        val (dialog, modality) =
            edt(ModalityState.any()) {
                checkNotNull(connectionDialog()).let { it to ModalityState.stateForComponent(it) }
            }
        edt(modality) {
            descendants(dialog).filterIsInstance<javax.swing.JPasswordField>().single().text =
                endpoint
            checkNotNull(dialog.rootPane.defaultButton).doClick()
        }
    }

    private fun connectionDialog(): javax.swing.JDialog? =
        java.awt.Window.getWindows().filterIsInstance<javax.swing.JDialog>().singleOrNull {
            it.isShowing && it.title == "Connect Target"
        }

    private suspend fun click(text: String) {
        val tool = edt {
            checkNotNull(ToolWindowManager.getInstance(project).getToolWindow("Compose Inspection"))
                .also { it.show() }
        }
        await { edt { tool.component.isShowing } }
        val window = edt {
            checkNotNull(javax.swing.SwingUtilities.getWindowAncestor(tool.component))
        }
        WindowCapture.activate(window)
        await {
            edt {
                val controls = descendants(tool.component).filterIsInstance<ActionButton>()
                Files.writeString(
                    Path.of(System.getProperty("jewel.test.output"), "live-control-state.txt"),
                    "requested=$text status=${status(tool.component)}\n" +
                        controls.joinToString("\n") {
                            "${it.action.templatePresentation.text}: enabled=${it.isEnabled}"
                        },
                )
                controls.any { it.action.templatePresentation.text == text && it.isEnabled }
            }
        }
        edt {
            descendants(tool.component)
                .filterIsInstance<ActionButton>()
                .single { it.action.templatePresentation.text == text }
                .click()
        }
    }

    private fun status(component: Component): String =
        descendants(component)
            .filterIsInstance<JLabel>()
            .singleOrNull { it.name == "jewel-live-status" }
            ?.text
            .orEmpty()

    private fun executions(component: Component): Int =
        descendants(component).filterIsInstance<JTable>().sumOf { table ->
            (0 until table.rowCount)
                .filter { table.getValueAt(it, 0).toString().contains("example.GreetingRow") }
                .sumOf { (table.getValueAt(it, 1) as Number).toInt() }
        }

    private fun descendants(component: Component): List<Component> =
        listOf(component) +
            ((component as? Container)?.components?.flatMap { descendants(it) }).orEmpty()

    private suspend fun await(condition: suspend () -> Boolean) =
        withTimeout(TIMEOUT_MS) { while (!condition()) delay(POLL_MS) }

    @Suppress("TooGenericExceptionCaught") // Transfer failures to the waiting test thread.
    private fun <T> edt(
        modality: ModalityState = ModalityState.defaultModalityState(),
        action: () -> T,
    ): T {
        val result = CompletableFuture<T>()
        ApplicationManager.getApplication()
            .invokeAndWait(
                {
                    try {
                        result.complete(action())
                    } catch (failure: Throwable) {
                        result.completeExceptionally(failure)
                    }
                },
                modality,
            )
        return result.get()
    }
}
