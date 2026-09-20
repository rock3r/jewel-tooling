package dev.sebastiano.jewel.tooling.recording

import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCompositionHostTest {
    @Test
    fun liveHostRetainsTheStoppedResultAndCanStartAgain() {
        val tracer = OwnedCompositionTracer()
        LiveCompositionHost(tracer, CaptureTarget("Host")) { it.run() }
            .use { host ->
                LiveConnection(LiveEndpoint.parse(host.connectionString)).use { client ->
                    client.connect()
                    val first = client.request(LiveCommand.START)!!
                    tracer.traceEventStart(1, 0, 0, "example.Component")
                    tracer.traceEventEnd()
                    val snapshot = client.request(LiveCommand.SNAPSHOT)!!
                    assertTrue(snapshot.events.isEmpty())
                    assertEquals(1, snapshot.completedExecutions())
                    val stopped = client.request(LiveCommand.STOP)!!
                    assertEquals(1, stopped.events.size)
                    assertEquals(stopped, client.request(LiveCommand.SNAPSHOT))
                    assertEquals(stopped, client.request(LiveCommand.STOP))
                    val next = client.request(LiveCommand.START)!!
                    assertNotEquals(first.sessionId, next.sessionId)
                    assertTrue(next.events.isEmpty())
                }
            }
        assertFalse(tracer.isTraceInProgress())
    }

    @Test
    fun timedOutStartCannotRunLater() {
        val tracer = OwnedCompositionTracer()
        val queued = AtomicReference<Runnable>()
        LiveCompositionHost(tracer, CaptureTarget("Host"), queued::set).use { host ->
            LiveConnection(LiveEndpoint.parse(host.connectionString)).use { client ->
                client.connect()
                assertThrows(IOException::class.java) { client.request(LiveCommand.START) }
                queued.get().run()
                assertFalse(tracer.isTraceInProgress())
            }
        }
    }
}
