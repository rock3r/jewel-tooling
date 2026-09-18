package example

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import org.jetbrains.jewel.bridge.addComposeTab

class FixtureToolWindow : ToolWindowFactory {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val manualRecording = System.getProperty("jewel.test.agentTarget") != "true"
    if (manualRecording) FixtureRecording.initialize()
    if (manualRecording)
      toolWindow.setTitleActions(
        listOf(
          object : DumbAwareAction() {
            init {
              templatePresentation.text = "Copy Inspection Connection"
              templatePresentation.icon = AllIcons.Actions.Copy
            }

            override fun actionPerformed(event: AnActionEvent) {
              FixtureRecording.copyLiveConnection()
            }
          }
        )
      )
    if (manualRecording)
      Disposer.register(
        toolWindow.disposable,
        Disposable { FixtureRecording.closeLiveConnection() },
      )
    toolWindow.addComposeTab("Jewel fixture", focusOnClickInside = true) {
      var items by remember { mutableStateOf(listOf("First item")) }
      GreetingRow(Greeting("A Jewel IntelliJ tool window"), items) {
        items = items + "Another item"
      }
    }
  }
}
