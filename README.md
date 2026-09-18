# Jewel Tooling

An IntelliJ Platform plugin for **Compose stability hints and live inspection**.
Works with Jewel and other Compose Kotlin code in imported **Gradle and Bazel** projects.
Static hints need no application dependency, agent, or server. Live inspection launches your selected development configuration with a bundled agent.

## Try it

Requires JDK 21 and IntelliJ IDEA 2026.2.0.1 with the bundled Kotlin plugin in K2 mode.

```sh
./gradlew :test :buildPlugin
./gradlew :runIde
```

To use an existing compatible IDE distribution instead of downloading one:

```sh
./gradlew -PlocalIdePath=/path/to/IntelliJIDEA.app/Contents :buildPlugin
```

Install `build/distributions/jewel-tooling-0.4.0.zip` through **Settings > Plugins > gear > Install Plugin from Disk**.
Open a project, allow indexing to finish, then open a Kotlin file with `@Composable` functions.
Hints appear after parameter types; hover for a short explanation. Click a function’s gutter indicator
for a summary and a scrollable explanation of every input, or use **Inspect Compose Stability** in Find Action.
Toggle inline hints under
**Settings > Editor > Inlay Hints**, under Kotlin; look for **Compose parameter stability**.
Small circles show each state. A border identifies compiler-confirmed type stability.
Change their fill and border colours under **Settings > Editor > Color Scheme > Jewel Tooling**.
The Gradle wrapper here builds the IDE plugin; it imposes no build system on the project being analyzed.

Read the [user guide](docs/user-guide.md) for explanations, limits, screenshots, and test commands.
To use the same static analysis from a coding agent, open **Tools > Compose Analysis MCP Server…**.
Enable access, select your client, and click **Install**. See the [MCP setup guide](docs/agents/mcp.md) for supported clients and instructions.

![Compose parameter stability hints](docs/images/standalone-editor.png)

## Project setup

- **Gradle / Jewel Standalone:** import the application's Gradle project normally. Compose dependencies must resolve in the IDE.
- **Bazel / IntelliJ sources:** import using the project's supported IDE model (including the monorepo's existing project files).
  Bazel files alone are not an IDE Kotlin module model. Once source roots and classpaths resolve, the same analyzer runs.
- KMP source-set models are not tested in v1. Unannotated types crossing source-set IDE module boundaries remain unknown.
- No root-directory scanning or build-output parsing. Class and annotation identities come from Kotlin's Analysis API.
- The platform and Kotlin APIs are version-sensitive. There is no upper IDE build limit. Only build 262.8665.337 has been
  validated; newer IDE and Android Studio builds may need API compatibility fixes. There is no dependency on Android-specific IDE APIs.

## What the hints mean

- **stable:** a built-in stable type, function, enum, declared `@Stable`/`@Immutable` contract, or a final source class
  whose stored properties recursively have stable types. A supported compiled class can also use compiler metadata and its selected type arguments. Explicit contracts are trusted, not verified.
- **unstable:** standard collections/arrays, varargs, or a stored mutable/unstable property.
- **unknown:** unresolved types, unspecialized type parameters, recursive/delegated/inherited cases, or external types without supported metadata.

These are **conservative static estimates**, not compiler reports. Stability alone does not determine skipping.
Strong skipping can skip unstable parameters when the relevant object identities are unchanged.
The compiler metadata reader supports a tested subset of Kotlin metadata `[2, 3, 0]` and `[2, 4, 0]` in Java 8–25 class files.
The compiler tests cover Kotlin/Compose 2.3.20 and 2.4.20-RC3 targeting Java 25, plus 2.4.0 targeting Java 21.
Other versions and computed initializers stay unknown. The details view shows the evidence behind each result.
The plugin does not read stability configuration files, infer inherited/custom stability contracts, or modify source. Do not add `@Stable` just to turn a hint green.

## Development

Start with [Contributing](CONTRIBUTING.md) for setup, validation commands, and repository-local agent workflows.

`src/test` uses real IDE Kotlin resolution with small Compose annotation fixtures. It covers aliases, false annotation matches,
mutable and immutable shapes, generic substitution, delegates, bounded inference, and parameter hint placement.
A separate fixture module compiles real dependency classes with the pinned Compose compiler; tests also reject malformed or unsupported metadata.
The implementation keeps Kotlin Analysis API objects inside their analysis session, checks cancellation, and runs through
IntelliJ's presentation-based inlay pass. Analysis runs outside the event dispatch thread.

## Inspect a recording

Choose **Tools > Open Compose Recording** to inspect a saved session.
The report shows observed executions, inclusive duration, threads, and recording limits.
It does not infer skips, invalidation causes, argument values, or composition instances.

Select your application's run configuration, then choose **Run with Compose Inspection** from the run widget, gutter, or **Run** menu.
The plugin installs support when needed, launches the target, connects, and starts capture when Compose loads.
You do not need to add a recorder dependency or copy a connection token.

Use a local Gradle `JavaExec`, Application, or Kotlin JVM configuration with JDK 21 or newer.
For a Bazel-built IntelliJ plugin, select the project's existing Application configuration for its development IDE.
See the [own-project instructions](docs/user-guide.md#run-your-project-with-live-inspection) for setup and limits.

## Inspiration and license

Inspired by [Skydoves Compose Stability Analyzer](https://github.com/skydoves/compose-stability-analyzer).
Jewel Tooling is an original implementation; no CSA source code was copied.

Licensed under [Apache 2.0](LICENSE).
