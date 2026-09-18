package dev.sebastiano.jewel.tooling.recording

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionRecorderTest {
  private fun recorder(): CompositionRecorder =
    CompositionRecorder(CaptureTarget("Fixture"), AtomicLong()::getAndIncrement).also { it.begin() }

  @Test
  fun liveSnapshotsPreservePendingRootsAndFinalCounts() {
    val recorder = recorder()
    recorder.start(1, 0, 0, "root", 1, "UI")
    recorder.start(2, 0, 0, "child", 1, "UI")
    recorder.end(1)
    val pending = recorder.snapshot()
    pending.validate()
    assertEquals(CaptureStatus.ACTIVE, pending.status)
    assertTrue(pending.events.isEmpty())
    assertEquals(0, pending.fidelity.discardedPairs)
    recorder.end(1)
    val committed = recorder.snapshot()
    assertEquals(2, committed.events.size)
    assertTrue(pending.events.isEmpty())
    assertEquals(committed.events, recorder.stop().events)
    assertSame(recorder.stop(), recorder.snapshot())
  }

  @Test
  fun pollingCanObserveTheDurationLimit() {
    val now = AtomicLong(0)
    val recorder = CompositionRecorder(CaptureTarget("Fixture"), now::get)
    recorder.begin()
    now.set(RecordingLimits.DURATION_NS)
    val result = recorder.snapshot()
    assertEquals(CaptureStatus.TRUNCATED, result.status)
    assertEquals(StopReason.DURATION_LIMIT, result.stopReason)
    assertSame(result, recorder.stop())
  }

  @Test
  fun nestedSegmentsKeepInclusiveTimes() {
    val recorder = recorder()
    recorder.start(7, 1, -1, "outer", 1, "UI")
    recorder.start(7, 2, 3, "inner", 1, "UI")
    recorder.end(1)
    recorder.end(1)
    val result = recorder.stop()
    assertEquals(2, result.sites.size)
    assertEquals(listOf(TraceEvent(2, 1, 2, 3, 2, 3), TraceEvent(1, 1, 1, 4, 1, -1)), result.events)
    assertEquals(CaptureStatus.STOPPED, result.status)
    assertEquals(5L, result.durationNs)
    result.validate()
  }

  @Test
  fun incompleteRootDiscardsItsCompletedChildren() {
    val recorder = recorder()
    recorder.start(1, 0, 0, "root", 1, "UI")
    recorder.start(2, 0, 0, "child", 1, "UI")
    recorder.end(1)
    recorder.start(3, 0, 0, "other thread", 2, "worker")
    recorder.end(2)
    val result = recorder.stop()
    assertEquals(1, result.events.size)
    assertEquals(2L, result.events.single().threadId)
    assertEquals(1, result.fidelity.abandonedStarts)
    assertEquals(1, result.fidelity.discardedPairs)
  }

  @Test
  fun depthLimitAbandonsEveryOpenFrame() {
    val recorder = recorder()
    repeat(RecordingLimits.DEPTH + 1) { recorder.start(1, 0, 0, "nested", 1, "UI") }
    repeat(RecordingLimits.DEPTH + 1) { recorder.end(1) }
    val result = recorder.stop()
    assertEquals(StopReason.DEPTH_LIMIT, result.stopReason)
    assertTrue(result.events.isEmpty())
    assertEquals(RecordingLimits.DEPTH, result.fidelity.abandonedStarts)
    assertEquals(1, result.fidelity.rejectedStarts)
    assertTrue(result.fidelity.laterActivityUnrecorded)
  }

  @Test
  fun eventLimitKeepsTheCompletedPrefix() {
    val recorder = recorder()
    repeat(RecordingLimits.EVENTS + 1) {
      recorder.start(1, 0, 0, "same", 1, "UI")
      recorder.end(1)
    }
    val result = recorder.stop()
    assertEquals(StopReason.EVENT_LIMIT, result.stopReason)
    assertEquals(RecordingLimits.EVENTS, result.events.size)
    assertEquals(1, result.fidelity.rejectedStarts)
  }

  @Test
  fun siteLimitDoesNotMergeIdentities() {
    val recorder = recorder()
    repeat(RecordingLimits.SITES + 1) {
      recorder.start(1, 0, 0, "site $it", 1, "UI")
      recorder.end(1)
    }
    val result = recorder.stop()
    assertEquals(StopReason.SITE_LIMIT, result.stopReason)
    assertEquals(RecordingLimits.SITES, result.sites.size)
  }

  @Test
  fun threadLimitIsTerminal() {
    val recorder = recorder()
    repeat(RecordingLimits.THREADS + 1) {
      recorder.start(1, 0, 0, "site", it + 1L, "worker")
      recorder.end(it + 1L)
    }
    val result = recorder.stop()
    assertEquals(StopReason.THREAD_LIMIT, result.stopReason)
    assertEquals(RecordingLimits.THREADS, result.threads.size)
  }

  @Test
  fun identityStringsAreRejectedInsteadOfTruncated() {
    for (info in
      listOf(
        "x".repeat(RecordingLimits.INFO_BYTES + 1),
        "é".repeat(RecordingLimits.INFO_BYTES),
        "\uD800",
      )) {
      val recorder = recorder()
      recorder.start(1, 0, 0, info, 1, "UI")
      val result = recorder.stop()
      assertEquals(StopReason.STRING_LIMIT, result.stopReason)
      assertTrue(result.sites.isEmpty())
    }
  }

  @Test
  fun displayNamesHaveBoundedValidUnicode() {
    val recorder = recorder()
    recorder.start(1, 0, 0, "site", 1, "😀".repeat(500) + "\uD800")
    recorder.end(1)
    val result = recorder.stop()
    assertEquals(RecordingLimits.LABEL_CHARACTERS, result.threads.single().name.length)
    result.validate()
  }

  @Test
  fun unmatchedEndsHaveAnExactBoundedCount() {
    val recorder = recorder()
    repeat(RecordingLimits.EVENTS + 2) { recorder.end(99) }
    val result = recorder.stop()
    assertEquals(StopReason.COUNTER_LIMIT, result.stopReason)
    assertEquals(RecordingLimits.EVENTS, result.fidelity.unmatchedEnds)
  }

  @Test
  fun clockFailureKeepsOnlyTheValidPrefix() {
    var now = 0L
    val recorder = CompositionRecorder(CaptureTarget("Fixture")) { now }.also { it.begin() }
    now = 10
    recorder.start(1, 0, 0, "valid", 1, "UI")
    now = 20
    recorder.end(1)
    now = 30
    recorder.start(2, 0, 0, "incomplete", 1, "UI")
    now = 15
    recorder.end(1)
    val result = recorder.stop()
    assertEquals(CaptureStatus.FAILED, result.status)
    assertEquals(StopReason.CLOCK_FAILURE, result.stopReason)
    assertEquals(30L, result.durationNs)
    assertEquals(1, result.events.size)
    assertEquals(1, result.fidelity.abandonedStarts)
  }

  @Test
  fun lateStopReportsTheExplicitWindowLimit() {
    var now = 0L
    val recorder = CompositionRecorder(CaptureTarget("Fixture")) { now }.also { it.begin() }
    recorder.start(1, 0, 0, "incomplete", 1, "UI")
    now = RecordingLimits.DURATION_NS + 100
    val result = recorder.stop()
    assertEquals(StopReason.DURATION_LIMIT, result.stopReason)
    assertEquals(RecordingLimits.DURATION_NS, result.durationNs)
    assertEquals(1, result.fidelity.abandonedStarts)
  }

  @Test
  fun nanoTimeWrapDoesNotCreateANegativeDuration() {
    var now = Long.MAX_VALUE - 3
    val recorder = CompositionRecorder(CaptureTarget("Fixture")) { now }.also { it.begin() }
    now = Long.MIN_VALUE + 3
    recorder.start(1, 0, 0, "wrapped", 1, "UI")
    now++
    recorder.end(1)
    now++
    val result = recorder.stop()
    assertEquals(CaptureStatus.STOPPED, result.status)
    assertEquals(9L, result.durationNs)
  }

  @Test
  fun stoppedRecorderIgnoresLateCallbacks() {
    val recorder = recorder()
    val result = recorder.stop()
    recorder.start(1, 0, 0, "late", 1, "UI")
    recorder.end(1)
    assertFalse(recorder.isActive)
    assertSame(result, recorder.stop())
    assertThrows(IllegalStateException::class.java) { recorder.begin() }
  }

  @Test
  fun concurrentThreadsProduceSeparateBalancedSegments() {
    val recorder = recorder()
    val pool = Executors.newFixedThreadPool(4)
    val ready = CountDownLatch(4)
    val start = CountDownLatch(1)
    try {
      val jobs =
        (1L..4L).map { id ->
          pool.submit {
            ready.countDown()
            check(start.await(10, TimeUnit.SECONDS))
            repeat(100) {
              recorder.start(1, 0, 0, "outer", id, "worker $id")
              recorder.start(2, 0, 0, "inner", id, "worker $id")
              recorder.end(id)
              recorder.end(id)
            }
          }
        }
      assertTrue(ready.await(10, TimeUnit.SECONDS))
      start.countDown()
      jobs.forEach { it.get(10, TimeUnit.SECONDS) }
      val result = recorder.stop()
      assertEquals(800, result.events.size)
      assertEquals(4, result.threads.size)
      assertEquals(0, result.fidelity.abandonedStarts)
      result.validate()
    } finally {
      pool.shutdownNow()
    }
  }
}
