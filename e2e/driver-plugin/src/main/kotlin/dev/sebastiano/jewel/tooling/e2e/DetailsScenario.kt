package dev.sebastiano.jewel.tooling.e2e

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.ide.ui.LafManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorGutterComponentEx
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
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
import javax.swing.JLabel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Drives the production popup through actual gutter and Action System entry points. */
internal class DetailsScenario {
    private fun detailsPanel(): Component? =
        Window.getWindows().filter { it.isShowing }.firstNotNullOfOrNull { findDetails(it) }

    private fun findDetails(component: Component): Component? =
        if (component.name == "jewel.stability.details" && component.isShowing) component
        else (component as? Container)?.components?.firstNotNullOfOrNull { findDetails(it) }

    private suspend fun openDetails(robot: RobotDriver, editor: Editor, project: Project) {
        var point: java.awt.Point? = null
        await("gutter marker") {
            point = edt {
                val marker =
                    DaemonCodeAnalyzerImpl.getLineMarkers(editor.document, project).firstOrNull {
                        it.lineMarkerTooltip?.contains("Click to inspect parameter stability") ==
                            true
                    } ?: return@edt null
                val gutter = editor.gutter as EditorGutterComponentEx
                gutter.getCenterPoint(requireNotNull(marker.createGutterRenderer()))?.apply {
                    translate(gutter.locationOnScreen.x, gutter.locationOnScreen.y)
                }
            }
            point != null
        }
        val clicked = requireNotNull(point)
        robot.click(clicked.x, clicked.y)
        await("gutter popup opened") { edt { detailsPanel() != null } }
        val content = edt { visibleText(requireNotNull(detailsPanel())).joinToString("\n") }
        check(content.contains("GreetingRow")) { content }
        check(content.contains("Pair<String, String>")) { content }
        check(
            content.contains("4 stable") &&
                content.contains("1 unstable") &&
                content.contains("1 unknown")
        ) {
            content
        }
        check(content.contains("Compiler metadata")) { content }
        check(content.contains("Evidence:")) { content }
        check(content.contains("strong skipping")) { content }
    }

    private suspend fun closeDetails() {
        val target = edt { requireNotNull(detailsPanel()) }
        // Spectre's synthetic keys redispatch directly to components. Escape belongs to the IDE's
        // event-queue popup dispatcher, so send it through that queue without global OS input.
        for (id in listOf(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED)) {
            java.awt.Toolkit.getDefaultToolkit()
                .systemEventQueue
                .postEvent(
                    KeyEvent(
                        target,
                        id,
                        System.currentTimeMillis(),
                        0,
                        KeyEvent.VK_ESCAPE,
                        KeyEvent.CHAR_UNDEFINED,
                    )
                )
        }
        await("Escape dismissed popup") { edt { detailsPanel() == null } }
    }

    private suspend fun captureDetails(robot: RobotDriver, output: Path, name: String) {
        delay(PAINT_SETTLE_MS)
        edt {
            val panel = requireNotNull(detailsPanel()) as Container
            val scroll = panel.components.filterIsInstance<JScrollPane>().single()
            check(scroll.viewport.viewPosition.y == 0) {
                val focus =
                    java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                "Details must open at the top: position=${scroll.viewport.viewPosition}, " +
                    "view=${scroll.viewport.viewSize}, extent=${scroll.viewport.extentSize}, " +
                    "focus=${focus?.javaClass?.name}, text=${(focus as? JTextComponent)?.text}, " +
                    "bounds=${focus?.bounds}, orientation=${scroll.componentOrientation}"
            }
        }
        val window = edt { SwingUtilities.getWindowAncestor(requireNotNull(detailsPanel())) }
        val region = edt { Rectangle(window.locationOnScreen, window.size) }
        val transform = edt { window.graphicsConfiguration.defaultTransform }
        val image = WindowCapture.capture(window, region, robot)
        check(image.width == (region.width * transform.scaleX).toInt())
        check(image.height == (region.height * transform.scaleY).toInt())
        ImageIO.write(image, "png", output.resolve("$name.png").toFile())
        Files.writeString(
            output.resolve("$name-capture.json"),
            """{"captureId":"${System.getProperty("jewel.test.captureId", "development")}",
        "logicalWidth":${region.width},"logicalHeight":${region.height},
        "scaleX":${transform.scaleX},"scaleY":${transform.scaleY}}""",
        )
    }

