package dev.sebastiano.jewel.tooling

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RecordingPlatformTest : BasePlatformTestCase() {
    fun testPlatformJacksonApiIsAvailable() {
        val descriptor =
            checkNotNull(
                PluginManagerCore.getPlugin(PluginId.getId("dev.sebastiano.jewel.tooling"))
            )
        val loader = checkNotNull(descriptor.pluginClassLoader)
        val version =
            loader
                .loadClass("com.fasterxml.jackson.core.json.PackageVersion")
                .getField("VERSION")
                .get(null)
        assertEquals("2.19.0", version.toString())
        val constraints = loader.loadClass("com.fasterxml.jackson.core.StreamReadConstraints")
        val builder = constraints.getMethod("builder").invoke(null)
        builder.javaClass
            .getMethod("maxNestingDepth", Int::class.javaPrimitiveType)
            .invoke(builder, 8)
        val built = builder.javaClass.getMethod("build").invoke(builder)
        assertEquals(8, constraints.getMethod("getMaxNestingDepth").invoke(built))
        assertNotNull(loader.loadClass("com.fasterxml.jackson.core.JsonFactory"))
    }
}
