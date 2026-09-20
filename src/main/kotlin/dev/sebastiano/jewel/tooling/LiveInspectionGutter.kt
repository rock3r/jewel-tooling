package dev.sebastiano.jewel.tooling

import com.intellij.codeInsight.hints.presentation.InputHandler
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

@Suppress("MagicNumber") // Default gutter colours and logical badge insets.
internal object LiveInspectionGutter {
    val text = ColorKey.createColorKey("JEWEL_LIVE_GUTTER")
    val hotText = ColorKey.createColorKey("JEWEL_LIVE_GUTTER_HOT")
    private val cursorRequestor = Any()
    private val hand = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

    private val light = Color(0x6B7380)
    private val dark = Color(0x9AA4B2)
    private val lightHot = Color(0xC45C5C)
    private val darkHot = Color(0xE08B8B)

    fun tooltip(share: Int, executions: Int): String {
        val muted = ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground())
        return HtmlChunk.tag("html")
            .child(
                HtmlChunk.div("font-weight: normal")
                    .child(
                        HtmlChunk.tag("b")
                            .addText(JewelToolingBundle.message("live.editor.tooltip.share", share))
                    )
                    .child(HtmlChunk.nbsp())
                    .child(HtmlChunk.nbsp())
                    .child(
                        HtmlChunk.tag("b")
                            .addText(
                                JewelToolingBundle.message(
                                    "live.editor.tooltip.executions",
                                    LiveInspectionFormat.integer(executions),
                                )
                            )
                    )
                    .child(HtmlChunk.br())
                    .child(
                        HtmlChunk.tag("span")
                            .attr("style", "color: $muted; font-size: smaller")
                            .addText(JewelToolingBundle.message("live.editor.tooltip.open"))
                    )
            )
            .toString()
    }

    fun foreground(scheme: EditorColorsScheme, hot: Boolean): Color {
        val key = if (hot) hotText else text
        return scheme.getColor(key)
            ?: if (ColorUtil.isDark(scheme.defaultBackground)) {
                if (hot) darkHot else dark
            } else {
                if (hot) lightHot else light
            }
    }

    fun badge(scheme: EditorColorsScheme, hot: Boolean): Color =
        ColorUtil.mix(scheme.defaultBackground, foreground(scheme, hot), if (hot) 0.38 else 0.22)

    fun badgeWidth(editor: Editor, hint: LiveEditorHint): Int {
        val font =
            editor.colorsScheme.getFont(if (hint.hot) EditorFontType.BOLD else EditorFontType.PLAIN)
        val text = editor.component.getFontMetrics(font).stringWidth(hint.text)
        val mark = if (hint.hot) JBUI.scale(HOT_DOT + HOT_DOT_GAP) else 0
        return text + mark + JBUI.scale(H_GAP + H_RIGHT)
    }

    fun rendererAt(event: EditorMouseEvent): LiveInspectionDurationRenderer? {
        if (event.area != EditorMouseEventArea.EDITING_AREA) return null
        (event.inlay?.renderer as? LiveInspectionDurationRenderer)?.let {
            return it
        }
        return rendererAt(event.editor, event.mouseEvent)
    }

    fun rendererAt(editor: Editor, event: MouseEvent): LiveInspectionDurationRenderer? {
        val point =
            SwingUtilities.convertPoint(event.component, event.point, editor.contentComponent)
        return rendererAt(editor, point)
    }

    fun rendererAt(editor: Editor, point: Point): LiveInspectionDurationRenderer? {
        (editor.inlayModel.getElementAt(point)?.renderer as? LiveInspectionDurationRenderer)?.let {
            return it
        }
        val line = editor.visualToLogicalPosition(editor.xyToVisualPosition(point)).line
        if (line !in 0 until editor.document.lineCount) return null
        for (inlay in editor.inlayModel.getAfterLineEndElementsForLogicalLine(line)) {
            val bounds = inlay.bounds ?: continue
            if (bounds.contains(point)) {
                return inlay.renderer as? LiveInspectionDurationRenderer
            }
        }
        return null
    }

    fun showHandCursor(editor: Editor) {
        (editor as? EditorEx)?.setCustomCursor(cursorRequestor, hand)
    }

    fun clearHandCursor(editor: Editor) {
        (editor as? EditorEx)?.setCustomCursor(cursorRequestor, null)
    }
}

internal class LiveInspectionDurationRenderer(
    private val hint: LiveEditorHint,
    private val editor: Editor,
) : EditorCustomElementRenderer, InputHandler {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int =
        JBUI.scale(LEADING) + LiveInspectionGutter.badgeWidth(inlay.editor, hint)

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes,
    ) {
        val editor = inlay.editor
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val scheme = editor.colorsScheme
            val leading = JBUI.scale(LEADING)
            val x = targetRegion.x + leading
            val width = (targetRegion.width - leading).coerceAtLeast(1)
            val vGap = JBUI.scale(V_GAP)
            val height = (targetRegion.height - 2 * vGap).coerceAtLeast(1)
            g2.color = LiveInspectionGutter.badge(scheme, hint.hot)
            g2.fillRoundRect(x, targetRegion.y + vGap, width, height, height, height)
            val font = scheme.getFont(if (hint.hot) EditorFontType.BOLD else EditorFontType.PLAIN)
            g2.font = font
            g2.color = LiveInspectionGutter.foreground(scheme, hint.hot)
            val metrics = g2.fontMetrics
            var textX = x + JBUI.scale(H_GAP)
            val textY =
                targetRegion.y + (targetRegion.height + metrics.ascent - metrics.descent) / 2
            if (hint.hot) {
                val mark = JBUI.scale(HOT_DOT)
                g2.fillOval(textX, targetRegion.y + (targetRegion.height - mark) / 2, mark, mark)
                textX += mark + JBUI.scale(HOT_DOT_GAP)
            }
            g2.drawString(hint.text, textX, textY)
        } finally {
            g2.dispose()
        }
    }

    override fun mousePressed(event: MouseEvent, translated: Point) = click(event)

    override fun mouseClicked(event: MouseEvent, translated: Point) = click(event)

    override fun mouseMoved(event: MouseEvent, translated: Point) {
        LiveInspectionGutter.showHandCursor(editor)
    }

    override fun mouseExited() {
        LiveInspectionGutter.clearHandCursor(editor)
    }

    private fun click(event: MouseEvent) {
        if (event.button != MouseEvent.BUTTON1) return
        event.consume()
        activate()
    }

    fun activate() {
        val project = editor.project ?: return
        if (project.isDisposed || editor.isDisposed) return
        val window =
            ToolWindowManager.getInstance(project).getToolWindow("Compose Inspection") ?: return
        window.show {
            if (!project.isDisposed)
                project.service<LiveInspectionService>().selectSite(hint.siteId)
        }
    }

    fun tooltip(): String = hint.tooltip
}

private const val LEADING = 8
private const val H_GAP = 6
private const val H_RIGHT = 6
private const val V_GAP = 1
private const val HOT_DOT = 6
private const val HOT_DOT_GAP = 3
