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

/** Completed executions shown in the live table. Live snapshots may omit the raw event list. */
fun Recording.completedExecutions(): Int =
    if (events.isNotEmpty()) events.size else summaries?.sumOf { it.executions } ?: 0

/** Groups completed events by their exact site identity within this recording. */
@ApiStatus.Experimental
fun Recording.summarize(checkCanceled: () -> Unit = {}): List<SiteSummary> {
    validate(checkCanceled)
    if (events.isEmpty()) {
        return java.util.List.copyOf(summaries.orEmpty().sortedWith(SITE_SUMMARY_ORDER))
    }
    val groups = LinkedHashMap<Int, MutableSiteSummary>()
    for (event in events) {
        checkCanceled()
        groups.getOrPut(event.siteId) { MutableSiteSummary() }.add(event)
    }
    val siteMap = sites.associateBy { it.id }
    return java.util.List.copyOf(
        groups
            .map { (id, group) -> group.toSummary(checkNotNull(siteMap[id])) }
            .sortedWith(SITE_SUMMARY_ORDER)
    )
}

internal val SITE_SUMMARY_ORDER =
    compareByDescending<SiteSummary> { it.totalNs }
        .thenByDescending { it.executions }
        .thenBy { it.site.info }
        .thenBy { it.site.key }
        .thenBy { it.site.id }

internal class MutableSiteSummary {
    var executions = 0
    var total = 0L
    val threads = LinkedHashMap<Long, Int>()

    fun add(event: TraceEvent) {
        executions++
        total += event.endNs - event.startNs
        threads[event.threadId] = (threads[event.threadId] ?: 0) + 1
    }

    fun toSummary(site: TraceSite): SiteSummary =
        SiteSummary(site, executions, total, java.util.Map.copyOf(threads))
}
