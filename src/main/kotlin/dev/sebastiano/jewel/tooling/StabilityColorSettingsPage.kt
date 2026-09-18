package dev.sebastiano.jewel.tooling

import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import java.util.Locale

internal class StabilityColorSettingsPage : ColorSettingsPage {
  override fun getDisplayName(): String = JewelToolingBundle.message("colors.name")

  override fun getIcon() = StabilityPresentation.icon(Stability.STABLE)

  override fun getHighlighter() = PlainSyntaxHighlighter()

  override fun getDemoText(): String = ""

  override fun getAdditionalHighlightingTagToDescriptorMap() = null

  override fun getAttributeDescriptors(): Array<AttributesDescriptor> = emptyArray()

  override fun getColorDescriptors(): Array<ColorDescriptor> {
    val stability =
      Stability.entries.flatMap { state ->
        val palette = StabilityColors.palettes.getValue(state)
        val name = state.name.lowercase(Locale.ROOT)
        listOf(
          ColorDescriptor(
            JewelToolingBundle.message("colors.$name.fill"),
            palette.fill,
            ColorDescriptor.Kind.FOREGROUND,
          ),
          ColorDescriptor(
            JewelToolingBundle.message("colors.$name.border"),
            palette.border,
            ColorDescriptor.Kind.FOREGROUND,
          ),
        )
      }
    val live =
      listOf(
        ColorDescriptor(
          JewelToolingBundle.message("colors.live.gutter"),
          LiveInspectionGutter.text,
          ColorDescriptor.Kind.FOREGROUND,
        ),
        ColorDescriptor(
          JewelToolingBundle.message("colors.live.gutter.hot"),
          LiveInspectionGutter.hotText,
          ColorDescriptor.Kind.FOREGROUND,
        ),
      )
    return (stability + live).toTypedArray()
  }
}
