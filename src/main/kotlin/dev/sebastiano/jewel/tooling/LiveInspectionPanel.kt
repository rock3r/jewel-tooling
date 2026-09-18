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
import java.awt.BorderLayout
import javax.swing.Icon
import javax.swing.JComponent
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
) {
  private companion object {
    const val NANOS_PER_MILLISECOND = 1_000_000.0
  }

  private val toolbar: ActionToolbar
  private var state = LiveInspectionState()
  private val heading =
    JBLabel().apply {
      putClientProperty("html.disable", true)
      font = font.deriveFont(java.awt.Font.BOLD)
    }
  private val status =
    JBLabel().apply {
      name = "jewel-live-status"
      putClientProperty("html.disable", true)
    }
  private val description =
    JBTextArea().apply {
      isEditable = false
      isOpaque = false
      lineWrap = true
      wrapStyleWord = true
      focusTraversalKeysEnabled = true
      font = JBUI.Fonts.smallFont()
      foreground = UIUtil.getContextHelpForeground()
      accessibleContext.accessibleName = JewelToolingBundle.message("live.description")
    }
  private val summary = JBLabel().apply { putClientProperty("html.disable", true) }
  private val body = JPanel(BorderLayout())
  private var report: RecordingReportPanel? = null
  val component: JComponent = JPanel(BorderLayout()).apply { name = "jewel-live-inspection" }

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
        action("live.stop", AllIcons.Actions.Suspend, { it.phase == LivePhase.CAPTURING }, stop),
        action(
          "live.export",
          AllIcons.ToolbarDecorator.Export,
          {
            it.data != null &&
              it.data.recording.status !=
                dev.sebastiano.jewel.tooling.recording.CaptureStatus.ACTIVE
          },
          export,
        ),
      )
    toolbar =
      ActionManager.getInstance().createActionToolbar("JewelComposeInspection", actions, true)
    toolbar.targetComponent = component
    val header =
      JPanel(BorderLayout(0, JBUI.scale(6))).apply {
        border = JBUI.Borders.empty(8)
        add(
          JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            add(heading, BorderLayout.CENTER)
            add(status, BorderLayout.EAST)
          },
          BorderLayout.NORTH,
        )
        add(
          JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(summary, BorderLayout.NORTH)
            add(description, BorderLayout.CENTER)
          },
          BorderLayout.SOUTH,
        )
      }
    val top =
      JPanel(BorderLayout()).apply {
        add(toolbar.component, BorderLayout.NORTH)
        add(header, BorderLayout.CENTER)
      }
    component.add(top, BorderLayout.NORTH)
    component.add(body, BorderLayout.CENTER)
    render(state)
  }

  fun render(next: LiveInspectionState) {
    state = next
    heading.text = next.target.ifBlank { JewelToolingBundle.message("live.title") }
    status.text =
      JewelToolingBundle.message("live.phase.${next.phase.name.lowercase(java.util.Locale.ROOT)}")
    status.icon = statusIcon(next)
    description.text = JewelToolingBundle.message(next.message)
    description.accessibleContext.accessibleDescription = description.text
    summary.isVisible = next.data != null
    summary.text =
      next.data
        ?.recording
        ?.let {
          JewelToolingBundle.message(
            "live.summary",
            it.events.size,
            it.durationNs / NANOS_PER_MILLISECOND,
          )
        }
        .orEmpty()
    renderReport(next)
    toolbar.updateActionsAsync()
    component.revalidate()
    component.repaint()
  }

  fun selectSite(id: Int) {
    report?.selectSite(id)
  }

  private fun statusIcon(next: LiveInspectionState): Icon =
    when {
      next.phase == LivePhase.UNSUPPORTED ||
        next.message == "live.incomplete" ||
        next.message == "live.connection.failed" -> AllIcons.General.Warning
      next.phase == LivePhase.CONNECTING || next.phase == LivePhase.BUSY -> AnimatedIcon.Default()
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
    val fidelity = recording.fidelity
    if (fidelity.laterActivityUnrecorded)
      description.text += "\n" + JewelToolingBundle.message("recording.later")
    if (recording.status != dev.sebastiano.jewel.tooling.recording.CaptureStatus.ACTIVE) {
      status.text +=
        " · " +
          JewelToolingBundle.message(
            "recording.reason.${recording.stopReason.name.lowercase(java.util.Locale.ROOT)}"
          )
    }
    if (
      fidelity.abandonedStarts +
        fidelity.discardedPairs +
        fidelity.unmatchedEnds +
        fidelity.rejectedStarts > 0
    ) {
      description.text +=
        "\n" +
          JewelToolingBundle.message(
            "recording.fidelity",
            fidelity.abandonedStarts,
            fidelity.discardedPairs,
            fidelity.unmatchedEnds,
            fidelity.rejectedStarts,
          )
    }
  }

  private fun action(
    key: String,
    icon: Icon,
    enabled: (LiveInspectionState) -> Boolean,
    perform: () -> Unit,
  ): DumbAwareAction =
    object : DumbAwareAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
          event.presentation.isEnabled = enabled(state)
        }

        override fun actionPerformed(event: AnActionEvent) = perform()
      }
      .apply {
        templatePresentation.text = JewelToolingBundle.message(key)
        templatePresentation.icon = icon
      }
}
