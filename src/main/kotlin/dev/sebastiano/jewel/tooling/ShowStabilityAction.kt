package dev.sebastiano.jewel.tooling

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import org.jetbrains.kotlin.psi.KtFile

internal class ShowStabilityAction : AnAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val file = e.getData(CommonDataKeys.PSI_FILE) as? KtFile
    val editor = e.getData(CommonDataKeys.EDITOR)
    val project = e.project
    e.presentation.isEnabledAndVisible =
      project != null &&
        file != null &&
        !file.isCompiled &&
        editor != null &&
        !DumbService.isDumb(project) &&
        StabilityDetailsService.functionAt(file, editor.caretModel.offset)
          ?.annotationEntries
          ?.isNotEmpty() == true
  }

  override fun actionPerformed(e: AnActionEvent) {
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return
    e.project
      ?.service<StabilityDetailsService>()
      ?.show(editor, editor.caretModel.offset, e.coroutineScope)
  }
}
