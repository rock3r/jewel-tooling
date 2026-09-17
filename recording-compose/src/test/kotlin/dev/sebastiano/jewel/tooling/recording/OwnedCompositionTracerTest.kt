package dev.sebastiano.jewel.tooling.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedCompositionTracerTest {
  @Test
  fun `callbacks outside a recording are ignored`() {
    val tracer = OwnedCompositionTracer()
    tracer.traceEventStart(1, 2, 3, "ignored")
    tracer.traceEventEnd()
    assertFalse(tracer.isTraceInProgress())
    tracer.startRecording(CaptureTarget("test"))
    assertTrue(tracer.isTraceInProgress())
    tracer.traceEventStart(1, 2, 3, "observed")
    tracer.traceEventEnd()
    val result = tracer.stopRecording()
    assertFalse(tracer.isTraceInProgress())
    tracer.traceEventEnd()
    assertEquals(1, result.events.size)
    assertEquals("observed", result.sites.single().info)
    assertEquals(2, result.events.single().dirty1)
    assertEquals(3, result.events.single().dirty2)
  }

  @Test
  fun `sessions require an explicit stop and have separate identities`() {
    val tracer = OwnedCompositionTracer()
    tracer.startRecording(CaptureTarget("first"))
    assertThrows(IllegalStateException::class.java) {
      tracer.startRecording(CaptureTarget("second"))
    }
    val first = tracer.stopRecording()
    tracer.startRecording(CaptureTarget("second"))
    val second = tracer.stopRecording()
    assertNotEquals(first.sessionId, second.sessionId)
    assertTrue(second.events.isEmpty())
    assertThrows(IllegalStateException::class.java) { tracer.stopRecording() }
  }

  @Test
  fun `truncated session stays attached until stopped`() {
    val tracer = OwnedCompositionTracer()
    tracer.startRecording(CaptureTarget("test"))
    tracer.traceEventStart(1, 0, 0, "x".repeat(RecordingLimits.INFO_BYTES + 1))
    assertFalse(tracer.isTraceInProgress())
    assertThrows(IllegalStateException::class.java) {
      tracer.startRecording(CaptureTarget("second"))
    }
    assertEquals(StopReason.STRING_LIMIT, tracer.stopRecording().stopReason)
  }

  @Test
  fun `close releases the sink and permits a later controlled session`() {
    val tracer = OwnedCompositionTracer()
    tracer.startRecording(CaptureTarget("test"))
    tracer.traceEventStart(1, 0, 0, "unfinished")
    tracer.close()
    tracer.close()
    assertFalse(tracer.isTraceInProgress())
    tracer.startRecording(CaptureTarget("second"))
    assertTrue(tracer.stopRecording().events.isEmpty())
  }
}
