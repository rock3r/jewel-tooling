# Static analysis

Format every Kotlin module with ktfmt kotlinLangStyle.
Run Detekt 2.0.0-alpha.6 with the Spectre complexity and naming thresholds from `config/detekt.yml`.
Preserve the Compose naming exception.
Fix findings without adding formatting rules, baselines, blanket suppressions, or weaker thresholds.

## Coverage

| Source | Formatting | Analysis |
| --- | --- | --- |
| Root, agent, recording, adapter, E2E modules, compiler fixture main/test Kotlin | Each project's `ktfmtCheck` | Ordinary `detekt`; typed `detektMain` and `detektTest` in full |
| Standalone main/test Kotlin | Separate build `:ktfmtCheck` | Separate ordinary and typed Detekt |
| Bazel fixture Kotlin | Root `:ktfmtCheckMain` | Root ordinary Detekt and scoped Compose check; no typed Gradle source set |
| Maintained Gradle Kotlin scripts | ktfmt script checks | Root `:detektBuildScripts`, syntax only |
| Compose fixtures and target adapter | Existing formatter tasks | Supplemental `:detektComposeFixtures` |
| Java bootstrap and bridge | Existing two-space style; no Kotlin formatter | Java compilation and agent tests; no Java static analyzer configured |

Maintained sources exclude generated build outputs and exported SDKs.
Ordinary Detekt runs without type resolution.
Typed checks resolve types through each Gradle source set's compile classpath.
Report typed coverage only after running typed checks.
The Bazel fixture has real compilation and end-to-end (E2E) tests. Its supplemental Compose check inspects syntax only.
Gradle DSL compilation checks script types. Ordinary Detekt cannot model Gradle DSL accessors.

## Compose rules

The supplemental task uses an isolated plugin classpath to load `io.nlopez.compose.rules:detekt:0.6.0`.
The [upstream compatibility table](https://mrmans0n.github.io/compose-rules/detekt/) lists this version for Detekt 2.x.
Keep that version pinned. Before upgrading, check task execution and the findings it produces.

Configure naming, parameter order, modifier, mutable parameter, content return, and remember checks in `config/detekt-compose.yml`.
The provider keeps its active defaults, including the missing-modifier check.
Compose checks cover standalone, IJPL, compiler fixtures, and `recording-compose` sources.
They exclude the Swing UI, Analysis API code, and startup agent.
Existing tasks still run default Detekt rules.
The supplemental task runs only Compose rules to avoid duplicate standard rule sets.
Keep ktfmt as the sole formatter. Do not add ktlint or Detekt formatting.

```sh
./gradlew :detektComposeFixtures :detektBuildScripts
./gradlew :detekt :detektMain :detektTest
./gradlew -p fixtures/standalone :detekt :detektMain :detektTest
python3 scripts/validate.py fast
```

## Repair findings

Find the relevant report under `build/reports/detekt/`. Trace the finding to its code path.
Repair the cause within the task's scope. Run the specific task again before an aggregate profile.
For concurrent work, tell its owner the affected file, rule, and trigger.
Leave unrelated source intact and keep the finding visible.

Treat tool crashes and unresolved classpaths as failed checks.
Retain diagnostics and separate analyzer limits from code findings.
Follow the [Detekt repair skill](../.agents/skills/detekt-repair/SKILL.md) for targeted repairs.
