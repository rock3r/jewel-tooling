package dev.sebastiano.jewel.tooling.e2e

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Connects two test processes without placing their capability in logs or VM arguments. */
internal class LiveStandaloneProcess(
    repository: Path,
    private val endpointFile: Path,
    private val output: Path,
) : AutoCloseable {
    private val process =
        ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                Files.readString(
                    repository.resolve("fixtures/standalone/build/live-test-classpath.txt")
                ),
                "example.LiveStandaloneTarget",
            )
            .redirectError(output.resolve("standalone-live-errors.log").toFile())
            .start()
    private val reader = process.inputStream.bufferedReader()
    private val writer = process.outputStream.bufferedWriter()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Jewel standalone E2E").apply { isDaemon = true }
    }

    fun start() {
        val endpoint =
            worker.submit<String> { readProtocol("JEWEL_ENDPOINT=") }.get(60, TimeUnit.SECONDS)
        Files.createFile(
            endpointFile,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
        )
        Files.writeString(endpointFile, endpoint)
        worker.submit {
            var previous = ""
            val request = output.resolve("live-click-request")
            while (process.isAlive && !Thread.currentThread().isInterrupted) {
                if (Files.exists(request)) {
                    val current = Files.readString(request)
                    if (current.isNotEmpty() && current != previous) {
                        writer.write("CLICK\n")
                        writer.flush()
                        readProtocol("JEWEL_CLICKED")
                        Files.writeString(output.resolve("live-click-ack"), current)
                        previous = current
                    }
                }
                writer.write("CHECK\n")
                writer.flush()
                Files.writeString(output.resolve("live-target-state"), readProtocol("JEWEL_STATE="))
                Thread.sleep(100)
            }
        }
    }

    private fun readProtocol(prefix: String): String {
        repeat(100) {
            val line = reader.readLine() ?: error("Standalone test target closed its protocol pipe")
            if (line.startsWith(prefix)) return line.removePrefix(prefix)
        }
        error("Standalone test target did not send its protocol reply")
    }

    override fun close() {
        process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        worker.shutdownNow()
        Files.deleteIfExists(endpointFile)
    }
}
