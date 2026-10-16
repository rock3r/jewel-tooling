# Architecture

This page maps the implemented modules and their boundaries.
For supported behaviour and limits, use the [user guide](../user-guide/README.md).
Proposals in `.plans/` describe no additional supported behaviour.

| Module | Responsibility | Boundary |
| --- | --- | --- |
| Root `src/` | K2 analysis, metadata reading, editor presentation, recording UI, live launch | Runs inside the authoring IDE |
| `mcp-runtime/` | Authenticated SDK transport and stdio bridge | Private JDK-parented classloader; no IDE or Compose dependency |
| `mcp-bootstrap/` | Private project discovery and stable launcher | JDK-only API and executable JAR |
| `recording/` | Bounded recording model, codec, files, authenticated loopback transport | No Compose or IDE dependency |
| `recording-compose/` | Optional owned tracer and manual live host | Target-side adapter; absent from the production IDE ZIP |
| `agent/` | Startup instrumentation and runtime discovery | Private target classloader; no bundled Compose or IDE runtime |
| `agent-premain/` | Java 8 startup guard and isolated loader | Loads the JVM 21 agent only on supported targets |
| `agent-bridge/` | Bootstrap callback bridge | Java 8 bytecode and JDK-only dependencies |
| `test-fixtures/compiler-metadata/` | Real compiler-produced binary evidence | Never substitute hand-authored metadata for compiler matrix tests |
| `e2e/driver-plugin/` | Disposable IDE test controls and screenshots | Separate test plugin, excluded from production |
| `e2e/runner/` | IDE Starter and Spectre assertions | Display-dependent JDK 25 tests |
| `fixtures/standalone/` | Released Jewel and Compose application | Separate Gradle build |
| `fixtures/ijpl/` | Bazel-built plugin and exported IDE model | Actual compile classpath, not a simulated Gradle import |

## Static evidence

Resolve annotations and types through the Kotlin Analysis API.
Short names and build-system detection cannot establish type identity.
Keep API symbols, types, and other session-owned objects within their analysis session.
Return immutable data. Let cancellation propagate.
Invalidate results after source or project state changes.

Trust declared `@Stable` and `@Immutable` contracts without claiming to verify their implementation.
Label source inference, built-in rules, and supported compiler metadata separately.
Inspect class files without loading dependency classes.
Return unknown for unsupported metadata, unsupported initializer shapes, or exhausted budgets.
Leave mixed evidence borderless even when a nested property has compiler metadata.

Strong skipping can skip functions with unstable parameters.
Stability results therefore cannot establish skippability.
Unstable hints and execution counts cannot establish performance defects.

## Runtime and lifecycle

The IDE places the Compose Inspection executor beside Run and Debug.
The executor installs bundled support, then launches a temporary copy of a supported local configuration.
The startup agent observes Compose callbacks in the target JVM.
A background writer pairs completed events and stores them in a local file.
Live snapshots drain the writer without losing in-flight pairs.
They send per-site totals over authenticated loopback. Stopping capture reads the full event list.

The target application classloader receives neither Compose nor the IDE's Kotlin runtime from the plugin.
Limit the bridge to JDK dependencies. Keep agent libraries private.
After dependency changes, inspect the packaged contents.

Capture receives callbacks on the composition thread. It rejects unsupported runtimes and multiple runtime copies.
Callbacks cannot identify skipped calls, invalidation causes, argument values, or composition instances.
The live view uses recorded compiler file names to annotate matching Kotlin sources, including dependencies.
File names in the details open sources that resolve within the project or its dependencies.
The [live inspection guide](../user-guide/live-inspection.md) describes supported launches and capture semantics.

On disconnect, project disposal, or plugin unload, cancel owned work and release sockets and listeners.
Modal unload waits for cancellation. Cleanup must therefore avoid waiting for nonmodal event dispatch thread (EDT) work.
Allow cancellation of UI completion. Bound resource cleanup and run blocking input and output work outside the EDT.

## Compatibility and fixtures

Consult the [compiler matrix](testing.md#compatibility) for accepted metadata and tested compiler configurations.
The [testing guide](testing.md#compatibility) also lists IDE, build, bytecode, and target runtime versions separately.
No upper IDE build limit blocks installation. Future IDE compatibility still requires testing.

Standalone tests run a real Gradle application. IJPL tests compile with Bazel and export the dependency model.
Coverage excludes other third-party Bazel importers and Kotlin Multiplatform source-set models.
Exclude SDK exports and fixture code from the production distribution.
