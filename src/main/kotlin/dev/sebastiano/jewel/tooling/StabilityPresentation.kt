package dev.sebastiano.jewel.tooling

import com.intellij.icons.AllIcons
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Rectangle
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.text.DefaultCaret
import org.jetbrains.kotlin.psi.KtNamedDeclaration

internal object StabilityPresentation {
  fun icon(stability: Stability): Icon =
    when (stability) {
      Stability.STABLE -> AllIcons.General.InspectionsOK
      Stability.UNSTABLE -> AllIcons.General.Warning
      Stability.UNKNOWN -> AllIcons.General.ContextHelp
    }

  fun overall(report: FunctionStability): Stability =
    when {
      report.parameters.any { it.assessment.stability == Stability.UNSTABLE } -> Stability.UNSTABLE
      report.parameters.isEmpty() ||
        report.parameters.any { it.assessment.stability == Stability.UNKNOWN } -> Stability.UNKNOWN
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
        *Stability.entries
          .map { state -> report.parameters.count { it.assessment.stability == state } }
          .toTypedArray(),
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
          }
        )
      }
      rows.add(section(counts, 0, 14))
    }
    for (parameter in report.parameters) {
      val row = JPanel(BorderLayout(JBUI.scale(12), JBUI.scale(6))).apply { isOpaque = false }
      val heading =
        text("${parameter.name}: ${parameter.typeText}").apply {
          font = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().size)
        }
      val state = parameter.assessment.stability
      val status =
        label(JewelToolingBundle.message(state.messageKey)).apply {
          icon = StabilityPresentation.icon(state)
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
          JButton(JewelToolingBundle.message("details.navigate")).apply {
            putClientProperty("html.disable", true)
            accessibleContext.accessibleName =
              JewelToolingBundle.message("details.navigate.accessible", parameter.name)
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
          text(StabilityPresentation.evidence(parameter.assessment)).apply {
            foreground = UIUtil.getContextHelpForeground()
          },
          BorderLayout.SOUTH,
        )
      }
      row.border =
        JBUI.Borders.compound(
          JBUI.Borders.customLineBottom(
            JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
          ),
          JBUI.Borders.empty(12, 0),
        )
      rows.add(row)
    }
    val empty = text(JewelToolingBundle.message("summary.empty"))
    if (report.parameters.isEmpty()) rows.add(section(empty, 0, 12))
    rows.add(section(text(JewelToolingBundle.message("details.scope")), 16, 8))
    rows.add(section(text(JewelToolingBundle.message("details.limit")), 0, 8))
    rows.add(section(text(JewelToolingBundle.message("details.footer")), 0, 0))
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
                report.parameters.count { it.assessment.sourceTarget != null && navigate != null } *
                  36,
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

  private fun label(value: String) =
    JBLabel(value).apply {
      putClientProperty("html.disable", true)
      accessibleContext.accessibleName = value
    }

  private fun text(value: String) =
    JBTextArea().apply {
      isEditable = false
      // Set the policy before inserting text: constructor text can queue a later caret scroll.
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
