package dev.sebastiano.jewel.tooling

import com.intellij.codeInsight.hints.ChangeListener
import com.intellij.codeInsight.hints.FactoryInlayHintsCollector
import com.intellij.codeInsight.hints.ImmediateConfigurable
import com.intellij.codeInsight.hints.InlayGroup
import com.intellij.codeInsight.hints.InlayHintsCollector
import com.intellij.codeInsight.hints.InlayHintsProvider
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ui.JBUI
import java.util.concurrent.CancellationException
import javax.swing.JPanel
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

internal class StabilityInlayProvider(
    private val analyzeFunction: (KtNamedFunction) -> List<ParameterHint> = {
        StabilityAnalysis.hints(it)
    }
) : InlayHintsProvider<NoSettings> {
    override val key = SettingsKey<NoSettings>(ID)
    override val name: String
        get() = JewelToolingBundle.message("hints.name")

    override val description: String
        get() = JewelToolingBundle.message("hints.description")

    override val group = InlayGroup.TYPES_GROUP
    override val previewText =
        """
        package androidx.compose.runtime
        annotation class Composable
        @Composable fun Row(title: String, items: List<String>) {}
        """
            .trimIndent()

    override fun createSettings() = NoSettings()

    override fun createConfigurable(settings: NoSettings) =
        object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener) = JPanel()
        }

    @Suppress("ReturnCount") // Indexing has no analysis session; skip compiled files separately.
    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink,
    ): InlayHintsCollector? {
        if (file !is KtFile || file.isCompiled || DumbService.isDumb(file.project)) return null
        if (file.isDependencySource()) return null
        return object : FactoryInlayHintsCollector(editor) {
            @Suppress(
                "TooGenericExceptionCaught",
                "ReturnCount",
                "InstanceOfCheckForException",
            ) // ControlFlowException is an interface, so it cannot be a catch subject.
            override fun collect(
                element: PsiElement,
                editor: Editor,
                sink: InlayHintsSink,
            ): Boolean {
                val function = element as? KtNamedFunction ?: return true
                if (function.annotationEntries.isEmpty()) return true
                val hints =
                    try {
                        analyzeFunction(function)
                    } catch (exception: Exception) {
                        if (exception is ControlFlowException || exception is CancellationException)
                            throw exception
                        Logger.getInstance(StabilityInlayProvider::class.java)
                            .warn("Compose stability analysis failed", exception)
                        return true
                    }
                for (hint in hints) {
                    sink.addInlineElement(
                        hint.offset,
                        true,
                        StabilityInlays.presentation(factory, editor, hint),
                        false,
                    )
                }
                return true
            }
        }
    }

    companion object {
        const val ID = "jewel.compose.stability"
    }
}

internal object StabilityInlays {
    fun presentation(
        factory: PresentationFactory,
        editor: Editor,
        hint: ParameterHint,
    ): InlayPresentation {
        val label = JewelToolingBundle.message(hint.assessment.stability.messageKey)
        val text = factory.smallTextWithoutBackground(label)
        val icon = factory.icon(StabilityStateIcon(hint.assessment) { editor.colorsScheme })
        val content =
            factory.seq(
                factory.inset(icon, top = maxOf(0, (text.height - icon.height) / 2)),
                factory.inset(text, left = JBUI.scale(TEXT_GAP)),
            )
        val tooltip = StabilityPresentation.tooltip(hint, editor.colorsScheme)
        return StabilityHintPresentation(
            label,
            tooltip,
            factory.withTooltip(
                tooltip,
                factory.inset(
                    content,
                    left = JBUI.scale(PAD),
                    right = JBUI.scale(PAD),
                    top = maxOf(0, (editor.lineHeight - content.height) / 2),
                ),
            ),
        )
    }

    private const val TEXT_GAP = 2
    private const val PAD = 3
}

internal class StabilityHintPresentation(
    val label: String,
    val tooltip: String,
    private val delegate: InlayPresentation,
) : InlayPresentation by delegate {
    override fun updateState(previousPresentation: InlayPresentation): Boolean =
        delegate.updateState(
            (previousPresentation as? StabilityHintPresentation)?.delegate ?: previousPresentation
        )

    override fun toString(): String = "JewelStability[$label]$tooltip"
}
