package dev.sebastiano.jewel.tooling

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager

internal class InstallInspectionSupportAction : DumbAwareAction() {
  override fun actionPerformed(event: AnActionEvent) {
    val project = event.project ?: return
    ToolWindowManager.getInstance(project).getToolWindow("Compose Inspection")?.show()
    project.service<InspectionLaunchService>().install()
  }
}
