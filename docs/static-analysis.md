# Static analysis

ktfmt owns formatting. Every Kotlin module uses kotlinLangStyle.
Detekt uses the pinned 2.0.0-alpha.6 version with the Spectre complexity and naming thresholds in `config/detekt.yml`.
Keep the existing Compose naming exception. Do not add a formatting rule set, baseline, blanket suppression, or weaker threshold to obtain a green build.

## Coverage

| Source | Formatting | Analysis |
| --- | --- | --- |
| Root, agent, recording, adapter, E2E modules, compiler fixture main/test Kotlin | Each project's `ktfmtCheck` | Ordinary `detekt`; typed `detektMain` and `detektTest` in full |
| Standalone main/test Kotlin | Separate build `:ktfmtCheck` | Separate ordinary and typed Detekt |
| Bazel fixture Kotlin | Root `:ktfmtCheckMain` | Root ordinary Detekt and scoped Compose check; no typed Gradle source set |
| Maintained Gradle Kotlin scripts | ktfmt script checks | Root `:detektBuildScripts`, syntax only |
| Compose fixtures and target adapter | Existing formatter tasks | Supplemental `:detektComposeFixtures` |
| Java bootstrap and bridge | Existing two-space style; no Kotlin formatter | Java compilation and agent tests; no Java static analyzer configured |

Generated build outputs and exported SDKs are not maintained sources.
Ordinary Detekt cannot run rules that need type resolution.
Typed checks use each Gradle source set's compile classpath. Do not claim typed coverage from an ordinary `detekt` result.
The Bazel fixture has real compilation and E2E coverage, but its supplemental Compose check is syntax-based.
Gradle DSL compilation checks script types; ordinary Detekt does not model Gradle DSL accessors.

## Compose rules

The supplemental task loads `io.nlopez.compose.rules:detekt:0.6.0` through an isolated plugin classpath.
The [upstream compatibility table](https://mrmans0n.github.io/compose-rules/detekt/) places this version on Detekt 2.x.
Keep this Compose rules pin. Validate both task execution and findings before adopting a newer Compose rules version.

`config/detekt-compose.yml` selects naming, parameter order, modifier, mutable parameter, content return, and remember checks.
The provider also retains its active defaults, including the missing-modifier check.
These checks target standalone, IJPL, compiler fixtures, and `recording-compose` sources.
They do not run on the Swing UI, Analysis API implementation, or startup agent.
Default Detekt rules remain active in their existing tasks. The supplemental task runs Compose rules without duplicating the standard Detekt rule sets.
Do not add ktlint or Detekt formatting alongside ktfmt.

```sh
./gradlew :detektComposeFixtures :detektBuildScripts
./gradlew :detekt :detektMain :detektTest
./gradlew -p fixtures/standalone :detekt :detektMain :detektTest
python3 scripts/validate.py fast
```

## Repair findings

Read the owning report under `build/reports/detekt/` and identify the actual code path.
Fix the underlying design within the requested scope. Rerun the owning task before an aggregate profile.
If a finding belongs to concurrent implementation work, report its file, rule, and trigger to that owner.
Do not hide it or rewrite unrelated source to unblock your task.

A tool crash or unresolved classpath is a failed check, not a clean report.
Preserve the diagnostic and distinguish analyzer limitations from code findings.
Use the [Detekt repair skill](../.agents/skills/detekt-repair/SKILL.md) for a focused workflow.
