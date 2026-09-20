package dev.sebastiano.jewel.tooling

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import dev.sebastiano.jewel.tooling.recording.Recording
import dev.sebastiano.jewel.tooling.recording.RecordingError
import dev.sebastiano.jewel.tooling.recording.RecordingFiles
import dev.sebastiano.jewel.tooling.recording.RecordingFormatException
import dev.sebastiano.jewel.tooling.recording.SiteSummary
import dev.sebastiano.jewel.tooling.recording.summarize
import java.io.IOException
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class RecordingReportData(val recording: Recording, val sites: List<SiteSummary>)

@Service(Service.Level.PROJECT)
internal class RecordingReportService
@JvmOverloads
constructor(
    private val project: Project,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Disposable {
    private var request: Job? = null
    private var dialog: RecordingReportDialog? = null
    @Volatile private var disposed = false

    /** Opens one report. Call on EDT. A later request cancels the earlier import. */
    fun open(path: Path, requestScope: CoroutineScope = scope) {
        request?.cancel()
        if (disposed || project.isDisposed) return
        val modality = ModalityState.current().asContextElement()
        request =
            requestScope.launch(ioDispatcher + modality) {
                val context = currentCoroutineContext()
                val data =
                    try {
                        val recording = RecordingFiles.read(path) { context.ensureActive() }
                        RecordingReportData(
                            recording,
                            recording.summarize { context.ensureActive() },
                        )
                    } catch (exception: IOException) {
                        withContext(Dispatchers.EDT) {
                            if (!disposed && !project.isDisposed) {
                                Messages.showErrorDialog(
                                    project,
                                    errorMessage(exception),
                                    JewelToolingBundle.message("recording.open.title"),
                                )
                            }
                        }
                        return@launch
                    }
                withContext(Dispatchers.EDT) {
                    context.ensureActive()
                    if (!disposed && !project.isDisposed) show(data)
                }
            }
    }

    private fun show(data: RecordingReportData) {
        dialog?.close(DialogWrapper.CANCEL_EXIT_CODE)
        val created = RecordingReportDialog(project, data)
        dialog = created
        Disposer.register(created.disposable, Disposable { if (dialog === created) dialog = null })
        created.show()
        Disposer.register(this, created.disposable)
    }

    private fun errorMessage(exception: IOException): String = recordingOpenError(exception)

    override fun dispose() {
        disposed = true
        request?.cancel()
        request = null
        dialog = null
    }
}

internal fun recordingOpenError(exception: IOException): String =
    JewelToolingBundle.message(
        when ((exception as? RecordingFormatException)?.code) {
            RecordingError.UNSUPPORTED_VERSION -> "recording.error.version"
            RecordingError.TOO_LARGE -> "recording.error.size"
            RecordingError.INVALID_FORMAT -> "recording.error.format"
            null -> "recording.error.read"
        }
    )
