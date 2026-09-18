package dev.sebastiano.jewel.tooling

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

internal class McpCodexInstall(private val process: McpProcessRunner = McpClientProcess()) {
  suspend fun install(
    destination: Path,
    executable: String,
    launch: McpClientLaunch,
    privateRoot: Path,
  ): Boolean {
    McpConfigUpdate(destination, privateRoot).use { config ->
      val stage = Files.createTempDirectory(privateRoot, "codex-")
      try {
        val file = stage.resolve("config.toml")
        McpConfigFiles.privateWrite(file, config.before ?: byteArrayOf())
        val before = list(executable, stage)
        val old = before.firstOrNull { it.asJsonObject.get("name")?.asString == launch.name }
        if (old != null) {
          if (!matches(old.asJsonObject.getAsJsonObject("transport"), launch))
            throw McpSetupFailure("conflict")
          return false
        }
        val gson = Gson()
        val block =
          "\n[mcp_servers.${launch.name}]\ncommand = ${gson.toJson(launch.command.first())}\nargs = ${gson.toJson(launch.command.drop(1))}\n"
        val candidate = config.text + block
        validateCandidate(candidate, file, executable, stage, launch)
        return config.commit(candidate)
      } finally {
        // The CLI owns this isolated staging directory; it contains no real client credentials.
        Files.walk(stage).use { paths ->
          paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
      }
    }
  }

  private suspend fun validateCandidate(
    candidate: String,
    file: Path,
    executable: String,
    stage: Path,
    launch: McpClientLaunch,
  ) {
    if (candidate.toByteArray().size > McpConfigFiles.LIMIT) throw McpSetupFailure("tooLarge")
    McpConfigFiles.privateWrite(file, candidate.toByteArray())
    val added =
      list(executable, stage).firstOrNull { it.asJsonObject.get("name")?.asString == launch.name }
    if (added == null || !matches(added.asJsonObject.getAsJsonObject("transport"), launch))
      throw McpSetupFailure("codex")
  }

  private suspend fun list(executable: String, directory: Path): JsonArray {
    val result =
      process.run(
        listOf(executable, "mcp", "list", "--json"),
        directory,
        mapOf("CODEX_HOME" to directory.toString()),
      )
    if (result.exitCode != 0) throw McpSetupFailure("codex")
    val parsed = JsonParser.parseString(result.output)
    if (
      !parsed.isJsonArray ||
        parsed.asJsonArray.any { !it.isJsonObject || !it.asJsonObject.has("name") }
    )
      throw McpSetupFailure("codex")
    return parsed.asJsonArray
  }

  private fun matches(transport: com.google.gson.JsonObject?, launch: McpClientLaunch): Boolean =
    transport?.get("type")?.asString == "stdio" &&
      transport.get("command")?.asString == launch.command.first() &&
      transport.getAsJsonArray("args")?.map { it.asString } == launch.command.drop(1)
}
