package dev.sebastiano.jewel.tooling.mcp.runtime

import dev.sebastiano.jewel.tooling.mcp.bootstrap.Discovery
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BridgeTest {
    @Rule @JvmField val temporary = TemporaryFolder()

    @Test(timeout = 20000)
    fun sdkClientUsesInstalledBootstrapAndCancelsAnalysis() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val pending = CompletableFuture<String>()
        val calls = AtomicInteger()
        server { _, _ ->
            if (calls.incrementAndGet() == 1) CompletableFuture.completedFuture(RESULT)
            else {
                entered.complete(Unit)
                pending
            }
        }
            .use { server ->
                discovery(server).use { discovery ->
                    withClient(command(discovery)) { client ->
                        assertEquals("test", client.listTools().tools.single().name)
                        val result =
                            client.callTool(CallToolRequest(CallToolRequestParams(name = "test")))
                        assertEquals(
                            "result",
                            result.structuredContent!!
                                .jsonObject["payload"]!!
                                .jsonObject["kind"]!!
                                .jsonPrimitive
                                .content,
                        )
                        val call = async {
                            client.callTool(CallToolRequest(CallToolRequestParams(name = "test")))
                        }
                        withTimeout(5000) { entered.await() }
                        call.cancelAndJoin()
                        withTimeout(5000) { while (!pending.isCancelled) delay(10) }
                    }
                    assertAllSessionSlotsAvailable(server)
                }
            }
    }

    @Test(timeout = 20000)
    fun bootstrapRejectsWrongProjectWithoutPrintingSecrets() {
        server { _, _ -> CompletableFuture.completedFuture(RESULT) }
            .use { server ->
                discovery(server).use { discovery ->
                    val command = command(discovery).dropLast(1) + "wrong-project"
                    val process = ProcessBuilder(command).start()
                    try {
                        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
                        assertEquals(1, process.exitValue())
                        assertEquals("", process.inputStream.bufferedReader().readText())
                        val error = process.errorStream.bufferedReader().readText()
                        assertTrue(error.contains("cannot connect"))
                        assertFalse(error.contains(TOKEN))
                    } finally {
                        process.destroyForcibly()
                    }
                }
            }
    }

    @Test(timeout = 20000)
    fun sameBootstrapCommandUsesRotatedDiscovery() = runBlocking {
        server { _, _ -> CompletableFuture.completedFuture(RESULT) }
            .use { first ->
                discovery(first).use { discovery ->
                    val command = command(discovery)
                    withClient(command) { client -> assertEquals(1, client.listTools().tools.size) }
                    first.close()
                    RuntimeServer.start(
                            mapOf("token" to TOKEN.reversed(), "pluginVersion" to "rotated"),
                            TOOLS,
                        ) { _, _ ->
                            CompletableFuture.completedFuture(RESULT)
                        }
                        .use { second ->
                            publish(discovery, second, TOKEN.reversed())
                            withClient(command) { client ->
                                assertEquals(1, client.listTools().tools.size)
                                assertFalse(
                                    client
                                        .callTool(
                                            CallToolRequest(CallToolRequestParams(name = "test"))
                                        )
                                        .isError == true
                                )
                            }
                        }
                }
            }
    }

    @Test(timeout = 20000)
    fun bootstrapStartsWithProtocolJsonWithoutLoggingBanner() {
        server { _, _ -> CompletableFuture.completedFuture(RESULT) }
            .use { server ->
                discovery(server).use { discovery ->
                    val process =
                        ProcessBuilder(command(discovery))
                            .apply { environment()["KOTLIN_LOGGING_STARTUP_MESSAGE"] = "true" }
                            .start()
                    try {
                        val initialize =
                            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{""" +
                                """"protocolVersion":"2025-11-25","capabilities":{},""" +
                                """"clientInfo":{"name":"raw-protocol-test","version":"1"}}}""" +
                                "\n"
                        process.outputStream.write(initialize.toByteArray())
                        process.outputStream.flush()
                        val first =
                            CompletableFuture.supplyAsync {
                                    process.inputStream.bufferedReader().readLine()
                                }
                                .get(10, TimeUnit.SECONDS)
                        val reply =
                            kotlinx.serialization.json.Json.parseToJsonElement(first).jsonObject
                        assertEquals("2.0", reply["jsonrpc"]!!.jsonPrimitive.content)
                        assertEquals("1", reply["id"]!!.jsonPrimitive.content)
                        assertTrue(reply["result"]!!.jsonObject.containsKey("serverInfo"))
                    } finally {
                        process.destroyForcibly()
                        process.waitFor(5, TimeUnit.SECONDS)
                        process.outputStream.close()
                        process.inputStream.close()
                        process.errorStream.close()
                    }
                }
            }
    }

    private fun server(callback: (String, String) -> CompletableFuture<String>): RuntimeServer =
        RuntimeServer.start(mapOf("token" to TOKEN, "pluginVersion" to "test"), TOOLS, callback)

    private fun discovery(server: RuntimeServer): Discovery {
        val project = temporary.newFolder("project").toPath()
        return Discovery.open(temporary.root.toPath().resolve("private"), project).also {
            publish(it, server, TOKEN)
        }
    }

    private fun publish(discovery: Discovery, server: RuntimeServer, token: String) {
        val bytes = Files.readAllBytes(Path.of(System.getProperty("jewel.mcp.runtime.jar")))
        val digest = Discovery.digest(bytes)
        val runtime = Discovery.install(discovery.descriptorPath().parent, "$digest.jar", bytes)
        discovery.publish(
            mapOf(
                "endpoint" to "http://127.0.0.1:${server.port}/mcp",
                "token" to token,
                "runtimePath" to runtime.toString(),
                "runtimeDigest" to digest,
            )
        )
    }

    private fun command(discovery: Discovery) =
        listOf(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-jar",
            System.getProperty("jewel.mcp.bootstrap.jar"),
            discovery.descriptorPath().toString(),
            discovery.projectId(),
        )

    private suspend fun withClient(command: List<String>, body: suspend (Client) -> Unit) {
        val process = ProcessBuilder(command).start()
        val client = Client(Implementation("bootstrap-test", "1"))
        val transport =
            StdioClientTransport(
                process.inputStream.asSource().buffered(),
                process.outputStream.asSink().buffered(),
                process.errorStream.asSource().buffered(),
            )
        try {
            withTimeout(10000) { client.connect(transport) }
            body(client)
            client.close()
            assertTrue(
                "Bootstrap must exit after stdin closes",
                process.waitFor(5, TimeUnit.SECONDS),
            )
            assertEquals(0, process.exitValue())
        } finally {
            process.outputStream.close()
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            client.close()
        }
    }

    private fun assertAllSessionSlotsAvailable(server: RuntimeServer) {
        val initialize =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
      "protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""
        java.net.http.HttpClient.newHttpClient().use { client ->
            repeat(8) {
                val request =
                    HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/mcp"))
                        .header("Authorization", "Bearer $TOKEN")
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(initialize))
                        .build()
                assertEquals(
                    200,
                    client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode(),
                )
            }
        }
    }

    companion object {
        private const val TOKEN = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"
        private const val RESULT =
            """{"schemaVersion":1,"payload":{"kind":"result","ready":true}}"""
        private const val TOOLS =
            """[{"name":"test","inputSchema":{"type":"object","properties":{}}}]"""
    }
}
