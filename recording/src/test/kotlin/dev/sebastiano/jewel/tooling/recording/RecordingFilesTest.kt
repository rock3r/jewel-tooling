package dev.sebastiano.jewel.tooling.recording

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingFilesTest {
  @get:Rule val temporary = TemporaryFolder()

  private fun sample(): Recording {
    val recorder = CompositionRecorder(CaptureTarget("Fixture"))
    recorder.begin()
    recorder.start(1, 0, 0, "site", 1, "UI")
    recorder.end(1)
    return recorder.stop()
  }

  @Test
  fun exportRoundTripCreatesANewFile() {
    val path = temporary.root.toPath().resolve("recording.json")
    val sample = sample()
    RecordingFiles.writeNew(path, sample)
    assertEquals(sample, RecordingFiles.read(path))
  }

  @Test
  fun existingFileIsNeverOverwritten() {
    val path = temporary.newFile("existing.json").toPath()
    Files.writeString(path, "keep me")
    assertThrows(FileAlreadyExistsException::class.java) { RecordingFiles.writeNew(path, sample()) }
    assertEquals("keep me", Files.readString(path))
  }

  @Test
  fun cancellationRemovesOnlyTheNewIncompleteFile() {
    val path = temporary.root.toPath().resolve("cancelled.json")
    assertThrows(CancellationException::class.java) {
      RecordingFiles.writeNew(path, sample()) {
        if (Files.exists(path)) throw CancellationException("test")
      }
    }
    assertFalse(Files.exists(path))
  }

  @Test
  fun cancellationDoesNotDeleteAReplacementFile() {
    val path = temporary.root.toPath().resolve("replaced.json")
    val original = temporary.root.toPath().resolve("original.json")
    assertThrows(CancellationException::class.java) {
      RecordingFiles.writeNew(path, sample()) {
        if (Files.exists(path)) {
          Files.move(path, original)
          Files.writeString(path, "replacement")
          throw CancellationException("test")
        }
      }
    }
    assertEquals("replacement", Files.readString(path))
  }

  @Test
  fun invalidRecordingDoesNotCreateAFile() {
    val path = temporary.root.toPath().resolve("invalid.json")
    assertThrows(IllegalArgumentException::class.java) {
      RecordingFiles.writeNew(path, sample().copy(durationNs = -1))
    }
    assertFalse(Files.exists(path))
  }
}
