package dev.sebastiano.jewel.tooling

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import java.util.concurrent.CancellationException
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

internal class StabilityLineMarkerProvider(
    private val inspect: (KtNamedFunction) -> FunctionStability? = { StabilityAnalysis.inspect(it) }
) : LineMarkerProviderDescriptor() {
    override fun getName(): String = JewelToolingBundle.message("gutter.name")

    override fun getIcon() = StabilityPresentation.icon(Stability.STABLE)

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    @Suppress(
        "TooGenericExceptionCaught",
        "CyclomaticComplexMethod",
        "LoopWithTooManyJumpStatements",
        "InstanceOfCheckForException",
    ) // Isolate broken functions; never swallow cancellation. ControlFlowException is an interface.
    override fun collectSlowLineMarkers(
        elements: List<PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        for (element in elements) {
            val function = element.parent as? KtNamedFunction ?: continue
            if (function.nameIdentifier != element || function.annotationEntries.isEmpty()) continue
            val file = function.containingFile as? KtFile ?: continue
            if (
                file.isCompiled || file.isDependencySource() || DumbService.isDumb(function.project)
            )
                continue
            val report =
                try {
                    inspect(function)
                } catch (exception: Exception) {
                    if (exception is ControlFlowException || exception is CancellationException)
                        throw exception
                    Logger.getInstance(StabilityLineMarkerProvider::class.java)
                        .warn("Compose stability summary failed", exception)
                    null
                } ?: continue
            val summary = StabilityPresentation.counts(report)
            val tooltip = StabilityPresentation.summaryTooltip(report)
            result +=
                LineMarkerInfo(
                    element,
                    element.textRange,
                    StabilityPresentation.icon(StabilityPresentation.overall(report)),
                    { tooltip },
                    { _, anchor ->
                        val project = anchor.project
                        val editor = FileEditorManager.getInstance(project).selectedTextEditor
                        if (
                            editor != null &&
                                !editor.isDisposed &&
                                com.intellij.psi.PsiDocumentManager.getInstance(project)
                                    .getDocument(anchor.containingFile) === editor.document
                        ) {
                            editor.caretModel.moveToOffset(anchor.textOffset)
                            project
                                .service<StabilityDetailsService>()
                                .show(editor, anchor.textOffset)
                        }
                    },
                    GutterIconRenderer.Alignment.RIGHT,
                    { JewelToolingBundle.message("summary.accessible", report.name, summary) },
                )
        }
    }
}
