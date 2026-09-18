package dev.sebastiano.jewel.tooling

import dev.sebastiano.jewel.tooling.mcp.bootstrap.Discovery
import java.nio.file.Path

internal data class McpSkillVersion(val text: String) : Comparable<McpSkillVersion> {
  private val parts = text.split('.').map { it.toIntOrNull() }

  init {
    require(Regex("(0|[1-9][0-9]{0,8})(\\.(0|[1-9][0-9]{0,8})){2}").matches(text))
  }

  override fun compareTo(other: McpSkillVersion): Int {
    for (index in parts.indices) {
      val result = requireNotNull(parts[index]).compareTo(requireNotNull(other.parts[index]))
      if (result != 0) return result
    }
    return 0
  }
}

internal class McpSkillBundle(val text: String) {
  val version: McpSkillVersion
  val digest: String = Discovery.digest(text.toByteArray())

  init {
    require(text.contains("\n---\n"))
    val header = text.substringBefore("\n---\n")
    require(header.startsWith("---\n"))
    require(header.lineSequence().count { it == "name: $ID" } == 1)
    require(
      header.lineSequence().any {
        it.startsWith("description: ") && it.removePrefix("description: ").isNotBlank()
      }
    )
    val versions = Regex("(?m)^  version: \"([^\"]+)\"$").findAll(header).toList()
    require(versions.size == 1 && header.lineSequence().count { it == "metadata:" } == 1)
    version = McpSkillVersion(versions.single().groupValues[1])
  }

  companion object {
    const val ID = "jewel-compose-analysis"

    fun bundled(): McpSkillBundle =
      McpSkillBundle(
        requireNotNull(McpSkillBundle::class.java.getResourceAsStream("/skills/$ID/SKILL.md"))
          .bufferedReader(Charsets.UTF_8)
          .use { it.readText() }
      )
  }
}

internal class McpSkillPaths(private val home: Path, private val environment: Map<String, String>) {
  fun destination(client: McpClient): Path {
    val root =
      when (client) {
        McpClient.ANDROID_STUDIO -> home.resolve(".android-studio/skills")
        McpClient.ANTIGRAVITY -> home.resolve(".gemini/config/skills")
        McpClient.CODEX -> home.resolve(".agents/skills")
        McpClient.CLAUDE -> env("CLAUDE_CONFIG_DIR", home.resolve(".claude")).resolve("skills")
        McpClient.PI -> env("PI_CODING_AGENT_DIR", home.resolve(".pi/agent")).resolve("skills")
        McpClient.AMP -> home.resolve(".config/agents/skills")
        McpClient.OPENCODE ->
          env("XDG_CONFIG_HOME", home.resolve(".config")).resolve("opencode/skills")
        McpClient.COPILOT_JETBRAINS,
        McpClient.COPILOT_CLI,
        McpClient.COPILOT_VSCODE -> home.resolve(".copilot/skills")
      }
    return root.resolve(McpSkillBundle.ID).resolve("SKILL.md")
  }

  private fun env(name: String, fallback: Path) =
    environment[name]?.takeIf { it.isNotBlank() }?.let(Path::of) ?: fallback
}
