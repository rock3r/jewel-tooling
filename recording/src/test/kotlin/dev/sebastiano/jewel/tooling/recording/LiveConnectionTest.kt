package dev.sebastiano.jewel.tooling.recording

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveConnectionTest {
    @Test
    fun localSessionProducesCumulativeSnapshotsAndIdempotentStop() {
        val aborted = CountDownLatch(1)
        LiveRecordingServer(CaptureTarget("Local target")) {
                object : LiveRecordingSession {
                    var recorder: CompositionRecorder? = null

                    override fun execute(command: LiveCommand): Recording? =
                        when (command) {
                            LiveCommand.START -> {
                                check(recorder == null)
                                recorder =
                                    CompositionRecorder(CaptureTarget("Local target")).also {
                                        it.begin()
                                    }
                                recorder!!.start(1, 0, 0, "example.Component", 1, "UI")
                                recorder!!.end(1)
                                recorder!!.snapshot()
                            }
                            LiveCommand.SNAPSHOT -> recorder?.snapshot()
                            LiveCommand.STOP -> recorder?.stop()
                            LiveCommand.KEEP_ALIVE -> null
                            LiveCommand.STATUS ->
                                error("Status must not be dispatched as a capture command")
                        }

                    override fun abort() {
                        recorder?.stop()
                        aborted.countDown()
                    }
                }
            }
            .use { server ->
                val original = server.endpoint.connectionString()
                LiveConnection(LiveEndpoint.parse(original)).use { client ->
                    assertEquals("Local target", client.connect())
                    assertEquals(LiveTargetStatus.READY, client.status())
                    assertNull(client.request(LiveCommand.SNAPSHOT))
                    assertNull(client.request(LiveCommand.KEEP_ALIVE))
                    val first = client.request(LiveCommand.START)!!
                    assertEquals(CaptureStatus.ACTIVE, first.status)
                    assertTrue(first.events.isEmpty())
                    assertEquals(1, first.completedExecutions())
                    assertEquals(first.summaries, client.request(LiveCommand.SNAPSHOT)!!.summaries)
                    val stopped = client.request(LiveCommand.STOP)!!
                    assertEquals(CaptureStatus.STOPPED, stopped.status)
                    assertEquals(1, stopped.events.size)
                    assertEquals(stopped, client.request(LiveCommand.STOP))
                    LiveConnection(server.endpoint).use { second ->
                        assertThrows(IOException::class.java) { second.connect() }
                    }
                }
                assertTrue(aborted.await(2, TimeUnit.SECONDS))
            }
    }

    @Test
    fun snapshotsDuringABurstKeepEveryPair() {
        val burst = 500
        LiveRecordingServer(CaptureTarget("Local target")) {
                object : LiveRecordingSession {
                    var recorder: CompositionRecorder? = null

                    override fun execute(command: LiveCommand): Recording? =
                        when (command) {
                            LiveCommand.START -> {
                                check(recorder == null)
                                val next =
                                    CompositionRecorder(CaptureTarget("Local target")).also {
                                        it.begin()
                                    }
                                recorder = next
                                Thread {
                                    repeat(burst) {
                                        next.start(1, 0, 0, "example.Component", 1, "UI")
                                        next.end(1)
                                    }
                                }
                                    .apply {
                                        isDaemon = true
                                        start()
                                    }
                                next.snapshot()
                            }
                            LiveCommand.SNAPSHOT -> recorder?.snapshot()
                            LiveCommand.STOP -> recorder?.stop()
                            LiveCommand.KEEP_ALIVE -> null
                            LiveCommand.STATUS ->
                                error("Status must not be dispatched as a capture command")
                        }

                    override fun abort() {
                        recorder?.stop()
                    }
                }
            }
            .use { server ->
                LiveConnection(server.endpoint).use { client ->
                    assertEquals("Local target", client.connect())
                    val first = checkNotNull(client.request(LiveCommand.START))
                    assertEquals(CaptureStatus.ACTIVE, first.status)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    var latest = first
                    while (
                        latest.completedExecutions() < burst &&
                            latest.status == CaptureStatus.ACTIVE &&
                            System.nanoTime() < deadline
                    ) {
                        latest = checkNotNull(client.request(LiveCommand.SNAPSHOT))
                    }
                    assertEquals(CaptureStatus.ACTIVE, latest.status)
                    assertEquals(burst, latest.completedExecutions())
                    val stopped = checkNotNull(client.request(LiveCommand.STOP))
                    assertEquals(CaptureStatus.STOPPED, stopped.status)
                    assertEquals(burst, stopped.events.size)
                }
            }
    }

    @Test
    fun onlyLiteralLoopbackCapabilitiesAreAccepted() {
        val token = "a".repeat(64)
        for (invalid in
            listOf(
                "http://127.0.0.1:1234/$token",
                "jewel-compose://localhost:1234/$token",
                "jewel-compose://192.168.1.1:1234/$token",
                "jewel-compose://user@127.0.0.1:1234/$token",
                "jewel-compose://127.0.0.1:1234/$token?x=1",
                "jewel-compose://127.0.0.1:1234/$token#x",
                "jewel-compose://127.0.0.1:1234/${token.uppercase()}",
                "jewel-compose://127.0.0.1:65536/$token",
            )) assertThrows(IllegalArgumentException::class.java) { LiveEndpoint.parse(invalid) }
        assertFalse(
            LiveEndpoint.parse("jewel-compose://127.0.0.1:1234/$token").toString().contains(token)
        )
    }

    @Test
    fun wrongTokenNeverCreatesASession() {
        val created = CountDownLatch(1)
        LiveRecordingServer(CaptureTarget("Target")) {
                created.countDown()
                error("Must not authenticate")
            }
            .use { server ->
                val wrong = LiveEndpoint.create(server.endpoint.port, ByteArray(32))
                LiveConnection(wrong).use { client ->
                    assertThrows(IOException::class.java) { client.connect() }
                }
                assertEquals(1L, created.count)
            }
    }
}
