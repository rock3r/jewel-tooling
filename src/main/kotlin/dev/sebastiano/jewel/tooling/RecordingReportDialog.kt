package dev.sebastiano.jewel.tooling

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import javax.swing.Action
import javax.swing.JComponent

internal class RecordingReportDialog(project: Project, data: RecordingReportData) :
    DialogWrapper(project, false, IdeModalityType.MODELESS) {
    private val view = RecordingReportPanel(project, data)

    init {
        title = JewelToolingBundle.message("recording.report.title")
        setCancelButtonText(JewelToolingBundle.message("recording.close"))
        init()
    }

    override fun createCenterPanel(): JComponent = view.component

    override fun getPreferredFocusedComponent(): JComponent = view.focus

    override fun createActions(): Array<Action> = arrayOf(cancelAction)
}
