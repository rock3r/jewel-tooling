package dev.sebastiano.jewel.tooling.mcp.runtime

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.modelcontextprotocol.kotlin.sdk.client.ReconnectionOptions
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.InitializeResult
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

/** Runs after the bootstrap validates private discovery and the selected project identity. */
object Bridge {
  private const val MAX_PORT = 65535
  private const val CLOSE_TIMEOUT_MS = 3000L

  @JvmStatic
  fun run(endpoint: String, token: String) = runBlocking {
    validateEndpoint(endpoint)
    val client =
      HttpClient(CIO) {
        followRedirects = false
        install(SSE)
        install(HttpTimeout) {
          requestTimeoutMillis = 20_000
          connectTimeoutMillis = 5000
        }
      }
    val http =
      StreamableHttpClientTransport(client, endpoint, ReconnectionOptions(maxRetries = 0)) {
        header("Authorization", "Bearer $token")
      }
    val stdio =
      StdioServerTransport(
        input = System.`in`.asSource().buffered(),
        output = System.out.asSink().buffered(),
      ) {
        scope = this@runBlocking
      }
    val finished = connectTransports(this, http, stdio)
    try {
      http.start()
      stdio.start()
      finished.await()
    } finally {
      closeTransports(http, stdio, client)
    }
  }

  private fun validateEndpoint(endpoint: String) {
    val uri = URI(endpoint)
    require(
      uri.scheme == "http" &&
        uri.host == "127.0.0.1" &&
        uri.port in 1..MAX_PORT &&
        uri.path == "/mcp" &&
        uri.userInfo == null &&
        uri.query == null &&
        uri.fragment == null
    )
  }

  private fun connectTransports(
    scope: CoroutineScope,
    http: StreamableHttpClientTransport,
    stdio: StdioServerTransport,
  ): CompletableDeferred<Unit> {
    val finished = CompletableDeferred<Unit>()
    http.onMessage { message ->
      if (message is JSONRPCResponse) {
        (message.result as? InitializeResult)?.protocolVersion?.let { http.protocolVersion = it }
      }
      stdio.send(message)
    }
    http.onError {
      finished.completeExceptionally(IllegalStateException("MCP_HTTP_CONNECTION_FAILED"))
    }
    http.onClose { finished.complete(Unit) }
    val pending = Semaphore(16)
    stdio.onMessage { message ->
      if (message is JSONRPCRequest && message.method != "initialize") {
        check(pending.tryAcquire()) { "MCP_BRIDGE_BUSY" }
        scope.launch {
          try {
            http.send(message)
          } finally {
            pending.release()
          }
        }
      } else {
        http.send(message)
      }
    }
    stdio.onClose { finished.complete(Unit) }
    stdio.onError {
      finished.completeExceptionally(IllegalStateException("MCP_STDIO_CONNECTION_FAILED"))
    }
    return finished
  }

  private suspend fun closeTransports(
    http: StreamableHttpClientTransport,
    stdio: StdioServerTransport,
    client: HttpClient,
  ) =
    withContext(NonCancellable) {
      try {
        withTimeoutOrNull(CLOSE_TIMEOUT_MS) { http.terminateSession() }
      } catch (_: IOException) {
        // A closed endpoint has already revoked the session.
      } catch (_: StreamableHttpError) {
        // An expired session no longer needs deletion.
      } finally {
        http.close()
        stdio.close()
        client.close()
      }
    }
}
