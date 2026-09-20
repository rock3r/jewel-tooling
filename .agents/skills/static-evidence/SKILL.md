---
name: static-evidence
description: Validate Jewel Tooling source inference and compiler stability metadata against real compiler fixtures.
---

# Validate static evidence

Use when changing source inference, evidence classification, or the binary metadata reader.
Require JDK 25, Python 3.10+, dependency access, and the configured IDE baseline. Run from the repository root.
Read [architecture](../../../docs/architecture.md#static-evidence) and the [compiler matrix](../../../docs/testing.md#compatibility).

```sh
./gradlew :test --tests '*StabilityAnalysisTest'
./gradlew :test
python3 scripts/validate.py full
```

The root tests build `test-fixtures/compiler-metadata/` with real pinned compilers.
Keep cases for supported generic metadata, unsupported shapes, mutable source properties, unresolved types, and mixed evidence.
Do not replace actual compiler output with a fabricated constant merely to make a reader test pass.
Keep Analysis API objects inside their session and preserve cancellation.

Inspect `build/test-results/test/` and `build/reports/tests/test/`. Record executed cases and compiler/IDE versions.
A failure from another task remains a blocker for the full profile; report it separately from the focused test result.

These tests do not prove skippability, recomposition causes, or all published library shapes.
For rendered hints in Gradle and Bazel models, run the [Spectre workflow](../spectre-evidence/SKILL.md).
