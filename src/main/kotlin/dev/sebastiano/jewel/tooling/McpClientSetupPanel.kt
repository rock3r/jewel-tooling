package dev.sebastiano.jewel.tooling

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel

internal class McpClientSetupPanel(
    project: Project,
    private val controller: McpClientSetupController,
) {
    private val skills = McpSkillSetupPanel(project, controller.skills)
    private lateinit var clients: JComboBox<McpClient>
    private lateinit var destination: TextFieldWithBrowseButton
    private lateinit var executable: TextFieldWithBrowseButton
    private lateinit var install: JButton
    private val result = JLabel()
    private val hint = JLabel()
    private var rendered: McpClientSetupState? = null
    val component = panel {
        group(JewelToolingBundle.message("mcp.install.group")) {
            row(JewelToolingBundle.message("mcp.install.client")) {
                clients = comboBox(McpClient.entries).component
                clients.selectedItem = controller.state.value.client
                clients.addActionListener { controller.select(clients.selectedItem as McpClient) }
            }
            row(JewelToolingBundle.message("mcp.install.destination")) {
                destination =
                    textFieldWithBrowseButton(
                            FileChooserDescriptor(true, false, false, false, false, false),
                            project,
                        )
                        .align(AlignX.FILL)
                        .resizableColumn()
                        .component
            }
            row(JewelToolingBundle.message("mcp.install.executable")) {
                executable =
                    textFieldWithBrowseButton(
                            FileChooserDescriptor(true, false, false, false, false, false),
                            project,
                        )
                        .align(AlignX.FILL)
                        .resizableColumn()
                        .component
            }
            row { cell(hint) }
            row {
                install =
                    button(JewelToolingBundle.message("mcp.install.button")) {
                            controller.install(
                                McpInstallRequest(
                                    clients.selectedItem as McpClient,
                                    destination.text,
                                    executable.text,
                                )
                            )
                        }
                        .component
                install.icon = AllIcons.Nodes.Plugin
            }
            row { cell(result) }
            row { comment(JewelToolingBundle.message("mcp.install.scope")) }
        }
        row { cell(skills.component) }
    }

    init {
        val state = controller.state.value
        if (controller.skills.state.value.destination.isEmpty())
            controller.skills.select(state.client)
        if (state.phase == "idle" && state.destination.isEmpty() && state.issue == null)
            controller.select(state.client)
    }

    private fun wrapped(text: String): String =
        "<html><body style='width: 380px'>$text</body></html>"

    fun refresh(serverReady: Boolean) {
        val state = controller.state.value
        if (state != rendered) {
            if (state.client != rendered?.client || rendered?.phase == "loading") {
                destination.text = state.destination
                executable.text = state.executable
            }
            renderResult(state)
            hint.text = wrapped(JewelToolingBundle.message("mcp.install.hint.${state.client.key}"))
            rendered = state
        }
        val busy = state.phase == "installing" || state.phase == "loading"
        skills.refresh()
        clients.isEnabled = !busy && !controller.skills.busy
        destination.isEnabled = !busy
        executable.isEnabled =
            !busy && (state.client == McpClient.CODEX || state.client == McpClient.PI)
        install.isEnabled = serverReady && !busy
    }

    private fun renderResult(state: McpClientSetupState) {
        result.text =
            wrapped(
                JewelToolingBundle.message(
                    state.issue?.let { "mcp.install.error.$it" }
                        ?: "mcp.install.state.${state.phase}"
                )
            )
        result.icon =
            when {
                state.issue != null -> AllIcons.General.Warning
                state.phase == "installed" || state.phase == "unchanged" ->
                    AllIcons.General.InspectionsOK
                else -> null
            }
    }
}
