package dev.sebastiano.jewel.tooling.recording

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.jetbrains.annotations.ApiStatus

/**
 * Connects an explicitly owned dispatcher to local inspection. The scheduler must use the host's
 * single composition thread. This host exclusively owns the dispatcher's sink until close.
 */
@ApiStatus.Experimental
class LiveCompositionHost(
    dispatcher: OwnedCompositionTracer,
    target: CaptureTarget,
    schedule: (Runnable) -> Unit,
) : AutoCloseable {
    private val server = LiveRecordingServer(target) { Session(dispatcher, target, schedule) }

    val connectionString: String
        get() = server.endpoint.connectionString()

    override fun close() = server.close()

    private class Session(
        private val dispatcher: OwnedCompositionTracer,
        private val target: CaptureTarget,
        private val schedule: (Runnable) -> Unit,
    ) : LiveRecordingSession {
        private val lock = Any()
        private var alive = true
        private var attached = false
        private var last: Recording? = null

        override fun execute(command: LiveCommand): Recording? =
            when (command) {
                LiveCommand.KEEP_ALIVE -> null
                LiveCommand.SNAPSHOT ->
                    synchronized(lock) {
                        checkAlive()
                        snapshot()
                    }
                else ->
                    onCompositionThread {
                        when (command) {
                            LiveCommand.START -> {
                                check(!attached) { "A session is attached" }
                                dispatcher.startRecording(target)
                                attached = true
                                last = null
                                snapshot()
                            }
                            else -> stop()
                        }
                    }
            }

        private fun snapshot(): Recording? {
            if (!attached) return last
            val next = dispatcher.snapshot()
            last = if (next.status == CaptureStatus.ACTIVE) next else stop()
            return last
        }

        private fun stop(): Recording? {
            if (attached) {
                last = dispatcher.stopRecording()
                attached = false
            }
            return last
        }

        @Suppress(
            "ThrowsCount"
        ) // Translate each bounded command failure at the transport boundary.
        private fun onCompositionThread(action: () -> Recording?): Recording? {
            val future = CompletableFuture<Recording?>()
            schedule(
                Runnable {
                    synchronized(lock) {
                        if (!alive) future.completeExceptionally(IOException("Connection closed"))
                        else {
                            try {
                                future.complete(action())
                            } catch (exception: IllegalStateException) {
                                future.completeExceptionally(exception)
                            }
                        }
                    }
                }
            )
            try {
                return future.get(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (exception: ExecutionException) {
                val cause = exception.cause
                if (cause is IllegalStateException)
                    throw IllegalStateException("Capture command failed", exception)
                throw IOException("Capture command failed", exception)
            } catch (exception: TimeoutException) {
                abort()
                throw IOException("Composition thread timed out", exception)
            } catch (exception: InterruptedException) {
                abort()
                Thread.currentThread().interrupt()
                throw IOException("Capture command interrupted", exception)
            }
        }

        private fun checkAlive() {
            if (!alive) throw IOException("Connection closed")
        }

        override fun abort() {
            synchronized(lock) {
                alive = false
                stop()
            }
        }
    }

    companion object {
        private const val COMMAND_TIMEOUT_MS = 2000L
    }
}
