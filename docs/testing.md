# Testing

Use JDK 25 and Python 3.10+ to run checks from the repository root.
The pinned Gradle wrapper provisions compilation toolchains.
On Windows, use `gradlew.bat`.

## Entry points

| Command | Work | Prerequisites |
| --- | --- | --- |
| `python3 scripts/validate.py fast` | Formatting, ordinary Detekt, Compose rules, build scripts, Python contracts and links | Dependency access; no display |
| `python3 scripts/validate.py package` | Typed Detekt, plugin/library tests, ZIP, plugin verifier, package and image gates | IDE downloads and build toolchains; no display |
| `python3 scripts/validate.py full` | Fast checks plus the package profile | IDE downloads and build toolchains; no display |
| `python3 scripts/validate.py e2e` | SDK export, real Bazel model, standalone interaction, six maintained IDE scenarios | Bazelisk and a graphical session |

The fast profile covers static checks only and excludes JVM tests.
Plugin Verifier can make the full profile substantially slower.

To inspect a profile's JSON command plan without execution, add `--dry-run`.
Profiles stop at the first failure. Read reports before diagnosing later stages with separate runs.
Continuous integration (CI) uses these entry points too.
On Linux, wrap `e2e` in `xvfb-run`, as CI does.
Set `SKIKO_RENDER_API=SOFTWARE_COMPAT` and `-Dskiko.renderApi=SOFTWARE_COMPAT` to let Compose paint without a GPU.
Avoid unqualified `test` at the root. That command also selects subproject tests that require a display.

## Targeted checks

```sh
./gradlew :test --tests '*StabilityAnalysisTest'
./gradlew :recording:test :recording-compose:test :agent:test
./gradlew :detekt :detektMain :detektTest
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/check-doc-links.py
```

The root tests compile and load fixtures produced by real compilers.
`agent:test` covers forked JVMs and the IDE classloader boundary.
Add regression tests for changes to verdicts, explanations, or editor behaviour.
Preserve negative metadata cases and cancellation tests during analysis changes.
Read [static analysis](static-analysis.md) for source coverage and limits of typed checks.

## Compatibility

| Component | Pinned or verified boundary |
| --- | --- |
| IDE baseline | IDEA 2026.2.0.1, build 262.8665.337, bundled K2, JBR 25 |
| Plugin and agent bytecode | JVM 21 |
| Premain and bridge bytecode | Java 8 guard and bridge; this does not make the agent work on Java 8 |
| IDE Starter and standalone test workers | JDK 25 |
| Binary metadata reader | Java 8–25 nonpreview classes; Kotlin metadata `[2, 3, 0]` and `[2, 4, 0]` |
| Compiler matrix | 2.3.20/JVM 25, 2.4.20-RC3/JVM 25, and 2.4.0/JVM 21 |

Tests compile real dependency classes using these configurations:

| Kotlin and Compose compiler | JVM target | Purpose |
| --- | --- | --- |
| 2.3.20 | 25 | Jewel Standalone compiler configuration |
| 2.4.20-RC3 | 25 | IntelliJ Platform compiler configuration |
| 2.4.0 | 21 | Previous supported configuration |

The compiler matrix describes dependency bytecode. The IDE runtime has a separate version.
JBR 25 can run dependencies targeting older Java versions. The plugin targets Java 21 bytecode.
Source analysis works independently of the binary metadata reader.
Metadata versions cannot identify compiler patch versions.
Computed stability initializers and unsupported shapes produce unknown results.

The [editor guide](../user-guide/editor.md#compiler-evidence) explains how authors can use class results.
Before claiming a new baseline works, compile and test it.
`:verifyPlugin` checks only the configured IDE.
Dynamic unload, newer IDEs, and Android Studio need separate checks.

## Spectre and lifecycle

Run `JewelTargetsTest` for the maintained suite.
Its six cases cover standalone and IJPL editor and live workflows, one-click launches, and static MCP controls.
The exploratory `EditorSpikeTest` remains outside this suite.

```sh
python3 scripts/validate.py e2e
```

Find JUnit XML, screenshots, and scenario assertions under `e2e/runner/build/` and `fixtures/standalone/build/`.
Confirm that all expected cases ran without skips.
Check live events, stopping, exporting, disconnecting, reloading, and preservation of saved configurations.
The suite exercises unload during capture. Static descriptor checks cannot establish that lifecycle behaviour.
Read Bazel evidence in `fixtures/ijpl/build/ide-project/model-evidence.json`.
The test checks the exported model without exercising an importer wizard.

To reuse a cached IDE, give the E2E entry point `--ide-path /path/to/IntelliJ-IDEA.app`.
Select an installed IDE home accepted by IDE Starter.
Flattened Gradle transform directories are unsuitable.

## Retina evidence

Capture in an unlocked graphical session with screen-capture permission.
The actual AWT scale must be 2.0.

```sh
./scripts/capture-retina.sh
python3 scripts/verify-artifacts.py --images
```

The script assigns one capture ID and snapshots source inputs.
It runs the fixtures and promotes captures only when they succeed and match those inputs.
Keep the thirteen images with `user-guide/images/manifest.json`.
Never enlarge 1x images or edit the manifest to accept stale captures.

Promotion regenerates three 1280×800 JetBrains Marketplace crops in `user-guide/images/marketplace/`.
Keep their original resolution too. Upload them through Plugin Screenshots. Exclude them from the listing HTML.
Find captions in `user-guide/images/marketplace/captions.txt`.

After capture inputs change, capture again.
Commit the new images, manifest, and Marketplace crops with the input change.
Linux Xvfb evidence cannot replace Retina assets.
For failures, follow the [capture skill](../.agents/skills/spectre-evidence/SKILL.md).

## Local distribution

The `full` profile builds the ZIP and checks structure, binary compatibility, package contents, and screenshot provenance.
Package mutation tests reject test-only classes, unwanted dependencies, and malformed nested artifacts.
`prepare-distribution.py` expects exactly one current-version ZIP and writes its SHA-256 file.
Inspect old ZIPs before moving obsolete generated distributions out of the directory.
Investigate unexpected contents without weakening allowlists.

Local ZIPs remain unsigned unless Marketplace signing environment variables are set.
Building a ZIP does not publish a release.
Get user approval before pushing tags or running publication commands.

## Static MCP

Runtime tests connect over HTTP and stdio with the official SDK client.
Bootstrap tests verify private discovery and project identity.
`McpAnalysisTest` compares the IDE facade's results with the engine.
It also checks unsaved documents, indexing, cancellation, ranges, and source limits.

```sh
./gradlew :mcp-runtime:test :mcp-bootstrap:test
./gradlew :test --tests '*McpAnalysisTest'
./gradlew :e2e:runner:test --tests '*JewelTargetsTest.standaloneGradleMcp' --tests '*JewelTargetsTest.bazelIjplMcp'
```

Before filtering an IDE run, prepare Gradle and Bazel fixtures through the E2E profile.
The two MCP scenarios test production controls, imported models, unsaved results, re-enabling access, plugin unload, and project close.
They produce `mcp-setup.png` and `mcp-evidence.txt` among their artifacts.
Run these tests alongside the editor and live inspection scenarios.
