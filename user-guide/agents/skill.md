# Agent skill

Install `jewel-compose-analysis` through **Tools → Compose Analysis MCP Server… → Agent skill**. This optional skill explains the four analysis tools and their evidence to the selected coding client. The skill and plugin have separate versions.

Skill installation works with the MCP server disabled. Tool calls require an enabled server. Installing the skill leaves MCP connections and approval settings unchanged.

1. Check the **Skill file** path. You can select a custom client profile or discovery directory.
2. Select **Check skill** after changing the path.
3. Select **Install skill**. Refresh or restart the agent to load it.

| Client | Default skill directory |
| --- | --- |
| Android Studio | `~/.android-studio/skills/jewel-compose-analysis/` |
| Antigravity | `~/.gemini/config/skills/jewel-compose-analysis/` |
| Codex | `~/.agents/skills/jewel-compose-analysis/` |
| Claude Code | `$CLAUDE_CONFIG_DIR/skills/jewel-compose-analysis/`, or `~/.claude/skills/jewel-compose-analysis/` |
| Pi | `$PI_CODING_AGENT_DIR/skills/jewel-compose-analysis/`, or `~/.pi/agent/skills/jewel-compose-analysis/` |
| Amp | `~/.config/agents/skills/jewel-compose-analysis/` |
| OpenCode | `$XDG_CONFIG_HOME/opencode/skills/jewel-compose-analysis/`, or `~/.config/opencode/skills/jewel-compose-analysis/` |
| GitHub Copilot | `~/.copilot/skills/jewel-compose-analysis/` for CLI, VS Code, and JetBrains |

The defaults install skills for the current user. Clients can also discover skills in other clients' directories. `CODEX_HOME` does not affect Codex's skill path. `OPENCODE_CONFIG` does not affect OpenCode's skill path. Older Antigravity installations may need a directory such as `~/.gemini/antigravity/skills/`. If your configuration uses symbolic links, select the direct directory.

When the plugin bundles a newer skill, **Check skill** offers **Update skill**. Selecting a client also checks its skill. Every update needs your confirmation. The installer preserves newer installed versions and refuses to overwrite edited or foreign skills. For conflicts, move the existing folder aside before reinstalling. Identical unmanaged copies stay unmanaged and ineligible for automatic replacement.

After interrupted setup, choose **Resume skill setup** to reconcile saved state. The private IDE support directory keeps one previous file per destination. Keep the hidden ownership record and lock file in the skill directory. Before writing, the installer rechecks changed destinations. Concurrent writes by another application can still block installation.

The default directories follow these client references:
[Android Studio](https://developer.android.com/studio/gemini/skills),
[Antigravity](https://antigravity.google/docs/migration/workflows-to-skills),
[Codex](https://learn.chatgpt.com/docs/build-skills),
[Claude Code](https://code.claude.com/docs/en/skills),
[Pi](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/skills.md),
[Amp](https://ampcode.com/docs/customize/skills),
[OpenCode](https://opencode.ai/docs/skills), and
[Copilot](https://docs.github.com/en/enterprise-cloud%40latest/copilot/concepts/agents/about-agent-skills).
