package dev.sebastiano.jewel.tooling

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.panel
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent
import javax.swing.Timer

internal class McpServerAction : DumbAwareAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(event: AnActionEvent) {
    event.presentation.isEnabledAndVisible = event.project != null
  }

  override fun actionPerformed(event: AnActionEvent) {
    event.project?.let { McpServerDialog(it).show() }
  }
}

internal class McpServerDialog(private val project: Project) :
  DialogWrapper(project, false, IdeModalityType.MODELESS) {
  private val server = project.service<McpServerService>()
  private val status = javax.swing.JLabel()
  private val clientSetup = McpClientSetupPanel(project, server.clientSetup)
  private val timer = Timer(250) { refresh() }
  private lateinit var enable: javax.swing.JButton
  private lateinit var disable: javax.swing.JButton
  private lateinit var rotate: javax.swing.JButton
  private lateinit var copy: javax.swing.JButton

  init {
    title = JewelToolingBundle.message("mcp.title")
    init()
    setOKButtonText(JewelToolingBundle.message("mcp.close"))
    com.intellij.openapi.util.Disposer.register(server, disposable)
    refresh()
    timer.start()
  }

  override fun createActions(): Array<javax.swing.Action> = arrayOf(okAction)

  override fun createCenterPanel(): JComponent = panel {
    row(JewelToolingBundle.message("mcp.project")) { label(project.name) }
    row { comment(project.basePath.orEmpty()) }
    row(JewelToolingBundle.message("mcp.status")) { cell(status) }
    row { comment(JewelToolingBundle.message("mcp.scope")) }
    row {
      enable = button(JewelToolingBundle.message("mcp.enable")) { server.enable() }.component
      enable.icon = AllIcons.Actions.Execute
      disable = button(JewelToolingBundle.message("mcp.disable")) { server.disable() }.component
      disable.icon = AllIcons.Actions.Suspend
      rotate = button(JewelToolingBundle.message("mcp.rotate")) { server.rotate() }.component
      rotate.icon = AllIcons.Actions.Refresh
    }
    row {
      copy =
        button(JewelToolingBundle.message("mcp.copy")) {
            CopyPasteManager.getInstance()
              .setContents(StringSelection(server.state.value.configuration))
          }
          .component
      copy.icon = AllIcons.Actions.Copy
    }
    row { comment(JewelToolingBundle.message("mcp.setup")) }
    row { cell(clientSetup.component) }
  }

  private fun refresh() {
    val state = server.state.value
    status.text =
      JewelToolingBundle.message(
        if (state.phase == "ready" && com.intellij.openapi.project.DumbService.isDumb(project))
          "mcp.state.indexing"
        else "mcp.state.${state.phase}"
      )
    enable.isEnabled = state.phase == "disabled" || state.phase == "error"
    disable.isEnabled = state.phase == "ready" || state.phase == "cleanupFailed"
    rotate.isEnabled = state.phase == "ready"
    copy.isEnabled = state.phase == "ready"
    clientSetup.refresh(state.phase == "ready")
  }

  override fun dispose() {
    timer.stop()
    super.dispose()
  }
}
