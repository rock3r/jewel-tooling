# Jewel Tooling user guide

Jewel Tooling shows a static stability estimate beside each parameter of a Kotlin `@Composable` function. Hover over a hint to see its reason, or click the function’s gutter indicator to inspect all its inputs. It helps you inspect Jewel and Compose APIs without running the application or changing its source.

## Install the plugin

Build with JDK 21 using `./gradlew :buildPlugin`. In IntelliJ IDEA, open **Settings → Plugins**, choose the gear menu, then **Install Plugin from Disk**. Select `build/distributions/jewel-tooling-0.3.0.zip` and restart when prompted.

The build targets IntelliJ IDEA 2026.2.0.1 with its bundled Kotlin plugin. This IDE uses K2. There is no upper IDE build limit, so newer IDEs can install the plugin. Only build 262.8665.337 has been validated; newer IDE and Android Studio builds may need API compatibility fixes.

## Open a project

For Jewel Standalone, import the Gradle project and let synchronization and indexing finish. The IDE must resolve Compose annotations and the parameter types.

For Bazel projects, use the project's supported IntelliJ project model. Bazel build files alone do not define the IDE's source roots, classpaths, or Kotlin compiler options. The model should include the Compose compiler plugin used by the build, especially for libraries using Kotlin 2.4 composable function types. Jewel Tooling uses those resolved roots and dependencies; it does not run Bazel or parse build output during editing.

The Bazel E2E boundary is a model exported from a real Bazel build. It does not test a third-party Bazel importer wizard. Kotlin Multiplatform source-set models are untested in v1. Unannotated types in another source module remain unknown. Supported compiled dependencies can use compiler metadata.

![Stable and unstable parameter hints in the imported Jewel Standalone project](images/standalone-editor.png)

The Gradle fixture uses released Jewel and Compose dependencies. The hints appear after the parameter types.

![Parameter hints in the Bazel-built IntelliJ plugin fixture](images/ijpl-editor.png)

The Bazel fixture exports its real compile classpath into an IDE module. Both project models use the same hint provider.

## Read a hint

| Hint | Meaning |
| --- | --- |
| stable | A recognized stable type, a trusted Compose stability contract, an inferred final source class whose stored properties have stable types, or supported compiler metadata with stable selected type arguments. |
| unstable | A standard collection or array, a vararg, a class with a mutable or unstable stored property, or a supported compiler metadata result that proves instability. |
| unknown | The type is unresolved, outside the supported inference rules, or too expensive to analyze within the fixed budget. |

The explanation tells you which rule produced the estimate. `@Stable` and `@Immutable` are contracts: the plugin trusts them and does not prove that their promises are valid. Do not add either annotation only to change a hint.

These hints are not Compose compiler reports. A stability estimate alone does not say whether a function will recompose. With strong skipping, an unstable parameter can still permit skipping when its relevant identity has not changed.

![A compact stability hint showing the inferred reason](images/explanation.png)

Hover over a hint for its status and reason. The longer explanation of stability and skipping is in the function detail view.

## Inspect a function

Click the gutter indicator beside a composable function to open **Compose stability**. The popup shows the function name, status counts, and each input’s source type, explanation, and evidence. Evidence labels distinguish built-in rules, declared contracts, source inference, and compiler metadata. A generic type can combine several kinds of evidence. Green check icons identify stable inputs, warning icons identify unstable inputs, and question icons identify unknown inputs. Text accompanies every status. These are input estimates, not a verdict that the function is skippable.

![Function stability details in the dark IDE theme](images/details-dark.png)

To open the same view from the keyboard, place the caret inside the function, open **Find Action**, and choose **Inspect Compose Stability**. The action is also in the editor context menu. You can select and copy explanation text, scroll through longer parameter lists, and resize the popup. Press **Escape** to return to the editor. Editing the source or switching editors closes the popup so it cannot keep showing an outdated result.

![The same details in the light IDE theme](images/details-light.png)

Counts include value parameters and an extension receiver, when present. Context parameters are not analyzed. Functions with no covered inputs show that explicitly. An unstable input takes precedence in the gutter icon; otherwise an unknown input takes precedence over stable inputs.

## Read compiler evidence

For a resolved binary dependency, the plugin can read the class’s existing Compose stability metadata. It reads the class file without loading or executing it. You do not need to add a plugin or dependency to the project being inspected.

Support is deliberately narrow: the current reader is tested with Kotlin and Compose compiler **2.4.0**, Kotlin metadata `[2, 4, 0]`, and JVM class files up to Java 21. Older compiler metadata, newer class-file versions, computed stability initializers, and other unsupported shapes stay **unknown**. This does not provide general coverage of published Jewel or Compose libraries.

