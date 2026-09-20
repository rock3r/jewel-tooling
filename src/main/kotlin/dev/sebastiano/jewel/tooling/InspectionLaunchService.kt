package dev.sebastiano.jewel.tooling

import com.intellij.execution.CommonJavaRunConfigurationParameters
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.configurations.ModuleRunProfile
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.RunnerAndConfigurationSettingsImpl
import com.intellij.execution.process.BaseOSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.target.TargetEnvironmentAwareRunProfile
import com.intellij.execution.target.getEffectiveTargetName
import com.intellij.execution.util.JavaParametersUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.JavaSdkVersion
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.execution.ParametersListUtil
import dev.sebastiano.jewel.tooling.recording.InspectionFiles
import dev.sebastiano.jewel.tooling.recording.LiveEndpoint
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Suppress("TooManyFunctions") // Owns the launch lifecycle and its validation steps.
@Service(Service.Level.PROJECT)
internal class InspectionLaunchService
@JvmOverloads
constructor(
    private val project: Project,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Disposable {
    @Volatile private var active: Pending? = null
    private var installJob: Job? = null
    @Volatile private var disposed = false
    private val live
        get() = project.service<LiveInspectionService>()

    init {
        project.messageBus
            .connect(this)
            .subscribe(
                ExecutionManager.EXECUTION_TOPIC,
                object : ExecutionListener {
                    override fun processStarted(
                        executorId: String,
                        env: ExecutionEnvironment,
                        handler: ProcessHandler,
                    ) {
                        val pending = active ?: return
                        if (env.runProfile === pending.settings.configuration)
                            pending.started.complete(handler)
                    }

                    override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
                        val pending = active ?: return
                        if (env.runProfile === pending.settings.configuration)
                            pending.ended.set(true)
                    }

                    override fun processTerminated(
                        executorId: String,
                        env: ExecutionEnvironment,
                        handler: ProcessHandler,
                        exitCode: Int,
                    ) {
                        val pending = active ?: return
                        if (env.runProfile === pending.settings.configuration)
                            pending.ended.set(true)
                    }
                },
            )
    }

    fun install() {
        if (disposed || installJob?.isActive == true || active != null) return
        live.launchMessage("launch.installing", busy = true)
        installJob =
            scope.launch(Dispatchers.EDT) {
                try {
                    service<InspectionSupport>().install()
                    live.supportInstalled()
                } catch (_: IOException) {
                    live.launchFailure("launch.install.failed")
                } catch (_: SecurityException) {
                    live.launchFailure("launch.install.failed")
                }
            }
    }

    fun run(environment: ExecutionEnvironment) {
        run(environment.runnerAndConfigurationSettings as? RunnerAndConfigurationSettingsImpl)
    }

    private fun run(selected: RunnerAndConfigurationSettingsImpl?) {
        if (disposed || active != null || installJob?.isActive == true) return
        ToolWindowManager.getInstance(project).getToolWindow("Compose Inspection")?.show()
        val reason = selectionFailure(selected)
        if (reason != null) {
            live.launchFailure(reason)
            return
        }
        checkNotNull(selected)
        val configuration = selected.configuration
        val native = configuration is CommonJavaRunConfigurationParameters
        val gradle = project.getService(InspectionGradleSupport::class.java)
        if (live.prepareLaunch()) {
            val copy = selected.clone()
            copy.name = JewelToolingBundle.message("launch.configuration.name", selected.name)
            copy.isEditBeforeRun = false
            val pending = Pending(copy, native)
            active = pending
            launch(pending, selected.name, gradle)
        }
    }

    private fun selectionFailure(selected: RunnerAndConfigurationSettingsImpl?): String? {
        if (selected == null) return "launch.select.configuration"
        val configuration = selected.configuration
        return when {
            !ComposeInspectionExecutor.supports(configuration) -> "launch.unsupported"
            configuration is TargetEnvironmentAwareRunProfile &&
                configuration.getEffectiveTargetName(project) != null -> "launch.remote.unsupported"
            else -> null
        }
    }

    private fun launch(pending: Pending, name: String, gradle: InspectionGradleSupport?) {
        pending.job =
            scope.launch(Dispatchers.EDT) {
                var connected = false
                try {
                    prepare(pending, name, gradle)
                    live.launchMessage("launch.building", name)
                    val environment =
                        ExecutionEnvironmentBuilder.create(
                                DefaultRunExecutor.getRunExecutorInstance(),
                                pending.settings,
                            )
                            .build()
                    ProgramRunnerUtil.executeConfiguration(environment, false, true)
                    val endpoint =
                        withTimeoutOrNull(OVERALL_TIMEOUT_MS) { awaitTarget(pending) }
                            ?: throw LaunchFailure("launch.timeout")
                    if (active === pending && !disposed) {
                        live.connect(endpoint, autoStart = true)
                        ToolWindowManager.getInstance(project)
                            .getToolWindow("Compose Inspection")
                            ?.show()
                        connected = true
                    }
                } catch (failure: LaunchFailure) {
                    reportFailure(pending, failure.messageKey)
                } catch (_: IOException) {
                    reportFailure(pending, "launch.failed")
                } catch (_: com.intellij.execution.ExecutionException) {
                    reportFailure(pending, "launch.failed")
                } catch (_: IllegalArgumentException) {
                    reportFailure(pending, "launch.failed")
                } catch (_: SecurityException) {
                    reportFailure(pending, "launch.failed")
                } finally {
                    withContext(NonCancellable + ioDispatcher) {
                        pending.directory?.let { directory ->
                            try {
                                InspectionFiles.cleanup(directory)
                            } catch (_: IOException) {}
                        }
                    }
                    if (!connected && active === pending) active = null
                }
            }
    }

    private suspend fun prepare(pending: Pending, name: String, gradle: InspectionGradleSupport?) {
        live.launchMessage("launch.installing", name)
        val support = service<InspectionSupport>().install()
        live.supportInstalled(keepBusy = true)
        if (pending.native)
            validateNative(pending.settings.configuration as CommonJavaRunConfigurationParameters)
        val directory =
            withContext(ioDispatcher) {
                InspectionFiles.createDirectory(
                        Path.of(PathManager.getSystemPath(), "jewel-tooling", "launches")
                    )
                    .also { pending.directory = it }
            }
        withContext(ioDispatcher) {
            InspectionFiles.write(
                directory.resolve(InspectionFiles.CONFIG),
                mapOf(
                    "version" to "1",
                    "nonce" to pending.nonce,
                    "name" to name.take(MAX_TARGET_NAME),
                ),
            )
        }
        if (pending.native) {
            val java = pending.settings.configuration as CommonJavaRunConfigurationParameters
            val parameters = ParametersListUtil.parse(java.vmParameters.orEmpty()).toMutableList()
            parameters += "-javaagent:${support.agent}=${directory.resolve(InspectionFiles.CONFIG)}"
            java.vmParameters = ParametersListUtil.join(parameters)
        } else
            withContext(ioDispatcher) {
                checkNotNull(gradle)
                    .prepare(
                        pending.settings.configuration,
                        support.agent,
                        directory,
                        pending.nonce,
                    )
            }
    }

    private fun reportFailure(pending: Pending, key: String) {
        if (active === pending && !disposed) live.launchFailure(key)
    }

    private suspend fun validateNative(configuration: CommonJavaRunConfigurationParameters) {
        val valid =
            withContext(computeDispatcher) {
                readAction {
                    val alternative =
                        configuration.alternativeJrePath.takeIf {
                            configuration.isAlternativeJrePathEnabled
                        }
                    val module = (configuration as? ModuleRunProfile)?.modules?.firstOrNull()
                    val sdk =
                        if (module != null)
                            JavaParametersUtil.createModuleJdk(module, true, alternative)
                        else JavaParametersUtil.createProjectJdk(project, alternative)
                    val home = sdk.homePath
                    home != null &&
                        !home.startsWith("\\\\") &&
                        Files.isDirectory(Path.of(home)) &&
                        JavaSdk.getInstance().getVersion(sdk)?.isAtLeast(JavaSdkVersion.JDK_21) ==
                            true
                }
            }
        if (!valid) throw LaunchFailure("launch.jvm.unsupported")
    }

    private suspend fun awaitTarget(pending: Pending): LiveEndpoint =
        withContext(ioDispatcher) {
            val directory = checkNotNull(pending.directory)
            var deadline: Long? = null
            var handler: ProcessHandler? = null
            while (true) {
                if (pending.ended.get()) throw LaunchFailure("launch.target.ended")
                if (handler == null && pending.started.isCompleted)
                    handler = pending.started.await()
                if (deadline == null) {
                    val started =
                        if (pending.native) handler != null else taskStarted(directory, pending)
                    if (started) {
                        deadline = System.nanoTime() + CONNECTION_TIMEOUT_NS
                        withContext(Dispatchers.EDT) {
                            if (active === pending) live.launchMessage("launch.waiting")
                        }
                    }
                }
                val ready = directory.resolve(InspectionFiles.READY)
                if (targetReady(ready, pending, handler)) {
                    return@withContext readEndpoint(ready, pending, handler)
                }
                if (deadline != null && System.nanoTime() > deadline)
                    throw LaunchFailure("launch.timeout")
                delay(RENDEZVOUS_POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable")
        }

    private fun targetReady(ready: Path, pending: Pending, handler: ProcessHandler?): Boolean =
        Files.exists(ready) && (!pending.native || handler != null)

    private fun taskStarted(directory: Path, pending: Pending): Boolean {
        val marker = directory.resolve(InspectionFiles.TASK_STARTED)
        if (!Files.exists(marker)) return false
        validateNonce(InspectionFiles.read(marker), pending)
        return true
    }

    private fun validateNonce(values: Map<String, String>, pending: Pending) {
        if (values["version"] != "1" || values["nonce"] != pending.nonce)
            throw LaunchFailure("launch.invalid.target")
    }

    @Suppress("ThrowsCount") // Reject each invalid rendezvous field before connecting.
    private fun readEndpoint(
        ready: Path,
        pending: Pending,
        handler: ProcessHandler?,
    ): LiveEndpoint {
        val values = InspectionFiles.read(ready)
        Files.delete(ready)
        validateNonce(values, pending)
        checkAgentFailure(values)
        val pid = values["pid"]?.toLongOrNull() ?: throw LaunchFailure("launch.invalid.target")
        val process = ProcessHandle.of(pid).orElseThrow { LaunchFailure("launch.target.ended") }
        if (
            !process.isAlive ||
                process
                    .info()
                    .startInstant()
                    .orElseThrow { LaunchFailure("launch.invalid.target") }
                    .toEpochMilli()
                    .toString() != values["started"]
        )
            throw LaunchFailure("launch.invalid.target")
        if (pending.native && (handler as? BaseOSProcessHandler)?.process?.pid() != pid)
            throw LaunchFailure("launch.invalid.target")
        val endpoint = values["endpoint"] ?: throw LaunchFailure("launch.invalid.target")
        return try {
            LiveEndpoint.parse(endpoint)
        } catch (_: java.net.URISyntaxException) {
            throw LaunchFailure("launch.invalid.target")
        }
    }

    private fun checkAgentFailure(values: Map<String, String>) {
        if (values["failure"] != null) {
            val stage =
                values["failureStage"].orEmpty().take(MAX_STAGE_LENGTH).filter {
                    it in 'A'..'Z' || it == '_'
                }
            val type =
                values["failureType"].orEmpty().take(MAX_TYPE_LENGTH).filter {
                    it.isLetterOrDigit() || it in "._$"
                }
            val frames =
                values["failureFrames"].orEmpty().take(MAX_FRAMES_LENGTH).filter {
                    it.isLetterOrDigit() || it in "._$:;<>-"
                }
            com.intellij.openapi.diagnostic.Logger.getInstance(InspectionLaunchService::class.java)
                .warn("Inspection agent startup failed: stage=$stage, type=$type, frames=$frames")
            throw LaunchFailure(
                if (values["failure"] == "UNSUPPORTED_JVM") "launch.jvm.unsupported"
                else "launch.agent.failed"
            )
        }
    }

    fun connectionEnded() {
        active = null
    }

    fun disconnect() {
        active?.job?.cancel()
        active = null
        live.disconnect()
    }

    override fun dispose() {
        disposed = true
        active?.job?.cancel()
        installJob?.cancel()
        active = null
    }

    private class Pending(val settings: RunnerAndConfigurationSettingsImpl, val native: Boolean) {
        val nonce = InspectionFiles.nonce()
        val started = CompletableDeferred<ProcessHandler>()
        val ended = AtomicBoolean()
        var directory: Path? = null
        var job: Job? = null
    }

    private class LaunchFailure(val messageKey: String) : IOException(messageKey)

    companion object {
        private const val MAX_TARGET_NAME = 256
        private const val MAX_STAGE_LENGTH = 40
        private const val MAX_TYPE_LENGTH = 256
        private const val MAX_FRAMES_LENGTH = 1024
        private const val RENDEZVOUS_POLL_MS = 100L
        private const val OVERALL_TIMEOUT_MS = 30 * 60 * 1000L
        private const val CONNECTION_TIMEOUT_NS = 90_000_000_000L
    }
}
