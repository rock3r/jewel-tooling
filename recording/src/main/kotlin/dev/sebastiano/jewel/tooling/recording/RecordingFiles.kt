package dev.sebastiano.jewel.tooling.recording

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Experimental
object RecordingFiles {
  /**
   * Creates a new file after serialization succeeds. Run this method on a worker thread. Failed
   * exports can leave an incomplete file when the file system cannot confirm its identity.
   */
  fun writeNew(path: Path, recording: Recording, checkCanceled: () -> Unit = {}) {
    val bytes = RecordingCodec.write(recording, checkCanceled)
    checkCanceled()
    val channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    var complete = false
    var key: Any? = null
    try {
      channel.use {
        key = fileKey(path)
        checkCanceled()
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
          checkCanceled()
          if (it.write(buffer) == 0) throw IOException("Output made no progress")
        }
        it.force(true)
      }
      complete = true
    } finally {
      if (!complete && key != null) removeIncomplete(path, key)
    }
  }

  /** Reads a local file without using its contents as paths or commands. */
  fun read(path: Path, checkCanceled: () -> Unit = {}): Recording =
    RecordingCodec.read(Files.newInputStream(path), checkCanceled)

  private fun fileKey(path: Path): Any? =
    Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()

  private fun removeIncomplete(path: Path, expectedKey: Any) {
    try {
      if (fileKey(path) == expectedKey) Files.deleteIfExists(path)
    } catch (_: IOException) {
      // The incomplete file stays on disk if cleanup fails.
    }
  }
}
