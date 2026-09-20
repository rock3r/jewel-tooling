package dev.sebastiano.jewel.tooling.agent

import dev.sebastiano.jewel.tooling.recording.CaptureStatus
import dev.sebastiano.jewel.tooling.recording.InspectionFiles
import dev.sebastiano.jewel.tooling.recording.LiveCommand
import dev.sebastiano.jewel.tooling.recording.LiveConnection
import dev.sebastiano.jewel.tooling.recording.LiveEndpoint
import dev.sebastiano.jewel.tooling.recording.LiveRuntimeState
import dev.sebastiano.jewel.tooling.recording.completedExecutions
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentForkTest {
    private companion object {
        const val BURST_EVENTS = 2_000
        const val OFF_EDT_EVENTS = 10
    }

    @Test
    fun java21RunsWithoutTargetBootstrap() =
        inspect(Path.of(System.getProperty("java.home"), "bin", "java"))

    @Test
    fun java25RunsWithoutTargetBootstrap() =
        inspect(Path.of(System.getProperty("inspection.java25")))

    @Test
    fun intellijPathClassLoaderRunsWithoutCodeSource() =
        inspect(Path.of(System.getProperty("inspection.java25")), pathClassLoader = true)

    @Suppress("LongMethod") // One child process owns the complete protocol scenario.
    private fun inspect(java: Path, pathClassLoader: Boolean = false) {
        val directory =
            InspectionFiles.createDirectory(Path.of(System.getProperty("java.io.tmpdir")))
        var process: Process? = null
        try {
            val agent = directory.resolve("inspection-agent.jar")
            Files.copy(Path.of(System.getProperty("inspection.agent")), agent)
            Files.copy(
                Path.of(System.getProperty("inspection.bridge")),
                directory.resolve("bridge.jar"),
            )
            val nonce = InspectionFiles.nonce()
            InspectionFiles.write(
                directory.resolve(InspectionFiles.CONFIG),
                mapOf("version" to "1", "nonce" to nonce, "name" to "Fork target"),
            )
            process =
                ProcessBuilder(
                        java.toString(),
                        "-Djava.awt.headless=true",
                        "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED",
                        if (pathClassLoader)
                            "-Djava.system.class.loader=com.intellij.util.lang.PathClassLoader"
                        else "-Dinspection.defaultLoader=true",
                        "-javaagent:$agent=${directory.resolve(InspectionFiles.CONFIG)}",
                        "-cp",
                        System.getProperty("inspection.test.classpath") +
                            if (pathClassLoader)
                                File.pathSeparator + System.getProperty("inspection.pathLoader")
                            else "",
                        AgentForkTarget::class.java.name,
                    )
                    .redirectOutput(directory.resolve("stdout.txt").toFile())
                    .redirectError(directory.resolve("stderr.txt").toFile())
                    .start()
            val ready = directory.resolve(InspectionFiles.READY)
            await { Files.exists(ready) || !process.isAlive }
            val exit = if (process.isAlive) "running" else process.exitValue()
            assertTrue(
                "Agent did not announce its endpoint (exit=$exit): " +
                    Files.readString(directory.resolve("stdout.txt"))
                        .take(3000)
                        .replace(directory.toString(), "<launch>") +
                    Files.readString(directory.resolve("stderr.txt"))
                        .take(3000)
                        .replace(directory.toString(), "<launch>"),
                Files.exists(ready),
            )
            val values = InspectionFiles.read(ready)
            assertNull("Agent failed to start", values["failure"])
            assertEquals(nonce, values["nonce"])
            assertEquals(process.pid().toString(), values["pid"])
            LiveConnection(LiveEndpoint.parse(checkNotNull(values["endpoint"]))).use { client ->
                assertEquals("Fork target", client.connect())
                assertEquals(LiveRuntimeState.NO_RUNTIME, client.status().state)
                val commands = process.outputStream.bufferedWriter()
                commands.write("load\n")
                commands.flush()
                await { client.status().state != LiveRuntimeState.NO_RUNTIME }
                assertEquals(LiveRuntimeState.READY, client.status().state)
                client.request(LiveCommand.START)
                commands.write("offedt\n")
                commands.flush()
                await {
                    (client.request(LiveCommand.SNAPSHOT)?.completedExecutions() ?: 0) >=
                        OFF_EDT_EVENTS
                }
                val offEdt = checkNotNull(client.request(LiveCommand.STOP))
                assertEquals(CaptureStatus.STOPPED, offEdt.status)
                assertEquals(OFF_EDT_EVENTS, offEdt.events.size)
                client.request(LiveCommand.START)
                commands.write("trace\n")
                commands.flush()
                await { (client.request(LiveCommand.SNAPSHOT)?.completedExecutions() ?: 0) > 0 }
                val stopped = checkNotNull(client.request(LiveCommand.STOP))
                assertEquals(CaptureStatus.STOPPED, stopped.status)
                assertEquals(1, stopped.events.size)
                client.request(LiveCommand.START)
                commands.write("burst\n")
                commands.flush()
                val burstDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                var burst = client.request(LiveCommand.SNAPSHOT)
                while (
                    burst != null &&
                        burst.status == CaptureStatus.ACTIVE &&
                        burst.completedExecutions() < BURST_EVENTS &&
                        System.nanoTime() < burstDeadline
                ) {
                    burst = client.request(LiveCommand.SNAPSHOT)
                }
                assertEquals(
                    "Burst snapshots must stay active while events arrive: status=${burst?.status} " +
                        "reason=${burst?.stopReason} executions=${burst?.completedExecutions()}",
                    CaptureStatus.ACTIVE,
                    burst?.status,
                )
                assertEquals(BURST_EVENTS, burst?.completedExecutions())
                val burstStopped = checkNotNull(client.request(LiveCommand.STOP))
                assertEquals(CaptureStatus.STOPPED, burstStopped.status)
                assertEquals(BURST_EVENTS, burstStopped.events.size)
                client.request(LiveCommand.START)
                commands.write("second\n")
                commands.flush()
                await { client.status().state == LiveRuntimeState.MULTIPLE_RUNTIMES }
                assertEquals(2, client.status().runtimeCount)
                val unavailable = checkNotNull(client.request(LiveCommand.SNAPSHOT))
                assertEquals(CaptureStatus.FAILED, unavailable.status)
                assertTrue(unavailable.events.isEmpty())
                assertTrue(process.isAlive)
            }
            assertTrue("Disconnect must leave the application alive", process.isAlive)
        } finally {
            process?.destroy()
            if (process?.waitFor(5, TimeUnit.SECONDS) == false)
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition()) {
            if (System.nanoTime() >= deadline) fail("Timed out waiting for the target")
            Thread.sleep(50)
        }
    }
}

