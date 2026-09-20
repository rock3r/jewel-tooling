package dev.sebastiano.jewel.tooling

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager

internal class OpenRecordingAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val open = { project.service<LiveInspectionService>().chooseRecording() }
        val window = ToolWindowManager.getInstance(project).getToolWindow("Compose Inspection")
        if (window != null) window.show { open() } else open()
    }
}
