package dev.sebastiano.jewel.tooling

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.rethrowControlFlowException
import com.intellij.util.EnvironmentUtil
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class McpSkillSetupState(
    val destination: String = "",
    val phase: String = "idle",
    val installedVersion: String = "",
    val bundledVersion: String = "",
    val issue: String? = null,
)

internal class McpSkillSetupController(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val mutableState = MutableStateFlow(McpSkillSetupState())
    val state = mutableState.asStateFlow()
    private val generation = AtomicInteger()
    private var job: Job? = null
    val busy: Boolean
        get() = state.value.phase == "installing"

    fun select(client: McpClient) {
        run(false) {
            McpSkillPaths(
                    Path.of(System.getProperty("user.home")),
                    EnvironmentUtil.getEnvironmentMap(),
                )
                .destination(client)
        }
    }

    fun check(destination: String) = run(false) { Path.of(destination) }

    fun install(destination: String) = run(true) { Path.of(destination) }

    @Suppress(
        "TooGenericExceptionCaught"
    ) // Keep filesystem failures bounded and preserve cancellation.
    private fun run(install: Boolean, destination: () -> Path) {
        if (busy) return
        val selected = generation.incrementAndGet()
        job?.cancel()
        mutableState.value =
            state.value.copy(phase = if (install) "installing" else "checking", issue = null)
        job =
            scope.launch(io) {
                try {
                    val path = destination()
                    val installer =
                        McpSkillInstaller(
                            Path.of(PathManager.getConfigPath()).resolve("jewel-tooling-skills")
                        )
                    publish(selected) {
                        it.copy(
                            destination = path.toString(),
                            bundledVersion = installer.bundle.version.text,
                        )
                    }
                    val result = if (install) installer.install(path) else installer.inspect(path)
                    publish(selected) {
                        it.copy(phase = result.phase, installedVersion = result.installedVersion)
                    }
                } catch (failure: Exception) {
                    rethrowControlFlowException(failure)
                    publish(selected) {
                        it.copy(
                            phase = "error",
                            issue = (failure as? McpSetupFailure)?.reason ?: "failed",
                        )
                    }
                } finally {
                    publish(selected) {
                        if (it.phase in setOf("checking", "installing"))
                            it.copy(phase = "cancelled")
                        else it
                    }
                }
            }
    }

    private fun publish(selected: Int, change: (McpSkillSetupState) -> McpSkillSetupState) {
        mutableState.update { current ->
            if (generation.get() == selected) change(current) else current
        }
    }
}
