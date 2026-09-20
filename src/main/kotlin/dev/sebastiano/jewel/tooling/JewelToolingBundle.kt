package dev.sebastiano.jewel.tooling

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

internal object JewelToolingBundle : DynamicBundle("messages.JewelToolingBundle") {
    @Nls
    fun message(
        @PropertyKey(resourceBundle = "messages.JewelToolingBundle") key: String,
        vararg params: Any,
    ): String = getMessage(key, *params)
}
