package dev.sebastiano.jewel.tooling.e2e

import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import dev.sebastiano.spectre.core.RobotDriver
import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

internal object McpClientSetupScenario {
  suspend fun install(window: JDialog, output: Path) {
    val file = output.resolve("client-config.json")
    Files.writeString(
      file,
      "{\n  // Keep this comment\n  \"mcpServers\": {\"other\": {\"command\": \"preserve\"}}\n}\n",
    )
    edt {
      val combo = descendants(window).filterIsInstance<JComboBox<*>>().single()
      val index =
        (0 until combo.itemCount).single { combo.getItemAt(it).toString() == "Antigravity" }
      combo.selectedIndex = index
    }
    val expectedDefault =
      Path.of(System.getProperty("user.home"), ".gemini", "config", "mcp_config.json")
    await {
      edt {
        descendants(window).filterIsInstance<TextFieldWithBrowseButton>().first().text ==
          expectedDefault.toString()
      }
    }
    edt {
      descendants(window).filterIsInstance<TextFieldWithBrowseButton>().first().text =
        file.toString()
    }
    WindowCapture.activate(window)
    val point = edt {
      val button = descendants(window).filterIsInstance<JButton>().single { it.text == "Install" }
      check(button.isEnabled)
      button.locationOnScreen.apply { translate(button.width / 2, button.height / 2) }
    }
    RobotDriver.synthetic(rootWindow = generateSequence<Window>(window) { it.owner }.last()).use {
      it.click(point.x, point.y)
    }
    await {
      edt { labels(window).any { it.contains("Configuration installed. Refresh the client.") } }
    }
    val text = Files.readString(file)
    check(text.contains("// Keep this comment"))
    val servers = JsonParser.parseString(text).asJsonObject.getAsJsonObject("mcpServers")
    check(servers.getAsJsonObject("other").get("command").asString == "preserve")
    val installed = servers.entrySet().single { it.key.startsWith("jewel-") }.value.asJsonObject
    val command =
      listOf(installed.get("command").asString) +
        installed.getAsJsonArray("args").map { it.asString }
    check(command.all { !it.contains("Bearer") })
    check(Path.of(command.first()).isAbsolute)
    verifyLaunch(command)
    installSkill(window, output)
    Files.writeString(
      output.resolve("mcp-client-install-evidence.txt"),
      "PASS: production client selector and Install; preserved existing entry/comment; " +
        "installed stdio launcher handshake; installed versioned agent skill",
    )
  }

  private suspend fun installSkill(window: JDialog, output: Path) {
    val file = output.resolve("skills/jewel-compose-analysis/SKILL.md")
    await {
      edt {
        descendants(window)
          .filterIsInstance<TextFieldWithBrowseButton>()
          .single { it.name == "jewel.skill.destination" }
          .isEnabled
      }
    }
    edt {
      descendants(window)
        .filterIsInstance<TextFieldWithBrowseButton>()
        .single { it.name == "jewel.skill.destination" }
        .text = file.toString()
    }
    click(window, "jewel.skill.check")
    await { edt { labels(window).any { it.contains("Not installed. Bundled version:") } } }
    click(window, "jewel.skill.install")
    await { edt { labels(window).any { it.contains("is installed and up to date.") } } }
    val text = Files.readString(file)
    check(text.contains("name: jewel-compose-analysis"))
    val receipt =
      JsonParser.parseString(Files.readString(file.resolveSibling(".jewel-tooling-skill.json")))
        .asJsonObject
    val version = receipt.getAsJsonObject("installed").get("version").asString
    check(text.contains("version: \"$version\""))
    check(receipt.get("pending").isJsonNull)
  }

  private suspend fun click(window: JDialog, name: String) {
    val point = edt {
      val button = descendants(window).filterIsInstance<JButton>().single { it.name == name }
      check(button.isEnabled)
      button.locationOnScreen.apply { translate(button.width / 2, button.height / 2) }
    }
    RobotDriver.synthetic(rootWindow = generateSequence<Window>(window) { it.owner }.last()).use {
      it.click(point.x, point.y)
    }
  }

  private suspend fun verifyLaunch(command: List<String>) {
    val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    try {
      process.outputStream.write(
        ("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{""" +
            """"protocolVersion":"2025-11-25","capabilities":{},""" +
            """"clientInfo":{"name":"jewel-installed-client","version":"1"}}}""" +
            "\n")
          .toByteArray()
      )
      process.outputStream.flush()
      val bytes = java.io.ByteArrayOutputStream()
      await {
        while (process.inputStream.available() > 0) {
          val next = process.inputStream.read()
          if (next < 0) break
          bytes.write(next)
        }
        check(bytes.size() < MAX_RESPONSE)
        bytes.toString(Charsets.UTF_8).contains('\n')
      }
      val reply =
        JsonParser.parseString(bytes.toString(Charsets.UTF_8).lineSequence().first()).asJsonObject
      check(reply.get("id").asInt == 1)
      check(reply.getAsJsonObject("result").has("serverInfo"))
    } finally {
      process.outputStream.close()
      process.destroyForcibly()
      process.inputStream.close()
    }
  }

  private fun descendants(component: Component): List<Component> =
    listOf(component) + (component as? Container)?.components.orEmpty().flatMap(::descendants)

  private fun labels(window: Window) =
    descendants(window).filterIsInstance<JLabel>().map { it.text.orEmpty() }

  private suspend fun await(condition: suspend () -> Boolean) =
    withTimeout(TIMEOUT) { while (!condition()) delay(POLL) }

  private suspend fun <T> edt(action: () -> T): T {
    val result = CompletableFuture<Result<T>>()
    ApplicationManager.getApplication().invokeLater { result.complete(runCatching(action)) }
    while (!result.isDone) delay(POLL)
    return result.get().getOrThrow()
  }

  private const val TIMEOUT = 30_000L
  private const val POLL = 50L
  private const val MAX_RESPONSE = 65536
}
