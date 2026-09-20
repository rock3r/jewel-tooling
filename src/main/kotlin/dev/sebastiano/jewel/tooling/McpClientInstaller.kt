package dev.sebastiano.jewel.tooling

import com.google.gson.JsonObject
import dev.sebastiano.jewel.tooling.mcp.bootstrap.Discovery
import java.nio.file.Files
import java.nio.file.Path

internal data class McpInstallRequest(
    val client: McpClient,
    val destination: String,
    val executable: String = "",
)

internal class McpClientInstaller(
    private val privateRoot: Path,
    private val process: McpProcessRunner = McpClientProcess(),
) {
    private val owned = McpOwnedConfig(privateRoot)

    suspend fun install(request: McpInstallRequest, launch: McpClientLaunch): Boolean {
        val raw = Path.of(request.destination)
        if (!raw.isAbsolute) throw McpSetupFailure("unsafePath")
        McpConfigFiles.directory(raw.parent)
        val destination = raw.parent.toRealPath().resolve(raw.fileName)
        McpConfigFiles.directory(privateRoot)
        return when (request.client) {
            McpClient.CODEX ->
                McpCodexInstall(process)
                    .install(destination, request.executable, launch, privateRoot)
            McpClient.ANDROID_STUDIO -> {
                studioPath(destination, launch)
                owned.install(request.client, destination, launch, studioEntry(launch))
            }
            McpClient.PI -> configurePi(request.executable, destination, launch)
            else -> owned.install(request.client, destination, launch, request.client.entry(launch))
        }
    }

    private suspend fun configurePi(
        executable: String,
        destination: Path,
        launch: McpClientLaunch,
    ): Boolean {
        val before = McpConfigFiles.read(destination)
        val document = McpJsonConfig(McpConfigFiles.text(before).ifBlank { "{}" })
        val previous = document.value(listOf(McpClient.PI.container, launch.name))
        if (previous != null && previous != McpClient.PI.entry(launch))
            throw McpSetupFailure("conflict")
        installPi(executable, destination.parent)
        if (!before.contentEquals(McpConfigFiles.read(destination)))
            throw McpSetupFailure("piPartial")
        return owned.install(McpClient.PI, destination, launch, McpClient.PI.entry(launch))
    }

    fun refreshStudio(launch: McpClientLaunch, enabled: Boolean) {
        val entry =
            if (enabled) studioEntry(launch)
            else
                JsonObject().apply {
                    addProperty("httpUrl", "http://127.0.0.1:1/mcp")
                    addProperty("enabled", false)
                }
        owned.studioDestinations(launch.name).forEach { destination ->
            studioPath(destination, launch)
            owned.install(McpClient.ANDROID_STUDIO, destination, launch, entry)
        }
    }

    private fun studioEntry(launch: McpClientLaunch): JsonObject {
        val descriptor = Discovery.read(launch.descriptor, launch.projectId)
        return JsonObject().apply {
            addProperty("httpUrl", descriptor.getProperty("endpoint"))
            addProperty("enabled", true)
            add(
                "headers",
                JsonObject().apply {
                    addProperty("Authorization", "Bearer ${descriptor.getProperty("token")}")
                },
            )
        }
    }

    private fun studioPath(destination: Path, launch: McpClientLaunch) {
        val project = launch.projectRoot.toRealPath()
        if (
            destination.fileName.toString() != "mcp.json" ||
                destination.startsWith(project) ||
                !Files.isDirectory(destination.parent.resolve("options"))
        ) {
            throw McpSetupFailure("studioProfile")
        }
        McpConfigFiles.owned(destination.parent, true)
    }

    private suspend fun installPi(executable: String, agentDirectory: Path) {
        val settings = agentDirectory.resolve("settings.json")
        val config =
            McpJsonConfig(McpConfigFiles.text(McpConfigFiles.read(settings)).ifBlank { "{}" })
        val packages = config.value(listOf("packages"))
        val installed =
            packages?.asJsonArray?.any { entry ->
                val source =
                    if (entry.isJsonPrimitive) entry.asString
                    else entry.asJsonObject.get("source")?.asString.orEmpty()
                source == "npm:pi-mcp-adapter" || source.startsWith("npm:pi-mcp-adapter@")
            } == true
        if (installed) return
        val stage = Files.createTempDirectory(privateRoot, "pi-")
        try {
            val result =
                process.run(
                    listOf(executable, "install", "npm:pi-mcp-adapter@2.34.0"),
                    stage,
                    mapOf("PI_CODING_AGENT_DIR" to agentDirectory.toString()),
                )
            if (result.exitCode != 0) throw McpSetupFailure("piInstall")
        } finally {
            Files.walk(stage).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
