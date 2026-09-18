package dev.sebastiano.jewel.tooling

import com.intellij.execution.CommonJavaRunConfigurationParameters
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.icons.AllIcons
import com.intellij.openapi.wm.ToolWindowId
import javax.swing.Icon

@Suppress("TooManyFunctions") // Executor needs one override per presentation method.
internal class ComposeInspectionExecutor : Executor() {
  override fun getStartActionText(): String = JewelToolingBundle.message("launch.run.mnemonic")

  override fun getStartActionText(configurationName: String): String {
    if (configurationName.isEmpty()) return startActionText
    return JewelToolingBundle.message("launch.run.named", shortenNameIfNeeded(configurationName))
  }

  override fun getToolWindowId(): String = ToolWindowId.RUN

  override fun getToolWindowIcon(): Icon = AllIcons.Toolwindows.ToolWindowRun

  override fun getIcon(): Icon = ComposeInspectionIcons.runWithCompose

  override fun getDisabledIcon(): Icon? = null

  override fun getDescription(): String = JewelToolingBundle.message("launch.run.description")

  override fun getActionName(): String = JewelToolingBundle.message("launch.run.action")

  override fun getId(): String = ID

  override fun getContextActionId(): String = CONTEXT_ACTION_ID

  override fun getHelpId(): String? = null

  companion object {
    const val ID = "JewelComposeInspection"
    const val CONTEXT_ACTION_ID = "JewelTooling.RunWithInspection"
    private val NATIVE_TYPES = setOf("Application", "JetRunConfigurationType")

    fun supports(profile: RunProfile): Boolean {
      val configuration = profile as? RunConfiguration ?: return false
      val native =
        configuration.type.id in NATIVE_TYPES &&
          configuration is CommonJavaRunConfigurationParameters
      return native || configuration.type.id == "GradleRunConfiguration"
    }
  }
}
