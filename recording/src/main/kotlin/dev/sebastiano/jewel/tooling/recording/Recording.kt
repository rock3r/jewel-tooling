package dev.sebastiano.jewel.tooling.recording

import java.util.UUID
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Experimental
object RecordingLimits {
  const val EVENTS = 10_000
  const val SITES = 1_024
  const val THREADS = 64
  const val DEPTH = 64
  const val INFO_BYTES = 1_024
  const val LABEL_CHARACTERS = 256
  const val LABEL_BYTES = 1_024
  const val FILE_BYTES = 8 * 1_024 * 1_024
  const val DURATION_NS = 3_600_000_000_000L
  const val SCHEMA_VERSION = 1
  const val COLLECTOR_VERSION = "1"
  const val CLOCK_UNIT = "nanoseconds"
  val CAPABILITIES: Set<String> =
    java.util.Set.of("paired-composition-trace-events", "inclusive-duration")
}

@ApiStatus.Experimental
enum class CaptureStatus {
  STOPPED,
  TRUNCATED,
  FAILED,
}

@ApiStatus.Experimental
enum class StopReason {
  MANUAL,
  EVENT_LIMIT,
  SITE_LIMIT,
  THREAD_LIMIT,
  DEPTH_LIMIT,
  STRING_LIMIT,
  DURATION_LIMIT,
  CLOCK_FAILURE,
  COUNTER_LIMIT,
}

/** Labels describe the target. They do not prove its build identity. */
@ApiStatus.Experimental
data class CaptureTarget(
  val displayName: String,
  val build: String? = null,
  val runtime: String? = null,
  val compiler: String? = null,
)

@ApiStatus.Experimental
data class CaptureFidelity(
  val abandonedStarts: Int,
  val discardedPairs: Int,
  val unmatchedEnds: Int,
  val rejectedStarts: Int,
  val laterActivityUnrecorded: Boolean,
)

@ApiStatus.Experimental data class TraceSite(val id: Int, val key: Int, val info: String)

@ApiStatus.Experimental data class TraceThread(val id: Long, val name: String)

/** One observed pair of callbacks. The duration includes nested calls and capture overhead. */
@ApiStatus.Experimental
data class TraceEvent(
  val siteId: Int,
  val threadId: Long,
  val startNs: Long,
  val endNs: Long,
  val dirty1: Int,
  val dirty2: Int,
)

/** A bounded snapshot. No field contains a target object or a source path to open. */
@ApiStatus.Experimental
data class Recording(
  val sessionId: String,
  val target: CaptureTarget,
  val durationNs: Long,
  val status: CaptureStatus,
  val stopReason: StopReason,
  val fidelity: CaptureFidelity,
  val sites: List<TraceSite>,
  val threads: List<TraceThread>,
  val events: List<TraceEvent>,
  val schemaVersion: Int = RecordingLimits.SCHEMA_VERSION,
  val collectorVersion: String = RecordingLimits.COLLECTOR_VERSION,
  val clockUnit: String = RecordingLimits.CLOCK_UNIT,
  val capabilities: Set<String> = RecordingLimits.CAPABILITIES,
) {
  fun validate(checkCanceled: () -> Unit = {}) {
    checkCanceled()
    require(schemaVersion == RecordingLimits.SCHEMA_VERSION)
    require(collectorVersion == RecordingLimits.COLLECTOR_VERSION)
    require(clockUnit == RecordingLimits.CLOCK_UNIT)
    require(capabilities == RecordingLimits.CAPABILITIES)
    require(UUID.fromString(sessionId).toString() == sessionId)
    require(validLabel(target.displayName) && target.displayName.isNotBlank())
    require(
      listOf(target.build, target.runtime, target.compiler).all { it == null || validLabel(it) }
    )
    require(durationNs in 0..RecordingLimits.DURATION_NS)
    validateStatus()
    require(
      listOf(
          fidelity.abandonedStarts,
          fidelity.discardedPairs,
          fidelity.unmatchedEnds,
          fidelity.rejectedStarts,
        )
        .all { it in 0..RecordingLimits.EVENTS }
    )
    require(sites.size <= RecordingLimits.SITES && threads.size <= RecordingLimits.THREADS)
    require(events.size <= RecordingLimits.EVENTS)
    val siteIds = HashSet<Int>()
    val identities = HashSet<Pair<Int, String>>()
    for (site in sites) {
      checkCanceled()
      require(site.id > 0 && siteIds.add(site.id))
      require(validText(site.info, RecordingLimits.INFO_BYTES, RecordingLimits.INFO_BYTES))
      require(identities.add(site.key to site.info))
    }
    val threadIds = HashSet<Long>()
    for (thread in threads) {
      checkCanceled()
      require(thread.id > 0 && threadIds.add(thread.id) && validLabel(thread.name))
    }
    for (event in events) {
      checkCanceled()
      require(event.siteId in siteIds && event.threadId in threadIds)
      require(event.startNs >= 0 && event.endNs in event.startNs..durationNs)
    }
  }

  private fun validateStatus() {
    require(
      when (status) {
        CaptureStatus.STOPPED -> stopReason == StopReason.MANUAL
        CaptureStatus.FAILED -> stopReason == StopReason.CLOCK_FAILURE
        CaptureStatus.TRUNCATED ->
          stopReason != StopReason.MANUAL && stopReason != StopReason.CLOCK_FAILURE
      }
    )
    require(status == CaptureStatus.STOPPED || fidelity.laterActivityUnrecorded)
  }
}

internal fun validLabel(value: String): Boolean =
  validText(value, RecordingLimits.LABEL_CHARACTERS, RecordingLimits.LABEL_BYTES)

internal fun validText(value: String, maxCharacters: Int, maxBytes: Int): Boolean {
  if (value.length > maxCharacters) return false
  var index = 0
  var valid = true
  while (valid && index < value.length) {
    val character = value[index++]
    valid =
      if (character.isHighSurrogate()) {
        index < value.length && value[index++].isLowSurrogate()
      } else !character.isLowSurrogate()
  }
  return valid && value.toByteArray(Charsets.UTF_8).size <= maxBytes
}

internal fun threadLabel(value: String): String {
  val output = StringBuilder()
  var index = 0
  while (index < value.length && output.length < RecordingLimits.LABEL_CHARACTERS) {
    val character = value[index++]
    when {
      character.isHighSurrogate() -> {
        if (index < value.length && value[index].isLowSurrogate()) {
          if (output.length + 2 > RecordingLimits.LABEL_CHARACTERS) break
          output.append(character).append(value[index++])
        } else output.append('?')
      }
      character.isLowSurrogate() -> output.append('?')
      else -> output.append(character)
    }
  }
  return output.toString()
}
