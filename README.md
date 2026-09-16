# Jewel Tooling

A small IntelliJ Platform plugin for **static Compose parameter stability hints**.
Works with Jewel and other Compose Kotlin code in imported **Gradle and Bazel** projects.
No CSA installation, compiler plugin, application dependency, tracing agent, or server is needed.

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

Install `build/distributions/jewel-tooling-0.1.2.zip` through **Settings > Plugins > gear > Install Plugin from Disk**.
Open a project, allow indexing to finish, then open a Kotlin file with `@Composable` functions.
Hints appear after parameter types; hover for a short explanation. Click a function’s gutter indicator
for a summary and a scrollable explanation of every input, or use **Inspect Compose Stability** in Find Action.
Toggle inline hints under
**Settings > Editor > Inlay Hints**, under Kotlin; look for **Compose parameter stability**.
The Gradle wrapper here builds the IDE plugin; it imposes no build system on the project being analyzed.

Read the [user guide](docs/user-guide.md) for explanations, limits, screenshots, and test commands.

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
  whose stored properties recursively have stable types. Explicit contracts are trusted, not verified.
- **unstable:** standard collections/arrays, varargs, or a stored mutable/unstable property.
- **unknown:** unresolved types, unspecialized type parameters, recursive/delegated/inherited cases, or unannotated external types.

These are **conservative static estimates**, not compiler reports. Stability alone does not determine skipping.
Strong skipping can skip unstable parameters when the relevant object identities are unchanged.
V1 does not read stability configuration files, decode `$stable` / `StabilityInferred`, infer inherited/custom stability contracts,
measure recomposition, or modify source. Do not add `@Stable` just to turn a hint green.

## Development

`src/test` uses real IDE Kotlin resolution with small Compose annotation fixtures. It covers aliases, false annotation matches,
mutable and immutable shapes, generic substitution, delegates, bounded inference, and parameter hint placement.
The implementation keeps Kotlin Analysis API objects inside their analysis session, checks cancellation, and runs through
IntelliJ's declarative inlay pass rather than doing analysis on the event dispatch thread.

## Later: runtime analysis

Runtime capture is independent of this plugin's static hints. Start with recorded sessions and file import; a live stream
is optional for an updating heatmap, not a correctness requirement. A target-side JVM agent could install Compose observers,
while an application bootstrap could do the same without dynamic attach. A web server is a transport choice, not the collector.
No runtime agents, ports, telemetry, or instrumentation are included in v1.

## Inspiration and license

Inspired by [Skydoves Compose Stability Analyzer](https://github.com/skydoves/compose-stability-analyzer).
Jewel Tooling is an original implementation; no CSA source code was copied.

Licensed under [Apache 2.0](LICENSE).
