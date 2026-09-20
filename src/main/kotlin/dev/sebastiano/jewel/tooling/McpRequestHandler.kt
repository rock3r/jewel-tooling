package dev.sebastiano.jewel.tooling

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.rethrowControlFlowException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import dev.sebastiano.jewel.tooling.mcp.bootstrap.Discovery
import java.nio.file.Path
import java.util.concurrent.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

internal class McpRequestHandler(
    private val project: Project,
    private val version: String,
    private val isCurrent: (Discovery) -> Boolean,
) {
    private val permits = Semaphore(2)

    // Convert unexpected request failures at the protocol boundary, without swallowing control
    // flow.
    @Suppress("TooGenericExceptionCaught")
    suspend fun call(
        tool: String,
        request: String,
        discovery: Discovery,
        root: Path,
        facade: McpAnalysisFacade,
    ): String {
        try {
            ensureCurrent(discovery)
            val message = JsonParser.parseString(request).asJsonObject
            val arguments = requireNotNull(message.getAsJsonObject("arguments"))
            validateArguments(tool, arguments)
            return if (tool == "jewel_status") status(message, discovery, root)
            else analyze(tool, arguments, facade)
        } catch (failure: Exception) {
            rethrowControlFlowException(failure)
            return McpResults.failure(
                when (failure) {
                    is McpFailure -> failure.code
                    is IllegalArgumentException,
                    is IllegalStateException -> "INVALID_ARGUMENT"
                    is java.io.IOException -> "UNSUPPORTED_FILE"
                    else -> "INTERNAL_ERROR"
                }
            )
        }
    }

    private fun ensureCurrent(discovery: Discovery) {
        if (!isCurrent(discovery)) throw McpFailure("PROJECT_UNAVAILABLE")
    }

    private fun validateArguments(tool: String, arguments: JsonObject) {
        val allowed =
            when (tool) {
                "jewel_status" -> emptySet()
                "jewel_composables" -> setOf("file", "expectedHash")
                "jewel_analyze" -> setOf("file", "expectedHash", "range", "declarationId")
                "jewel_explain" -> setOf("file", "expectedHash", "declarationId", "parameter")
                else -> throw McpFailure("INVALID_ARGUMENT")
            }
        if (arguments.keySet().any { it !in allowed }) throw McpFailure("INVALID_ARGUMENT")
    }

    private fun status(message: JsonObject, discovery: Discovery, root: Path) =
        McpResults.success(
            mapOf(
                "pluginVersion" to version,
                "protocolVersion" to message.string("protocolVersion"),
                "projectId" to discovery.projectId(),
                "generation" to discovery.generation(),
                "projectName" to project.name,
                "projectRoot" to root.toString(),
                "readiness" to if (DumbService.isDumb(project)) "INDEXING" else "READY",
                "tools" to
                    listOf("jewel_status", "jewel_composables", "jewel_analyze", "jewel_explain"),
                "limits" to McpAnalysisFacade.LIMITS,
                "liveInspection" to false,
            )
        )

    private suspend fun analyze(
        tool: String,
        arguments: JsonObject,
        facade: McpAnalysisFacade,
    ): String {
        if (!permits.tryAcquire()) return McpResults.failure("BUSY")
        try {
            return withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) {
                val result = McpResults.success(facade.inspect(tool, arguments))
                if (result.toByteArray(Charsets.UTF_8).size > McpAnalysisFacade.MAX_BYTES)
                    McpResults.failure("LIMIT_EXCEEDED")
                else result
            } ?: McpResults.failure("TIMEOUT")
        } finally {
            permits.release()
        }
    }

    companion object {
        private const val REQUEST_TIMEOUT_MILLIS = 10_000L
    }
}
