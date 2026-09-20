package dev.sebastiano.jewel.tooling.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingSummaryTest {
    @Test
    fun countsAndInclusiveTimesDoNotMergeDifferentSites() {
        val recording =
            Recording(
                "123e4567-e89b-12d3-a456-426614174000",
                CaptureTarget("Fixture"),
                100,
                CaptureStatus.STOPPED,
                StopReason.MANUAL,
                CaptureFidelity(0, 0, 0, 0, false),
                listOf(TraceSite(1, 7, "first"), TraceSite(2, 7, "second")),
                listOf(TraceThread(1, "UI"), TraceThread(2, "worker")),
                listOf(
                    TraceEvent(1, 1, 0, 50, 0, 0),
                    TraceEvent(2, 1, 10, 20, 0, 0),
                    TraceEvent(1, 2, 60, 80, 0, 0),
                ),
            )
        val rows = recording.summarize()
        assertEquals(listOf("first", "second"), rows.map { it.site.info })
        assertEquals(2, rows[0].executions)
        assertEquals(70L, rows[0].totalNs)
        assertEquals(35.0, rows[0].meanNs, 0.0)
        assertEquals(mapOf(1L to 1, 2L to 1), rows[0].threads)
    }

    @Test
    fun liveSnapshotsSummarizeFromSiteTotalsWhenEventsAreOmitted() {
        val site = TraceSite(1, 7, "first")
        val recording =
            Recording(
                "123e4567-e89b-12d3-a456-426614174000",
                CaptureTarget("Fixture"),
                100,
                CaptureStatus.ACTIVE,
                StopReason.NONE,
                CaptureFidelity(0, 0, 0, 0, false),
                listOf(site),
                listOf(TraceThread(1, "UI")),
                emptyList(),
                listOf(SiteSummary(site, 4, 80, mapOf(1L to 4))),
            )
        val rows = recording.summarize()
        assertEquals(4, recording.completedExecutions())
        assertEquals(4, rows.single().executions)
        assertEquals(80L, rows.single().totalNs)
    }
}
