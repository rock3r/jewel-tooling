package dev.sebastiano.jewel.tooling.recording

import java.io.IOException
import java.lang.System.Logger.Level
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.jetbrains.annotations.ApiStatus

/** Collects balanced trace segments. Call begin and stop between controlled UI interactions. */
@ApiStatus.Experimental
@Suppress("TooManyFunctions")
class CompositionRecorder(
    private val target: CaptureTarget,
    private val clock: () -> Long = System::nanoTime,
    private val eventLimit: Int = RecordingLimits.EVENTS,
    queueLimit: Int = RecordingLimits.QUEUE,
) {
    private data class SiteKey(val key: Int, val info: String)

    private data class Frame(val siteId: Int, val start: Long, val dirty1: Int, val dirty2: Int)

    private class ThreadState(val label: TraceThread) {
        val frames = ArrayList<Frame>()
        val pending = ArrayList<TraceEvent>()
    }

    private sealed interface Work

    private class StartWork(
        val key: Int,
        val dirty1: Int,
        val dirty2: Int,
        val info: String,
        val threadId: Long,
        val threadName: String,
        val at: Long,
    ) : Work

    private class EndWork(val threadId: Long, val at: Long) : Work

    private class DrainWork(var at: Long, val done: CountDownLatch) : Work {
        var snapshot: Recording? = null
    }

    private class HaltWork(val at: Long, val requested: StopReason, val done: CountDownLatch) : Work

    private val publish = Any()
    private val order = Any()
    private val queue = LinkedBlockingQueue<Work>(queueLimit)
    private val loggedBackpressure = AtomicBoolean()
    private val loggedEmptySnapshot = AtomicBoolean()
    private val sessionId = UUID.randomUUID().toString()
    private val sites = LinkedHashMap<SiteKey, TraceSite>()
    private val threads = LinkedHashMap<Long, ThreadState>()
    private val summaries = LinkedHashMap<Int, MutableSiteSummary>()
    private var spill: EventSpill? = null
    private var writer: Thread? = null
    private var started = false
    private var origin = 0L
    private var elapsed = 0L
    private var committed = 0
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
        synchronized(publish) {
            check(!started)
            require(validLabel(target.displayName) && target.displayName.isNotBlank())
            require(
                listOf(target.build, target.runtime, target.compiler).all {
                    it == null || validLabel(it)
                }
            )
            spill = EventSpill()
            writer =
                Thread(::runWriter, "Compose inspection recorder").apply {
                    isDaemon = true
                    priority = (Thread.NORM_PRIORITY - 1).coerceAtLeast(Thread.MIN_PRIORITY)
                    start()
                }
            origin = clock()
            started = true
            isActive = true
            LOG.log(Level.INFO, "Compose inspection recording started: session=$sessionId")
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
        enqueue { StartWork(key, dirty1, dirty2, info, threadId, threadName, it) }
    }

    fun end(threadId: Long = Thread.currentThread().threadId()) {
        enqueue { EndWork(threadId, it) }
    }

    /** Copies committed site totals without ending an active recording. */
    fun snapshot(): Recording =
        synchronized(publish) {
            check(started)
            result?.let {
                return it
            }
            val snapshot = drainSnapshot()
            if (reason != null) return stopLocked(StopReason.MANUAL)
            checkNotNull(snapshot)
        }

    fun stop(): Recording = synchronized(publish) { stopLocked(StopReason.MANUAL) }

    /** Ends capture when its target can no longer supply reliable trace events. */
    fun targetUnavailable(): Recording =
        synchronized(publish) { stopLocked(StopReason.TARGET_UNAVAILABLE) }

    private fun stopLocked(requested: StopReason): Recording {
        check(started)
        result?.let {
            return it
        }
        isActive = false
        val done = CountDownLatch(1)
        synchronized(order) { offerLocked(HaltWork(clock(), requested, done)) }
        waitFor(done)
        writer?.join(JOIN_MS)
        val events =
            try {
                spill?.readAll().orEmpty()
            } catch (failure: IOException) {
                LOG.log(Level.WARNING, "Compose inspection could not read spilled events", failure)
                emptyList()
            }
        spill?.close()
        spill = null
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
                events,
            )
        snapshot.validate()
        result = snapshot
        sites.clear()
        threads.clear()
        summaries.clear()
        if (events.size != committed) {
            LOG.log(
                Level.ERROR,
                "Compose inspection spill mismatch: session=$sessionId file=${events.size} committed=$committed",
            )
        }
        LOG.log(
            if (status == CaptureStatus.STOPPED) Level.INFO else Level.WARNING,
            "Compose inspection recording ended: session=$sessionId status=$status reason=$finalReason " +
                "events=${events.size} durationNs=$elapsed abandoned=$abandonedStarts discarded=$discardedPairs " +
                "unmatched=$unmatchedEnds rejected=$rejectedStarts",
        )
        return snapshot
    }

    private fun published(active: Boolean): Recording =
        Recording(
                sessionId,
                target,
                elapsed,
                if (active) CaptureStatus.ACTIVE else CaptureStatus.STOPPED,
                if (active) StopReason.NONE else checkNotNull(reason),
                CaptureFidelity(
                    abandonedStarts,
                    discardedPairs,
                    unmatchedEnds,
                    rejectedStarts,
                    false,
                ),
                java.util.List.copyOf(sites.values),
                java.util.List.copyOf(threads.values.map { it.label }),
                emptyList(),
                java.util.List.copyOf(
                    summaries
                        .map { it.value.toSummary(siteById(it.key)) }
                        .sortedWith(SITE_SUMMARY_ORDER)
                ),
            )
            .also { snapshot ->
                if (
                    active &&
                        snapshot.completedExecutions() == 0 &&
                        elapsed >= EMPTY_SNAPSHOT_NS &&
                        loggedEmptySnapshot.compareAndSet(false, true)
                ) {
                    val open = threads.values.sumOf { it.frames.size }
                    val pending = threads.values.sumOf { it.pending.size }
                    LOG.log(
                        Level.WARNING,
                        "Compose inspection snapshot has no completed executions after ${elapsed}ns: " +
                            "openFrames=$open unmatched=$unmatchedEnds pending=$pending",
                    )
                }
            }

    private fun siteById(id: Int): TraceSite = sites.values.first { it.id == id }

    private fun enqueue(create: (Long) -> Work) {
        if (!isActive) return
        synchronized(order) {
            if (!isActive) return
            offerLocked(create(clock()))
        }
    }

    private fun drainSnapshot(): Recording? {
        val done = CountDownLatch(1)
        val work = DrainWork(0L, done)
        synchronized(order) {
            work.at = clock()
            offerLocked(work)
        }
        waitFor(done)
        return work.snapshot
    }

    private fun waitFor(done: CountDownLatch) {
        if (!done.await(CONTROL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            LOG.log(
                Level.WARNING,
                "Compose inspection writer did not respond within ${CONTROL_TIMEOUT_MS}ms",
            )
            writer?.interrupt()
            if (reason == null) terminate(StopReason.CLOCK_FAILURE)
        }
    }

    private fun offerLocked(work: Work) {
        if (queue.remainingCapacity() == 0 && loggedBackpressure.compareAndSet(false, true)) {
            LOG.log(
                Level.WARNING,
                "Compose inspection writer is behind; composition callbacks are waiting",
            )
        }
        try {
            queue.put(work)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            LOG.log(Level.WARNING, "Compose inspection enqueue interrupted")
        }
    }

    private fun runWriter() {
        try {
            while (true) {
                when (val work = queue.take()) {
                    is StartWork -> handleStart(work)
                    is EndWork -> handleEnd(work)
                    is DrainWork -> {
                        if (reason == null) observe(work.at)
                        if (reason == null) work.snapshot = published(active = true)
                        work.done.countDown()
                    }
                    is HaltWork -> {
                        halt(work)
                        return
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            LOG.log(Level.WARNING, "Compose inspection writer interrupted")
            if (reason == null) terminate(StopReason.CLOCK_FAILURE)
        } catch (failure: IOException) {
            LOG.log(Level.WARNING, "Compose inspection writer failed", failure)
            if (reason == null) terminate(StopReason.EVENT_LIMIT)
        }
    }

    private fun halt(work: HaltWork) {
        if (reason == null) {
            when {
                !observe(work.at) -> Unit
                else -> terminate(work.requested)
            }
        }
        try {
            spill?.finishWrite()
        } catch (failure: IOException) {
            LOG.log(Level.WARNING, "Compose inspection could not finish the event spill", failure)
            if (reason == null) terminate(StopReason.EVENT_LIMIT)
        }
        work.done.countDown()
    }

    private fun handleStart(work: StartWork) {
        if (reason != null) return
        if (observe(work.at)) {
            acceptStart(
                work.key,
                work.dirty1,
                work.dirty2,
                work.info,
                work.threadId,
                work.threadName,
            )
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
                threads.getOrPut(threadId) {
                    ThreadState(TraceThread(threadId, threadLabel(threadName)))
                }
            state.frames.add(Frame(site.id, elapsed, dirty1, dirty2))
        }
    }

    private fun startLimit(info: String, threadId: Long): StopReason? =
        when {
            committed >= eventLimit -> StopReason.EVENT_LIMIT
            !validText(info, RecordingLimits.INFO_BYTES, RecordingLimits.INFO_BYTES) ->
                StopReason.STRING_LIMIT
            threadId <= 0 -> StopReason.THREAD_LIMIT
            threadId !in threads && threads.size == RecordingLimits.THREADS ->
                StopReason.THREAD_LIMIT
            threads[threadId]?.frames?.size == RecordingLimits.DEPTH -> StopReason.DEPTH_LIMIT
            else -> null
        }

    private fun handleEnd(work: EndWork) {
        if (reason != null) return
        if (observe(work.at)) completeEnd(work.threadId)
    }

    private fun completeEnd(threadId: Long) {
        val state = threads[threadId]
        if (state == null || state.frames.isEmpty()) {
            unmatchedEnds++
            if (unmatchedEnds == RecordingLimits.COUNTERS) terminate(StopReason.COUNTER_LIMIT)
        } else {
            val frame = state.frames.removeAt(state.frames.lastIndex)
            state.pending.add(
                TraceEvent(frame.siteId, threadId, frame.start, elapsed, frame.dirty1, frame.dirty2)
            )
            if (state.frames.isEmpty()) commitPending(state)
        }
    }

    private fun commitPending(state: ThreadState) {
        val log = checkNotNull(spill)
        for (event in state.pending) {
            if (committed >= eventLimit || !log.append(event)) {
                terminate(StopReason.EVENT_LIMIT)
                return
            }
            committed++
            summaries.getOrPut(event.siteId) { MutableSiteSummary() }.add(event)
        }
        state.pending.clear()
    }

    private fun observe(at: Long): Boolean {
        val next = at - origin
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
        LOG.log(
            if (stopReason == StopReason.MANUAL) Level.DEBUG else Level.WARNING,
            "Compose inspection stopping: session=$sessionId reason=$stopReason " +
                "committed=$committed abandoned=$abandonedStarts discarded=$discardedPairs " +
                "unmatched=$unmatchedEnds rejected=$rejectedStarts",
        )
        for (state in threads.values) {
            abandonedStarts += state.frames.size
            discardedPairs += state.pending.size
            state.frames.clear()
            state.pending.clear()
        }
    }

    private companion object {
        const val CONTROL_TIMEOUT_MS = 15_000L
        const val JOIN_MS = 2_000L
        const val EMPTY_SNAPSHOT_NS = 50_000_000L
        private val LOG: System.Logger = System.getLogger(CompositionRecorder::class.java.name)
    }
}
