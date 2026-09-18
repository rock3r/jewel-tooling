package dev.sebastiano.jewel.tooling

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.sebastiano.jewel.tooling.mcp.bootstrap.Discovery
import java.nio.file.Files
import java.nio.file.Path

internal enum class McpClient(val key: String, val container: String) {
  ANDROID_STUDIO("studio", "mcpServers"),
  ANTIGRAVITY("antigravity", "mcpServers"),
  CODEX("codex", "mcp_servers"),
  CLAUDE("claude", "mcpServers"),
  PI("pi", "mcpServers"),
  AMP("amp", "amp.mcpServers"),
  OPENCODE("opencode", "mcp"),
  COPILOT_JETBRAINS("copilotJetbrains", "servers"),
  COPILOT_CLI("copilotCli", "mcpServers"),
  COPILOT_VSCODE("copilotVscode", "servers");

  override fun toString(): String = JewelToolingBundle.message("mcp.client.$key")

  fun entry(launch: McpClientLaunch): JsonObject =
    JsonObject().apply {
      if (this@McpClient == OPENCODE) {
        addProperty("type", "local")
        add("command", JsonArray().apply { launch.command.forEach { add(it) } })
        addProperty("enabled", true)
      } else {
        addProperty("command", launch.command.first())
        add("args", JsonArray().apply { launch.command.drop(1).forEach { add(it) } })
        if (this@McpClient == CLAUDE || this@McpClient == COPILOT_VSCODE)
          addProperty("type", "stdio")
        if (this@McpClient == COPILOT_CLI) add("tools", JsonArray().apply { add("*") })
      }
    }
}

internal data class McpClientLaunch(
  val command: List<String>,
  val name: String,
  val projectRoot: Path,
) {
  val descriptor: Path
    get() = Path.of(command[DESCRIPTOR_ARGUMENT])

  val projectId: String
    get() = command[PROJECT_ARGUMENT]

  companion object {
    private const val ID_LENGTH = 12
    private const val DESCRIPTOR_ARGUMENT = 3
    private const val PROJECT_ARGUMENT = 4

    fun create(bootstrap: Path, discovery: Discovery, root: Path): McpClientLaunch {
      val java =
        Path.of(
          System.getProperty("java.home"),
          "bin",
          if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
        )
      val profile =
        Discovery.digest(bootstrap.parent.toRealPath().toString().toByteArray()).take(ID_LENGTH)
      return McpClientLaunch(
        listOf(
          java.toString(),
          "-jar",
          bootstrap.toString(),
          discovery.descriptorPath().toString(),
          discovery.projectId(),
        ),
        "jewel-$profile-${discovery.projectId().take(ID_LENGTH)}",
        root,
      )
    }
  }
}

internal class McpClientPaths(
  private val home: Path,
  private val ideConfig: Path,
  private val environment: Map<String, String>,
  private val os: String,
) {
  private val config = env("XDG_CONFIG_HOME", home.resolve(".config"))

  fun destination(client: McpClient): Path? =
    when (client) {
      McpClient.ANDROID_STUDIO -> studio()
      McpClient.ANTIGRAVITY -> home.resolve(".gemini/config/mcp_config.json")
      McpClient.CODEX -> env("CODEX_HOME", home.resolve(".codex")).resolve("config.toml")
      McpClient.CLAUDE -> env("CLAUDE_CONFIG_DIR", home).resolve(".claude.json")
      McpClient.PI -> env("PI_CODING_AGENT_DIR", home.resolve(".pi/agent")).resolve("mcp.json")
      McpClient.AMP -> jsonOrJsonc(home.resolve(".config/amp/settings.json"))
      McpClient.OPENCODE -> openCode()
      McpClient.COPILOT_JETBRAINS ->
        (if (os.startsWith("Windows")) env("LOCALAPPDATA", home.resolve("AppData/Local"))
          else config)
          .resolve("github-copilot/intellij/mcp.json")
      McpClient.COPILOT_CLI -> home.resolve(".copilot/mcp-config.json")
      McpClient.COPILOT_VSCODE -> vscode()
    }

  private fun openCode(): Path =
    environment["OPENCODE_CONFIG"]?.takeIf { it.isNotBlank() }?.let(Path::of)
      ?: jsonOrJsonc(config.resolve("opencode/opencode.json"))

  private fun vscode(): Path =
    when {
      os.startsWith("Mac") -> home.resolve("Library/Application Support/Code/User/mcp.json")
      os.startsWith("Windows") ->
        env("APPDATA", home.resolve("AppData/Roaming")).resolve("Code/User/mcp.json")
      else -> config.resolve("Code/User/mcp.json")
    }

  private fun studio(): Path? {
    if (ideConfig.fileName.toString().startsWith("AndroidStudio"))
      return ideConfig.resolve("mcp.json")
    val google =
      when {
        os.startsWith("Mac") -> home.resolve("Library/Application Support/Google")
        os.startsWith("Windows") ->
          env("APPDATA", home.resolve("AppData/Roaming")).resolve("Google")
        else -> config.resolve("Google")
      }
    return if (!Files.isDirectory(google)) null
    else
      Files.list(google).use { dirs ->
        dirs
          .filter { it.fileName.toString().startsWith("AndroidStudio") && Files.isDirectory(it) }
          .limit(2)
          .toList()
          .singleOrNull()
          ?.resolve("mcp.json")
      }
  }

  private fun env(name: String, fallback: Path): Path =
    environment[name]?.takeIf { it.isNotBlank() }?.let(Path::of) ?: fallback

  private fun jsonOrJsonc(json: Path): Path {
    val jsonc = json.resolveSibling(json.fileName.toString() + "c")
    if (Files.exists(json) && Files.exists(jsonc)) throw McpSetupFailure("ambiguous")
    return if (Files.exists(jsonc)) jsonc else json
  }
}