    suspend fun inspect(robot: RobotDriver, editor: Editor, project: Project, output: Path) {
        openDetails(robot, editor, project)
        if (System.getProperty("jewel.test.target") == "standalone")
            captureDetails(robot, output, "details-dark")
        closeDetails()
        if (System.getProperty("jewel.test.target") == "standalone") {
            edt {
                val laf = LafManager.getInstance()
                laf.setCurrentUIThemeLookAndFeel(requireNotNull(laf.defaultLightLaf))
                laf.updateUI()
            }
            openDetails(robot, editor, project)
            captureDetails(robot, output, "details-light")
            closeDetails()
            edt {
                val laf = LafManager.getInstance()
                laf.setCurrentUIThemeLookAndFeel(requireNotNull(laf.defaultDarkLaf))
                laf.updateUI()
            }
        }
        openDetails(robot, editor, project)
        val button = edt { navigationButtons(requireNotNull(detailsPanel())).single() }
        check(edt { button.accessibleContext.accessibleName == "Go to declaration for greeting" })
        val point = edt {
            button.locationOnScreen.apply { translate(button.width / 2, button.height / 2) }
        }
        robot.click(point.x, point.y)
        await("declaration navigation") {
            edt {
                detailsPanel() == null &&
                    editor.caretModel.offset == editor.document.text.indexOf("Greeting(val")
            }
        }
        Files.writeString(
            output.resolve("navigation.txt"),
            "PASS: greeting navigated to the Greeting declaration",
        )
        edt { editor.caretModel.moveToOffset(editor.document.text.indexOf("GreetingRow")) }
        // Invoke the registered keyboard/menu action through the actual Action System.
        edt {
            ActionManager.getInstance()
                .tryToExecute(
                    requireNotNull(
                        ActionManager.getInstance().getAction("JewelTooling.ShowStability")
                    ),
                    null,
                    editor.contentComponent,
                    "JewelTooling.E2E",
                    true,
                )
        }
        await("action popup opened") { edt { detailsPanel() != null } }
        dismissWithEdit(editor, project)
    }

    private suspend fun dismissWithEdit(editor: Editor, project: Project) {
        // Changing text while details are open dismisses the stale snapshot.
        edt {
            WriteCommandAction.runWriteCommandAction(project) {
                editor.document.insertString(editor.document.textLength, "\n")
            }
        }
        await("popup edit committed") {
            edt {
                val documents = PsiDocumentManager.getInstance(project)
                documents.isCommitted(editor.document) &&
                    documents.getPsiFile(editor.document)?.text == editor.document.text
            }
        }
        await("edit dismissed popup") { edt { detailsPanel() == null } }
    }

    private fun navigationButtons(component: Component): List<JButton> =
        (if (component is JButton && component.text == "Go to declaration") listOf(component)
        else emptyList()) +
            (component as? Container)?.components?.flatMap { navigationButtons(it) }.orEmpty()

    private fun visibleText(component: Component): List<String> {
        if (!component.isShowing) return emptyList()
        val own =
            when (component) {
                is JLabel -> listOf(component.text.orEmpty())
                is JTextComponent -> listOf(component.text.orEmpty())
                else -> emptyList()
            }
        return own +
            if (component is Container) component.components.flatMap { visibleText(it) }
            else emptyList()
    }

    private suspend fun await(stage: String, condition: () -> Boolean) {
        check(
            withTimeoutOrNull(CONDITION_TIMEOUT_MS) {
                while (!condition()) delay(POLL_INTERVAL_MS)
                true
            } == true
        ) {
            "Details scenario timed out: $stage"
        }
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
        private const val PAINT_SETTLE_MS = 500L
        private const val CONDITION_TIMEOUT_MS = 60_000L
        private const val POLL_INTERVAL_MS = 100L
    }
}
