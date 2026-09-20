package dev.sebastiano.jewel.tooling

import com.intellij.execution.Executor
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.util.text.TextWithMnemonic
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.KeyStroke

class ComposeInspectionExecutorTest : BasePlatformTestCase() {
    fun testExecutorIsRegisteredBesideRun() {
        val executor =
            checkNotNull(
                ExecutorRegistry.getInstance().getExecutorById(ComposeInspectionExecutor.ID)
            )
        assertEquals(JewelToolingBundle.message("launch.run.mnemonic"), executor.startActionText)
        assertEquals(
            "Run 'Run' with Compose Inspection",
            TextWithMnemonic.parse(executor.getStartActionText("Run")).text,
        )
        assertEquals("Run", executor.toolWindowId)
        assertEquals(AllIcons.Toolwindows.ToolWindowRun, executor.toolWindowIcon)
        assertEquals(ComposeInspectionIcons.runWithCompose, executor.icon)
        assertEquals(ComposeInspectionExecutor.CONTEXT_ACTION_ID, executor.contextActionId)
        val shortcuts =
            KeymapManager.getInstance().activeKeymap.getShortcuts(ComposeInspectionShortcuts.SOURCE)
        assertTrue(
            shortcuts.any {
                it == KeyboardShortcut(KeyStroke.getKeyStroke("control alt shift F10"), null) ||
                    it == KeyboardShortcut(KeyStroke.getKeyStroke("control alt shift R"), null)
            }
        )
    }

    fun testRunnerHandlesApplicationConfigurationsForInspectionOnly() {
        val configuration = ApplicationConfiguration("inspect", project)
        val inspection =
            checkNotNull(ProgramRunner.getRunner(ComposeInspectionExecutor.ID, configuration))
        assertTrue(inspection is ComposeInspectionProgramRunner)
        assertFalse(inspection.canRun(DefaultRunExecutor.EXECUTOR_ID, configuration))
        val other =
            object : RunProfile {
                override fun getName() = "other"

                override fun getIcon() = null

                override fun getState(executor: Executor, environment: ExecutionEnvironment) = null
            }
        assertNull(ProgramRunner.getRunner(ComposeInspectionExecutor.ID, other))
    }
}
