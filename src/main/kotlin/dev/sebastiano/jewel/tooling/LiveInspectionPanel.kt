package dev.sebastiano.jewel.tooling

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import dev.sebastiano.jewel.tooling.recording.completedExecutions
import java.awt.BorderLayout
import javax.swing.Icon
import javax.swing.JPanel

internal enum class LivePhase {
    DISCONNECTED,
    CONNECTING,
    READY,
    CAPTURING,
    STOPPED,
    BUSY,
    WAITING_RUNTIME,
    UNSUPPORTED,
}

internal data class LiveInspectionState(
    val phase: LivePhase = LivePhase.DISCONNECTED,
    val target: String = "",
    val data: RecordingReportData? = null,
    val message: String = "live.welcome",
    val connected: Boolean = false,
    val launching: Boolean = false,
    val supportInstalled: Boolean = false,
    val runtimeReady: Boolean = false,
)

internal class LiveInspectionPanel(
    private val project: Project,
    connect: () -> Unit,
    disconnect: () -> Unit,
    start: () -> Unit,
    stop: () -> Unit,
    export: () -> Unit,
    open: () -> Unit,
    close: () -> Unit,
) {
    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000.0
        private val STATUS_COVERS_MESSAGE = setOf("live.finished", "live.capturing")
    }

    private val toolbar: ActionToolbar
    private var state = LiveInspectionState()
    private val status =
        JBLabel().apply {
            name = "jewel-live-status"
            putClientProperty("html.disable", true)
        }
    private val description =
        JBTextArea().apply {
            name = "jewel-live-description"
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            focusTraversalKeysEnabled = true
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getContextHelpForeground()
            accessibleContext.accessibleName = JewelToolingBundle.message("live.description")
        }
    private val summary =
        JBLabel().apply {
            name = "jewel-live-summary"
            putClientProperty("html.disable", true)
        }
    private val body = JPanel(BorderLayout())
    private var report: RecordingReportPanel? = null
    val component = BorderLayoutPanel().apply { name = "jewel-live-inspection" }

    init {
        val actions =
            DefaultActionGroup(
                action(
                    "live.connect",
                    AllIcons.Debugger.AttachToProcess,
                    { !it.connected && !it.launching && it.phase != LivePhase.CONNECTING },
                    connect,
                ),
                action(
                    "live.disconnect",
                    AllIcons.Actions.Close,
                    { it.connected || it.launching || it.phase == LivePhase.CONNECTING },
                    disconnect,
                ),
                Separator.getInstance(),
                action(
                    "live.start",
                    AllIcons.Actions.Execute,
                    {
                        it.connected &&
                            it.runtimeReady &&
                            it.phase in listOf(LivePhase.READY, LivePhase.STOPPED)
                    },
                    start,
                ),
                action(
                    "live.stop",
                    AllIcons.Actions.Suspend,
                    { it.phase == LivePhase.CAPTURING },
                    stop,
                ),
                action(
                    "live.export",
                    AllIcons.ToolbarDecorator.Export,
                    {
                        it.data != null &&
                            it.data.recording.status !=
                                dev.sebastiano.jewel.tooling.recording.CaptureStatus.ACTIVE
                    },
                    export,
                    "live.export.description",
                ),
                action(
                    "live.open",
                    AllIcons.ToolbarDecorator.Import,
                    { importEnabled(it) },
                    open,
                    "live.open.description",
                ),
                Separator.getInstance(),
                action(
                    "live.close",
                    AllIcons.Actions.Cancel,
                    { recordingActionsEnabled(it) && it.data != null },
                    close,
                    "live.close.description",
                ),
            )
        toolbar =
            ActionManager.getInstance()
                .createActionToolbar("JewelComposeInspection", actions, false)
        toolbar.targetComponent = component
        val header =
            JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                border = JBUI.Borders.empty(8)
                add(
                    JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
                        isOpaque = false
                        add(summary, BorderLayout.CENTER)
                        add(status, BorderLayout.EAST)
                    },
                    BorderLayout.NORTH,
                )
                add(description, BorderLayout.SOUTH)
            }
        component.addToLeft(toolbar.component)
        component.addToTop(header)
        component.addToCenter(body)
        render(state)
    }

    fun render(next: LiveInspectionState) {
        state = next
        status.text =
            JewelToolingBundle.message(
                "live.phase.${next.phase.name.lowercase(java.util.Locale.ROOT)}"
            )
        status.icon = statusIcon(next)
        status.toolTipText = null
        description.text =
            if (next.data != null && next.message in STATUS_COVERS_MESSAGE) ""
            else JewelToolingBundle.message(next.message)
        description.accessibleContext.accessibleDescription = description.text
        summary.isVisible = next.data != null
        summary.text =
            next.data
                ?.recording
                ?.let {
                    JewelToolingBundle.message(
                        "live.summary",
                        it.completedExecutions(),
                        it.durationNs / NANOS_PER_MILLISECOND,
                    )
                }
                .orEmpty()
        renderReport(next)
        description.isVisible = description.text.isNotBlank()
        toolbar.updateActionsAsync()
        component.revalidate()
        component.repaint()
    }

    fun selectSite(id: Int) {
        report?.selectSite(id)
    }

    private fun importEnabled(state: LiveInspectionState): Boolean =
        !state.launching && state.phase != LivePhase.CONNECTING && state.phase != LivePhase.BUSY

    private fun recordingActionsEnabled(state: LiveInspectionState): Boolean =
        importEnabled(state) && state.phase != LivePhase.CAPTURING

    private fun statusIcon(next: LiveInspectionState): Icon =
        when {
            next.phase == LivePhase.UNSUPPORTED ||
                next.message == "live.incomplete" ||
                next.message == "live.connection.failed" -> AllIcons.General.Warning
            next.phase == LivePhase.CONNECTING || next.phase == LivePhase.BUSY ->
                AnimatedIcon.Default()
            next.phase == LivePhase.CAPTURING -> AllIcons.Debugger.Db_set_breakpoint
            next.connected -> AllIcons.General.InspectionsOK
            else -> AllIcons.Debugger.AttachToProcess
        }

    private fun renderReport(next: LiveInspectionState) {
        val data = next.data
        data?.recording?.let { renderFidelity(it) }
        if (data != null) {
            val current = report
            if (current == null) {
                val created = RecordingReportPanel(project, data, embedded = true)
                report = created
                body.add(created.component, BorderLayout.CENTER)
            } else current.update(data)
        } else if (report != null) {
            body.removeAll()
            report = null
        }
    }

    private fun renderFidelity(recording: dev.sebastiano.jewel.tooling.recording.Recording) {
        if (recording.status == dev.sebastiano.jewel.tooling.recording.CaptureStatus.ACTIVE) return
        val reason = recording.stopReason.name.lowercase(java.util.Locale.ROOT)
        status.text += " · " + JewelToolingBundle.message("recording.reason.$reason")
        val tooltip =
            StringBuilder(JewelToolingBundle.message("recording.reason.$reason.description"))
        if (recording.fidelity.laterActivityUnrecorded) {
            tooltip.append('\n').append(JewelToolingBundle.message("recording.later"))
        }
        status.toolTipText = tooltip.toString()
    }

    private fun action(
        key: String,
        icon: Icon,
        enabled: (LiveInspectionState) -> Boolean,
        perform: () -> Unit,
        descriptionKey: String? = null,
    ): DumbAwareAction =
        object : DumbAwareAction() {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT

                override fun update(event: AnActionEvent) {
                    event.presentation.isEnabled = enabled(state)
                    event.presentation.isVisible = true
                }

                override fun actionPerformed(event: AnActionEvent) = perform()
            }
            .apply {
                templatePresentation.text = JewelToolingBundle.message(key)
                templatePresentation.icon = icon
                if (descriptionKey != null) {
                    templatePresentation.description = JewelToolingBundle.message(descriptionKey)
                }
            }
}
