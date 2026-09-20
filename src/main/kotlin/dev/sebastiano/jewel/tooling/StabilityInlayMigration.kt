package dev.sebastiano.jewel.tooling

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.InlayHintsPassFactoryInternal
import com.intellij.codeInsight.hints.InlayHintsSettings
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.codeInsight.hints.declarative.DeclarativeInlayHintsSettings
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.idea.KotlinLanguage

internal class StabilityInlayMigration : ProjectActivity {
    override suspend fun execute(project: Project) {
        withContext(Dispatchers.EDT) {
            val changed = writeAction { migrate() }
            if (changed) {
                InlayHintsPassFactoryInternal.forceHintsUpdateOnNextPass()
                for (open in ProjectManager.getInstance().openProjects) {
                    if (!open.isDisposed) DaemonCodeAnalyzer.getInstance(open).restart(MARKER_V2)
                }
            }
            if (!project.isDisposed) project.service<StabilityLibraryEditorHints>()
        }
    }

    companion object {
        const val MARKER = "jewel.tooling.presentation.inlays.migrated"
        const val MARKER_V2 = "jewel.tooling.presentation.inlays.migrated.v2"

        fun isComplete(): Boolean = PropertiesComponent.getInstance().getBoolean(MARKER_V2)

        fun migrate(): Boolean {
            val properties = PropertiesComponent.getInstance()
            if (isComplete()) return false
            val settings = InlayHintsSettings.instance()
            val key = SettingsKey<NoSettings>(StabilityInlayProvider.ID)
            val language = KotlinLanguage.INSTANCE
            val id = key.getFullId(language)
            val state = settings.state
            val explicitLegacy =
                DeclarativeInlayHintsSettings.getInstance()
                    .state
                    .providerIdToEnabled[StabilityInlayProvider.ID]
            val alreadyConfigured =
                id in state.enabledHintProviderIds || id in state.disabledHintProviderIds
            val repairV1Disable =
                properties.getBoolean(MARKER) &&
                    explicitLegacy != false &&
                    !settings.hintsEnabled(key, language)
            if (!alreadyConfigured && explicitLegacy == false) {
                settings.changeHintTypeStatus(key, language, false)
            } else if (repairV1Disable) {
                settings.changeHintTypeStatus(key, language, true)
            }
            properties.setValue(MARKER, true)
            properties.setValue(MARKER_V2, true)
            return true
        }
    }
}