For generic classes, the metadata identifies which type arguments matter. For example, a compiled `Box<T>(val value: T)` combines the compiler’s class result with the analysis of `T`. A star projection stays unknown when that argument is required. An unused type argument does not affect the result. The details view labels compiler and source evidence separately when both contribute.

These class results do not establish whether a function is restartable or skippable. Stability configuration files are not read. A dependency without supported metadata can still be stable through a recognized `@Stable` or `@Immutable` contract.

## Change hint visibility

Open **Settings → Editor → Inlay Hints** and find **Compose parameter stability** under Kotlin. Turn the provider off to hide its hints; turn it on to restore them. The setting does not modify your source or build. Gutter indicators have a separate switch under **Settings → Editor → General → Gutter Icons → Compose stability summary**. The detail action remains available when indicators are hidden.

## Understand the limits

The provider covers value parameters and extension receivers; it does not show context-parameter hints. Explicit `FunctionN` class references can remain unknown even when function syntax such as `() -> Unit` is stable.

The plugin does not interpret external stability configuration files or custom and inherited stability contracts. It leaves unannotated library types such as `Pair` and `Triple` unknown. Singleton objects and value classes, including unsigned types, are also unknown unless a recognized contract applies.

Generic substitution handles concrete stored types. Repeated instantiations of the same class, such as `Box<Box<String>>`, conservatively stop at the recursion guard. Recursive types remain unknown. Inner and local classes also remain unknown because they can capture state outside their declared properties. Computed properties without backing fields and companion state do not count as instance storage; delegated storage is unknown.

Analysis processes the extension receiver, then parameters in declaration order. It stops after 256 type visits for a function or a nesting depth of 12. Earlier completed hints remain; exhausted assessments say unknown and explain the budget limit. Binary reads also stop at 1 MiB per class, 4 MiB per function, or 32 distinct class files. No result is retained across editing passes.

## Troubleshoot missing hints

Wait for indexing and Gradle synchronization to finish. Check that `androidx.compose.runtime.Composable` resolves in the editor and that the function has explicit parameter types. An unrelated annotation named `Composable` does not activate the provider.

Check the inlay setting and the IDE baseline. An unknown result is useful evidence of an unsupported case; it is not a claim that the type is unstable. Include a small public reproduction when reporting a result you believe is wrong.

## Inspect a recording

Choose **Tools → Open Compose Recording**, or find that action with **Find Action**. Select a recording saved by a configured development target.

The report shows the target, session, status, recording window, and any incomplete or rejected events. Click a column heading to sort the sites. Select a site to see its full compiler text and executions by thread. You can select and copy the text. Press **Escape** to close the report.

![A saved Jewel Standalone recording with execution counts and inclusive durations](images/recording-standalone.png)

![A saved IJPL recording from the Bazel fixture](images/recording-ijpl.png)

An execution is a completed pair of Compose trace callbacks. It can be an initial composition or a later call. The callback does not identify which occurred. Total and mean durations include nested calls and capture overhead. They are not frame times.

A site combines the compiler key and its exact text within one session. Matching function names do not prove matching composition instances. Source navigation stays disabled because the recording contains no verified source map.

The report does not show skips, invalidation causes, parameter values, or composition instances. Missing or disabled trace markers can hide activity. An empty recording does not prove that the target did no work.

## Record a development target

The recorder is experimental. The two repository fixtures provide working examples for Gradle and Bazel. The authoring plugin only imports files.

For a quick standalone recording, run these commands from the repository root:

```sh
./gradlew :e2e:driver-plugin:exportFixtureSdk
./gradlew -p fixtures/standalone test
```

Spectre clicks **Add item** in the real Jewel application. The test saves `fixtures/standalone/build/capture/recording.json`. Open that file with **Open Compose Recording**. The graphical test needs the display and capture permissions described below.

For the Bazel/IJPL recording, run the complete end-to-end sequence below. Each IJPL scenario saves `recording.json` inside its artifact directory under `e2e/runner/build/artifacts`.

The bootstrap must own the Compose tracer slot before the target starts its Compose content. Compose provides a setter without a getter. The adapter cannot detect or restore another tracer. Do not install it from an ordinary IDE plugin.

In a controlled standalone application, include the exported `recording.jar` and `recording-compose.jar` from `fixtures/standalone/.local`. Supply Jackson Core 2.19.0. Reuse the application's Compose runtime. The adapter targets the AndroidX runtime 1.11.1 API and requires JVM 21 or newer.

