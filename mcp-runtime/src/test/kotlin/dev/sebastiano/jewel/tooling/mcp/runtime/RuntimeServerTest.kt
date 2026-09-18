package dev.sebastiano.jewel.tooling.mcp.runtime

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeServerTest {
  @Test(timeout = 20000)
  fun sdkClientNegotiatesListsAndCallsStructuredTool() = runBlocking {
    val input = AtomicReference<String>()
    endpoint { _, arguments ->
        input.set(arguments)
        CompletableFuture.completedFuture(RESULT)
      }
      .use { server ->
        connected(server) { client, _ ->
          val listed = client.listTools().tools.single()
          assertEquals("jewel_status", listed.name)
          assertNotNull(listed.outputSchema)
          val result =
            client.callTool(CallToolRequest(CallToolRequestParams(name = "jewel_status")))
          assertEquals(
            "result",
            result.structuredContent!!
              .jsonObject["payload"]!!
              .jsonObject["kind"]!!
              .jsonPrimitive
              .content,
          )
          assertTrue(input.get().contains("2025-11-25"))
          assertFalse(result.isError == true)
        }
      }
  }

  @Test(timeout = 20000)
  fun authRunsBeforeParsingForEveryMethod() {
    endpoint { _, _ -> CompletableFuture.completedFuture(RESULT) }
      .use { server ->
        for (method in listOf("POST", "GET", "DELETE")) {
          assertEquals(401, request(server, method, token = null, body = "x".repeat(70_000)))
          assertEquals(403, request(server, method, origin = "http://localhost"))
        }
        assertEquals(405, request(server, "GET"))
        assertEquals(404, request(server, "GET", path = "/other"))
        assertEquals(413, request(server, "POST", body = "x".repeat(70_000)))
      }
  }

  @Test(timeout = 20000)
  fun explicitClientCancellationCancelsIdeFuture() = runBlocking {
    val future = CompletableFuture<String>()
    val entered = CompletableDeferred<Unit>()
    endpoint { _, _ ->
        entered.complete(Unit)
        future
      }
      .use { server ->
        connected(server) { client, _ ->
          val call = async {
            client.callTool(CallToolRequest(CallToolRequestParams(name = "jewel_status")))
          }
          withTimeout(5000) { entered.await() }
          call.cancelAndJoin()
          withTimeout(5000) { while (!future.isCancelled) delay(10) }
        }
      }
  }

  @Test(timeout = 20000)
  fun deleteSessionCancelsInFlightAnalysis() = runBlocking {
    val future = CompletableFuture<String>()
    val entered = CompletableDeferred<Unit>()
    endpoint { _, _ ->
        entered.complete(Unit)
        future
      }
      .use { server ->
        connected(server) { client, transport ->
          val call = launch {
            runCatching {
              client.callTool(CallToolRequest(CallToolRequestParams(name = "jewel_status")))
            }
          }
          withTimeout(5000) { entered.await() }
          assertEquals(200, request(server, "DELETE", session = transport.sessionId))
          withTimeout(5000) { while (!future.isCancelled) delay(10) }
          call.cancelAndJoin()
        }
      }
  }

  @Test(timeout = 20000)
  fun closeRevokesEndpointAndCancelsWork() = runBlocking {
    val future = CompletableFuture<String>()
    val entered = CompletableDeferred<Unit>()
    val server = endpoint { _, _ ->
      entered.complete(Unit)
      future
    }
    connected(server) { client, _ ->
      val call = launch {
        runCatching {
          client.callTool(CallToolRequest(CallToolRequestParams(name = "jewel_status")))
        }
      }
      withTimeout(5000) { entered.await() }
      withContext(Dispatchers.IO) { server.close() }
      assertTrue(future.isCancelled)
      call.cancelAndJoin()
    }
    server.close()
  }

  @Test(timeout = 20000)
  fun oldProtocolIsRejectedWithoutCallingIde() {
    endpoint { _, _ -> throw AssertionError("Analysis must not run during initialization") }
      .use { server ->
        val response = raw(server, initializeMessage("2025-03-26"))
        assertTrue(response.contains("2025-06-18 or newer"))
        assertTrue(response.contains("-32602"))
      }
  }

  @Test(timeout = 20000)
  fun sessionAdmissionIsBoundedAndDeleteFreesSlot() = runBlocking {
    endpoint { _, _ -> CompletableFuture.completedFuture(RESULT) }
      .use { server ->
        val clients = ArrayList<Triple<Client, HttpClient, StreamableHttpClientTransport>>()
        try {
          repeat(8) {
            val http = HttpClient(CIO) { install(SSE) }
            val client = Client(Implementation("test", "1"))
            val transport =
              StreamableHttpClientTransport(http, "http://127.0.0.1:${server.port}/mcp") {
                header("Authorization", "Bearer $TOKEN")
              }
            client.connect(transport)
            clients.add(Triple(client, http, transport))
          }
          assertEquals(429, request(server, "POST"))
        } finally {
          clients.forEach { (client, http, transport) ->
            transport.terminateSession()
            client.close()
            http.close()
          }
        }
        connected(server) { client, _ -> assertEquals(1, client.listTools().tools.size) }
      }
  }

  @Test(timeout = 20000)
  fun oversizedResultHasStructuredErrorEnvelope() = runBlocking {
    endpoint { _, _ ->
        CompletableFuture.completedFuture(
          """{"schemaVersion":1,"payload":{"kind":"result","value":"${"x".repeat(600000)}"}}"""
        )
      }
      .use { server ->
        connected(server) { client, _ ->
          val result =
            client.callTool(CallToolRequest(CallToolRequestParams(name = "jewel_status")))
          assertTrue(result.isError == true)
          assertEquals(
            "LIMIT_EXCEEDED",
            result.structuredContent!!
              .jsonObject["payload"]!!
              .jsonObject["code"]!!
              .jsonPrimitive
              .content,
          )
        }
      }
  }

  private fun raw(server: RuntimeServer, body: String): String =
    java.net.http.HttpClient.newHttpClient().use {
      it
        .send(
          HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/mcp"))
            .header("Authorization", "Bearer $TOKEN")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
          HttpResponse.BodyHandlers.ofString(),
        )
        .body()
    }

  @Test(timeout = 20000)
  fun hostHeaderMustMatchBoundEndpoint() {
    endpoint { _, _ -> CompletableFuture.completedFuture(RESULT) }
      .use { server ->
        java.net.Socket("127.0.0.1", server.port).use { socket ->
          socket.soTimeout = 3000
          socket
            .getOutputStream()
            .write(
              ("GET /mcp HTTP/1.1\r\nHost: attacker.example\r\n" +
                  "Authorization: Bearer $TOKEN\r\nConnection: close\r\n\r\n")
                .toByteArray()
            )
          assertTrue(socket.getInputStream().bufferedReader().readLine().contains("403"))
        }
      }
  }

  @Test(timeout = 20000)
  fun endpointCredentialsDoNotAuthorizeAnotherProject() {
    endpoint { _, _ -> CompletableFuture.completedFuture(RESULT) }
      .use { first ->
        RuntimeServer.start(mapOf("token" to TOKEN.reversed(), "pluginVersion" to "test"), TOOLS) {
            _,
            _ ->
            CompletableFuture.completedFuture(RESULT)
          }
          .use { second ->
            assertEquals(405, request(first, "GET"))
            assertEquals(401, request(second, "GET"))
          }
      }
  }

  @Test(timeout = 20000)
  fun unfinishedHandshakesExpireAndReleaseAdmission() = runBlocking {
    endpoint { _, _ -> CompletableFuture.completedFuture(RESULT) }
      .use { server ->
        repeat(8) {
          val response = raw(server, initializeMessage("2025-11-25"))
          assertTrue(response.contains("2025-11-25"))
        }
        assertEquals(429, request(server, "POST"))
        withTimeout(13000) { while (request(server, "POST") == 429) delay(100) }
        connected(server) { client, _ -> assertEquals(1, client.listTools().tools.size) }
      }
  }

  private fun initializeMessage(version: String): String =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$version",
      "capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""

  private fun endpoint(callback: (String, String) -> CompletableFuture<String>) =
    RuntimeServer.start(mapOf("token" to TOKEN, "pluginVersion" to "test"), TOOLS, callback)

  private suspend fun connected(
    server: RuntimeServer,
    body: suspend (Client, StreamableHttpClientTransport) -> Unit,
  ) {
    val http =
      HttpClient(CIO) {
        install(SSE)
        followRedirects = false
      }
    val transport =
      StreamableHttpClientTransport(http, "http://127.0.0.1:${server.port}/mcp") {
        header("Authorization", "Bearer $TOKEN")
      }
    val client = Client(Implementation("test", "1"))
    try {
      client.connect(transport)
      body(client, transport)
    } finally {
      client.close()
      http.close()
    }
  }

  private fun request(
    server: RuntimeServer,
    method: String,
    token: String? = TOKEN,
    body: String = "{}",
    origin: String? = null,
    path: String = "/mcp",
    session: String? = null,
  ): Int {
    val builder =
      HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}$path"))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .header("MCP-Protocol-Version", "2025-11-25")
        .method(method, HttpRequest.BodyPublishers.ofString(body))
    token?.let { builder.header("Authorization", "Bearer $it") }
    origin?.let { builder.header("Origin", it) }
    session?.let { builder.header("Mcp-Session-Id", it) }
    return java.net.http.HttpClient.newHttpClient().use {
      it.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    }
  }

  companion object {
    private const val TOKEN = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"
    private const val RESULT = """{"schemaVersion":1,"payload":{"kind":"result","ready":true}}"""
    private const val TOOLS =
      """[{"name":"jewel_status","description":"Status","inputSchema":{"type":"object","properties":{}},"outputSchema":{"type":"object","properties":{"schemaVersion":{"type":"integer"},"payload":{"type":"object"}},"required":["schemaVersion","payload"]},"annotations":{"readOnlyHint":true,"destructiveHint":false,"idempotentHint":true,"openWorldHint":false}}]"""
  }
}
