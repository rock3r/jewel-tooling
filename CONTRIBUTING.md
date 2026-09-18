# Contributing

Use JDK 25, Python 3.10+, and the checked-in Gradle wrapper.
Gradle provisions the pinned compilation toolchains. The first build needs network access for dependencies and the IDE distribution.
Bazelisk and a graphical session are required only for the integration workflows.
Keep dependency versions pinned and use public repositories. Local Maven repositories are not supported.

Start with [AGENTS.md](AGENTS.md). Use the [architecture](docs/architecture.md),
[conventions](docs/conventions.md), [testing](docs/testing.md), and [static analysis](docs/static-analysis.md) guides for implementation work.
The [user guide](docs/user-guide.md) describes supported behavior.

```sh
python3 scripts/validate.py fast
python3 scripts/validate.py full
```

Inspect `git status --short` before and after work. Keep unrelated edits intact.
Report checks that fail before your change separately from regressions.
Do not change existing implementation files owned by concurrent tasks without coordination.
Use `.plans/` for local proposals. Do not link public documents to that ignored directory.

## Agent discovery

`AGENTS.md` is the canonical instruction file. `CLAUDE.md` links to it.
Each directory under `.claude/skills/` links to the matching directory under `.agents/skills/`.
Edit only the canonical files. No hook or global permission configuration is required.

On Windows, enable Developer Mode or use an account that can create symbolic links.
Clone with `git -c core.symlinks=true clone <repository-url>`.
For an existing checkout, enable `core.symlinks` locally and restore only the tracked symlinks after preserving local edits.
A plain file containing a link target is not a working symlink. Run `python3 scripts/check-doc-links.py` to verify discovery.
Use `python` if your Python 3 installation does not provide `python3`.
The validation runner uses `gradlew.bat` on Windows. Headed Windows E2E and capture are not yet validated.

## Review and publication

Keep changes small enough to review. Include the behavior change, validation evidence, and known limits.
Do not include private project names, machine paths, credentials, or connection tokens in public artifacts.
Contributions use the repository's [Apache 2.0 license](LICENSE).

This checkout is under local review. Do not push, publish, create a repository or PR, or release without user approval.
Public publication also waits for the user to test the plugin.
The existing release workflow reacts to tags; do not create or push a release tag during local validation.
