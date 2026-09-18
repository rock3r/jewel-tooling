package dev.sebastiano.jewel.tooling

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.util.concurrent.atomic.AtomicBoolean

internal class ComposeInspectionShortcuts : ProjectActivity {
  override suspend fun execute(project: Project) {
    if (!bound.compareAndSet(false, true)) return
    val manager = ActionManager.getInstance()
    val source = manager.getAction(SOURCE) ?: return
    for (id in listOf(ComposeInspectionExecutor.ID, ComposeInspectionExecutor.CONTEXT_ACTION_ID)) {
      manager.getAction(id)?.shortcutSet = source.shortcutSet
    }
  }

  companion object {
    const val SOURCE = "JewelTooling.ComposeInspectionShortcuts"
    private val bound = AtomicBoolean()
  }
}
