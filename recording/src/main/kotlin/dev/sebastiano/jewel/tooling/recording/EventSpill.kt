package dev.sebastiano.jewel.tooling.recording

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Appends completed events to a bounded temp file. Not safe for concurrent writers. */
internal class EventSpill(
    private val limitBytes: Long = RecordingLimits.SPILL_BYTES,
    private val path: Path = Files.createTempFile("jewel-compose-events-", ".bin"),
) : Closeable {
    private val output =
        DataOutputStream(BufferedOutputStream(Files.newOutputStream(path), BUFFER_BYTES))
    private var bytes = 0L
    private var writing = true
    private var closed = false

    fun append(event: TraceEvent): Boolean {
        check(writing && !closed)
        if (bytes + RecordingLimits.EVENT_RECORD_BYTES > limitBytes) return false
        output.writeInt(event.siteId)
        output.writeLong(event.threadId)
        output.writeLong(event.startNs)
        output.writeLong(event.endNs)
        output.writeInt(event.dirty1)
        output.writeInt(event.dirty2)
        bytes += RecordingLimits.EVENT_RECORD_BYTES
        return true
    }

    fun finishWrite() {
        if (!writing) return
        writing = false
        output.flush()
        output.close()
    }

    fun readAll(checkCanceled: () -> Unit = {}): List<TraceEvent> {
        finishWrite()
        if (bytes == 0L) return emptyList()
        val count = (bytes / RecordingLimits.EVENT_RECORD_BYTES).toInt()
        DataInputStream(BufferedInputStream(Files.newInputStream(path), BUFFER_BYTES)).use { input
            ->
            val events = ArrayList<TraceEvent>(count)
            repeat(count) {
                checkCanceled()
                events.add(
                    TraceEvent(
                        input.readInt(),
                        input.readLong(),
                        input.readLong(),
                        input.readLong(),
                        input.readInt(),
                        input.readInt(),
                    )
                )
            }
            return java.util.List.copyOf(events)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            finishWrite()
        } catch (_: IOException) {}
        Files.deleteIfExists(path)
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1_024
    }
}
