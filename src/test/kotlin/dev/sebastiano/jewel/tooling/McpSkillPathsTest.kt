package dev.sebastiano.jewel.tooling

import java.nio.file.Path
import junit.framework.TestCase

class McpSkillPathsTest : TestCase() {
    fun testAllClientDefaults() {
        val home = Path.of(System.getProperty("user.home")).resolve("jewel-test-home")
        val paths = McpSkillPaths(home, emptyMap())
        val expected =
            listOf(
                ".android-studio/skills",
                ".gemini/config/skills",
                ".agents/skills",
                ".claude/skills",
                ".pi/agent/skills",
                ".config/agents/skills",
                ".config/opencode/skills",
                ".copilot/skills",
                ".copilot/skills",
                ".copilot/skills",
            )
        McpClient.entries.zip(expected).forEach { (client, prefix) ->
            assertEquals(
                home.resolve("$prefix/${McpSkillBundle.ID}/SKILL.md"),
                paths.destination(client),
            )
        }
    }

    fun testSkillOverridesAreIndependentOfMcpConfigurationOverrides() {
        val home = Path.of(System.getProperty("user.home")).resolve("jewel-test-home")
        val custom = home.resolve("custom")
        val paths =
            McpSkillPaths(
                home,
                mapOf(
                    "CLAUDE_CONFIG_DIR" to custom.toString(),
                    "PI_CODING_AGENT_DIR" to custom.toString(),
                    "XDG_CONFIG_HOME" to custom.toString(),
                    "CODEX_HOME" to custom.toString(),
                    "OPENCODE_CONFIG" to custom.resolve("config.json").toString(),
                ),
            )
        val suffix = "${McpSkillBundle.ID}/SKILL.md"
        assertEquals(custom.resolve("skills/$suffix"), paths.destination(McpClient.CLAUDE))
        assertEquals(custom.resolve("skills/$suffix"), paths.destination(McpClient.PI))
        assertEquals(
            custom.resolve("opencode/skills/$suffix"),
            paths.destination(McpClient.OPENCODE),
        )
        assertEquals(home.resolve(".agents/skills/$suffix"), paths.destination(McpClient.CODEX))
        assertEquals(
            home.resolve(".config/agents/skills/$suffix"),
            paths.destination(McpClient.AMP),
        )
        assertEquals(
            home.resolve(".config/opencode/skills/$suffix"),
            McpSkillPaths(home, mapOf("XDG_CONFIG_HOME" to "")).destination(McpClient.OPENCODE),
        )
    }
}
