package dev.sebastiano.jewel.tooling

import com.google.gson.JsonObject
import dev.sebastiano.jewel.tooling.mcp.bootstrap.Discovery
import java.nio.file.Files
import java.nio.file.Path
import junit.framework.TestCase
import kotlinx.coroutines.runBlocking

class McpClientInstallTest : TestCase() {
  private lateinit var temporary: Path
  private lateinit var support: Path
  private lateinit var launch: McpClientLaunch

  override fun setUp() {
    temporary = Files.createTempDirectory("jewel-client-tests-")
    support = Files.createDirectory(temporary.resolve("support"))
    launch =
      McpClientLaunch(
        listOf(
          "/absolute/java",
          "-jar",
          "/support/bootstrap.jar",
          "/support/project.properties",
          "project",
        ),
        "jewel-profile-project",
        temporary.resolve("project"),
      )
  }

  override fun tearDown() {
    Files.walk(temporary).use { files ->
      files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
  }

  fun testJsonClientsPreserveSettingsAndAreIdempotent() = runBlocking {
    val clients =
      McpClient.entries.filter { it != McpClient.CODEX && it != McpClient.ANDROID_STUDIO }
    for (client in clients) {
      val dir = Files.createDirectory(temporary.resolve(client.key))
      if (client == McpClient.PI)
        Files.writeString(
          dir.resolve("settings.json"),
          """{"packages":["npm:pi-mcp-adapter@2.34.0"]}""",
        )
      val file = dir.resolve("config.json")
      val original =
        "{\n  // retain this comment\n  \"unrelated\": {\"answer\":42},\n  \"${client.container}\": {\"other\": {\"command\": \"keep\"},},\n}\n"
      Files.writeString(file, original)
      val installer = McpClientInstaller(support)
      val request = McpInstallRequest(client, file.toString())
      assertTrue(installer.install(request, launch))
      val installed = Files.readString(file)
      val parsed = McpJsonConfig(installed)
      assertTrue(installed.contains("// retain this comment"))
      assertEquals(42, parsed.value(listOf("unrelated", "answer"))!!.asInt)
      assertEquals("keep", parsed.value(listOf(client.container, "other", "command"))!!.asString)
      assertEquals(client.entry(launch), parsed.value(listOf(client.container, launch.name)))
      assertFalse(installer.install(request, launch))
      assertEquals(installed, Files.readString(file))
      Discovery.checkPrivate(file, false)
    }
  }

  fun testJsonParserRejectsDuplicatesMalformedAndDeepInput() {
    listOf(
        "{\"x\":1,\"x\":2}",
        "{\"x\":01}",
        "{\"x\":NaN}",
        "{\"x\":\"\\q\"}",
        "{\"x\":}",
        "{\"x\":[],}garbage",
        "{\"x\":".repeat(66) + "0" + "}".repeat(66),
      )
      .forEach { assertEquals("invalidConfig", failure { McpJsonConfig(it) }.reason) }
    val document = McpJsonConfig("{/* retained */}")
    assertEquals(
      "value",
      McpJsonConfig(document.put(listOf("nested", "key"), com.google.gson.JsonPrimitive("value")))
        .value(listOf("nested", "key"))!!
        .asString,
    )
  }

  fun testConflictingEntryAndConcurrentChangesRemainUntouched() {
    val file = temporary.resolve("client.json")
    Files.writeString(file, """{"mcpServers":{"jewel-profile-project":{"command":"foreign"}}}""")
    val original = Files.readString(file)
    assertEquals(
      "conflict",
      failure {
          McpOwnedConfig(support)
            .install(McpClient.CLAUDE, file, launch, McpClient.CLAUDE.entry(launch))
        }
        .reason,
    )
    assertEquals(original, Files.readString(file))
    McpConfigUpdate(file, support).use { update ->
      Files.writeString(file, "{\"external\":true}")
      assertEquals("changed", failure { update.commit("{}") }.reason)
    }
    assertEquals("{\"external\":true}", Files.readString(file))
  }

  fun testBackupPermissionsAndSymlinkProtection() {
    val file = temporary.resolve("client.json")
    Files.writeString(file, "{\"retained\":true}")
    McpConfigUpdate(file, support).use {
      assertTrue(it.commit("{\"retained\":true,\"added\":true}"))
    }
    val backup = support.resolve(Discovery.digest(file.toString().toByteArray()) + ".backup")
    assertEquals("{\"retained\":true}", Files.readString(backup))
    Discovery.checkPrivate(backup, false)
    val link = temporary.resolve("link.json")
    Files.createSymbolicLink(link, file)
    assertEquals("unsafePath", failure { McpConfigUpdate(link, support).close() }.reason)
    assertTrue(Files.isSymbolicLink(link))
  }

  fun testLargeClaudeConfigAndCanonicalOwnership() {
    val file = temporary.resolve("claude.json")
    val large = "{\"state\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}"
    Files.writeString(file, large)
    val owned = McpOwnedConfig(support)
    assertTrue(owned.install(McpClient.CLAUDE, file, launch, McpClient.CLAUDE.entry(launch)))
    val parsed = McpJsonConfig(Files.readString(file))
    assertEquals(2 * 1024 * 1024, parsed.value(listOf("state"))!!.asString.length)
    val entry = McpClient.CLAUDE.entry(launch)
    val reordered = JsonObject().apply { entry.keySet().reversed().forEach { add(it, entry[it]) } }
    assertEquals(McpOwnedConfig.fingerprint(entry), McpOwnedConfig.fingerprint(reordered))
    Files.writeString(file, parsed.put(listOf("mcpServers", launch.name), reordered))
    val repaired = launch.copy(command = listOf("/moved/java") + launch.command.drop(1))
    assertTrue(owned.install(McpClient.CLAUDE, file, repaired, McpClient.CLAUDE.entry(repaired)))
  }

  fun testDifferentProfilesAndProjectsKeepSeparateEntries() {
    val file = temporary.resolve("client.json")
    val owned = McpOwnedConfig(support)
    for (name in
      listOf("jewel-profile1-project1", "jewel-profile1-project2", "jewel-profile2-project1")) {
      val target = launch.copy(name = name, command = launch.command.dropLast(1) + name)
      owned.install(McpClient.ANTIGRAVITY, file, target, McpClient.ANTIGRAVITY.entry(target))
    }
    assertEquals(
      3,
      McpJsonConfig(Files.readString(file)).value(listOf("mcpServers"))!!.asJsonObject.size(),
    )
  }

  fun testStudioRefreshRevocationAndForeignEdit() = runBlocking {
    val project = Files.createDirectory(temporary.resolve("project"))
    val profile = Files.createDirectory(temporary.resolve("AndroidStudio"))
    Files.createDirectory(profile.resolve("options"))
    val privateFiles = temporary.resolve("discovery")
    var discovery = Discovery.open(privateFiles, project)
    try {
      discovery.publish(mapOf("endpoint" to "http://127.0.0.1:43210/mcp", "token" to "first"))
      val target = McpClientLaunch.create(privateFiles.resolve("bootstrap.jar"), discovery, project)
      val file = profile.resolve("mcp.json")
      val installer = McpClientInstaller(support)
      installer.install(McpInstallRequest(McpClient.ANDROID_STUDIO, file.toString()), target)
      assertTrue(Files.readString(file).contains("Bearer first"))
      discovery.close()
      installer.refreshStudio(target, false)
      val disabled =
        McpJsonConfig(Files.readString(file))
          .value(listOf("mcpServers", target.name))!!
          .asJsonObject
      assertFalse(disabled["enabled"].asBoolean)
      assertFalse(disabled.has("headers"))
      discovery = Discovery.open(privateFiles, project)
      discovery.publish(mapOf("endpoint" to "http://127.0.0.1:43211/mcp", "token" to "second"))
      installer.refreshStudio(target, true)
      assertTrue(Files.readString(file).contains("Bearer second"))
      val edited =
        McpJsonConfig(Files.readString(file))
          .put(
            listOf("mcpServers", target.name, "httpUrl"),
            com.google.gson.JsonPrimitive("https://user.example/mcp"),
          )
      Files.writeString(file, edited)
      assertEquals("conflict", failure { installer.refreshStudio(target, false) }.reason)
      assertEquals(edited, Files.readString(file))
    } finally {
      discovery.close()
    }
  }

  fun testPathsAndAmbiguousStudioProfiles() {
    val paths = McpClientPaths(temporary, temporary.resolve("idea"), emptyMap(), "Mac OS X")
    assertEquals(
      temporary.resolve(".gemini/config/mcp_config.json"),
      paths.destination(McpClient.ANTIGRAVITY),
    )
    assertEquals(
      temporary.resolve(".config/github-copilot/intellij/mcp.json"),
      paths.destination(McpClient.COPILOT_JETBRAINS),
    )
    assertNull(paths.destination(McpClient.ANDROID_STUDIO))
    Files.createDirectories(temporary.resolve("Library/Application Support/Google/AndroidStudio1"))
    assertNotNull(paths.destination(McpClient.ANDROID_STUDIO))
    Files.createDirectories(temporary.resolve("Library/Application Support/Google/AndroidStudio2"))
    assertNull(paths.destination(McpClient.ANDROID_STUDIO))
    val windows =
      McpClientPaths(
        temporary,
        temporary,
        mapOf("LOCALAPPDATA" to temporary.resolve("local").toString()),
        "Windows 11",
      )
    assertEquals(
      temporary.resolve("local/github-copilot/intellij/mcp.json"),
      windows.destination(McpClient.COPILOT_JETBRAINS),
    )
  }

  private fun failure(block: () -> Unit): McpSetupFailure {
    try {
      block()
    } catch (failure: McpSetupFailure) {
      return failure
    }
    error("Expected setup failure")
  }
}
