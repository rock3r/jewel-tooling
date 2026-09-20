package dev.sebastiano.jewel.tooling

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Rectangle
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.text.DefaultCaret
import org.jetbrains.kotlin.psi.KtNamedDeclaration

internal object StabilityPresentation {
    fun icon(stability: Stability): Icon =
        StabilityStateIcon(StabilityAssessment(stability, "")) {
            EditorColorsManager.getInstance().globalScheme
        }

    fun foreground(stability: Stability): Color =
        StabilityColors.color(EditorColorsManager.getInstance().globalScheme, stability)

    fun title(stability: Stability): String =
        JewelToolingBundle.message("state.${stability.name.lowercase(java.util.Locale.ROOT)}")

    fun tooltip(
        hint: ParameterHint,
        scheme: EditorColorsScheme = EditorColorsManager.getInstance().globalScheme,
    ): String {
        val state = hint.assessment.stability
        val muted = ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground())
        val scope =
            when {
                StabilityColors.isCompilerConfirmed(hint.assessment) -> "hint.confirmed"
                state == Stability.UNSTABLE -> "hint.skipping"
                else -> "hint.scope"
            }
        return HtmlChunk.tag("html")
            .child(
                HtmlChunk.div("font-weight: normal; width: 360px")
                    .child(HtmlChunk.tag("b").addText("${hint.name}: ${hint.typeText}"))
                    .child(HtmlChunk.br())
                    .child(
                        colored(ColorUtil.toHtmlColor(StabilityColors.color(scheme, state)))
                            .addText(title(state))
                    )
                    .child(HtmlChunk.tag("p").addText(hint.assessment.reason))
                    .child(
                        colored(muted)
                            .child(
                                HtmlChunk.tag("small")
                                    .attr("style", "color: $muted")
                                    .addText(evidence(hint.assessment))
                            )
                    )
                    .child(HtmlChunk.br())
                    .child(
                        colored(muted)
                            .child(
                                HtmlChunk.tag("small")
                                    .attr("style", "color: $muted")
                                    .addText(JewelToolingBundle.message(scope))
                            )
                    )
                    .child(HtmlChunk.br())
                    .child(
                        colored(muted)
                            .child(
                                HtmlChunk.tag("small")
                                    .attr("style", "color: $muted")
                                    .addText(JewelToolingBundle.message("hint.open"))
                            )
                    )
            )
            .toString()
    }

    @Suppress(
        "SpreadOperator"
    ) // HtmlChunk.fragment takes varargs; the state list is at most three.
    fun summaryTooltip(report: FunctionStability): String {
        val muted = ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground())
        val states =
            Stability.entries.map { state ->
                colored(ColorUtil.toHtmlColor(foreground(state)))
                    .addText(
                        JewelToolingBundle.message(
                            "summary.state",
                            report.parameters.count { it.assessment.stability == state },
                            JewelToolingBundle.message(state.messageKey),
                        )
                    )
            }
        return HtmlChunk.tag("html")
            .child(HtmlChunk.tag("b").addText(report.name))
            .child(HtmlChunk.br())
            .child(
                HtmlChunk.text(counts(report)).takeIf { report.parameters.isEmpty() }
                    ?: HtmlChunk.fragment(
                        *states
                            .flatMapIndexed { index, chunk ->
                                if (index == 0) listOf(chunk)
                                else listOf(HtmlChunk.text(" · "), chunk)
                            }
                            .toTypedArray()
                    )
            )
            .child(HtmlChunk.br())
            .child(HtmlChunk.br())
            .child(
                colored(muted)
                    .child(
                        HtmlChunk.tag("small")
                            .attr("style", "color: $muted")
                            .addText(JewelToolingBundle.message("summary.scope"))
                    )
            )
            .child(HtmlChunk.br())
            .child(
                colored(muted)
                    .child(
                        HtmlChunk.tag("small")
                            .attr("style", "color: $muted")
                            .addText(JewelToolingBundle.message("summary.open"))
                    )
            )
            .toString()
    }

    private fun colored(color: String): HtmlChunk.Element =
        HtmlChunk.tag("span").attr("style", "color: $color")

    fun overall(report: FunctionStability): Stability =
        when {
            report.parameters.any { it.assessment.stability == Stability.UNSTABLE } ->
                Stability.UNSTABLE
            report.parameters.isEmpty() ||
                report.parameters.any { it.assessment.stability == Stability.UNKNOWN } ->
                Stability.UNKNOWN
            else -> Stability.STABLE
        }

    fun evidence(assessment: StabilityAssessment): String {
        val names =
            Evidence.entries
                .filter { it in assessment.evidence }
                .joinToString(" · ") { JewelToolingBundle.message(it.messageKey) }
        val key =
            if (Evidence.COMPILER_METADATA in assessment.evidence && assessment.evidence.size > 1)
                "details.mixedEvidence"
            else "details.evidence"
        return JewelToolingBundle.message(key, names)
    }

    fun counts(report: FunctionStability): String =
        if (report.parameters.isEmpty()) JewelToolingBundle.message("summary.empty")
        else
            JewelToolingBundle.message(
                "summary.counts",
                report.parameters.count { it.assessment.stability == Stability.STABLE },
                report.parameters.count { it.assessment.stability == Stability.UNSTABLE },
                report.parameters.count { it.assessment.stability == Stability.UNKNOWN },
            )
}

