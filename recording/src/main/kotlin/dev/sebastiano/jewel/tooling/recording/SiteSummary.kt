package dev.sebastiano.jewel.tooling.recording

import org.jetbrains.annotations.ApiStatus

@ApiStatus.Experimental
data class SiteSummary(
  val site: TraceSite,
  val executions: Int,
  val totalNs: Long,
  val threads: Map<Long, Int>,
) {
  val meanNs: Double
    get() = if (executions == 0) 0.0 else totalNs.toDouble() / executions
}

/** Groups completed events by their exact site identity within this recording. */
@ApiStatus.Experimental
fun Recording.summarize(checkCanceled: () -> Unit = {}): List<SiteSummary> {
  validate(checkCanceled)
  val groups = LinkedHashMap<Int, MutableSummary>()
  for (event in events) {
    checkCanceled()
    val group = groups.getOrPut(event.siteId) { MutableSummary() }
    group.executions++
    group.total += event.endNs - event.startNs
    group.threads[event.threadId] = (group.threads[event.threadId] ?: 0) + 1
  }
  val siteMap = sites.associateBy { it.id }
  return java.util.List.copyOf(
    groups
      .map { (id, group) ->
        SiteSummary(
          checkNotNull(siteMap[id]),
          group.executions,
          group.total,
          java.util.Map.copyOf(group.threads),
        )
      }
      .sortedWith(
        compareByDescending<SiteSummary> { it.totalNs }
          .thenByDescending { it.executions }
          .thenBy { it.site.info }
          .thenBy { it.site.key }
          .thenBy { it.site.id }
      )
  )
}

private class MutableSummary {
  var executions = 0
  var total = 0L
  val threads = LinkedHashMap<Long, Int>()
}
