---
name: detekt-repair
description: Repair scoped Detekt findings in Jewel Tooling without hiding design problems or changing formatter policy.
---

# Repair Detekt findings

Use for ordinary, typed, or Compose Detekt failures. Require JDK 25, Python 3.10+, and the checked-in wrapper.
Run from the repository root. Read [static analysis](../../../docs/static-analysis.md) for source coverage.

1. Read the failing task's report under its module's `build/reports/detekt/` directory.
2. Identify whether the finding is a design problem, a classpath failure, or an analyzer incompatibility.
3. Check ownership before editing. Report findings in concurrent work instead of changing that implementation.
4. Fix the cause. Do not add a baseline, blanket suppression, relaxed threshold, or formatting plugin.
5. Rerun the owning task, then the fast profile:

```sh
./gradlew :detekt
./gradlew :detektMain :detektTest
./gradlew :detektComposeFixtures :detektBuildScripts
python3 scripts/validate.py fast
```

Choose only the relevant targeted command first. Use a module prefix such as `:recording:detekt` for module findings.
Use `./gradlew -p fixtures/standalone :detekt` for the separate standalone build.
ktfmt kotlinLangStyle remains authoritative. Format only files owned by the change.

Keep the rule, path, task, and result as evidence. Stop repeated runs on an unchanged analyzer crash and diagnose its version/classpath.
Ordinary checks do not prove type-resolved rules ran. Static checks do not prove runtime behavior or absence of performance defects.
