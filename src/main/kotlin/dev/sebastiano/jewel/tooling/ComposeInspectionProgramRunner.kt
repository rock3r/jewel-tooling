package dev.sebastiano.jewel.tooling

import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.components.service

internal class ComposeInspectionProgramRunner : ProgramRunner<RunnerSettings> {
    override fun getRunnerId(): String = ID

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == ComposeInspectionExecutor.ID && ComposeInspectionExecutor.supports(profile)

    override fun execute(environment: ExecutionEnvironment) {
        environment.project.service<InspectionLaunchService>().run(environment)
    }

    companion object {
        const val ID = "JewelComposeInspectionRunner"
    }
}