/** Native, selectable text: source names and types are never interpreted as HTML. */
@Suppress("MagicNumber") // Fixed, scaled spacing and viewport sizes for this native popup.
internal class StabilityDetailsPanel(
    report: FunctionStability,
    navigate: ((SmartPsiElementPointer<KtNamedDeclaration>) -> Unit)? = null,
) {
    val component: JComponent
    val focus: JComponent

    init {
        val rows = WrappingPanel()
        val title = text(report.name).apply { font = font.deriveFont(Font.BOLD, font.size2D + 2) }
        rows.add(section(title, 0, 10))
        if (report.parameters.isNotEmpty()) {
            val counts =
                JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(16), 0)).apply { isOpaque = false }
            for (state in Stability.entries) {
                val count = report.parameters.count { it.assessment.stability == state }
                counts.add(
                    label("$count ${JewelToolingBundle.message(state.messageKey)}").apply {
                        icon = StabilityPresentation.icon(state)
                        foreground = StabilityPresentation.foreground(state)
                    }
                )
            }
            rows.add(section(counts, 0, 14))
        }
        for (parameter in report.parameters) {
            val row = JPanel(BorderLayout(JBUI.scale(12), JBUI.scale(6))).apply { isOpaque = false }
            val heading =
                text("${parameter.name}: ${parameter.typeText}").apply {
                    font =
                        EditorColorsManager.getInstance()
                            .globalScheme
                            .getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN)
                }
            val state = parameter.assessment.stability
            val status =
                label(StabilityPresentation.title(state)).apply {
                    icon =
                        StabilityStateIcon(parameter.assessment) {
                            EditorColorsManager.getInstance().globalScheme
                        }
                    foreground = StabilityPresentation.foreground(state)
                    verticalAlignment = SwingConstants.TOP
                }
            val header =
                JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
                    isOpaque = false
                    add(heading, BorderLayout.CENTER)
                    add(status, BorderLayout.EAST)
                }
            val explanation = text(parameter.assessment.reason)
            row.add(header, BorderLayout.NORTH)
            val body =
                JPanel().apply {
                    isOpaque = false
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(explanation)
                }
            row.add(body, BorderLayout.CENTER)
            val target = parameter.assessment.sourceTarget
            if (target != null && navigate != null) {
                val button =
                    ActionLink(JewelToolingBundle.message("details.navigate")).apply {
                        putClientProperty("html.disable", true)
                        accessibleContext.accessibleName =
                            JewelToolingBundle.message(
                                "details.navigate.accessible",
                                parameter.name,
                            )
                        addActionListener { navigate(target) }
                    }
                val actions =
                    JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply {
                        isOpaque = false
                        add(button)
                    }
                body.add(section(actions, 8, 0))
            }
            if (parameter.assessment.evidence.isNotEmpty()) {
                row.add(
                    secondary(StabilityPresentation.evidence(parameter.assessment)),
                    BorderLayout.SOUTH,
                )
            }
            row.border =
                JBUI.Borders.compound(
                    JBUI.Borders.customLineBottom(JBUI.CurrentTheme.Popup.separatorColor()),
                    JBUI.Borders.empty(12, 0),
                )
            rows.add(row)
        }
        val empty = text(JewelToolingBundle.message("summary.empty"))
        if (report.parameters.isEmpty()) rows.add(section(empty, 0, 12))
        rows.add(section(secondary(JewelToolingBundle.message("details.scope")), 16, 8))
        rows.add(section(secondary(JewelToolingBundle.message("details.limit")), 0, 8))
        rows.add(section(secondary(JewelToolingBundle.message("details.footer")), 0, 0))
        rows.border = JBUI.Borders.empty(16)
        val scroll =
            JBScrollPane(rows).apply {
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                preferredSize =
                    JBUI.size(
                        580,
                        minOf(
                            520,
                            210 +
                                report.parameters.size * 105 +
                                report.parameters.count {
                                    it.assessment.sourceTarget != null && navigate != null
                                } * 36,
                        ),
                    )
            }
        component =
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                name = "jewel.stability.details"
                accessibleContext.accessibleName =
                    JewelToolingBundle.message("details.parameters", report.name)
                add(scroll, BorderLayout.CENTER)
            }
        focus = title
    }

    private fun secondary(value: String) =
        text(value).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = JBUI.Fonts.smallFont()
        }

    private fun label(value: String) =
        JBLabel(value).apply {
            putClientProperty("html.disable", true)
            accessibleContext.accessibleName = value
        }

    private fun text(value: String) =
        JBTextArea().apply {
            isEditable = false
            // Set the policy before inserting text: constructor text can queue a later caret
            // scroll.
            (caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE
            text = value
            focusTraversalKeysEnabled = true
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            font = UIUtil.getLabelFont()
            foreground = UIUtil.getLabelForeground()
            border = JBUI.Borders.empty()
            accessibleContext.accessibleName = value
        }

    private fun section(child: JComponent, top: Int, bottom: Int) =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(top, 0, bottom, 0)
            add(child, BorderLayout.CENTER)
        }

    private class WrappingPanel : JPanel(), Scrollable {
        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

        override fun getScrollableTracksViewportWidth(): Boolean = true

        override fun getScrollableTracksViewportHeight(): Boolean = false

        override fun getScrollableUnitIncrement(
            visibleRect: Rectangle,
            orientation: Int,
            direction: Int,
        ): Int = JBUI.scale(16)

        override fun getScrollableBlockIncrement(
            visibleRect: Rectangle,
            orientation: Int,
            direction: Int,
        ): Int = visibleRect.height
    }
}
