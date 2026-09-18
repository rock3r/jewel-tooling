package dev.sebastiano.jewel.tooling

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ui.JBUI
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction

@Service(Service.Level.PROJECT)
internal class StabilityDetailsService
@JvmOverloads
constructor(
  private val project: Project,
  private val scope: CoroutineScope,
  private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Disposable {
  private var request: Job? = null
  private var popup: JBPopup? = null
  private var currentSnapshot: StabilityReportSnapshot? = null
  @Volatile private var disposed = false

  /** Called on EDT; each invocation owns one cancellable background read. */
  @Suppress(
    "TooGenericExceptionCaught",
    "InstanceOfCheckForException",
  ) // ControlFlowException is an interface, so it cannot be a catch subject.
  fun show(editor: Editor, offset: Int, requestScope: CoroutineScope = scope) {
    request?.cancel()
    popup?.cancel()
    if (disposed || project.isDisposed || editor.isDisposed) return
    val stamp = editor.document.modificationStamp
    val modality = ModalityState.current().asContextElement()
    request =
      requestScope.launch(computeDispatcher + modality) {
        val report =
          try {
            smartReadAction(project) { readSnapshot(editor, offset) }
          } catch (exception: Exception) {
            if (exception is ControlFlowException || exception is CancellationException)
              throw exception
            Logger.getInstance(StabilityDetailsService::class.java)
              .warn("Compose stability details failed", exception)
            withContext(Dispatchers.EDT) {
              if (canShow(editor, stamp))
                HintManager.getInstance()
                  .showInformationHint(editor, JewelToolingBundle.message("details.failed"))
            }
            return@launch
          }
        withContext(Dispatchers.EDT) {
          if (!canShow(editor, stamp)) return@withContext
          if (report == null)
            HintManager.getInstance()
              .showInformationHint(editor, JewelToolingBundle.message("details.unavailable"))
          else open(editor, report)
        }
      }
  }

  private fun readSnapshot(editor: Editor, offset: Int): StabilityReportSnapshot? {
    val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) as? KtFile
    if (file == null || file.isCompiled || !file.isValid) return null
    return functionAt(file, offset)?.let { function ->
      StabilityAnalysis.inspect(function, navigation = true)?.let { report ->
        StabilityReportSnapshot(
          report,
          PsiModificationTracker.getInstance(project).modificationCount,
          editor.document.modificationStamp,
        )
      }
    }
  }

  private fun canShow(editor: Editor, stamp: Long): Boolean =
    !disposed &&
      !project.isDisposed &&
      !editor.isDisposed &&
      editor.document.modificationStamp == stamp &&
      FileEditorManager.getInstance(project).selectedTextEditor === editor

  private fun open(editor: Editor, snapshot: StabilityReportSnapshot) {
    currentSnapshot = snapshot
    val view =
      StabilityDetailsPanel(snapshot.report) { target -> navigate(editor, snapshot, target) }
    val created =
      JBPopupFactory.getInstance()
        .createComponentPopupBuilder(view.component, view.focus)
        .setTitle(JewelToolingBundle.message("details.title"))
        .setFocusable(true)
        .setRequestFocus(true)
        .setResizable(true)
        .setMovable(true)
        .setMinSize(JBUI.size(360, 180))
        .createPopup()
    popup = created
    created.addListener(
      object : JBPopupListener {
        override fun onClosed(event: LightweightWindowEvent) {
          if (popup === created) {
            popup = null
            currentSnapshot = null
          }
        }
      }
    )
    Disposer.register(this, created)
    editor.document.addDocumentListener(
      object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
          ApplicationManager.getApplication().invokeLater {
            if (!created.isDisposed) created.cancel()
          }
        }
      },
      created,
    )
    EditorFactory.getInstance()
      .addEditorFactoryListener(
        object : EditorFactoryListener {
          override fun editorReleased(event: EditorFactoryEvent) {
            if (event.editor === editor) created.cancel()
          }
        },
        created,
      )
    project.messageBus
      .connect(created)
      .subscribe(
        FileEditorManagerListener.FILE_EDITOR_MANAGER,
        object : FileEditorManagerListener {
          override fun selectionChanged(event: FileEditorManagerEvent) {
            created.cancel()
          }
        },
      )
    created.showInBestPositionFor(editor)
  }

  internal fun navigate(
    editor: Editor,
    snapshot: StabilityReportSnapshot,
    target: SmartPsiElementPointer<KtNamedDeclaration>,
  ) {
    val alive = !disposed && !project.isDisposed && !editor.isDisposed && popup?.isDisposed == false
    if (!alive || currentSnapshot !== snapshot) return
    val indexing = DumbService.isDumb(project)
    var location: DeclarationLocation? = null
    val status =
      ApplicationManager.getApplication().runReadAction<NavigationStatus> {
        val initial =
          StabilityNavigation.gate(
            snapshot,
            currentSnapshot,
            alive,
            indexing,
            PsiModificationTracker.getInstance(project).modificationCount,
            editor.document.modificationStamp,
            true,
          )
        if (initial != NavigationStatus.READY) initial
        else {
          location = StabilityNavigation.resolve(target)
          if (location == null) NavigationStatus.INVALID else NavigationStatus.READY
        }
      }
    when (status) {
      NavigationStatus.READY -> {
        val destination = location ?: return
        popup?.cancel()
        OpenFileDescriptor(project, destination.file, destination.offset).navigate(true)
      }
      NavigationStatus.STALE,
      NavigationStatus.INVALID ->
        HintManager.getInstance()
          .showInformationHint(editor, JewelToolingBundle.message("details.navigate.stale"))
      NavigationStatus.CLOSED -> Unit
    }
  }

  override fun dispose() {
    disposed = true
    request?.cancel()
    request = null
    popup = null
    currentSnapshot = null
  }

  companion object {
    fun functionAt(file: KtFile, offset: Int): KtNamedFunction? {
      val element =
        file.findElementAt(offset.coerceIn(0, maxOf(0, file.textLength - 1))) ?: return null
      return PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java, false)
    }
  }
}
