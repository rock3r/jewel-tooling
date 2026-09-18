package dev.sebastiano.jewel.tooling

import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.util.concurrent.atomic.AtomicBoolean

internal class ComposeInspectionShortcuts : ProjectActivity {
  override suspend fun execute(project: Project) {
    if (!bound.compareAndSet(false, true)) return
    val keymap = KeymapManager.getInstance().activeKeymap
    val shortcuts = keymap.getShortcuts(SOURCE)
    for (id in listOf(ComposeInspectionExecutor.ID, ComposeInspectionExecutor.CONTEXT_ACTION_ID)) {
      val existing = keymap.getShortcuts(id).toSet()
      for (shortcut in shortcuts) {
        if (shortcut !in existing) keymap.addShortcut(id, shortcut)
      }
    }
  }

  companion object {
    const val SOURCE = "JewelTooling.ComposeInspectionShortcuts"
    private val bound = AtomicBoolean()
  }
}
