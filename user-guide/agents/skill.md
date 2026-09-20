# Agent skill

The optional **Agent skill** section in **Tools → Compose Analysis MCP Server…** installs `jewel-compose-analysis` for the selected client. It teaches the agent how to use the four analysis tools and interpret their evidence. The skill has its own version, separate from the plugin version.

You can install the skill while the MCP server is disabled. Enable the server before using its tools. Installing the skill does not install an MCP connection or change approval settings.

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

These are user-level paths. Some clients also discover other clients' skill directories. Codex's skill path is independent of `CODEX_HOME`. OpenCode's skill path is independent of the `OPENCODE_CONFIG` file override. For older Antigravity installations, select the client's supported skill directory, such as `~/.gemini/antigravity/skills/`. Use a direct directory when your configuration uses symbolic links.

When a plugin update bundles a newer skill, **Check skill** offers **Update skill**. The dialog also checks when you select a client. Updates require your click; nothing updates silently. A newer installed version is preserved. Locally edited or foreign skills are never overwritten. To recover a conflict, move the skill folder aside to preserve your work, then install again. An identical unmanaged copy remains unmanaged and cannot receive automatic replacement.

If setup was interrupted, select **Resume skill setup** to reconcile its saved state. The installer keeps one previous file per destination in its private IDE support directory. The skill directory also contains a hidden ownership record and lock file. Keep them with the skill. A changed destination is checked again before writing. Another application's concurrent writes can still prevent installation.

The directory defaults follow the client documentation:
[Android Studio](https://developer.android.com/studio/gemini/skills),
[Antigravity](https://antigravity.google/docs/migration/workflows-to-skills),
[Codex](https://learn.chatgpt.com/docs/build-skills),
[Claude Code](https://code.claude.com/docs/en/skills),
[Pi](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/skills.md),
[Amp](https://ampcode.com/docs/customize/skills),
[OpenCode](https://opencode.ai/docs/skills), and
[Copilot](https://docs.github.com/en/enterprise-cloud%40latest/copilot/concepts/agents/about-agent-skills).
