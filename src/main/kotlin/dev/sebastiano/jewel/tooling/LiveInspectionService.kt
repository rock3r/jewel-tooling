package dev.sebastiano.jewel.tooling

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.sebastiano.jewel.tooling.recording.CaptureStatus
import dev.sebastiano.jewel.tooling.recording.LiveCommand
import dev.sebastiano.jewel.tooling.recording.LiveCommandRejectedException
import dev.sebastiano.jewel.tooling.recording.LiveConnection
import dev.sebastiano.jewel.tooling.recording.LiveEndpoint
import dev.sebastiano.jewel.tooling.recording.LiveRuntimeState
import dev.sebastiano.jewel.tooling.recording.LiveTargetStatus
import dev.sebastiano.jewel.tooling.recording.Recording
import dev.sebastiano.jewel.tooling.recording.RecordingFiles
import dev.sebastiano.jewel.tooling.recording.summarize
import java.io.IOException
import java.net.URISyntaxException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import javax.swing.JComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Suppress("TooManyFunctions") // One controller owns connection, capture, export, and disposal.
@Service(Service.Level.PROJECT)
internal class LiveInspectionService(
  private val project: Project,
  private val scope: CoroutineScope,
) : Disposable {
  private var view: LiveInspectionPanel? = null
  private var state = LiveInspectionState()
  private var job: Job? = null
  private var generation = 0L
  private var commands: Channel<LiveCommand>? = null
  private var exportedSession: String? = null
  @Volatile private var connection: LiveConnection? = null
  @Volatile private var disposed = false

  fun createComponent(): JComponent {
    val created =
      LiveInspectionPanel(
        project,
        ::connectDialog,
        { project.service<InspectionLaunchService>().disconnect() },
        { command(LiveCommand.START) },
        { command(LiveCommand.STOP) },
        ::exportDialog,
      )
    view = created
    created.render(state)
    return created.component
  }

  @Suppress("ReturnCount") // Each invalid input exits before opening a connection.
  private fun connectDialog() {
    val input =
      Messages.showPasswordDialog(project, text("live.connect.prompt"), text("live.connect"), null)
        ?: return
    val endpoint =
      try {
        LiveEndpoint.parse(input)
      } catch (_: IllegalArgumentException) {
        invalidConnection()
        return
      } catch (_: URISyntaxException) {
        invalidConnection()
        return
      }
    connect(endpoint)
  }

  private fun invalidConnection() =
    Messages.showErrorDialog(project, text("live.connect.invalid"), text("live.connect"))

  fun connect(endpoint: LiveEndpoint, autoStart: Boolean = false) {
    if (disposed || !allowDiscard()) return
    disconnect()
    val requestGeneration = ++generation
    val queue = Channel<LiveCommand>(1)
    commands = queue
    val client = LiveConnection(endpoint)
    connection = client
    render(
      LiveInspectionState(
        phase = LivePhase.CONNECTING,
        message = "live.connecting",
        supportInstalled = state.supportInstalled,
      )
    )
    val modality = ModalityState.current().asContextElement()
    job =
      scope.launch(Dispatchers.IO + modality) {
        try {
          val target = client.connect()
          withContext(Dispatchers.EDT) {
            if (current(requestGeneration))
              render(
                state.copy(
                  phase = LivePhase.READY,
                  target = target,
                  connected = true,
                  message = "live.ready",
                )
              )
          }
          receive(client, queue, requestGeneration, autoStart)
        } catch (_: IOException) {
          currentCoroutineContext().ensureActive()
          withContext(Dispatchers.EDT) {
            if (current(requestGeneration)) render(disconnectedState("live.connection.failed"))
          }
        } finally {
          client.close()
          queue.close()
          withContext(Dispatchers.EDT) {
            if (current(requestGeneration))
              project.getServiceIfCreated(InspectionLaunchService::class.java)?.connectionEnded()
          }
        }
      }
  }

  private suspend fun receive(
    client: LiveConnection,
    queue: Channel<LiveCommand>,
    requestGeneration: Long,
    autoStart: Boolean,
  ) {
    var active = false
    var startWhenReady = autoStart
    var previousRuntime: LiveTargetStatus? = null
    while (true) {
      currentCoroutineContext().ensureActive()
      val runtime = client.status()
      if (runtime != previousRuntime) {
        previousRuntime = runtime
        renderRuntime(runtime, requestGeneration)
      }
      val command =
        if (startWhenReady && runtime.state == LiveRuntimeState.READY) {
          startWhenReady = false
          LiveCommand.START
        } else
          withTimeoutOrNull(if (active) POLL_MS else HEARTBEAT_MS) { queue.receive() }
            ?: if (active) LiveCommand.SNAPSHOT else LiveCommand.KEEP_ALIVE
      val snapshot =
        try {
          client.request(command)
        } catch (_: LiveCommandRejectedException) {
          withContext(Dispatchers.EDT) {
            if (current(requestGeneration))
              render(state.copy(phase = LivePhase.READY, message = "live.command.unavailable"))
          }
          continue
        }
      if (snapshot != null) {
        active = snapshot.status == CaptureStatus.ACTIVE
        renderSnapshot(snapshot, runtime, active, requestGeneration)
      }
    }
  }

  private suspend fun renderRuntime(runtime: LiveTargetStatus, requestGeneration: Long) {
    withContext(Dispatchers.EDT) {
      if (current(requestGeneration)) {
        val ready = runtime.state == LiveRuntimeState.READY
        render(
          state.copy(
            runtimeReady = ready,
            phase =
              if (ready) LivePhase.READY
              else if (runtime.state == LiveRuntimeState.NO_RUNTIME) LivePhase.WAITING_RUNTIME
              else LivePhase.UNSUPPORTED,
            message = runtimeMessage(runtime),
          )
        )
      }
    }
  }

  private suspend fun renderSnapshot(
    snapshot: dev.sebastiano.jewel.tooling.recording.Recording,
    runtime: LiveTargetStatus,
    active: Boolean,
    requestGeneration: Long,
  ) {
    val context = currentCoroutineContext()
    val data = RecordingReportData(snapshot, snapshot.summarize { context.ensureActive() })
    withContext(Dispatchers.EDT) {
      if (current(requestGeneration)) {
        if (state.data?.recording?.sessionId != snapshot.sessionId) exportedSession = null
        render(
          state.copy(
            phase =
              if (runtime.state != LiveRuntimeState.READY) LivePhase.UNSUPPORTED
              else if (active) LivePhase.CAPTURING else LivePhase.STOPPED,
            data = data,
            connected = true,
            message =
              if (runtime.state != LiveRuntimeState.READY) runtimeMessage(runtime)
              else if (active) "live.capturing" else "live.finished",
          )
        )
      }
    }
  }

  private fun runtimeMessage(runtime: LiveTargetStatus): String =
    when (runtime.state) {
      LiveRuntimeState.NO_RUNTIME -> "live.runtime.waiting"
      LiveRuntimeState.READY -> "live.ready"
      LiveRuntimeState.UNSUPPORTED_ABI -> "live.runtime.unsupported"
      LiveRuntimeState.MULTIPLE_RUNTIMES -> "live.runtime.multiple"
      LiveRuntimeState.FAILED -> "live.runtime.failed"
    }

  fun prepareLaunch(): Boolean {
    if (disposed || !allowDiscard()) return false
    disconnect()
    render(LiveInspectionState(supportInstalled = state.supportInstalled, launching = true))
    return true
  }

  fun launchMessage(key: String, target: String = state.target, busy: Boolean = true) {
    render(
      state.copy(
        phase = if (busy) LivePhase.BUSY else LivePhase.DISCONNECTED,
        launching = busy,
        message = key,
        target = target,
      )
    )
  }

  fun supportInstalled(keepBusy: Boolean = false) {
    render(
      state.copy(
        supportInstalled = true,
        launching = keepBusy,
        phase = if (keepBusy) LivePhase.BUSY else LivePhase.DISCONNECTED,
        message = "launch.installed",
      )
    )
  }

  fun launchFailure(key: String) {
    render(
      state.copy(phase = LivePhase.UNSUPPORTED, launching = false, connected = false, message = key)
    )
  }

  private fun command(command: LiveCommand) {
    if (!state.connected || state.phase == LivePhase.BUSY) return
    if (command == LiveCommand.START && !allowDiscard()) return
    if (commands?.trySend(command)?.isSuccess == true)
      render(state.copy(phase = LivePhase.BUSY, message = "live.command.pending"))
  }

  private fun allowDiscard(): Boolean {
    val recording = state.data?.recording
    if (recording == null || recording.events.isEmpty() || recording.sessionId == exportedSession)
      return true
    return Messages.showYesNoDialog(
      project,
      text("live.discard.prompt"),
      text("live.title"),
      text("live.discard.confirm"),
      text("live.discard.keep"),
      Messages.getQuestionIcon(),
    ) == Messages.YES
  }

  fun disconnect() {
    generation++
    connection?.close()
    connection = null
    job?.cancel()
    job = null
    commands?.close()
    commands = null
    render(disconnectedState("live.disconnected"))
  }

  private fun disconnectedState(message: String): LiveInspectionState =
    state.copy(
      phase = LivePhase.DISCONNECTED,
      connected = false,
      launching = false,
      message =
        if (state.data?.recording?.status == CaptureStatus.ACTIVE) "live.incomplete" else message,
    )

  private fun exportDialog() {
    val recording =
      state.data?.recording?.takeUnless { it.status == CaptureStatus.ACTIVE } ?: return
    val descriptor =
      FileSaverDescriptor(text("live.export"), text("live.export.description"), "json")
    val selected =
      FileChooserFactory.getInstance()
        .createSaveFileDialog(descriptor, project)
        .save("compose-recording.json") ?: return
    export(selected.file.toPath(), recording)
  }

  fun export(path: Path, recording: Recording) {
    val modality = ModalityState.current().asContextElement()
    scope.launch(Dispatchers.IO + modality) {
      val context = currentCoroutineContext()
      val failure =
        try {
          RecordingFiles.writeNew(path, recording) { context.ensureActive() }
          null
        } catch (_: FileAlreadyExistsException) {
          "live.export.exists"
        } catch (_: IOException) {
          "live.export.failed"
        }
      withContext(Dispatchers.EDT) {
        if (!disposed && !project.isDisposed) {
          if (failure != null) Messages.showErrorDialog(project, text(failure), text("live.export"))
          else if (state.data?.recording?.sessionId == recording.sessionId) {
            exportedSession = recording.sessionId
            render(state.copy(message = "live.exported"))
          }
        }
      }
    }
  }

  private fun current(value: Long) = !disposed && !project.isDisposed && generation == value

  fun snapshot(): RecordingReportData? = state.data

  fun selectSite(id: Int) {
    view?.selectSite(id)
  }

  private fun render(next: LiveInspectionState) {
    state = next
    if (!disposed) {
      view?.render(next)
      project.service<LiveInspectionEditorHints>().show(next.data)
    }
  }

  private fun text(key: String) = JewelToolingBundle.message(key)

  override fun dispose() {
    disposed = true
    connection?.close()
    job?.cancel()
    commands?.close()
    view = null
  }

  companion object {
    private const val POLL_MS = 500L
    private const val HEARTBEAT_MS = 2000L
  }
}
