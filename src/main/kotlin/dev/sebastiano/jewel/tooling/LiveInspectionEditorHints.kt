package dev.sebastiano.jewel.tooling

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Disposer
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Service(Service.Level.PROJECT)
internal class LiveInspectionEditorHints
@JvmOverloads
constructor(
    private val project: Project,
    private val scope: CoroutineScope,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Disposable {
    private val hints = AtomicReference<Map<String, Map<Int, LiveEditorHint>>>(emptyMap())
    private val attached = HashMap<Editor, Gutter>()
    private var request: Job? = null
    private var generation = 0L
    @Volatile private var disposed = false

    init {
        EditorFactory.getInstance()
            .addEditorFactoryListener(
                object : EditorFactoryListener {
                    override fun editorCreated(event: EditorFactoryEvent) {
                        val editor = event.editor
                        if (editor.project == project) attach(editor)
                    }

                    override fun editorReleased(event: EditorFactoryEvent) = detach(event.editor)
                },
                this,
            )
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            DumbService.DUMB_MODE,
            object : DumbService.DumbModeListener {
                override fun exitDumbMode() {
                    val data =
                        project.getServiceIfCreated(LiveInspectionService::class.java)?.snapshot()
                    if (data != null) show(data)
                }
            },
        )
        connection.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { refreshLater() })
    }

    fun show(data: RecordingReportData?) {
        request?.cancel()
        val requestGeneration = ++generation
        if (disposed || project.isDisposed) {
            hints.set(emptyMap())
            refreshLater()
            return
        }
        if (data == null || data.sites.isEmpty()) {
            hints.set(emptyMap())
            refreshLater()
            return
        }
        val modality = ModalityState.current().asContextElement()
        request =
            scope.launch(computeDispatcher + modality) {
                val mapped = smartReadAction(project) { TraceSiteLocations.map(project, data) }
                if (disposed || project.isDisposed || generation != requestGeneration) return@launch
                hints.set(mapped)
                withContext(Dispatchers.EDT) { refresh() }
            }
    }

    private fun refreshLater() {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) refresh()
        else
            app.invokeLater(
                { if (!disposed && !project.isDisposed) refresh() },
                ModalityState.any(),
                Condition<Any?> { disposed || project.isDisposed },
            )
    }

    private fun refresh() {
        if (disposed || project.isDisposed) return
        for (editor in EditorFactory.getInstance().allEditors) {
            if (editor.project == project && !editor.isDisposed) attach(editor)
        }
        val live = HashSet(EditorFactory.getInstance().allEditors.filter { it.project == project })
        attached.keys.filter { it !in live }.forEach(::detach)
    }

    private fun attach(editor: Editor) {
        if (disposed || editor.isDisposed || editor.project != project) return
        attached.getOrPut(editor) { Gutter(editor) }.sync()
    }

    private fun detach(editor: Editor) {
        attached.remove(editor)?.let(Disposer::dispose)
    }

    private fun hint(editor: Editor): Map<Int, LiveEditorHint> {
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return emptyMap()
        return hints.get()[file.url].orEmpty()
    }

    override fun dispose() {
        disposed = true
        request?.cancel()
        attached.keys.toList().forEach(::detach)
        hints.set(emptyMap())
    }

    private inner class Gutter(private val editor: Editor) : Disposable {
        private val inlays = ArrayList<Inlay<*>>()

        init {
            val ex = editor as? EditorEx
            if (ex != null) {
                var hovering = false
                val listener =
                    object : EditorMouseListener, EditorMouseMotionListener {
                        override fun mousePressed(event: EditorMouseEvent) = click(event)

                        override fun mouseClicked(event: EditorMouseEvent) = click(event)

                        override fun mouseMoved(event: EditorMouseEvent) {
                            val renderer = LiveInspectionGutter.rendererAt(event)
                            if (renderer != null) {
                                editor.contentComponent.toolTipText = renderer.tooltip()
                                LiveInspectionGutter.showHandCursor(editor)
                                event.consume()
                                hovering = true
                            } else if (hovering) {
                                editor.contentComponent.toolTipText = null
                                LiveInspectionGutter.clearHandCursor(editor)
                                hovering = false
                            }
                        }

                        override fun mouseExited(event: EditorMouseEvent) {
                            if (!hovering) return
                            editor.contentComponent.toolTipText = null
                            LiveInspectionGutter.clearHandCursor(editor)
                            hovering = false
                        }

                        private fun click(event: EditorMouseEvent) {
                            val renderer = LiveInspectionGutter.rendererAt(event) ?: return
                            if (event.mouseEvent.button != MouseEvent.BUTTON1) return
                            event.consume()
                            event.mouseEvent.consume()
                            renderer.activate()
                        }
                    }
                ex.addEditorMouseListener(listener, this)
                ex.addEditorMouseMotionListener(listener, this)
            }
        }

        fun sync() {
            if (disposed || editor.isDisposed) return
            clear()
            val byLine = hint(editor)
            val document = editor.document
            for ((line, value) in byLine) {
                if (line !in 0 until document.lineCount) continue
                val inlay =
                    editor.inlayModel.addAfterLineEndElement(
                        document.getLineEndOffset(line),
                        InlayProperties().relatesToPrecedingText(true).disableSoftWrapping(true),
                        LiveInspectionDurationRenderer(value, editor),
                    )
                if (inlay != null) inlays += inlay
            }
        }

        private fun clear() {
            inlays.forEach { Disposer.dispose(it) }
            inlays.clear()
        }

        override fun dispose() {
            LiveInspectionGutter.clearHandCursor(editor)
            if (!editor.isDisposed) clear() else inlays.clear()
        }
    }
}
