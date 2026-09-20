package example

import dev.sebastiano.jewel.tooling.recording.CaptureTarget
import dev.sebastiano.jewel.tooling.recording.LiveCompositionHost
import dev.sebastiano.jewel.tooling.recording.OwnedCompositionTracer
import dev.sebastiano.jewel.tooling.recording.Recording
import dev.sebastiano.jewel.tooling.recording.RecordingFiles
import java.nio.file.Path
import javax.swing.SwingUtilities

/** Owns tracing only in this disposable development target. */
object FixtureRecording {
    private val tracer = OwnedCompositionTracer.installOwnedDispatcher()
    private var live: LiveCompositionHost? = null
    @Volatile private var completed: Recording? = null

    fun initialize() {
        check(SwingUtilities.isEventDispatchThread())
    }

    fun isLiveCapturing(): Boolean = tracer.isTraceInProgress()

    fun openLiveConnection(): String {
        check(SwingUtilities.isEventDispatchThread())
        if (live == null)
            live =
                LiveCompositionHost(
                    tracer,
                    CaptureTarget("Jewel standalone development target"),
                    SwingUtilities::invokeLater,
                )
        return checkNotNull(live).connectionString
    }

    fun closeLiveConnection() {
        live?.close()
        live = null
    }

    fun copyLiveConnection() {
        val value = openLiveConnection()
        java.awt.Toolkit.getDefaultToolkit()
            .systemClipboard
            .setContents(java.awt.datatransfer.StringSelection(value), null)
    }

    fun start() {
        check(live == null)
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
