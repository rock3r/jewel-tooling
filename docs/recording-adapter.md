# Experimental recording adapter

Contributors with control of a target bootstrap can use this older manual adapter.
Static hints and **Run with Compose Inspection** work without it.
Keep the adapter separate from the inspection agent.

Reserve the Compose tracer slot in the bootstrap before starting Compose content.
Compose exposes a setter with no getter, so the adapter cannot detect or restore another tracer.
Ordinary IDE plugins must not install this adapter.

For a controlled standalone target, add `recording.jar` and `recording-compose.jar` exported to `fixtures/standalone/.local`.
Add Jackson Core 2.19.0 and reuse the application's Compose runtime.
The adapter requires JVM 21 or newer and targets the AndroidX runtime 1.11.1 API.

For IJPL, use a disposable target whose bootstrap explicitly reserves the tracer slot.
The fixture reuses platform Compose and Jackson libraries without packaging another runtime.
The capture can include other compositions that share that runtime classloader.

Call `installOwnedDispatcher()` once to install an `OwnedCompositionTracer`.
On the composition thread, call `startRecording(CaptureTarget(...))` and `stopRecording()` around controlled interactions.
On a worker thread, write the snapshot with `RecordingFiles.writeNew(path, recording)`.
That method rejects existing destination files.
See the [standalone fixture](../fixtures/standalone/src/main/kotlin/example/FixtureRecording.kt) for the full sequence.

To support live controls, pass the existing dispatcher to `LiveCompositionHost` before displaying the development UI:

```kotlin
val dispatcher = OwnedCompositionTracer.installOwnedDispatcher()
val inspection = LiveCompositionHost(
  dispatcher,
  CaptureTarget("My development application"),
  SwingUtilities::invokeLater,
)
```

Add the recording imports and `javax.swing.SwingUtilities` for this example.
All captured composition work must run on the Swing event dispatch thread.
Offer an explicit control to copy `inspection.connectionString`. Keep the token out of displays and logs.
Call `inspection.close()` when closing the development target.

The open host owns the recording sink. Avoid concurrent calls to the dispatcher's recording methods.
Closing the host leaves the dispatcher installed but inactive. It cannot restore a previous tracer.

Attach at most one session. Stop it before starting another, even after truncation.
The callback API lacks session tokens, so general concurrent restart is unsupported.

Application authors should follow the supported setup in [Live inspection](../user-guide/live-inspection.md).
