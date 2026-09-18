package dev.sebastiano.jewel.tooling.mcp.runtime

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class RuntimeSession(
  version: String,
  tools: List<Tool>,
  private var callback: BiFunction<String, String, CompletableFuture<String>>?,
) {
  val transport =
    StreamableHttpServerTransport(
      StreamableHttpServerTransport.Configuration(
        enableJsonResponse = true,
        maxRequestBodySize = 65536,
      )
    )
  private val versionTransport = VersionTransport(transport)
  val initialized
    get() = versionTransport.accepted

  private val closing = Mutex()
  private val requests = RequestJobs()
  private var closed = false
  private val futures = ConcurrentHashMap.newKeySet<CompletableFuture<String>>()
  private val server =
    Server(
      Implementation("jewel-tooling", version),
      ServerOptions(
        capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))
      ),
    )
  private var session: ServerSession? = null
  @Volatile
  var ready = false
    private set

  @Volatile var lastUsed = System.nanoTime()
  val created = System.nanoTime()

  init {
    tools.forEach { tool ->
      server.addTool(tool) { request ->
        coroutineScope {
          val job = currentCoroutineContext().job
          if (!requests.admit(job)) job.cancel()
          currentCoroutineContext().ensureActive()
          invoke(tool.name, request)
        }
      }
    }
  }

  private suspend fun invoke(name: String, request: CallToolRequest): CallToolResult {
    val input = buildJsonObject {
      put("arguments", request.params.arguments ?: JsonObject(emptyMap()))
      put("protocolVersion", versionTransport.protocolVersion)
    }
    val operation = checkNotNull(callback).apply(name, input.toString())
    futures.add(operation)
    return try {
      encodeResult(operation.await())
    } finally {
      operation.cancel(true)
      futures.remove(operation)
    }
  }

  private fun encodeResult(value: String): CallToolResult {
    if (value.toByteArray(Charsets.UTF_8).size > MAX_RESULT_BYTES) return limitResult()
    val parsed = McpJson.parseToJsonElement(value).jsonObject
    val candidate =
      CallToolResult(
        content = listOf(TextContent(value)),
        structuredContent = parsed,
        isError = parsed["payload"]?.jsonObject?.get("kind")?.jsonPrimitive?.content == "error",
      )
    return if (
      McpJson.encodeToString(candidate).toByteArray(Charsets.UTF_8).size > MAX_RESULT_BYTES
    )
      limitResult()
    else candidate
  }

  suspend fun start() {
    session = server.createSession(versionTransport).also { it.onInitialized { ready = true } }
  }

  suspend fun close() = closing.withLock {
    if (closed) return@withLock
    requests.close()
    futures.forEach { it.cancel(true) }
    server.close()
    futures.clear()
    callback = null
    session = null
    closed = true
  }

  private fun limitResult(): CallToolResult {
    val text =
      """{"schemaVersion":1,"payload":{"kind":"error","code":"LIMIT_EXCEEDED","message":"The result exceeds one MiB.","retryable":false,"remedy":"Select fewer declarations."}}"""
    return CallToolResult(
      content = listOf(TextContent(text)),
      structuredContent = McpJson.parseToJsonElement(text).jsonObject,
      isError = true,
    )
  }

  companion object {
    private const val MAX_RESULT_BYTES = 983000
  }
}