For IJPL, keep the bootstrap in a disposable target that explicitly reserves the tracer slot. The fixture uses the platform's Compose and Jackson libraries. It does not package another runtime. Activity from other compositions in that runtime classloader can also appear.

Install one `OwnedCompositionTracer` with `installOwnedDispatcher()`. Call `startRecording(CaptureTarget(...))` and `stopRecording()` between controlled interactions on the composition thread. Serialize the returned snapshot with `RecordingFiles.writeNew(path, recording)` on a worker thread. The method refuses to overwrite an existing file. The [standalone fixture](../fixtures/standalone/src/main/kotlin/example/FixtureRecording.kt) shows this sequence.

Only one session can be attached at a time. Stop it before starting another, including after truncation. General concurrent restart is unsupported because the callback API has no session token.

## Understand recording limits

A session accepts at most 10,000 starts, 1,024 sites, 64 threads, and a nesting depth of 64. Compiler text is limited to 1 KiB per site. Files are limited to 8 MiB, and the recording window is limited to one hour.

Reaching a limit ends capture and marks the result **Truncated**. The recorder discards unfinished root segments and reports their counts. Later activity is unrecorded; its extent is unknown. Clock failures produce **Failed** recordings. The report preserves completed events from before the failure.

Target, compiler, runtime, and build labels are declarations. They do not prove a build identity. Runtime classloader identity remains unknown. Sessions are not merged.

The importer rejects malformed, incomplete, oversized, and unsupported files. It treats compiler text as plain text, never as paths or commands. Files stay local. No agent, server, or live connection is used.

## Development and screenshots

Unit and IDE fixture tests run with `./gradlew :test :recording:test :recording-compose:test`. That task builds a small dependency with the pinned Compose compiler and loads its JAR into the IDE tests, so metadata tests use real compiler output. The separate IDE Starter runner uses JDK 25. Its test-only plugin inspects real rendered inlays and uses Spectre for device-scale captures. The production ZIP contains the plugin and shared recording library. It excludes Spectre, the Compose adapter, and Compose or Jackson implementations.

Documentation captures require an unlocked graphical session, screen-capture permission, and a real AWT device transform of 2.0. On macOS, Spectre captures the target window through its native helper, excluding other applications. The capture harness checks native PNG dimensions and never upscales a 1x image. Public Linux CI exercises headed scenarios under Xvfb and verifies committed screenshot hashes and source provenance; it does not claim to regenerate Retina assets.

## Run the end-to-end tests

The editor tests install the production ZIP and a separate test driver in disposable IDEA instances. IDE Driver and platform actions inspect rendered inlays, edit a property, hover a hint, toggle the provider, click a gutter indicator, and exercise the detail action and Escape dismissal. They also check that an edit dismisses an open detail view. Spectre captures the editor and drives the actual Compose surfaces through their semantics. The production plugin does not depend on the test driver or Spectre.

Both targets import a real recording and check the report. They unload the plugin with the report open, reload it, and import again.

```sh
./gradlew :e2e:driver-plugin:exportFixtureSdk
python3 scripts/prepare-bazel-fixture.py
./gradlew -p fixtures/standalone test
./gradlew :e2e:runner:test
```

Install Bazelisk before preparing the Bazel fixture; its version is selected by `fixtures/ijpl/.bazelversion`. The fixture uses a small Kotlin Bazel action and exports `JavaInfo`. The SDK export task also prepares the compiler-produced test dependency used by both editor targets. It does not depend on an IntelliJ source checkout.

![The real Jewel Standalone application after Spectre clicks Add item](images/standalone-ui.png)

Spectre verifies that clicking **Add item** changes the count from one to two.

![The real Jewel tool window after the same Spectre interaction](images/ijpl-ui.png)

The IntelliJ fixture runs against the IDE's Compose and Jewel runtime.

To regenerate the nine guide images on a Retina display, run:

```sh
./scripts/capture-retina.sh
```

The script builds the fixtures, runs both editor scenarios and the standalone interaction, and promotes only successful captures from that run. It records source hashes and native image dimensions in `docs/images/manifest.json`. Any change to a capture input requires a new capture. Allow up to an hour for the first run with empty dependency caches.

GitHub Actions runs the tests under a Linux virtual display and checks the committed Retina assets. Its screenshots are diagnostic artifacts, not replacements for the guide images. Release tags run validation before publishing the unsigned plugin ZIP and its SHA-256 digest; Marketplace publication and signing are not configured.
