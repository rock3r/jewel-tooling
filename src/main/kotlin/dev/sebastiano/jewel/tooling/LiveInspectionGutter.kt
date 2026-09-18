package dev.sebastiano.jewel.tooling

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.ActiveGutterRenderer
import com.intellij.openapi.editor.markup.LineMarkerRendererEx
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseEvent

@Suppress("MagicNumber") // Default gutter colours and logical badge insets.
internal object LiveInspectionGutter {
  val text = ColorKey.createColorKey("JEWEL_LIVE_GUTTER")
  val hotText = ColorKey.createColorKey("JEWEL_LIVE_GUTTER_HOT")

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
    ColorUtil.mix(scheme.defaultBackground, foreground(scheme, hot), if (hot) 0.38 else 0.16)

  fun badgeWidth(editor: Editor, hint: LiveEditorHint): Int {
    val font =
      editor.colorsScheme.getFont(if (hint.hot) EditorFontType.BOLD else EditorFontType.PLAIN)
    val text = editor.component.getFontMetrics(font).stringWidth(hint.text)
    val mark = if (hint.hot) JBUI.scale(HOT_DOT + HOT_DOT_GAP) else 0
    return text + mark + JBUI.scale(H_GAP + H_RIGHT)
  }
}

internal class LiveInspectionGutterRenderer(private val hint: LiveEditorHint) :
  LineMarkerRendererEx, ActiveGutterRenderer {
  override fun getPosition(): LineMarkerRendererEx.Position = LineMarkerRendererEx.Position.RIGHT

  override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
    val g2 = g.create() as Graphics2D
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      val scheme = editor.colorsScheme
      val bounds = calcBounds(editor, 0, r)
      val vGap = JBUI.scale(V_GAP)
      val height = (bounds.height - 2 * vGap).coerceAtLeast(1)
      g2.color = LiveInspectionGutter.badge(scheme, hint.hot)
      g2.fillRoundRect(bounds.x, bounds.y + vGap, bounds.width, height, height, height)
      val font = scheme.getFont(if (hint.hot) EditorFontType.BOLD else EditorFontType.PLAIN)
      g2.font = font
      g2.color = LiveInspectionGutter.foreground(scheme, hint.hot)
      val metrics = g2.fontMetrics
      var x = bounds.x + JBUI.scale(H_GAP)
      val y = bounds.y + (bounds.height + metrics.ascent - metrics.descent) / 2
      if (hint.hot) {
        val mark = JBUI.scale(HOT_DOT)
        g2.fillOval(x, bounds.y + (bounds.height - mark) / 2, mark, mark)
        x += mark + JBUI.scale(HOT_DOT_GAP)
      }
      g2.drawString(hint.text, x, y)
    } finally {
      g2.dispose()
    }
  }

  override fun calcBounds(editor: Editor, lineNum: Int, r: Rectangle): Rectangle {
    val width = LiveInspectionGutter.badgeWidth(editor, hint).coerceAtMost(r.width)
    return Rectangle(r.x + r.width - width, r.y, width, r.height)
  }

  override fun getTooltipText(): String = hint.tooltip

  override fun getAccessibleName(): String = hint.text

  override fun getAccessibleTooltipText(): String = hint.accessible

  override fun canDoAction(e: MouseEvent): Boolean = true

  override fun canDoAction(editor: Editor, e: MouseEvent): Boolean = true

  override fun doAction(editor: Editor, e: MouseEvent) {
    val project = editor.project ?: return
    e.consume()
    ToolWindowManager.getInstance(project).getToolWindow("Compose Inspection")?.show()
    project.service<LiveInspectionService>().selectSite(hint.siteId)
  }
}

private const val H_GAP = 6
private const val H_RIGHT = 6
private const val V_GAP = 1
private const val HOT_DOT = 6
private const val HOT_DOT_GAP = 3
