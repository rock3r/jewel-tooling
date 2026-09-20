package dev.sebastiano.jewel.tooling

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.ui.ColorUtil
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import javax.swing.Icon

@Suppress("MagicNumber") // Default marker colours and logical icon dimensions.
internal object StabilityColors {
    data class Palette(
        val fill: ColorKey,
        val border: ColorKey,
        val light: Color,
        val dark: Color,
        val lightBorder: Color,
        val darkBorder: Color,
    )

    val palettes =
        mapOf(
            Stability.STABLE to palette("STABLE", 0x7F9E86, 0x759880, 0x517A5A, 0x9AB7A3),
            Stability.UNSTABLE to palette("UNSTABLE", 0xBF8585, 0xB37D80, 0xA35E5E, 0xD39C9F),
            Stability.UNKNOWN to palette("UNKNOWN", 0x8796A7, 0x8192A7, 0x64798F, 0xA0B1C4),
        )

    private fun palette(name: String, light: Int, dark: Int, lightBorder: Int, darkBorder: Int) =
        Palette(
            ColorKey.createColorKey("JEWEL_STABILITY_${name}_FILL"),
            ColorKey.createColorKey("JEWEL_STABILITY_${name}_BORDER"),
            Color(light),
            Color(dark),
            Color(lightBorder),
            Color(darkBorder),
        )

    fun color(scheme: EditorColorsScheme, state: Stability, border: Boolean = false): Color {
        val palette = palettes.getValue(state)
        return scheme.getColor(if (border) palette.border else palette.fill)
            ?: if (ColorUtil.isDark(scheme.defaultBackground)) {
                if (border) palette.darkBorder else palette.dark
            } else {
                if (border) palette.lightBorder else palette.light
            }
    }

    fun isCompilerConfirmed(assessment: StabilityAssessment): Boolean =
        assessment.stability != Stability.UNKNOWN &&
            assessment.evidence == setOf(Evidence.COMPILER_METADATA)
}

@Suppress("MagicNumber") // A 7-point circle in a 10-point icon; stroke stays inside its bounds.
internal class StabilityStateIcon(
    private val assessment: StabilityAssessment,
    private val scheme: () -> EditorColorsScheme,
) : Icon {
    override fun getIconWidth(): Int = JBUI.scale(10)

    override fun getIconHeight(): Int = JBUI.scale(10)

    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val colors = scheme()
            val size = JBUI.scale(7).toDouble()
            val confirmed = StabilityColors.isCompilerConfirmed(assessment)
            val stroke = JBUIScale.scale(1f)
            val inset = if (confirmed) stroke / 2.0 else 0.0
            val circle =
                Ellipse2D.Double(
                    x + (iconWidth - size) / 2 + inset,
                    y + (iconHeight - size) / 2 + inset,
                    size - 2 * inset,
                    size - 2 * inset,
                )
            g.color = StabilityColors.color(colors, assessment.stability)
            g.fill(circle)
            if (confirmed) {
                g.color = StabilityColors.color(colors, assessment.stability, border = true)
                g.stroke = BasicStroke(stroke)
                g.draw(circle)
            }
        } finally {
            g.dispose()
        }
    }
}
