---
name: jewel-compose-analysis
description: Explain Compose parameter stability in Jewel or Compose JVM projects using the Jewel Tooling MCP server in the authoring IDE. Use for stability findings and evidence, not live performance profiling.
license: Apache-2.0
metadata:
  version: "1.0.0"
---

# Compose stability with Jewel Tooling

Use the connected Jewel Tooling server for the user's project. The tools inspect the IDE's Kotlin model, including unsaved editor content.

1. Call `jewel_status`. Check the returned project root and readiness before requesting analysis. Do not select an unrelated open project.
2. Call `jewel_composables` with a project-relative Kotlin file path. Use a returned declaration ID instead of constructing one.
3. Call `jewel_analyze` with that file and declaration ID. Pass the returned content hash as `expectedHash` to reject changed content.
4. For one parameter, call `jewel_explain` with the file, declaration ID, and parameter name. An extension receiver uses `<receiver>`.
5. Explain the classification, its evidence, and any incomplete results. Cite the returned source location and identify unsaved content when relevant.

Tool names may have a client namespace. Discover the available tools and their schemas rather than guessing names or arguments.
Pi support uses pi-mcp-adapter, which can expose tools through its `mcp` proxy. Ask the user's agent to adapt setup for another extension.

## Interpret evidence

- `COMPILER_METADATA`: supported compiled metadata contributes to the result.
- `DECLARED_CONTRACT`: a resolved stability annotation declares a contract. It does not prove that the implementation satisfies it.
- `SOURCE`: the engine infers stability from source.
- `BUILTIN`: a known language or library rule applies.
- `UNSUPPORTED`: the available evidence cannot support a conclusion.

Keep mixed evidence and unknown results visible. Do not describe source inference as compiler confirmation.
Do not add `@Stable` or `@Immutable` merely to silence a finding.
Stability alone does not prove skippability or a performance defect. These MCP tools do not provide live inspection data.

## Handle stale or unavailable results

Success uses `schemaVersion: 1` and `payload.kind: result`. Errors provide a code, retryability, and remedy; do not treat them as analysis.
Declaration IDs depend on the endpoint generation, file, and content hash. Re-list declarations after edits or endpoint restarts.
Locations use one-based lines and UTF-16 columns, with exclusive range ends. Do not apply old locations to changed content.
If indexing prevents analysis, wait for readiness and retry once. If that still fails, report the returned remedy.
If the server is unavailable, explain how to enable it through **Tools → Compose Analysis MCP Server…** in the intended IDE project.
A Gradle or Bazel project needs a working imported Kotlin model. Missing dependencies can produce unknown results.
The skill does not authorize project edits, builds, application launches, permission changes, or installation in other clients.
