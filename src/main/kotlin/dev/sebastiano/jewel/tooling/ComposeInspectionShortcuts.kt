package dev.sebastiano.jewel.tooling

import com.intellij.openapi.actionSystem.ex.ActionManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.util.concurrent.atomic.AtomicBoolean

internal class ComposeInspectionShortcuts : ProjectActivity {
  override suspend fun execute(project: Project) {
    if (!bound.compareAndSet(false, true)) return
    val manager = ActionManagerEx.getInstanceEx()
    manager.bindShortcuts(SOURCE, ComposeInspectionExecutor.ID)
    manager.bindShortcuts(SOURCE, ComposeInspectionExecutor.CONTEXT_ACTION_ID)
  }

  companion object {
    const val SOURCE = "JewelTooling.ComposeInspectionShortcuts"
    private val bound = AtomicBoolean()
  }
}
