package dev.sebastiano.jewel.tooling

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.sebastiano.jewel.tooling.mcp.bootstrap.Discovery
import java.nio.file.Files
import java.nio.file.Path

/** Records only configuration ownership fingerprints, never credentials. */
internal class McpOwnedConfig(private val directory: Path) {
    fun install(
        client: McpClient,
        destination: Path,
        launch: McpClientLaunch,
        entry: JsonObject,
    ): Boolean {
        McpConfigFiles.directory(directory)
        val registration =
            directory.resolve(
                Discovery.digest((destination.toString() + "\n" + launch.name).toByteArray()) +
                    ".json"
            )
        McpConfigUpdate(registration, directory).use { record ->
            val old = if (record.before == null) null else McpJsonConfig(record.text).objectValue()
            val hashes = old?.getAsJsonArray("hashes")?.map { it.asString }.orEmpty()
            val limit = if (client == McpClient.CLAUDE) CLAUDE_LIMIT else McpConfigFiles.LIMIT
            McpConfigUpdate(destination, directory, limit).use { config ->
                val document = McpJsonConfig(config.text.ifBlank { "{}" })
                val path = listOf(client.container, launch.name)
                val previous = document.value(path)
                val expected = fingerprint(entry)
                if (previous != null && previous != entry && fingerprint(previous) !in hashes)
                    throw McpSetupFailure("conflict")
                val pending =
                    JsonObject().apply {
                        addProperty("client", client.name)
                        addProperty("destination", destination.toString())
                        addProperty("name", launch.name)
                        add(
                            "hashes",
                            JsonArray().apply {
                                add(expected)
                                previous?.let { add(fingerprint(it)) }
                            },
                        )
                    }
                record.commit(pending.toString())
                return config.commit(
                    if (previous == entry) config.text else document.put(path, entry)
                )
            }
        }
    }

    fun studioDestinations(name: String): List<Path> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { files ->
            files
                .filter { it.fileName.toString().endsWith(".json") }
                .limit(MAX_REGISTRATIONS.toLong() + 1)
                .toList()
                .also { if (it.size > MAX_REGISTRATIONS) throw McpSetupFailure("tooLarge") }
                .mapNotNull { file ->
                    Discovery.checkPrivate(file, false)
                    val record =
                        McpJsonConfig(McpConfigFiles.text(McpConfigFiles.read(file))).objectValue()
                    if (
                        record.get("client").asString == McpClient.ANDROID_STUDIO.name &&
                            record.get("name").asString == name
                    )
                        Path.of(record.get("destination").asString)
                    else null
                }
        }
    }

    companion object {
        private const val CLAUDE_LIMIT = 32 * 1024 * 1024
        private const val MAX_REGISTRATIONS = 128

        fun fingerprint(value: JsonElement): String =
            Discovery.digest(canonical(value).toString().toByteArray())

        private fun canonical(value: JsonElement): JsonElement =
            when {
                value.isJsonObject ->
                    JsonObject().apply {
                        value.asJsonObject.keySet().sorted().forEach {
                            add(it, canonical(value.asJsonObject.get(it)))
                        }
                    }
                value.isJsonArray ->
                    JsonArray().apply { value.asJsonArray.forEach { add(canonical(it)) } }
                else -> value
            }
    }
}
