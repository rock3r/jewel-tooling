package dev.sebastiano.jewel.tooling.agent

import dev.sebastiano.jewel.tooling.bridge.TraceBridge
import dev.sebastiano.jewel.tooling.recording.CaptureTarget
import dev.sebastiano.jewel.tooling.recording.CompositionRecorder
import dev.sebastiano.jewel.tooling.recording.InspectionFiles
import dev.sebastiano.jewel.tooling.recording.LiveCommand
import dev.sebastiano.jewel.tooling.recording.LiveRecordingServer
import dev.sebastiano.jewel.tooling.recording.LiveRecordingSession
import dev.sebastiano.jewel.tooling.recording.LiveRuntimeState
import dev.sebastiano.jewel.tooling.recording.LiveTargetStatus
import dev.sebastiano.jewel.tooling.recording.Recording
import java.awt.EventQueue
import java.io.IOException
import java.lang.System.Logger.Level
import java.lang.instrument.Instrumentation
import java.nio.file.Path
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Owns the isolated recorder for one JVM launch. */
object AgentRuntime {
    private var host: AgentHost? = null

    @Suppress("TooGenericExceptionCaught") // Close the host, then propagate the original failure.
    @JvmStatic
    fun start(argument: String, instrumentation: Instrumentation) {
        check(host == null)
        val configPath = Path.of(argument)
        require(configPath.fileName.toString() == InspectionFiles.CONFIG)
        val values = InspectionFiles.read(configPath)
        require(values["version"] == "1")
        val nonce =
            checkNotNull(values["nonce"]).also { require(it.matches(Regex("[0-9a-f]{64}"))) }
        val name =
            checkNotNull(values["name"]).also { require(it.isNotBlank() && it.length <= 256) }
        val created = AgentHost(name, instrumentation)
        host = created
        try {
            created.install()
            val process = ProcessHandle.current()
            InspectionFiles.write(
                configPath.parent.resolve(InspectionFiles.READY),
                mapOf(
                    "version" to "1",
                    "nonce" to nonce,
                    "pid" to process.pid().toString(),
                    "started" to
                        process.info().startInstant().orElseThrow().toEpochMilli().toString(),
                    "endpoint" to created.endpoint,
                ),
            )
        } catch (failure: Exception) {
            created.close()
            throw failure
        }
    }
}

private const val CONNECTION_LEASE_SECONDS = 120L
private const val MAX_RUNTIME_COUNT = 64

