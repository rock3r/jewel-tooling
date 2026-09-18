# Jewel Tooling

Jewel Tooling adds Compose stability hints and local live inspection to IntelliJ IDEA.
Read the [user guide](docs/user-guide.md) for implemented behavior and limits.
Keep proposals in the ignored `.plans/` directory. A proposal is not a supported feature.

## Start here

- [Contributing](CONTRIBUTING.md): setup and review boundaries.
- [Architecture](docs/architecture.md): module map, evidence, and lifecycle constraints.
- [Testing](docs/testing.md): targeted tests, compatibility, packaging, and captures.
- [Conventions](docs/conventions.md): Kotlin, UI, threading, and cancellation.
- [Static analysis](docs/static-analysis.md): source coverage and Detekt repair.

Inspect `git status --short` before editing. Preserve existing changes and coordinate ownership of shared files.
Do not create a worktree without asking. Do not modify another task's implementation to make a check pass.
Keep this work local. Do not publish, push, create a remote repository or PR, or release without user approval.
The user must test the plugin before public publication.

## Modules and skills

| Area | Location | Workflow |
| --- | --- | --- |
| IDE analysis, hints, UI, launch | `src/` | [Static evidence](.agents/skills/static-evidence/SKILL.md) |
| Recording model and transport | `recording/` | [Local ZIP and lifecycle](.agents/skills/local-plugin/SKILL.md) |
| Optional target adapter | `recording-compose/` | [Detekt repair](.agents/skills/detekt-repair/SKILL.md) |
| Startup agent, loader, bridge | `agent/`, `agent-premain/`, `agent-bridge/` | [Local ZIP and lifecycle](.agents/skills/local-plugin/SKILL.md) |
| Static MCP transport and bootstrap | `mcp-runtime/`, `mcp-bootstrap/` | [MCP setup](docs/agents/mcp.md), [local ZIP and lifecycle](.agents/skills/local-plugin/SKILL.md) |
| Real compiler output | `test-fixtures/compiler-metadata/` | [Static evidence](.agents/skills/static-evidence/SKILL.md) |
| Gradle and Bazel targets, IDE driver | `fixtures/`, `e2e/` | [Spectre and Retina](.agents/skills/spectre-evidence/SKILL.md) |

## Invariants

- Resolve declarations with Kotlin Analysis API. Keep symbols and types inside their analysis session.
- Preserve cancellation. Unsupported or unresolved evidence produces unknown, never optimistic stable results.
- Keep compiler evidence distinct from inference. Stability does not prove skippability or a performance problem.
- Keep blocking work off the EDT. Use read actions for PSI and respect indexing and project disposal.
- Own jobs and listeners with disposable scopes. Never make plugin unload wait for nonmodal EDT cleanup.
- Keep IDE and target dependencies separate. Never package the test driver, Spectre, or Compose runtime in the plugin.
- Use localized strings, theme-aware IDE UI, and editor scheme colours. Preserve keyboard and screen-reader access.
- Keep two-space ktfmt Google style. Do not add formatter overlap, baselines, or blanket suppressions.

## Validate

Run commands from the repository root with Python 3.10+ and JDK 25 available.
Use `gradlew.bat` instead of `./gradlew` on Windows. The Python entry point selects it automatically.
Personal Gradle output wrappers are optional; follow an installed agent workflow when applicable.

```sh
python3 scripts/validate.py fast
./gradlew :test --tests '*StabilityAnalysisTest'
python3 scripts/validate.py full
python3 scripts/validate.py e2e
```

`full` includes binary plugin verification and committed screenshot provenance. `e2e` needs a display and Bazelisk.
Use `--dry-run` to inspect commands without executing them. Never use unqualified `test` in the root build.
Report the exact checks, target versions, failures, and skipped checks. Passing package checks does not prove dynamic unload or live launch.
