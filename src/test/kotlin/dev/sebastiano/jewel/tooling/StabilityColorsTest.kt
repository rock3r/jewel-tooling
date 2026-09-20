package dev.sebastiano.jewel.tooling

import com.intellij.codeInsight.hints.InlayHintsSettings
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.codeInsight.hints.declarative.DeclarativeInlayHintsSettings
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Color
import java.awt.image.BufferedImage
import org.jetbrains.kotlin.idea.KotlinLanguage

class StabilityColorsTest : BasePlatformTestCase() {
    fun testOnlyCompilerConfirmedResultsHaveBorders() {
        for (state in Stability.entries) {
            for (evidence in
                listOf(
                    emptySet(),
                    setOf(Evidence.BUILTIN),
                    setOf(Evidence.SOURCE),
                    setOf(Evidence.DECLARED_CONTRACT),
                    setOf(Evidence.COMPILER_METADATA),
                    setOf(Evidence.COMPILER_METADATA, Evidence.BUILTIN),
                    setOf(Evidence.COMPILER_METADATA, Evidence.SOURCE),
                    setOf(Evidence.COMPILER_METADATA, Evidence.UNSUPPORTED),
                )) {
                val assessment = StabilityAssessment(state, "", evidence)
                assertEquals(
                    "$state $evidence",
                    state != Stability.UNKNOWN && evidence == setOf(Evidence.COMPILER_METADATA),
                    StabilityColors.isCompilerConfirmed(assessment),
                )
            }
        }
    }

    fun testCircleUsesSchemeOverridesAtPaintTimeAndScalesItsBorder() {
        val scheme = EditorColorsManager.getInstance().globalScheme.clone() as EditorColorsScheme
        val keys = StabilityColors.palettes.getValue(Stability.STABLE)
        scheme.setColor(keys.fill, Color.GREEN)
        scheme.setColor(keys.border, Color.RED)
        val estimate =
            StabilityStateIcon(StabilityAssessment(Stability.STABLE, "", setOf(Evidence.SOURCE))) {
                scheme
            }
        val confirmed =
            StabilityStateIcon(
                StabilityAssessment(Stability.STABLE, "", setOf(Evidence.COMPILER_METADATA))
            ) {
                scheme
            }
        for (scale in listOf(1, 2)) {
            val plain = render(estimate, scale)
            val bordered = render(confirmed, scale)
            assertEquals(Color.GREEN.rgb, plain.getRGB(plain.width / 2, plain.height / 2))
            assertEquals(Color.GREEN.rgb, bordered.getRGB(bordered.width / 2, bordered.height / 2))
            assertEquals(0, redPixels(plain))
            assertTrue(redPixels(bordered) > 0)
            assertEquals("Circle corners must remain transparent", 0, plain.getRGB(0, 0))
        }
        scheme.setColor(keys.fill, Color.BLUE)
        val repainted = render(estimate, 2)
        assertEquals(Color.BLUE.rgb, repainted.getRGB(repainted.width / 2, repainted.height / 2))
        assertEquals(
            8,
            StabilityColorSettingsPage().colorDescriptors.map { it.key }.distinct().size,
        )
    }

    fun testDefaultSchemesHaveDistinctMarkerColors() {
        val manager = EditorColorsManager.getInstance()
        val light = requireNotNull(manager.getScheme("Default"))
        val dark = requireNotNull(manager.getScheme("Darcula"))
        for ((state, palette) in StabilityColors.palettes) {
            assertEquals(palette.light, StabilityColors.color(light, state))
            assertEquals(palette.dark, StabilityColors.color(dark, state))
            assertEquals(palette.lightBorder, StabilityColors.color(light, state, border = true))
            assertEquals(palette.darkBorder, StabilityColors.color(dark, state, border = true))
        }
    }

    fun testMigrationRunsOnceAndPreservesLaterUserChoices() = verifyMigration()

    fun testMigrationReenablesHintsDisabledByTheFirstPresentationPass() = verifyV1Repair()