object AgentForkTarget {
    @JvmStatic
    fun main(args: Array<String>) {
        var runtime: Class<*>? = null
        System.`in`.bufferedReader().forEachLine { command ->
            when (command) {
                "load" -> runtime = Class.forName("androidx.compose.runtime.ComposerKt")
                "second" -> {
                    val urls =
                        System.getProperty("java.class.path")
                            .split(java.io.File.pathSeparator)
                            .map { Path.of(it).toUri().toURL() }
                            .toTypedArray()
                    val loader = java.net.URLClassLoader(urls, ClassLoader.getPlatformClassLoader())
                    runtime = Class.forName("androidx.compose.runtime.ComposerKt", true, loader)
                }
                "offedt" -> {
                    check(!java.awt.EventQueue.isDispatchThread())
                    val type = checkNotNull(runtime)
                    val gate = type.getMethod("isTraceInProgress")
                    val start =
                        type.getMethod(
                            "traceEventStart",
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            String::class.java,
                        )
                    val end = type.getMethod("traceEventEnd")
                    repeat(10) {
                        check(gate.invoke(null) == true)
                        start.invoke(null, 123, 0, 0, "example.OffEdt")
                        check(gate.invoke(null) == true)
                        end.invoke(null)
                    }
                }
                "trace" ->
                    java.awt.EventQueue.invokeAndWait {
                        val type = checkNotNull(runtime)
                        val gate = type.getMethod("isTraceInProgress")
                        check(gate.invoke(null) == true)
                        type
                            .getMethod(
                                "traceEventStart",
                                Int::class.javaPrimitiveType,
                                Int::class.javaPrimitiveType,
                                Int::class.javaPrimitiveType,
                                String::class.java,
                            )
                            .invoke(null, 123, 0, 0, "example.RealRuntime")
                        if (gate.invoke(null) == true) type.getMethod("traceEventEnd").invoke(null)
                    }
                "burst" ->
                    java.awt.EventQueue.invokeAndWait {
                        val type = checkNotNull(runtime)
                        val gate = type.getMethod("isTraceInProgress")
                        val start =
                            type.getMethod(
                                "traceEventStart",
                                Int::class.javaPrimitiveType,
                                Int::class.javaPrimitiveType,
                                Int::class.javaPrimitiveType,
                                String::class.java,
                            )
                        val end = type.getMethod("traceEventEnd")
                        repeat(2_000) {
                            check(gate.invoke(null) == true)
                            start.invoke(null, 123, 0, 0, "example.RealRuntime")
                            check(gate.invoke(null) == true)
                            end.invoke(null)
                        }
                    }
            }
        }
    }
}
