package dev.sebastiano.jewel.tooling

import com.intellij.util.EnvironmentUtil
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal data class McpProcessResult(val exitCode: Int, val output: String)

internal fun interface McpProcessRunner {
    suspend fun run(
        command: List<String>,
        directory: Path,
        environment: Map<String, String>,
    ): McpProcessResult
}

internal class McpClientProcess : McpProcessRunner {
    override suspend fun run(
        command: List<String>,
        directory: Path,
        environment: Map<String, String>,
    ): McpProcessResult {
        val executable = Path.of(command.first())
        if (
            !executable.isAbsolute ||
                !Files.isRegularFile(executable) ||
                !Files.isExecutable(executable)
        )
            throw McpSetupFailure("executable")
        val builder =
            ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
        builder.environment().putAll(EnvironmentUtil.getEnvironmentMap())
        builder.environment().putAll(environment)
        val process = builder.start()
        process.outputStream.close()
        val output = ByteArrayOutputStream()
        val started = System.nanoTime()
        val children = linkedSetOf<ProcessHandle>()
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                collectChildren(process, children)
                readOutput(process, output)
                if (!process.isAlive && process.inputStream.available() == 0) break
                if (System.nanoTime() - started > TIMEOUT_NANOS)
                    throw McpSetupFailure("processTimeout")
                delay(POLL_MILLIS)
            }
            return McpProcessResult(process.exitValue(), output.toString(Charsets.UTF_8))
        } finally {
            stop(process, children)
        }
    }

    private fun collectChildren(process: Process, children: MutableSet<ProcessHandle>) {
        process.descendants().use { current ->
            current.limit(MAX_CHILDREN.toLong() + 1).forEach { children.add(it) }
        }
        if (children.size > MAX_CHILDREN) throw McpSetupFailure("processOutput")
    }

    private fun readOutput(process: Process, output: ByteArrayOutputStream) {
        val available = process.inputStream.available()
        if (available > 0) {
            output.write(process.inputStream.readNBytes(minOf(available, BUFFER_SIZE)))
            if (output.size() > MAX_OUTPUT) throw McpSetupFailure("processOutput")
        }
    }

    private fun stop(process: Process, children: MutableSet<ProcessHandle>) {
        try {
            process.descendants().use { current ->
                current.limit(MAX_CHILDREN.toLong()).forEach { children.add(it) }
            }
            children.filter { it.isAlive }.forEach { it.destroyForcibly() }
            if (process.isAlive) process.destroyForcibly()
            process.waitFor(CLEANUP_SECONDS, TimeUnit.SECONDS)
        } finally {
            process.inputStream.close()
            process.errorStream.close()
        }
    }

    companion object {
        private const val CLEANUP_SECONDS = 1L
        private const val MAX_CHILDREN = 128
        private const val BUFFER_SIZE = 8192
        private const val MAX_OUTPUT = 65536
        private const val POLL_MILLIS = 25L
        private const val TIMEOUT_NANOS = 180_000_000_000L
    }
}
