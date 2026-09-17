package dev.sebastiano.jewel.tooling

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages

internal class OpenRecordingAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val descriptor =
      FileChooserDescriptorFactory.createSingleFileDescriptor("json")
        .withFileFilter { it.isInLocalFileSystem }
        .withTitle(JewelToolingBundle.message("recording.open.title"))
    FileChooser.chooseFile(descriptor, project, null) { file ->
      if (file.isInLocalFileSystem) {
        project.service<RecordingReportService>().open(file.toNioPath(), e.coroutineScope)
      } else {
        Messages.showErrorDialog(
          project,
          JewelToolingBundle.message("recording.error.read"),
          JewelToolingBundle.message("recording.open.title"),
        )
      }
    }
  }
}
