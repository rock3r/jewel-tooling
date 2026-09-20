package dev.sebastiano.jewel.tooling

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JButton
import javax.swing.JLabel

internal class McpSkillSetupPanel(
    project: Project,
    private val controller: McpSkillSetupController,
) {
    private lateinit var destination: TextFieldWithBrowseButton
    private lateinit var install: JButton
    private lateinit var check: JButton
    private val status = JLabel()
    private var rendered: McpSkillSetupState? = null
    val component = panel {
        group(JewelToolingBundle.message("mcp.skill.group")) {
            row(JewelToolingBundle.message("mcp.skill.destination")) {
                destination =
                    textFieldWithBrowseButton(
                            FileChooserDescriptor(true, false, false, false, false, false),
                            project,
                        )
                        .align(AlignX.FILL)
                        .resizableColumn()
                        .component
                destination.name = "jewel.skill.destination"
            }
            row { cell(status) }
            row {
                install =
                    button(JewelToolingBundle.message("mcp.skill.install")) {
                            controller.install(destination.text)
                        }
                        .component
                install.name = "jewel.skill.install"
                install.icon = AllIcons.Actions.Download
                check =
                    button(JewelToolingBundle.message("mcp.skill.check")) {
                            controller.check(destination.text)
                        }
                        .component
                check.name = "jewel.skill.check"
                check.icon = AllIcons.Actions.Refresh
            }
            row { comment(JewelToolingBundle.message("mcp.skill.scope")) }
        }
    }

    fun refresh() {
        val state = controller.state.value
        if (state.destination != rendered?.destination) destination.text = state.destination
        val changed = destination.text != state.destination
        val text = statusText(state, changed)
        status.text = "<html><body style='width: 380px'>$text</body></html>"
        status.icon =
            when {
                state.issue != null -> AllIcons.General.Warning
                state.phase == "current" && !changed -> AllIcons.General.InspectionsOK
                else -> null
            }
        val busy = state.phase in setOf("checking", "installing")
        destination.isEnabled = !busy
        check.isEnabled = !busy
        install.isEnabled =
            !busy && !changed && state.phase in setOf("absent", "update", "interrupted")
        install.text = JewelToolingBundle.message(buttonKey(state.phase))
        rendered = state
    }

    private fun statusText(state: McpSkillSetupState, changed: Boolean): String =
        if (changed) JewelToolingBundle.message("mcp.skill.changed")
        else if (state.issue != null) JewelToolingBundle.message("mcp.install.error.${state.issue}")
        else
            JewelToolingBundle.message(
                "mcp.skill.state.${state.phase}",
                state.installedVersion,
                state.bundledVersion,
            )

    private fun buttonKey(phase: String): String =
        when (phase) {
            "update" -> "mcp.skill.update"
            "interrupted" -> "mcp.skill.resume"
            else -> "mcp.skill.install"
        }
}
