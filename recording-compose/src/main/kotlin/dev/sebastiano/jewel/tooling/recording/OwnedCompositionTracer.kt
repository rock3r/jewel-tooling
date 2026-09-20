package dev.sebastiano.jewel.tooling.recording

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import org.jetbrains.annotations.ApiStatus

/**
 * Routes callbacks from one host runtime to a recording. Start and stop between controlled UI
 * interactions. The host must reserve the tracer slot for this dispatcher before compositions
 * start.
 */
@OptIn(InternalComposeTracingApi::class)
@ApiStatus.Experimental
class OwnedCompositionTracer internal constructor() : CompositionTracer, AutoCloseable {
    private val lock = Any()
    @Volatile private var recorder: CompositionRecorder? = null

    /** Starts a fresh session. An earlier session must be stopped first, even after truncation. */
    fun startRecording(target: CaptureTarget) {
        synchronized(lock) {
            check(recorder == null) { "A recording is already attached" }
            val next = CompositionRecorder(target)
            next.begin()
            recorder = next
        }
    }

    /**
     * Detaches the sink and returns its bounded snapshot. Serialization belongs on a worker thread.
     */
    fun stopRecording(): Recording =
        synchronized(lock) {
            val current = checkNotNull(recorder) { "No recording is attached" }
            recorder = null
            current.stop()
        }

    /** Reads the attached recording without ending an active capture. */
    fun snapshot(): Recording =
        synchronized(lock) { checkNotNull(recorder) { "No recording is attached" }.snapshot() }

    override fun isTraceInProgress(): Boolean = recorder?.isActive == true

    override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
        recorder?.start(key, dirty1, dirty2, info)
    }

    override fun traceEventEnd() {
        recorder?.end()
    }

    /** Releases the recording sink. The dispatcher retains ownership of the host tracer slot. */
    override fun close() {
        synchronized(lock) {
            val current = recorder
            recorder = null
            current?.stop()
        }
    }

    companion object {
        private var installed = false

        /**
         * Installs the dispatcher once in an explicitly controlled development host. This method
         * cannot detect or restore another tracer. Do not call it from a normal authoring IDE
         * plugin.
         */
        fun installOwnedDispatcher(): OwnedCompositionTracer =
            synchronized(this) {
                check(!installed) { "The owned dispatcher is already installed" }
                val dispatcher = OwnedCompositionTracer()
                Composer.setTracer(dispatcher)
                installed = true
                dispatcher
            }
    }
}