    private fun verifyV1Repair() {
        WriteAction.run<RuntimeException> {
            val properties = PropertiesComponent.getInstance()
            val settings = InlayHintsSettings.instance()
            val marker = properties.getValue(StabilityInlayMigration.MARKER)
            val markerV2 = properties.getValue(StabilityInlayMigration.MARKER_V2)
            val previous = settings.state
            val key = SettingsKey<NoSettings>(StabilityInlayProvider.ID)
            val language = KotlinLanguage.INSTANCE
            try {
                properties.unsetValue(StabilityInlayMigration.MARKER_V2)
                properties.setValue(StabilityInlayMigration.MARKER, true)
                settings.loadState(InlayHintsSettings.State())
                settings.changeHintTypeStatus(key, language, false)
                DeclarativeInlayHintsSettings.getInstance()
                    .state
                    .providerIdToEnabled
                    .remove(StabilityInlayProvider.ID)
                assertTrue(StabilityInlayMigration.migrate())
                assertTrue(settings.hintsEnabled(key, language))
                assertTrue(StabilityInlayMigration.isComplete())
                assertFalse(StabilityInlayMigration.migrate())
            } finally {
                settings.loadState(previous)
                properties.setValue(StabilityInlayMigration.MARKER, marker)
                properties.setValue(StabilityInlayMigration.MARKER_V2, markerV2)
            }
        }
    }

    private fun verifyMigration() {
        WriteAction.run<RuntimeException> {
            val properties = PropertiesComponent.getInstance()
            val legacy = DeclarativeInlayHintsSettings.getInstance()
            val settings = InlayHintsSettings.instance()
            val marker = properties.getValue(StabilityInlayMigration.MARKER)
            val markerV2 = properties.getValue(StabilityInlayMigration.MARKER_V2)
            val previous = settings.state
            val oldLegacy = legacy.isProviderEnabled(StabilityInlayProvider.ID)
            val key = SettingsKey<NoSettings>(StabilityInlayProvider.ID)
            val language = KotlinLanguage.INSTANCE
            try {
                for (old in listOf(null, true, false)) {
                    properties.unsetValue(StabilityInlayMigration.MARKER)
                    properties.unsetValue(StabilityInlayMigration.MARKER_V2)
                    settings.loadState(InlayHintsSettings.State())
                    if (old == null)
                        legacy.state.providerIdToEnabled.remove(StabilityInlayProvider.ID)
                    else legacy.setProviderEnabled(StabilityInlayProvider.ID, old)
                    assertTrue(StabilityInlayMigration.migrate())
                    assertEquals(old != false, settings.hintsEnabled(key, language))
                    assertEquals(old, legacy.isProviderEnabled(StabilityInlayProvider.ID))
                    settings.changeHintTypeStatus(key, language, old == false)
                    assertFalse(StabilityInlayMigration.migrate())
                    assertEquals(old == false, settings.hintsEnabled(key, language))
                }
                properties.unsetValue(StabilityInlayMigration.MARKER)
                properties.unsetValue(StabilityInlayMigration.MARKER_V2)
                settings.loadState(InlayHintsSettings.State())
                settings.changeHintTypeStatus(key, language, false)
                legacy.setProviderEnabled(StabilityInlayProvider.ID, true)
                StabilityInlayMigration.migrate()
                assertFalse(settings.hintsEnabled(key, language))
            } finally {
                settings.loadState(previous)
                properties.setValue(StabilityInlayMigration.MARKER, marker)
                properties.setValue(StabilityInlayMigration.MARKER_V2, markerV2)
                if (oldLegacy == null)
                    legacy.state.providerIdToEnabled.remove(StabilityInlayProvider.ID)
                else legacy.setProviderEnabled(StabilityInlayProvider.ID, oldLegacy)
            }
        }
    }

    private fun render(icon: StabilityStateIcon, scale: Int): BufferedImage {
        val image =
            BufferedImage(
                icon.iconWidth * scale,
                icon.iconHeight * scale,
                BufferedImage.TYPE_INT_ARGB,
            )
        val graphics = image.createGraphics()
        try {
            graphics.scale(scale.toDouble(), scale.toDouble())
            icon.paintIcon(null, graphics, 0, 0)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun redPixels(image: BufferedImage): Int =
        (0 until image.width).sumOf { x ->
            (0 until image.height).count { y ->
                val color = Color(image.getRGB(x, y), true)
                color.alpha > 0 && color.red > color.green
            }
        }
}
