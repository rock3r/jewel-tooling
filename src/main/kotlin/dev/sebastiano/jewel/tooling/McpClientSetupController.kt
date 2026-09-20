package dev.sebastiano.jewel.tooling

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
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
import kotlinx.coroutines.launch

internal data class McpClientSetupState(
    val client: McpClient = McpClient.CODEX,
    val destination: String = "",
    val executable: String = "",
    val phase: String = "idle",
    val issue: String? = null,
)

internal class McpClientSetupController(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val installClient: suspend (McpInstallRequest) -> Boolean,
) {
    val skills = McpSkillSetupController(scope, io)
    private val mutableState = MutableStateFlow(McpClientSetupState())
    val state = mutableState.asStateFlow()
    private val selection = AtomicInteger()
    private var job: Job? = null

    @Suppress(
        "TooGenericExceptionCaught"
    ) // Convert setup failures without exposing configuration content.
    fun select(client: McpClient) {
        if (state.value.phase == "installing" || skills.busy) return
        skills.select(client)
        val selected = selection.incrementAndGet()
        mutableState.value = McpClientSetupState(client, phase = "loading")
        scope.launch(io) {
            try {
                val paths =
                    McpClientPaths(
                        Path.of(System.getProperty("user.home")),
                        Path.of(PathManager.getConfigPath()),
                        EnvironmentUtil.getEnvironmentMap(),
                        System.getProperty("os.name"),
                    )
                val command =
                    when (client) {
                        McpClient.CODEX -> "codex"
                        McpClient.PI -> "pi"
                        else -> null
                    }
                val executable =
                    command
                        ?.let { PathEnvironmentVariableUtil.findInPath(it)?.absolutePath }
                        .orEmpty()
                val destination = paths.destination(client)?.toString().orEmpty()
                if (selection.get() == selected)
                    mutableState.value = McpClientSetupState(client, destination, executable)
            } catch (failure: Exception) {
                rethrowControlFlowException(failure)
                if (selection.get() == selected)
                    mutableState.value = McpClientSetupState(client, issue = failureReason(failure))
            }
        }
    }

    @Suppress(
        "TooGenericExceptionCaught"
    ) // Display bounded localized failures, never raw CLI output or config data.
    fun install(request: McpInstallRequest) {
        if (state.value.phase == "installing") return
        selection.incrementAndGet()
        mutableState.value =
            McpClientSetupState(
                request.client,
                request.destination,
                request.executable,
                "installing",
            )
        job =
            scope.launch(io) {
                try {
                    val changed = installClient(request)
                    mutableState.value =
                        mutableState.value.copy(phase = if (changed) "installed" else "unchanged")
                } catch (failure: Exception) {
                    rethrowControlFlowException(failure)
                    mutableState.value =
                        mutableState.value.copy(phase = "error", issue = failureReason(failure))
                } finally {
                    if (mutableState.value.phase == "installing")
                        mutableState.value = mutableState.value.copy(phase = "cancelled")
                }
            }
    }

    fun cancel() {
        job?.cancel()
    }

    fun refreshFailed() {
        mutableState.value = mutableState.value.copy(issue = "studioRefresh")
    }

    private fun failureReason(failure: Exception) =
        (failure as? McpSetupFailure)?.reason ?: "failed"
}
