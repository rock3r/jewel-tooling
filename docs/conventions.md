# Conventions

Write new plugin code in Kotlin. Maintain the existing bootstrap code in Java.
Format Kotlin and Gradle Kotlin scripts with ktfmt kotlinLangStyle.
Use no additional formatter. Limit formatting to files owned by your change.
Leave concurrent tasks' files unchanged.

## IDE correctness

Resolve declarations with the Kotlin Analysis API in a valid session and read action.
Keep symbols and types out of caches, UI models, and asynchronous callbacks.
During indexing, defer analysis or return unknown for unresolved declarations.
Before applying asynchronous results, check source validity and disposal state.

Propagate cancellation without converting it into an unknown result or user-facing error.
When catching broad IDE exceptions, preserve platform control-flow exceptions before reporting failures.
Assign coroutine, listener, and resource ownership to the relevant service or disposable.
Run network, file, and process work outside the event dispatch thread (EDT).
Use the EDT to change Swing components.
Release read actions before waiting for network, process, or EDT work.

After resource ownership changes, test disconnect, project close, and dynamic unload.
Bound resource cleanup so unload never waits for nonmodal UI work.
Read [architecture](architecture.md#runtime-and-lifecycle) for classloader boundaries.

## UI and language

Store user-facing strings in `src/main/resources/messages/JewelToolingBundle.properties`.
Choose IDE icons and theme-aware colours. Configure editor hints through the Jewel Tooling colour scheme keys.
Show text labels with state colours.
Check light and dark themes, keyboard focus, accessible names, and dismissal.
Use IDE splitters and colours. Check divider contrast in each theme.

Write short active sentences, each about one idea. Use a consistent term for each concept.
Explanations must separate compiler evidence, inference, unknown results, and runtime observations.
Recommend stability annotations only when their contracts hold.

## Changes and evidence

Read the current diff before changing shared files. Leave unrelated and untracked work intact.
Keep rule thresholds intact and avoid blanket suppressions or baselines that hide findings.
For justified exceptions, explain the unsupported case and limit the exception to it.

For each new module, update the explicit lists in `scripts/validate.py`.
Check whether it adds capture inputs to `scripts/verify-artifacts.py`.

Report observed commands and results. Identify unrun checks, existing failures, and regressions separately.
Plans alone cannot establish implementation. Store local plans in ignored `.plans/`.
Exclude private project names and personal environment paths from public files.
