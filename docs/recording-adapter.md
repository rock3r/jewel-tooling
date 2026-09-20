# Experimental recording adapter

This page describes the older manual adapter for contributors who control a target bootstrap. It is not required for static hints or **Run with Compose Inspection**. Do not combine it with the inspection agent.

The bootstrap must own the Compose tracer slot before the target starts its Compose content. Compose provides a setter without a getter. The adapter cannot detect or restore another tracer. Do not install it from an ordinary IDE plugin.

In a controlled standalone application, include the exported `recording.jar` and `recording-compose.jar` from `fixtures/standalone/.local`. Supply Jackson Core 2.19.0. Reuse the application's Compose runtime. The adapter targets the AndroidX runtime 1.11.1 API and requires JVM 21 or newer.

For IJPL, keep the bootstrap in a disposable target that explicitly reserves the tracer slot. The fixture uses the platform's Compose and Jackson libraries. It does not package another runtime. Activity from other compositions in that runtime classloader can also appear.

Install one `OwnedCompositionTracer` with `installOwnedDispatcher()`. Call `startRecording(CaptureTarget(...))` and `stopRecording()` between controlled interactions on the composition thread. Serialize the returned snapshot with `RecordingFiles.writeNew(path, recording)` on a worker thread. The method refuses to overwrite an existing file. The [standalone fixture](../fixtures/standalone/src/main/kotlin/example/FixtureRecording.kt) shows this sequence.

For live controls, give the existing dispatcher to a `LiveCompositionHost` before showing the development UI:

```kotlin
val dispatcher = OwnedCompositionTracer.installOwnedDispatcher()
val inspection = LiveCompositionHost(
  dispatcher,
  CaptureTarget("My development application"),
  SwingUtilities::invokeLater,
)
```

This example requires the corresponding recording imports and `javax.swing.SwingUtilities`. It assumes that all captured composition work uses the Swing event dispatch thread. Expose `inspection.connectionString` through an explicit copy control. Do not display or log the token. Call `inspection.close()` when the development target closes. The host owns the recording sink while open; do not call the dispatcher's recording methods concurrently. Closing the host leaves the dispatcher installed and inactive. It does not restore another tracer.

Only one session can be attached at a time. Stop it before starting another, including after truncation. General concurrent restart is unsupported because the callback API has no session token.

The [live inspection guide](../user-guide/live-inspection.md) is the supported path for application authors.
