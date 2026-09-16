# Bazel IJPL fixture

This public fixture compiles a Jewel tool window with Bazel. First run the root Gradle task `:e2e:driver-plugin:exportFixtureSdk`; it exports pinned public IDE and Kotlin compiler dependencies into ignored `.local/sdk`. No sibling checkout or Maven Local artifacts are used.

Run `bazel build //:ide_model` with the pinned Bazel version. The fixture-only Kotlin action returns real `JavaInfo`. The model exporter records that target's source files and transitive compile classpath. The E2E runner uses this model to create disposable IntelliJ modules and verifies roots and dependencies before checking editor hints.

This tests a Bazel-derived resolved model. It does not test a third-party Bazel importer wizard. The production Jewel Tooling plugin has no dependency on this exporter, Kotlin build rule, or test fixture.
