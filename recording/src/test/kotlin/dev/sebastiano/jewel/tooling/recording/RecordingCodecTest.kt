package dev.sebastiano.jewel.tooling.recording

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCodecTest {
  private fun sample(): Recording =
    Recording(
      "123e4567-e89b-12d3-a456-426614174000",
      CaptureTarget("Fixture", runtime = "declared"),
      10,
      CaptureStatus.STOPPED,
      StopReason.MANUAL,
      CaptureFidelity(0, 0, 0, 0, false),
      listOf(TraceSite(1, -7, "example.Function (Example.kt:12)")),
      listOf(TraceThread(1, "UI")),
      listOf(TraceEvent(1, 1, 1, 9, Int.MIN_VALUE, Int.MAX_VALUE)),
    )

  private fun json(): String = RecordingCodec.write(sample()).toString(Charsets.UTF_8)

  private fun read(value: String): Recording = RecordingCodec.read(value.byteInputStream())

  private fun rejected(value: String) {
    assertThrows(RecordingFormatException::class.java) { read(value) }
  }

  @Test
  fun legacyFinalAndLiveSnapshotsHaveSeparateFileRules() {
    val legacy = sample().copy(schemaVersion = 1)
    assertEquals(legacy, RecordingCodec.read(RecordingCodec.write(legacy).inputStream()))
    val live = sample().copy(status = CaptureStatus.ACTIVE, stopReason = StopReason.NONE)
    assertEquals(live, RecordingCodec.read(RecordingCodec.write(live).inputStream()))
    assertThrows(IllegalArgumentException::class.java) { live.copy(schemaVersion = 1).validate() }
    val directory = java.nio.file.Files.createTempDirectory("live-recording-test")
    val path = directory.resolve("capture.json")
    try {
      assertThrows(RecordingFormatException::class.java) { RecordingFiles.writeNew(path, live) }
      assertTrue(!java.nio.file.Files.exists(path))
      java.nio.file.Files.write(path, RecordingCodec.write(live))
      assertThrows(RecordingFormatException::class.java) { RecordingFiles.read(path) }
    } finally {
      java.nio.file.Files.deleteIfExists(path)
      java.nio.file.Files.deleteIfExists(directory)
    }
  }

  @Test
  fun futureStatusReportsVersionBeforeEnumFailure() {
    val text =
      json().replace("STOPPED", "FUTURE").replace("\"schemaVersion\":2,", "").dropLast(1) +
        ",\"schemaVersion\":3}"
    val failure = assertThrows(RecordingFormatException::class.java) { read(text) }
    assertEquals(RecordingError.UNSUPPORTED_VERSION, failure.code)
  }

  @Test
  fun completeRoundTripPreservesMasksAndLabels() {
    assertEquals(sample(), read(json()))
  }

  @Test
  fun arbitraryFieldOrderIsAccepted() {
    val text = json().replace("\"schemaVersion\":2,", "").dropLast(1) + ",\"schemaVersion\":2}"
    assertEquals(sample(), read(text))
  }

  @Test
  fun missingDuplicateAndUnknownFieldsAreRejected() {
    rejected(json().replace("\"schemaVersion\":2,", ""))
    rejected(json().replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"schemaVersion\":2"))
    rejected(json().replace("\"schemaVersion\":2", "\"unknown\":{},\"schemaVersion\":2"))
    rejected(json().replace("\"build\":null,", ""))
    rejected(json().replace("\"key\":-7", "\"key\":-7,\"key\":-7"))
  }

  @Test
  fun futureVersionsHaveADistinctError() {
    val failure =
      assertThrows(RecordingFormatException::class.java) {
        read(json().replace("\"schemaVersion\":2", "\"schemaVersion\":3"))
      }
    assertEquals(RecordingError.UNSUPPORTED_VERSION, failure.code)
  }

  @Test
  fun numericCoercionsAndOverflowAreRejected() {
    for (replacement in listOf("1.0", "\"1\"", "null", "true", "9223372036854775808", "-1")) {
      rejected(json().replace("\"durationNs\":10", "\"durationNs\":$replacement"))
    }
    rejected(json().replace("\"key\":-7", "\"key\":2147483648"))
    rejected(json().replace("\"endNs\":9", "\"endNs\":11"))
  }

  @Test
  fun invalidIdentitiesAndReferencesAreRejected() {
    rejected(json().replace("\"siteId\":1", "\"siteId\":2"))
    rejected(json().replace("\"threadId\":1", "\"threadId\":2"))
    rejected(json().replace("\"id\":1,\"key\":-7", "\"id\":0,\"key\":-7"))
    rejected(json().replace("123e4567-e89b-12d3-a456-426614174000", "123"))
    rejected(json().replace("\"startNs\":1", "\"startNs\":10"))
  }

  @Test
  fun unsupportedCapabilitiesAndStatusCombinationsAreRejected() {
    rejected(json().replace("inclusive-duration", "observed-skips"))
    rejected(json().replace("inclusive-duration", "paired-composition-trace-events"))
    rejected(json().replace("STOPPED", "FAILED"))
    rejected(json().replace("MANUAL", "CLOCK_FAILURE"))
  }

  @Test
  fun truncatedAndTrailingDocumentsAreRejected() {
    val source = json()
    for (index in source.indices step 13) rejected(source.take(index))
    rejected(source + source)
    rejected(source + " false")
  }

  @Test
  fun invalidUnicodeAndUtf8AreRejected() {
    rejected(json().replace("Fixture", "\\uD800"))
    val bytes = json().toByteArray()
    val start = json().indexOf("Fixture")
    bytes[start] = 0xc0.toByte()
    bytes[start + 1] = 0xaf.toByte()
    assertThrows(RecordingFormatException::class.java) {
      RecordingCodec.read(ByteArrayInputStream(bytes))
    }
  }

  @Test
  fun hostileNestingAndLongStringsAreRejected() {
    rejected("[".repeat(1000) + "0" + "]".repeat(1000))
    rejected(json().replace("Fixture", "x".repeat(5000)))
    rejected(json().replace("Fixture", "x".repeat(RecordingLimits.LABEL_CHARACTERS + 1)))
  }

  @Test
  fun actualByteLimitAppliesToWhitespaceAndClosesInput() {
    val input =
      object : InputStream() {
        var reads = 0
        var closed = false

        override fun read(): Int {
          reads++
          return ' '.code
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
          buffer.fill(' '.code.toByte(), offset, offset + length)
          reads += length
          return length
        }

        override fun close() {
          closed = true
        }
      }
    val failure = assertThrows(RecordingFormatException::class.java) { RecordingCodec.read(input) }
    assertEquals(RecordingError.TOO_LARGE, failure.code)
    assertTrue(input.reads <= RecordingLimits.FILE_BYTES + 1)
    assertTrue(input.closed)
  }

  @Test
  fun cancellationClosesInputAndPropagates() {
    var closed = false
    val input =
      object : ByteArrayInputStream(json().toByteArray()) {
        override fun close() {
          closed = true
          super.close()
        }
      }
    assertThrows(CancellationException::class.java) {
      RecordingCodec.read(input) { throw CancellationException("test") }
    }
    assertTrue(closed)
  }

  @Test
  fun decodedListsCannotBeChangedThroughAMutableCast() {
    val result = read(json())
    assertThrows(UnsupportedOperationException::class.java) {
      (result.events as MutableList<TraceEvent>).clear()
    }
  }

  @Test
  fun duplicateIdsAndExcessEventsAreRejected() {
    val text = json()
    val site = "{\"id\":1,\"key\":-7,\"info\":\"example.Function (Example.kt:12)\"}"
    rejected(text.replace(site, "$site,$site"))
    val many = sample().copy(events = List(RecordingLimits.EVENTS + 1) { sample().events.single() })
    assertThrows(IllegalArgumentException::class.java) { RecordingCodec.write(many) }
  }

  @Test
  fun readerRejectsExcessEventsBeforeAcceptingTheFile() {
    val event =
      """{"siteId":1,"threadId":1,"startNs":1,"endNs":9,"dirty1":-2147483648,"dirty2":2147483647}"""
    val text = json()
    assertTrue(text.contains(event))
    rejected(text.replace(event, List(RecordingLimits.EVENTS + 1) { event }.joinToString(",")))
  }

  @Test
  fun inputThatMakesNoProgressFailsAndCloses() {
    var closed = false
    val input =
      object : InputStream() {
        override fun read(): Int = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0

        override fun close() {
          closed = true
        }
      }
    assertThrows(RecordingFormatException::class.java) { RecordingCodec.read(input) }
    assertTrue(closed)
  }
}
