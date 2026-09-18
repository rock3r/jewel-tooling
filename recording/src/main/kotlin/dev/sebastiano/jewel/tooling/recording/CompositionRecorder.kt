package dev.sebastiano.jewel.tooling.recording

import java.util.UUID
import org.jetbrains.annotations.ApiStatus

/** Collects balanced trace segments. Call begin and stop between controlled UI interactions. */
@ApiStatus.Experimental
class CompositionRecorder(
  private val target: CaptureTarget,
  private val clock: () -> Long = System::nanoTime,
) {
  private data class SiteKey(val key: Int, val info: String)

  private data class Frame(val siteId: Int, val start: Long, val dirty1: Int, val dirty2: Int)

  private class ThreadState(val label: TraceThread) {
    val frames = ArrayList<Frame>()
    val pending = ArrayList<TraceEvent>()
  }

  private val lock = Any()
  private val sessionId = UUID.randomUUID().toString()
  private val sites = LinkedHashMap<SiteKey, TraceSite>()
  private val threads = LinkedHashMap<Long, ThreadState>()
  private val events = ArrayList<TraceEvent>()
  private var started = false
  private var origin = 0L
  private var elapsed = 0L
  private var acceptedStarts = 0
  private var abandonedStarts = 0
  private var discardedPairs = 0
  private var unmatchedEnds = 0
  private var rejectedStarts = 0
  private var reason: StopReason? = null
  private var result: Recording? = null
  @Volatile
  var isActive: Boolean = false
    private set

  fun begin() =
    synchronized(lock) {
      check(!started)
      require(validLabel(target.displayName) && target.displayName.isNotBlank())
      require(
        listOf(target.build, target.runtime, target.compiler).all { it == null || validLabel(it) }
      )
      origin = clock()
      started = true
      isActive = true
    }

  @Suppress("LongParameterList") // Matches the Compose compiler start callback arity.
  fun start(
    key: Int,
    dirty1: Int,
    dirty2: Int,
    info: String,
    threadId: Long = Thread.currentThread().threadId(),
    threadName: String = Thread.currentThread().name,
  ) {
    if (!isActive) return
    synchronized(lock) {
      if (isActive && advanceClock()) acceptStart(key, dirty1, dirty2, info, threadId, threadName)
    }
  }

  @Suppress("LongParameterList") // Forwards the same compiler start fields into the session.
  private fun acceptStart(
    key: Int,
    dirty1: Int,
    dirty2: Int,
    info: String,
    threadId: Long,
    threadName: String,
  ) {
    val limit = startLimit(info, threadId)
    if (limit != null) {
      rejectedStarts++
      terminate(limit)
      return
    }
    val identity = SiteKey(key, info)
    if (identity !in sites && sites.size == RecordingLimits.SITES) {
      rejectedStarts++
      terminate(StopReason.SITE_LIMIT)
    } else {
      val site = sites.getOrPut(identity) { TraceSite(sites.size + 1, key, info) }
      val state =
        threads.getOrPut(threadId) { ThreadState(TraceThread(threadId, threadLabel(threadName))) }
      state.frames.add(Frame(site.id, elapsed, dirty1, dirty2))
      acceptedStarts++
    }
  }

  private fun startLimit(info: String, threadId: Long): StopReason? =
    when {
      acceptedStarts == RecordingLimits.EVENTS -> StopReason.EVENT_LIMIT
      !validText(info, RecordingLimits.INFO_BYTES, RecordingLimits.INFO_BYTES) ->
        StopReason.STRING_LIMIT
      threadId <= 0 -> StopReason.THREAD_LIMIT
      threadId !in threads && threads.size == RecordingLimits.THREADS -> StopReason.THREAD_LIMIT
      threads[threadId]?.frames?.size == RecordingLimits.DEPTH -> StopReason.DEPTH_LIMIT
      else -> null
    }

  fun end(threadId: Long = Thread.currentThread().threadId()) {
    if (!isActive) return
    synchronized(lock) { if (isActive && advanceClock()) completeEnd(threadId) }
  }

  private fun completeEnd(threadId: Long) {
    val state = threads[threadId]
    if (state == null || state.frames.isEmpty()) {
      unmatchedEnds++
      if (unmatchedEnds == RecordingLimits.EVENTS) terminate(StopReason.COUNTER_LIMIT)
    } else {
      val frame = state.frames.removeAt(state.frames.lastIndex)
      state.pending.add(
        TraceEvent(frame.siteId, threadId, frame.start, elapsed, frame.dirty1, frame.dirty2)
      )
      if (state.frames.isEmpty()) {
        events.addAll(state.pending)
        state.pending.clear()
      }
    }
  }

  /** Copies committed segments without ending an active recording. */
  fun snapshot(): Recording =
    synchronized(lock) {
      check(started)
      result?.let {
        return it
      }
      if (!isActive || !advanceClock()) return stop()
      Recording(
        sessionId,
        target,
        elapsed,
        CaptureStatus.ACTIVE,
        StopReason.NONE,
        CaptureFidelity(abandonedStarts, discardedPairs, unmatchedEnds, rejectedStarts, false),
        java.util.List.copyOf(sites.values),
        java.util.List.copyOf(threads.values.map { it.label }),
        java.util.List.copyOf(events),
      )
    }

  fun stop(): Recording =
    synchronized(lock) {
      check(started)
      result?.let {
        return it
      }
      if (isActive && advanceClock()) terminate(StopReason.MANUAL)
      val finalReason = checkNotNull(reason)
      val status =
        when (finalReason) {
          StopReason.MANUAL -> CaptureStatus.STOPPED
          StopReason.CLOCK_FAILURE,
          StopReason.TARGET_UNAVAILABLE -> CaptureStatus.FAILED
          else -> CaptureStatus.TRUNCATED
        }
      val snapshot =
        Recording(
          sessionId,
          target,
          elapsed,
          status,
          finalReason,
          CaptureFidelity(
            abandonedStarts,
            discardedPairs,
            unmatchedEnds,
            rejectedStarts,
            status != CaptureStatus.STOPPED,
          ),
          java.util.List.copyOf(sites.values),
          java.util.List.copyOf(threads.values.map { it.label }),
          java.util.List.copyOf(events),
        )
      snapshot.validate()
      result = snapshot
      sites.clear()
      threads.clear()
      events.clear()
      snapshot
    }

  /** Ends capture when its target can no longer supply reliable trace events. */
  fun targetUnavailable(): Recording =
    synchronized(lock) {
      check(started)
      if (isActive) terminate(StopReason.TARGET_UNAVAILABLE)
      stop()
    }

  private fun advanceClock(): Boolean {
    val next = clock() - origin
    return when {
      next < elapsed || next < 0 -> {
        terminate(StopReason.CLOCK_FAILURE)
        false
      }
      next >= RecordingLimits.DURATION_NS -> {
        elapsed = RecordingLimits.DURATION_NS
        terminate(StopReason.DURATION_LIMIT)
        false
      }
      else -> {
        elapsed = next
        true
      }
    }
  }

  private fun terminate(stopReason: StopReason) {
    isActive = false
    reason = stopReason
    for (state in threads.values) {
      abandonedStarts += state.frames.size
      discardedPairs += state.pending.size
      state.frames.clear()
      state.pending.clear()
    }
  }
}
