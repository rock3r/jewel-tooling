package example

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import org.jetbrains.jewel.bridge.addComposeTab

class FixtureToolWindow : ToolWindowFactory {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    FixtureRecording.initialize()
    toolWindow.addComposeTab("Jewel fixture", focusOnClickInside = true) {
      var items by remember { mutableStateOf(listOf("First item")) }
      GreetingRow(Greeting("A Jewel IntelliJ tool window"), items) {
        items = items + "Another item"
      }
    }
  }
}
