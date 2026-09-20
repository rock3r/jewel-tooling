package dev.sebastiano.jewel.tooling

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

internal object ComposeInspectionIcons {
    val compose: Icon by lazy {
        IconLoader.getIcon("/icons/compose.svg", ComposeInspectionIcons::class.java)
    }

    val runWithCompose: Icon by lazy {
        IconLoader.getIcon("/icons/runWithCompose.svg", ComposeInspectionIcons::class.java)
    }
}
