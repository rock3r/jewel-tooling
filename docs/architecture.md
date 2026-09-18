# Architecture

The [user guide](user-guide.md) is authoritative for supported behavior and limits.
This map describes implemented code. Local proposals in `.plans/` do not extend this contract.

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

Use resolved annotations and types, not short-name matching or build-system detection.
Keep Analysis API symbols, types, and session-owned objects inside the analysis session.
Return immutable result data. Preserve cancellation and invalidate results when source or project state changes.

Declared `@Stable` and `@Immutable` annotations are contracts, not proofs that their promises hold.
Source inference and built-in knowledge remain distinct from supported compiler metadata.
Read class files without loading dependency classes. Unsupported metadata, initializer shapes, or budgets produce unknown results.
Mixed evidence must not acquire a compiler-confirmed border merely because one nested property has metadata.

Stability alone does not prove skippability. Strong skipping can skip functions with unstable parameters.
Neither an unstable hint nor an execution count proves a performance defect.

## Runtime and lifecycle

The IDE registers a Compose Inspection executor beside Run and Debug. That executor installs bundled support and launches a temporary copy of a supported local run configuration.
The startup agent observes Compose callbacks in the target JVM and sends bounded data over authenticated loopback transport.
The IDE does not inject Compose or its own Kotlin runtime into the target's application classloader.
Keep the bridge JDK-only. Keep agent libraries private. Check packaged contents after dependency changes.

Current capture observes EDT callbacks and rejects unsupported or multiple runtime copies.
It does not identify skipped calls, invalidation causes, argument values, or composition instances.
The live view can annotate matching Kotlin files in the editor from compiler file names in the recording. Details file names open when that file resolves in the project or its dependencies.
See the [live inspection guide](user-guide.md#run-your-project-with-live-inspection) for launch types and capture semantics.

Cancel owned work, close sockets, and release listeners on disconnect, project disposal, and plugin unload.
Cleanup must not wait for nonmodal EDT work while a modal unload waits for cancellation.
Keep UI completion cancellable. Bound necessary resource cleanup and keep blocking I/O off the EDT.

## Compatibility and fixtures

The [compiler matrix](user-guide.md#read-compiler-evidence) lists the accepted metadata and tested compiler configurations.
The [testing guide](testing.md#compatibility) distinguishes IDE, build, bytecode, and target runtime versions.
A missing upper IDE build limit permits installation; it does not prove compatibility with every future IDE.

Standalone tests cover a real Gradle application. IJPL tests build through Bazel and export its dependency model.
They do not validate every third-party Bazel importer or KMP source-set model.
Keep SDK exports and fixture code outside the production distribution.