private class AgentHost(name: String, private val instrumentation: Instrumentation) :
    TraceBridge.Sink, AutoCloseable {
    private val target = CaptureTarget(name, runtime = "Compose runtime 1")
    private val runtimeStatus = AtomicReference(LiveTargetStatus(LiveRuntimeState.NO_RUNTIME))
    private val runtimeLock = Any()
    private val loaders = WeakHashMap<ClassLoader?, Int>()
    @Volatile private var discovered = 0
    private val connected = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val captureLock = Any()
    @Volatile private var recorder: CompositionRecorder? = null
    private val skippedCallback = AtomicBoolean()
    private val lease = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "Compose inspection launch lease").apply { isDaemon = true }
    }
    private val server =
        LiveRecordingServer(target) {
            check(connected.compareAndSet(false, true))
            Session()
        }
    val endpoint: String
        get() = server.endpoint.connectionString()

    fun install() {
        // Resolve the bridge before a target class enters the transformer.
        TraceBridge.install(this)
        if (
            instrumentation.allLoadedClasses.any {
                it.name == ComposeTransformer.COMPOSER.replace('/', '.')
            }
        ) {
            unavailable(LiveRuntimeState.UNSUPPORTED_ABI)
        } else {
            instrumentation.addTransformer(
                ComposeTransformer(
                    ::runtimeLoaded,
                    { unavailable(LiveRuntimeState.UNSUPPORTED_ABI) },
                    ::available,
                )
            )
        }
        lease.schedule(
            { if (!connected.get()) close() },
            CONNECTION_LEASE_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    private fun runtimeLoaded(loader: ClassLoader?, module: Module?): Int =
        synchronized(runtimeLock) {
            if (closed.get()) return@synchronized 0
            loaders[loader]?.let {
                return@synchronized it
            }
            discovered = (discovered + 1).coerceAtMost(MAX_RUNTIME_COUNT)
            if (discovered > 1) {
                unavailable(LiveRuntimeState.MULTIPLE_RUNTIMES)
                return@synchronized 0
            }
            if (
                module != null && module.isNamed && !module.canRead(TraceBridge::class.java.module)
            ) {
                instrumentation.redefineModule(
                    module,
                    setOf(TraceBridge::class.java.module),
                    emptyMap(),
                    emptyMap(),
                    emptySet(),
                    emptyMap(),
                )
            }
            loaders[loader] = discovered
            discovered
        }

    private fun available(runtime: Int) =
        synchronized(runtimeLock) {
            if (
                runtime == 1 &&
                    discovered == 1 &&
                    runtimeStatus.get().state == LiveRuntimeState.NO_RUNTIME
            )
                runtimeStatus.set(LiveTargetStatus.READY)
        }

    private fun unavailable(state: LiveRuntimeState) {
        runtimeStatus.updateAndGet { previous ->
            when {
                discovered > 1 ->
                    LiveTargetStatus(LiveRuntimeState.MULTIPLE_RUNTIMES, runtimeCount = discovered)
                previous.state !in setOf(LiveRuntimeState.NO_RUNTIME, LiveRuntimeState.READY) ->
                    previous
                else -> LiveTargetStatus(state, runtimeCount = discovered)
            }
        }
        synchronized(captureLock) { recorder?.targetUnavailable() }
    }

    override fun enabled(runtime: Int): Boolean {
        val capturing = recorder?.isActive == true
        val accepted =
            runtime == 1 && runtimeStatus.get().state == LiveRuntimeState.READY && capturing
        if (capturing && !accepted && skippedCallback.compareAndSet(false, true)) {
            LOG.log(
                Level.WARNING,
                "Compose inspection skipped a callback: runtime=$runtime " +
                    "state=${runtimeStatus.get().state} thread=${Thread.currentThread().name} " +
                    "edt=${EventQueue.isDispatchThread()}",
            )
        }
        return accepted
    }

    override fun start(runtime: Int, key: Int, dirty1: Int, dirty2: Int, info: String) {
        if (enabled(runtime)) recorder?.start(key, dirty1, dirty2, info)
    }

    override fun end(runtime: Int) {
        if (enabled(runtime)) recorder?.end()
    }

    override fun failed() {
        unavailable(LiveRuntimeState.FAILED)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        TraceBridge.install(null)
        synchronized(captureLock) { recorder?.stop() }
        server.close()
        lease.shutdownNow()
    }

    private inner class Session : LiveRecordingSession {
        private var alive = true

        override fun status(): LiveTargetStatus = runtimeStatus.get()

        override fun execute(command: LiveCommand): Recording? =
            synchronized(captureLock) {
                checkAlive()
                when (command) {
                    LiveCommand.KEEP_ALIVE,
                    LiveCommand.STATUS -> null
                    LiveCommand.SNAPSHOT -> recorder?.snapshot()
                    LiveCommand.START -> {
                        check(runtimeStatus.get().state == LiveRuntimeState.READY)
                        check(recorder?.isActive != true)
                        recorder = CompositionRecorder(target).also { it.begin() }
                        recorder?.snapshot()
                    }
                    LiveCommand.STOP -> recorder?.stop()
                }
            }

        private fun checkAlive() {
            if (!alive || closed.get()) throw IOException("Inspection launch closed")
        }

        override fun abort() {
            synchronized(captureLock) {
                if (!alive) return
                alive = false
                recorder?.stop()
            }
            close()
        }
    }

    private companion object {
        private val LOG: System.Logger = System.getLogger(AgentHost::class.java.name)
    }
}
