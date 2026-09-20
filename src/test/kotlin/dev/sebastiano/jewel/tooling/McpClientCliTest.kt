package dev.sebastiano.jewel.tooling

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import junit.framework.TestCase
import kotlinx.coroutines.runBlocking

class McpClientCliTest : TestCase() {
    private lateinit var temporary: Path
    private lateinit var support: Path
    private lateinit var launch: McpClientLaunch

    override fun setUp() {
        temporary = Files.createTempDirectory("jewel-cli-tests-")
        support = Files.createDirectory(temporary.resolve("support"))
        launch =
            McpClientLaunch(
                listOf("/java", "-jar", "/bootstrap.jar", "/descriptor", "project"),
                "jewel-profile-project",
                temporary,
            )
    }

    override fun tearDown() {
        Files.walk(temporary).use { files ->
            files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    fun testCodexPreservesTomlAndValidatesBeforeWriting() = runBlocking {
        val destination = temporary.resolve("config.toml")
        val original = "# Keep this comment\nmodel = \"chosen\"\n"
        Files.writeString(destination, original)
        var calls = 0
        val process = McpProcessRunner { command, stage, environment ->
            calls++
            assertEquals(listOf("/codex", "mcp", "list", "--json"), command)
            assertEquals(stage.toString(), environment["CODEX_HOME"])
            assertTrue(stage.startsWith(support))
            assertEquals(original, Files.readString(destination))
            val staged = Files.readString(stage.resolve("config.toml"))
            assertTrue(staged.startsWith(original))
            McpProcessResult(0, if (staged.contains("[mcp_servers.")) codexEntry() else "[]")
        }
        assertTrue(McpCodexInstall(process).install(destination, "/codex", launch, support))
        assertEquals(2, calls)
        assertTrue(Files.readString(destination).startsWith(original))
        val installed = Files.readString(destination)
        val repeat = McpProcessRunner { _, _, _ -> McpProcessResult(0, codexEntry()) }
        assertFalse(McpCodexInstall(repeat).install(destination, "/codex", launch, support))
        assertEquals(installed, Files.readString(destination))
    }

    fun testRejectedCodexCandidateDoesNotChangeConfig() = runBlocking {
        val destination = temporary.resolve("config.toml")
        Files.writeString(destination, "# original\n")
        var calls = 0
        val process = McpProcessRunner { _, _, _ ->
            calls++
            McpProcessResult(if (calls == 1) 0 else 1, "[]")
        }
        try {
            McpCodexInstall(process).install(destination, "/codex", launch, support)
            fail("Expected validation failure")
        } catch (failure: McpSetupFailure) {
            assertEquals("codex", failure.reason)
        }
        assertEquals("# original\n", Files.readString(destination))
        assertEquals(
            0L,
            Files.list(support).use { paths -> paths.filter { Files.isDirectory(it) }.count() },
        )
    }

    fun testPiPackageFailureDoesNotWriteConfiguration() = runBlocking {
        val destination = temporary.resolve("mcp.json")
        Files.writeString(destination, "{\"unrelated\":true}")
        val process = McpProcessRunner { command, stage, environment ->
            assertEquals(listOf("/pi", "install", "npm:pi-mcp-adapter@2.34.0"), command)
            assertEquals(temporary.toRealPath().toString(), environment["PI_CODING_AGENT_DIR"])
            assertTrue(stage.startsWith(support))
            McpProcessResult(1, "")
        }
        try {
            McpClientInstaller(support, process)
                .install(McpInstallRequest(McpClient.PI, destination.toString(), "/pi"), launch)
            fail("Expected package failure")
        } catch (failure: McpSetupFailure) {
            assertEquals("piInstall", failure.reason)
        }
        assertEquals("{\"unrelated\":true}", Files.readString(destination))
    }

    fun testPiDetectsConfigChangeDuringPackageInstallation() = runBlocking {
        val destination = temporary.resolve("mcp.json")
        Files.writeString(destination, "{}")
        val process = McpProcessRunner { _, _, _ ->
            Files.writeString(destination, "{\"external\":true}")
            McpProcessResult(0, "")
        }
        try {
            McpClientInstaller(support, process)
                .install(McpInstallRequest(McpClient.PI, destination.toString(), "/pi"), launch)
            fail("Expected concurrent change")
        } catch (failure: McpSetupFailure) {
            assertEquals("piPartial", failure.reason)
        }
        assertEquals("{\"external\":true}", Files.readString(destination))
    }

    private fun codexEntry(): String =
        JsonArray()
            .apply {
                add(
                    JsonObject().apply {
                        addProperty("name", launch.name)
                        add("transport", McpClient.CLAUDE.entry(launch))
                    }
                )
            }
            .toString()
}
