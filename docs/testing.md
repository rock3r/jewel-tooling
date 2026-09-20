# Testing

Run commands from the repository root. Use JDK 25 and Python 3.10+.
The pinned wrapper provisions compilation toolchains. Windows uses `gradlew.bat`.

## Entry points

| Command | Work | Prerequisites |
| --- | --- | --- |
| `python3 scripts/validate.py fast` | Formatting, ordinary Detekt, Compose rules, build scripts, Python contracts and links | Dependency access; no display |
| `python3 scripts/validate.py package` | Typed Detekt, plugin/library tests, ZIP, plugin verifier, package and image gates | IDE downloads and build toolchains; no display |
| `python3 scripts/validate.py full` | Fast checks plus the package profile | IDE downloads and build toolchains; no display |
| `python3 scripts/validate.py e2e` | SDK export, real Bazel model, standalone interaction, six maintained IDE scenarios | Bazelisk and a graphical session |

The fast profile runs static checks, not JVM tests. The full profile can take substantially longer because it runs Plugin Verifier.

Add `--dry-run` to print the exact command plan as JSON without running tools.
Each profile stops at its first failure. Inspect reports before running later stages separately for diagnosis.
CI calls these same entry points. On Linux, wrap `e2e` in `xvfb-run` as CI does.
Do not use unqualified `test` from the root: it also selects display-dependent subproject tests.

## Targeted checks

```sh
./gradlew :test --tests '*StabilityAnalysisTest'
./gradlew :recording:test :recording-compose:test :agent:test
./gradlew :detekt :detektMain :detektTest
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/check-doc-links.py
```

Root tests compile and load real compiler fixtures. `agent:test` exercises forked JVMs and the IDE classloader boundary.
Add a regression case when changing a verdict, explanation, or editor behavior.
Keep negative metadata and cancellation tests when changing analysis.
See [static analysis](static-analysis.md) for source coverage and typed-check limitations.

## Compatibility

| Component | Pinned or verified boundary |
| --- | --- |
| IDE baseline | IDEA 2026.2.0.1, build 262.8665.337, bundled K2, JBR 25 |
| Plugin and agent bytecode | JVM 21 |
| Premain and bridge bytecode | Java 8 guard and bridge; this does not make the agent work on Java 8 |
| IDE Starter and standalone test workers | JDK 25 |
| Binary metadata reader | Java 8–25 nonpreview classes; Kotlin metadata `[2, 3, 0]` and `[2, 4, 0]` |
| Compiler matrix | 2.3.20/JVM 25, 2.4.20-RC3/JVM 25, and 2.4.0/JVM 21 |

The tests compile real dependencies with these configurations:

| Kotlin and Compose compiler | JVM target | Purpose |
| --- | --- | --- |
| 2.3.20 | 25 | Jewel Standalone compiler configuration |
| 2.4.20-RC3 | 25 | IntelliJ Platform compiler configuration |
| 2.4.0 | 21 | Previous supported configuration |

These versions describe the compiled dependencies, not the IDE runtime. Running on JBR 25 does not require every dependency to target Java 25. The plugin keeps its Java 21 bytecode target. Source analysis does not depend on the binary metadata reader. A metadata version does not identify the compiler patch version. Computed stability initializers and other unsupported shapes stay unknown.

The [editor guide](../user-guide/editor.md#compiler-evidence) explains what those class results mean for authors.
Compile and test a new baseline before changing compatibility claims.
`:verifyPlugin` checks the current configured IDE. It does not prove dynamic unload, every newer IDE, or Android Studio support.

## Spectre and lifecycle

The maintained suite is `JewelTargetsTest`. Its six cases cover standalone and IJPL editor/live workflows, one-click launches, and static MCP controls.
The older `EditorSpikeTest` is exploratory and is not part of this maintained suite.

```sh
python3 scripts/validate.py e2e
```

Inspect JUnit XML, screenshots, and scenario assertions under `e2e/runner/build/` and `fixtures/standalone/build/`.
Require the expected cases to run without skips. Check live events, stop/export, disconnect, reload, and unchanged saved configurations.
The suite tests unload during capture. Static descriptor verification alone cannot replace that test.
Bazel evidence is in `fixtures/ijpl/build/ide-project/model-evidence.json`.
The test validates an exported model, not an importer wizard.

For a cached IDE, pass `--ide-path /path/to/IntelliJ-IDEA.app` to the E2E entry point.
Use an installed IDE home accepted by IDE Starter, not a flattened Gradle transform directory.

## Retina evidence

Use an unlocked graphical session with screen-capture permission and a real AWT scale of 2.0.

```sh
./scripts/capture-retina.sh
python3 scripts/verify-artifacts.py --images
```

The script creates one capture ID, snapshots source inputs, runs the fixtures, and promotes only matching successful captures.
Keep all thirteen images and `user-guide/images/manifest.json` together. Never upscale a 1x image or edit the manifest to bless stale captures.
A changed input requires a fresh capture. Include the new images and manifest with the input change. Linux Xvfb evidence does not replace Retina assets.
See the [capture skill](../.agents/skills/spectre-evidence/SKILL.md) for failure handling.

## Local distribution

`full` builds the ZIP, runs structure and binary compatibility verification, and checks package contents and screenshot provenance.
The package mutation tests reject test-only classes, unwanted dependencies, and malformed nested artifacts.
`prepare-distribution.py` requires one current-version ZIP and writes its SHA-256 file.
If old ZIPs exist, inspect them and move only obsolete generated distributions out of that directory.
Do not change allowlists merely to accept unexpected contents.

A local ZIP is unsigned unless Marketplace signing environment variables are set. It is not a release. Do not push tags or run publication commands without user approval.

## Static MCP

The runtime tests use the official SDK client for HTTP and stdio connections.
The bootstrap tests check private discovery and project identity.
`McpAnalysisTest` compares the IDE facade with the existing engine and checks unsaved documents, indexing, cancellation, ranges, and source limits.

```sh
./gradlew :mcp-runtime:test :mcp-bootstrap:test
./gradlew :test --tests '*McpAnalysisTest'
./gradlew :e2e:runner:test --tests '*JewelTargetsTest.standaloneGradleMcp' --tests '*JewelTargetsTest.bazelIjplMcp'
```

Prepare the Gradle and Bazel fixtures through the E2E profile before a filtered IDE run.
The two MCP scenarios exercise production controls, imported models, unsaved results, re-enable, plugin unload, and project close.
Their artifacts include `mcp-setup.png` and `mcp-evidence.txt`.
These tests supplement the existing editor and live inspection scenarios.
