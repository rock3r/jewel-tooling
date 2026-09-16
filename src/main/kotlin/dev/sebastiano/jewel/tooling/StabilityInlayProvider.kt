package dev.sebastiano.jewel.tooling

import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.util.concurrent.CancellationException
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

internal class StabilityInlayProvider(
  private val analyzeFunction: (KtNamedFunction) -> List<ParameterHint> = {
    StabilityAnalysis.hints(it)
  }
) : InlayHintsProvider {
  private companion object {
    const val TOOLTIP_COLUMNS = 72
  }

  private fun wrapTooltip(text: String): String =
    text.lineSequence().joinToString("\n") { line ->
      buildString {
        var column = 0
        for (word in line.split(' ')) {
          if (column > 0) {
            if (column + word.length + 1 > TOOLTIP_COLUMNS) {
              append('\n')
              column = 0
            } else {
              append(' ')
              column++
            }
          }
          append(word)
          column += word.length
        }
      }
    }

  override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
    if (file !is KtFile || file.isCompiled || DumbService.isDumb(file.project)) return null
    return object : SharedBypassCollector {
      // A failed function must not suppress sibling hints; cancellation is rethrown below.
      @Suppress("TooGenericExceptionCaught", "ReturnCount")
      override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
        val function = element as? KtNamedFunction ?: return
        // Cheap syntax gate; the analyzer resolves annotation identities (including import
        // aliases).
        if (function.annotationEntries.isEmpty()) return
        val hints =
          try {
            analyzeFunction(function)
          } catch (exception: Exception) {
            if (exception is ControlFlowException || exception is CancellationException)
              throw exception
            Logger.getInstance(StabilityInlayProvider::class.java)
              .warn("Compose stability analysis failed", exception)
            return
          }
        for (hint in hints) {
          val label = JewelToolingBundle.message(hint.assessment.stability.messageKey)
          sink.addPresentation(
            InlineInlayPosition(hint.offset, relatedToPrevious = true),
            tooltip =
              wrapTooltip(
                JewelToolingBundle.message("hint.tooltip", hint.name, label, hint.assessment.reason)
              ),
            hintFormat = HintFormat.default,
          ) {
            text(label)
          }
        }
      }
    }
  }
}
