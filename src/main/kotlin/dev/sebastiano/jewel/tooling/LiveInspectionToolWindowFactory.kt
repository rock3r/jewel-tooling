package dev.sebastiano.jewel.tooling

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

internal class LiveInspectionToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val service = project.service<LiveInspectionService>()
    val content = ContentFactory.getInstance().createContent(service.createComponent(), "", false)
    content.setDisposer(Disposable { service.disconnect() })
    toolWindow.contentManager.addContent(content)
  }
}
