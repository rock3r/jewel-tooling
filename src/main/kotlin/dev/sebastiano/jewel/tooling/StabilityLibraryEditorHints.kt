package dev.sebastiano.jewel.tooling

import com.intellij.codeInsight.daemon.LineMarkerSettings
import com.intellij.codeInsight.hints.InlayHintsSettings
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.codeInsight.hints.presentation.PresentationRenderer
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import java.util.concurrent.CancellationException
import javax.swing.Icon
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

@Service(Service.Level.PROJECT)
internal class StabilityLibraryEditorHints
@JvmOverloads
constructor(
    private val project: Project,
    private val scope: CoroutineScope,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Disposable {
    private val attached = HashMap<Editor, Session>()
    @Volatile private var disposed = false

    init {
        EditorFactory.getInstance()
            .addEditorFactoryListener(
                object : EditorFactoryListener {
                    override fun editorCreated(event: EditorFactoryEvent) {
                        if (event.editor.project == project) attach(event.editor)
                    }

                    override fun editorReleased(event: EditorFactoryEvent) = detach(event.editor)
                },
                this,
            )
        project.messageBus
            .connect(this)
            .subscribe(
                DumbService.DUMB_MODE,
                object : DumbService.DumbModeListener {
                    override fun exitDumbMode() = refresh()
                },
            )
        refresh()
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
        attached.getOrPut(editor) { Session(editor) }.schedule()
    }

    private fun detach(editor: Editor) {
        attached.remove(editor)?.let(Disposer::dispose)
    }

    override fun dispose() {
        disposed = true
        attached.keys.toList().forEach(::detach)
    }

    private inner class Session(private val editor: Editor) : Disposable {
        private var request: Job? = null
        private val inlays = ArrayList<Inlay<*>>()
        private val markers = ArrayList<RangeHighlighter>()

        @Suppress(
            "TooGenericExceptionCaught",
            "InstanceOfCheckForException",
        ) // ControlFlowException is an interface, so it cannot be a catch subject.
        fun schedule() {
            request?.cancel()
            if (disposed || editor.isDisposed || DumbService.isDumb(project)) {
                clear()
                return
            }
            val stamp = editor.document.modificationStamp
            request =
                scope.launch(
                    computeDispatcher + ModalityState.defaultModalityState().asContextElement()
                ) {
                    val snapshot =
                        try {
                            smartReadAction(project) { read(stamp) }
                        } catch (exception: Exception) {
                            if (
                                exception is ControlFlowException ||
                                    exception is CancellationException
                            )
                                throw exception
                            Logger.getInstance(StabilityLibraryEditorHints::class.java)
                                .warn(
                                    "Compose stability hints failed for a dependency file",
                                    exception,
                                )
                            emptyList()
                        }
                    withContext(Dispatchers.EDT) {
                        if (
                            !disposed &&
                                !editor.isDisposed &&
                                editor.document.modificationStamp == stamp
                        ) {
                            apply(snapshot)
                        }
                    }
                }
        }

        @Suppress("ReturnCount") // Missing PSI or disabled settings produce no hints.
        private fun read(stamp: Long): List<Hint> {
            if (editor.document.modificationStamp != stamp) return emptyList()
            val file =
                PsiDocumentManager.getInstance(project).getPsiFile(editor.document) as? KtFile
                    ?: return emptyList()
            if (!file.isDependencySource()) return emptyList()
            val inlaysEnabled =
                InlayHintsSettings.instance()
                    .hintsEnabled(
                        SettingsKey<NoSettings>(StabilityInlayProvider.ID),
                        KotlinLanguage.INSTANCE,
                    )
            val markersEnabled =
                LineMarkerSettings.getSettings().isEnabled(StabilityLineMarkerProvider())
            if (!inlaysEnabled && !markersEnabled) return emptyList()
            return PsiTreeUtil.collectElementsOfType(file, KtNamedFunction::class.java).flatMap {
                hintsFor(it, inlaysEnabled, markersEnabled)
            }
        }

        private fun hintsFor(
            function: KtNamedFunction,
            inlaysEnabled: Boolean,
            markersEnabled: Boolean,
        ): List<Hint> {
            val report =
                function
                    .takeIf { it.annotationEntries.isNotEmpty() }
                    ?.let { StabilityAnalysis.inspect(it) } ?: return emptyList()
            val hints = ArrayList<Hint>()
            if (inlaysEnabled) report.parameters.forEach { hints += Hint.Parameter(it) }
            val name = function.nameIdentifier
            if (markersEnabled && name != null) hints += Hint.Function(report, name.textOffset)
            return hints
        }

        private fun apply(snapshot: List<Hint>) {
            clear()
            if (snapshot.isEmpty()) return
            val factory = PresentationFactory(editor)
            val markup = editor.markupModel
            val document = editor.document
            for (hint in snapshot) {
                when (hint) {
                    is Hint.Parameter -> {
                        val presentation = StabilityInlays.presentation(factory, editor, hint.value)
                        val inlay =
                            editor.inlayModel.addInlineElement(
                                hint.value.offset,
                                true,
                                PresentationRenderer(presentation),
                            )
                        if (inlay != null) inlays += inlay
                    }
                    is Hint.Function -> {
                        if (hint.offset !in 0 until document.textLength) continue
                        val line = document.getLineNumber(hint.offset)
                        val highlighter =
                            markup.addLineHighlighter(
                                line,
                                HighlighterLayer.ADDITIONAL_SYNTAX,
                                TextAttributes(),
                            )
                        highlighter.gutterIconRenderer =
                            FunctionGutter(editor, hint.report, hint.offset)
                        markers += highlighter
                    }
                }
            }
        }

        private fun clear() {
            inlays.forEach { Disposer.dispose(it) }
            inlays.clear()
            markers.forEach { it.dispose() }
            markers.clear()
        }

        override fun dispose() {
            request?.cancel()
            if (!editor.isDisposed) clear()
            else {
                inlays.clear()
                markers.clear()
            }
        }
    }

    private sealed interface Hint {
        data class Parameter(val value: ParameterHint) : Hint

        data class Function(val report: FunctionStability, val offset: Int) : Hint
    }

    private inner class FunctionGutter(
        private val editor: Editor,
        private val report: FunctionStability,
        private val offset: Int,
    ) : GutterIconRenderer() {
        override fun getIcon(): Icon =
            StabilityPresentation.icon(StabilityPresentation.overall(report))

        override fun getTooltipText(): String = StabilityPresentation.summaryTooltip(report)

        override fun getAlignment(): Alignment = Alignment.RIGHT

        override fun isNavigateAction(): Boolean = true

        override fun getAccessibleName(): String =
            JewelToolingBundle.message(
                "summary.accessible",
                report.name,
                StabilityPresentation.counts(report),
            )

        override fun getClickAction(): AnAction =
            object : AnAction() {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                override fun actionPerformed(event: AnActionEvent) {
                    if (editor.isDisposed) return
                    project.service<StabilityDetailsService>().show(editor, offset)
                }
            }

        override fun equals(other: Any?): Boolean =
            other is FunctionGutter && other.report == report && other.offset == offset

        override fun hashCode(): Int = 31 * report.hashCode() + offset
    }
}
