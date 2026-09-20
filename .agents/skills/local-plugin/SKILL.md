---
name: local-plugin
description: Prepare a verified local Jewel Tooling ZIP and check compatibility or dynamic lifecycle changes without publishing.
---

# Validate a local plugin

Use for a local ZIP handoff, dependency changes, IDE compatibility, or dynamic plugin lifecycle work.
Require JDK 25, Python 3.10+, and dependency access. Run from the repository root.
Read [testing](../../../docs/testing.md) and inspect the existing diff before touching packaging.

```sh
python3 scripts/validate.py full
```

This includes unit and IDE tests, ordinary and typed static checks, package mutation tests, plugin verification, and screenshot provenance.
Inspect `build/reports/` and the exact failure before retrying. Do not claim a complete verification when a stage stops the profile.
Do not relax package allowlists for unexpected classes or dependencies.
If old distributions block preparation, inspect and move only obsolete generated ZIPs; preserve the current artifact.

For resource ownership, listeners, coroutines, or unload changes, also run:

```sh
python3 scripts/validate.py e2e
```

Check the maintained scenarios' disconnect, unload during capture, reload, and export assertions.
Descriptor checks alone do not demonstrate dynamic unload. Inspect `idea.log` for unload failures in the disposable IDE.
Do not make cleanup wait for nonmodal EDT work while unload waits for cancellation.

For a new IDE baseline, compile and test that exact IDE and bundled Kotlin plugin before changing compatibility claims.
The absence of an upper build limit is not test evidence.

Hand off `build/distributions/jewel-tooling-<pluginVersion>.zip` and its `.sha256` file only after verifying their current version.
State the checks run and limits. A ZIP is unsigned unless Marketplace credentials are in the environment; this workflow never publishes, pushes, tags, or creates a release.
Public publication waits for explicit approval and the user's plugin test.
