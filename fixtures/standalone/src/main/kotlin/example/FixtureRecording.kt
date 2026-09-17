package example

import dev.sebastiano.jewel.tooling.recording.CaptureTarget
import dev.sebastiano.jewel.tooling.recording.OwnedCompositionTracer
import dev.sebastiano.jewel.tooling.recording.Recording
import dev.sebastiano.jewel.tooling.recording.RecordingFiles
import java.nio.file.Path
import javax.swing.SwingUtilities

/** Owns tracing only in this disposable development target. */
object FixtureRecording {
  private val tracer = OwnedCompositionTracer.installOwnedDispatcher()
  @Volatile private var completed: Recording? = null

  fun initialize() {
    check(SwingUtilities.isEventDispatchThread())
  }

  fun start() {
    check(SwingUtilities.isEventDispatchThread())
    completed = null
    tracer.startRecording(
      CaptureTarget(
        "Jewel standalone fixture",
        build = System.getProperty("jewel.test.captureId"),
        compiler = "2.4.0",
      )
    )
  }

  fun stop() {
    check(SwingUtilities.isEventDispatchThread())
    completed = tracer.stopRecording()
  }

  fun export(path: Path) {
    check(!SwingUtilities.isEventDispatchThread())
    val recording = checkNotNull(completed)
    check(recording.events.isNotEmpty())
    check(recording.sites.any { it.info.contains("example.GreetingRow") })
    RecordingFiles.writeNew(path, recording)
    completed = null
  }
}
