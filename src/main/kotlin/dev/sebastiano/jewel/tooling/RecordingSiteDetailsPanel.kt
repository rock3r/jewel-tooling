package dev.sebastiano.jewel.tooling

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.sebastiano.jewel.tooling.recording.SiteSummary
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Rectangle
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.text.DefaultCaret

@Suppress("MagicNumber") // Scaled spacing for this native details pane.
internal class RecordingSiteDetailsPanel(
    private val openFile: (TraceSiteLocation) -> Unit,
    private val canOpen: (TraceSiteLocation) -> Boolean,
) {
    private val rows = WrappingPanel()
    val component: JComponent =
        JBScrollPane(rows).apply {
            name = "jewel-recording-details"
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            accessibleContext.accessibleName = JewelToolingBundle.message("recording.site.details")
        }

    fun show(site: SiteSummary?, labels: Map<Long, String>, emptyMessage: String) {
        rows.removeAll()
        if (site == null) rows.add(section(text(emptyMessage), 0, 0)) else bind(site, labels)
        rows.revalidate()
        rows.repaint()
    }

    private fun bind(site: SiteSummary, labels: Map<Long, String>) {
        val location = TraceSiteLocations.parse(site.site.info)
        val declaration = location?.qualifiedName ?: site.site.info
        rows.add(
            section(
                editorText(declaration).apply { font = editorFont().deriveFont(Font.BOLD) },
                0,
                8,
            )
        )
        if (location != null) {
            val fileLabel = "${location.fileName}:${location.line}"
            rows.add(
                section(
                    if (canOpen(location)) fileLink(location, fileLabel) else editorText(fileLabel),
                    0,
                    12,
                )
            )
        }
        addMetrics(site)
        addThreads(site, labels)
    }

    private fun addMetrics(site: SiteSummary) {
        rows.add(
            section(
                metric(
                    JewelToolingBundle.message("recording.details.executions"),
                    LiveInspectionFormat.integer(site.executions),
                ),
                0,
                4,
            )
        )
        rows.add(
            section(
                metric(
                    JewelToolingBundle.message("recording.details.total"),
                    JewelToolingBundle.message(
                        "recording.details.ms",
                        LiveInspectionFormat.milliseconds(site.totalNs / NANOS),
                    ),
                ),
                0,
                4,
            )
        )
        rows.add(
            section(
                metric(
                    JewelToolingBundle.message("recording.details.mean"),
                    JewelToolingBundle.message(
                        "recording.details.ms",
                        LiveInspectionFormat.milliseconds(site.meanNs / NANOS),
                    ),
                ),
                0,
                12,
            )
        )
        rows.add(
            section(
                secondary(
                    JewelToolingBundle.message("recording.details.session", site.site.id.toString())
                ),
                0,
                2,
            )
        )
        rows.add(
            section(
                secondary(
                    JewelToolingBundle.message("recording.details.key", site.site.key.toString())
                ),
                0,
                14,
            )
        )
    }

    private fun addThreads(site: SiteSummary, labels: Map<Long, String>) {
        rows.add(
            section(
                JBLabel(JewelToolingBundle.message("recording.threads")).apply {
                    putClientProperty("html.disable", true)
                    font = font.deriveFont(Font.BOLD)
                },
                0,
                6,
            )
        )
        if (site.threads.isEmpty()) {
            rows.add(
                section(secondary(JewelToolingBundle.message("recording.threads.empty")), 0, 0)
            )
            return
        }
        site.threads.toSortedMap().forEach { (id, count) ->
            rows.add(
                section(
                    metric(
                        JewelToolingBundle.message(
                            "recording.details.thread",
                            labels[id].orEmpty().ifBlank {
                                JewelToolingBundle.message("recording.unknown")
                            },
                            id.toString(),
                        ),
                        LiveInspectionFormat.integer(count),
                    ),
                    0,
                    4,
                )
            )
        }
    }

    private fun fileLink(location: TraceSiteLocation, label: String): JComponent {
        val link =
            ActionLink(label).apply {
                putClientProperty("html.disable", true)
                font = editorFont()
                accessibleContext.accessibleName =
                    JewelToolingBundle.message("recording.details.file.accessible", label)
                addActionListener { openFile(location) }
            }
        return JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply {
            isOpaque = false
            add(link)
        }
    }

    private fun metric(label: String, value: String): JComponent =
        JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            isOpaque = false
            add(
                JBLabel(label).apply {
                    putClientProperty("html.disable", true)
                    foreground = UIUtil.getContextHelpForeground()
                },
                BorderLayout.WEST,
            )
            add(
                JBLabel(value).apply {
                    putClientProperty("html.disable", true)
                    horizontalAlignment = SwingConstants.TRAILING
                },
                BorderLayout.CENTER,
            )
        }

    private fun secondary(value: String) =
        text(value).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = JBUI.Fonts.smallFont()
        }

    private fun editorText(value: String) = text(value).apply { font = editorFont() }

    private fun editorFont(): Font =
        EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)

    private fun text(value: String): JTextArea =
        JBTextArea().apply {
            isEditable = false
            (caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE
            text = value
            focusTraversalKeysEnabled = true
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            font = UIUtil.getLabelFont()
            foreground = UIUtil.getLabelForeground()
            border = JBUI.Borders.empty()
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
            isOpaque = false
            border = JBUI.Borders.empty(8)
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

    companion object {
        private const val NANOS = 1_000_000.0
    }
}
