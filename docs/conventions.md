# Conventions

Use Kotlin for new plugin code. Keep existing Java bootstrap code in Java.
Use ktfmt kotlinLangStyle, including Gradle Kotlin scripts. Keep ktfmt as the only formatter.
Apply formatting only to files in your change. Do not reformat a concurrent task's files.

## IDE correctness

Use Kotlin Analysis API resolution inside a valid analysis session and read action.
Do not retain symbols or types in caches, UI models, or asynchronous callbacks.
Respect indexing: defer analysis or return unknown when the IDE cannot resolve a declaration.
Check source validity and disposal before applying asynchronous results.

Do not swallow cancellation or convert it into an unknown result or user error.
At broad IDE exception boundaries, preserve platform control-flow exceptions before reporting a failure.
Own coroutines, listeners, and resources with the relevant service or disposable.
Keep network, file, and process work off the EDT. Use the EDT for Swing mutations.
Do not hold a read action while waiting for network, process, or EDT work.

Exercise disconnect, project close, and dynamic unload when changing resource ownership.
A bounded cleanup section may release resources; it must not make unload wait for nonmodal UI work.
See [architecture](architecture.md#runtime-and-lifecycle) for the classloader boundary.

## UI and language

Put user-visible plugin strings in `src/main/resources/messages/JewelToolingBundle.properties`.
Use IDE icons and theme-aware colours. Editor hints use the configurable Jewel Tooling colour scheme keys.
Keep state labels alongside colours. Test light and dark themes, keyboard focus, accessible names, and dismissal.
Use IDE splitter components and colours; inspect divider contrast in both themes.

Write concise active sentences. State one topic per sentence and use one term per concept.
Keep explanations factual: distinguish compiler evidence, inference, unknown results, and runtime observations.
Do not recommend adding stability annotations merely to make a warning disappear.

## Changes and evidence

Inspect the existing diff before editing shared files. Preserve unrelated and untracked work.
Do not weaken rules, add blanket suppressions, or create baselines to hide findings.
Explain narrowly justified exceptions and keep them local to the actual unsupported case.

When adding a module, update the explicit module lists in `scripts/validate.py` and assess its capture inputs in `scripts/verify-artifacts.py`.

Report the commands and results you observed. Separate unrun checks, existing failures, and regressions.
Do not call a feature implemented because a plan describes it. Keep local plans in ignored `.plans/`.
Do not include private project names or personal environment paths in public files.
