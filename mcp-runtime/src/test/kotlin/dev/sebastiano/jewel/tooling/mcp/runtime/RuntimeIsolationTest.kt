package dev.sebastiano.jewel.tooling.mcp.runtime

import java.net.URLClassLoader
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.function.BiFunction
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeIsolationTest {
    @Test(timeout = 20000)
    fun bundledRuntimeUsesOwnKotlinAndOnlyJdkBoundaryTypes() {
        val before = Thread.getAllStackTraces().keys
        URLClassLoader(
                arrayOf(Path.of(System.getProperty("jewel.mcp.runtime.jar")).toUri().toURL()),
                ClassLoader.getPlatformClassLoader(),
            )
            .use { loader ->
                assertSame(loader, loader.loadClass("kotlin.Unit").classLoader)
                assertSame(loader, loader.loadClass("kotlinx.coroutines.Job").classLoader)
                assertSame(
                    loader,
                    loader
                        .loadClass("io.modelcontextprotocol.kotlin.sdk.server.Server")
                        .classLoader,
                )
                val type =
                    loader.loadClass("dev.sebastiano.jewel.tooling.mcp.runtime.RuntimeServer")
                val start =
                    type.getMethod(
                        "start",
                        Map::class.java,
                        String::class.java,
                        BiFunction::class.java,
                    )
                val callback =
                    BiFunction<String, String, CompletableFuture<String>> { _, _ ->
                        CompletableFuture.completedFuture("{}")
                    }
                val server =
                    start.invoke(
                        null,
                        mapOf(
                            "token" to "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG",
                            "pluginVersion" to "test",
                        ),
                        "[]",
                        callback,
                    ) as AutoCloseable
                assertTrue(type.getMethod("getPort").invoke(server) as Int > 0)
                server.close()
                server.close()
                val deadline = System.nanoTime() + 3_000_000_000L
                var remaining: List<Thread>
                do {
                    remaining =
                        Thread.getAllStackTraces().keys.filter {
                            it !in before &&
                                it.isAlive &&
                                (it.name.startsWith("DefaultDispatcher") ||
                                    it.name.startsWith("Jewel MCP") ||
                                    it.name == "kotlinx.coroutines.DefaultExecutor")
                        }
                    if (remaining.isNotEmpty()) Thread.sleep(10)
                } while (remaining.isNotEmpty() && System.nanoTime() < deadline)
                assertTrue(
                    "Private runtime threads survived close: ${remaining.map { it.name }}",
                    remaining.isEmpty(),
                )
            }
    }
}
