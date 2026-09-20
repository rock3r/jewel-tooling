package dev.sebastiano.jewel.tooling.recording

import java.io.IOException
import java.nio.ByteBuffer
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Experimental
enum class LiveRuntimeState {
    NO_RUNTIME,
    READY,
    UNSUPPORTED_ABI,
    MULTIPLE_RUNTIMES,
    FAILED,
}

/** Describes recorder availability without exposing a target classloader or object. */
@ApiStatus.Experimental
data class LiveTargetStatus(
    val state: LiveRuntimeState,
    val runtimeId: Int = 0,
    val runtimeCount: Int = 0,
) {
    init {
        require(runtimeCount in 0..MAX_RUNTIMES)
        require(runtimeId in 0..MAX_RUNTIMES)
        require(state != LiveRuntimeState.READY || (runtimeId > 0 && runtimeCount == 1))
        require(state != LiveRuntimeState.MULTIPLE_RUNTIMES || runtimeCount > 1)
    }

    internal fun encode(): ByteArray =
        ByteBuffer.allocate(WIRE_BYTES)
            .putInt(state.ordinal)
            .putInt(runtimeId)
            .putInt(runtimeCount)
            .array()

    companion object {
        private const val MAX_RUNTIMES = 64
        internal const val WIRE_BYTES = 12
        val READY = LiveTargetStatus(LiveRuntimeState.READY, 1, 1)

        @Suppress("ThrowsCount") // Reject malformed fields before constructing the status.
        internal fun decode(bytes: ByteArray): LiveTargetStatus {
            if (bytes.size != WIRE_BYTES) throw IOException("Invalid target status")
            val input = ByteBuffer.wrap(bytes)
            val state =
                LiveRuntimeState.entries.getOrNull(input.int)
                    ?: throw IOException("Invalid runtime state")
            return try {
                LiveTargetStatus(state, input.int, input.int)
            } catch (failure: IllegalArgumentException) {
                throw IOException("Invalid target status", failure)
            }
        }
    }
}
