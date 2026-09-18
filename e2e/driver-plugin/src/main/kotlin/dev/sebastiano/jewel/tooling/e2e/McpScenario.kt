package dev.sebastiano.jewel.tooling.e2e

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.plugins.DynamicPlugins
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import dev.sebastiano.spectre.core.RobotDriver
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Properties
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Exercises the production MCP controls and protocol in an imported project. */
internal class McpScenario {
  private lateinit var stageFile: Path

  suspend fun inspect(project: Project, editor: Editor, output: Path) {
    stageFile = output.resolve("mcp-stage.txt")
    stage("Open setup")
    openSetup(editor)
    click("Enable")
    await { edt { text().contains("Enabled on loopback") } }
    click("Copy client setup commands")
    val configuration = edt {
      requireNotNull(CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor))
    }
    check(!configuration.contains("token", true))
    val descriptor =
      Path.of(requireNotNull(Regex("'([^']+\\.properties)'").find(configuration)).groupValues[1])
    stage("Install disposable client configuration")
    McpClientSetupScenario.install(edt { requireNotNull(dialog()) }, output)
    stage("Read private setup and initialize client")
    val properties = readMcpDiscovery(descriptor)
    val client = Client(properties)
    val status = client.tool("jewel_status", JsonObject())
    check(status.getAsJsonObject("data").get("readiness").asString == "READY")
    check(
      Path.of(status.getAsJsonObject("data").get("projectRoot").asString).toRealPath() ==
        Path.of(requireNotNull(project.basePath)).toRealPath()
    )
    analyze(client, editor, output)
    cycle(project, editor, descriptor, properties, client)
    Files.writeString(
      output.resolve("mcp-evidence.txt"),
      "PASS: production setup; authenticated handshake; imported model; " +
        "compiler/unknown evidence; unsaved revision; disable/re-enable; unload/reload; project close",
    )
  }

  private suspend fun cycle(
    project: Project,
    editor: Editor,
    descriptor: Path,
    properties: Properties,
    client: Client,
  ) {
    val generation = properties.getProperty("generation")
    click("Disable")
    await { !Files.exists(descriptor) && edt { text().contains("Disabled") } }
    client.assertDisconnected()
    click("Enable")
    await { Files.exists(descriptor) && edt { text().contains("Enabled on loopback") } }
    val refreshed = readMcpDiscovery(descriptor)
    check(refreshed.getProperty("generation") != generation)
    check(refreshed.getProperty("token") != properties.getProperty("token"))
    Client(refreshed).use {
      check(it.tool("jewel_status", JsonObject()).get("kind").asString == "result")
    }
    click("Close")
    await { edt { dialog() == null } }
    stage("Unload and reload plugin")
    val plugin =
      requireNotNull(
        PluginManagerCore.getPluginSet()
          .findInstalledPlugin(PluginId.getId("dev.sebastiano.jewel.tooling"))
      )
    check(edt { DynamicPlugins.unloadPlugin(plugin) }) { "MCP prevented dynamic plugin unload" }
    await { !Files.exists(descriptor) }
    Client(refreshed, initialize = false).use { it.assertDisconnected() }
    check(edt { DynamicPlugins.loadPlugin(plugin, project) }) {
      "Plugin reload failed after MCP teardown"
    }
    edt { editor.caretModel.moveToOffset(0) }
    client.close()
    stage("Reopen setup after reload")
    openSetup(editor)
    click("Enable")
    await { Files.exists(descriptor) && edt { text().contains("Enabled on loopback") } }
    val closing = readMcpDiscovery(descriptor)
    Client(closing).use {
      check(it.tool("jewel_status", JsonObject()).get("kind").asString == "result")
    }
    click("Close")
    await { edt { dialog() == null } }
    stage("Close project with enabled MCP server")
    check(
      edt {
        com.intellij.openapi.project.ex.ProjectManagerEx.getInstanceEx()
          .forceCloseProject(project, false)
      }
    )
    await { !Files.exists(descriptor) }
    Client(closing, initialize = false).use { it.assertDisconnected() }
  }

  private suspend fun analyze(client: Client, editor: Editor, output: Path) {
    val arguments = JsonObject().apply { addProperty("file", "src/main/kotlin/example/Example.kt") }
    stage("Analyze imported project")
    val listing = client.tool("jewel_composables", arguments).getAsJsonObject("data")
    val declaration =
      listing
        .getAsJsonArray("declarations")
        .first { it.asJsonObject.get("name").asString == "GreetingRow" }
        .asJsonObject
    val parameters = declaration.getAsJsonArray("parameters")
    check(
      parameters.any { parameter ->
        parameter.asJsonObject.getAsJsonArray("evidence").any {
          it.asJsonObject.get("code").asString == "COMPILER_METADATA"
        }
      }
    )
    check(parameters.any { it.asJsonObject.get("stability").asString == "UNKNOWN" })
    val explain =
      arguments.deepCopy().apply {
        addProperty("declarationId", declaration.get("id").asString)
        addProperty("parameter", parameters.first().asJsonObject.get("name").asString)
      }
    check(client.tool("jewel_explain", explain).get("kind").asString == "result")
    val window = edt { requireNotNull(dialog()) }
    val robot = RobotDriver.synthetic(rootWindow = window)
    val bounds = edt { Rectangle(window.locationOnScreen, window.size) }
    ImageIO.write(
      WindowCapture.capture(window, bounds, robot),
      "png",
      output.resolve("mcp-setup.png").toFile(),
    )
    val transform = edt { window.graphicsConfiguration.defaultTransform }
    Files.writeString(
      output.resolve("mcp-capture.json"),
      """{"captureId":"${System.getProperty("jewel.test.captureId", "development")}",
        "logicalWidth":${bounds.width},"logicalHeight":${bounds.height},
        "scaleX":${transform.scaleX},"scaleY":${transform.scaleY}}""",
    )
    Files.writeString(output.resolve("mcp-target.txt"), System.getProperty("jewel.test.target"))
    stage("Analyze unsaved edit")
    edt {
      WriteCommandAction.runWriteCommandAction(editor.project) {
        val offset = editor.document.text.indexOf("val title")
        check(offset >= 0)
        editor.document.replaceString(offset, offset + "val".length, "var")
      }
    }
    check(client.tool("jewel_explain", explain).get("code").asString == "STALE_LOCATION")
    val edited = client.tool("jewel_analyze", arguments).getAsJsonObject("data")
    check(edited.get("contentHash") != listing.get("contentHash"))
  }

  private suspend fun openSetup(editor: Editor) {
    ApplicationManager.getApplication().invokeLater {
      ActionManager.getInstance()
        .tryToExecute(
          requireNotNull(ActionManager.getInstance().getAction("JewelTooling.McpServer")),
          null,
          editor.contentComponent,
          "JewelTooling.E2E",
          true,
        )
    }
    await { edt { dialog() != null } }
  }

  private class Client(properties: Properties, initialize: Boolean = true) : AutoCloseable {
    private val http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build()
    private val endpoint = URI(properties.getProperty("endpoint"))
    private val token = properties.getProperty("token")
    private var session: String? = null
    private var id = 0

    init {
      if (initialize) {
        val init =
          send(
            """{"jsonrpc":"2.0","id":0,"method":"initialize","params":{
              "protocolVersion":"2025-11-25","capabilities":{},
              "clientInfo":{"name":"jewel-spectre","version":"1"}}}"""
          )
        check(init.statusCode() == HTTP_OK)
        session = init.headers().firstValue("Mcp-Session-Id").orElseThrow()
        check(
          send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""").statusCode() ==
            HTTP_ACCEPTED
        )
        val tools = send("""{"jsonrpc":"2.0","id":99,"method":"tools/list"}""")
        check(
          JsonParser.parseString(tools.body())
            .asJsonObject
            .getAsJsonObject("result")
            .getAsJsonArray("tools")
            .size() == TOOL_COUNT
        )
      }
    }

    fun tool(name: String, arguments: JsonObject): JsonObject {
      val request =
        JsonObject().apply {
          addProperty("jsonrpc", "2.0")
          addProperty("id", ++id)
          addProperty("method", "tools/call")
          add(
            "params",
            JsonObject().apply {
              addProperty("name", name)
              add("arguments", arguments)
            },
          )
        }
      val response = send(request.toString())
      check(response.statusCode() == HTTP_OK)
      return JsonParser.parseString(response.body())
        .asJsonObject
        .getAsJsonObject("result")
        .getAsJsonObject("structuredContent")
        .getAsJsonObject("payload")
    }

    fun assertDisconnected() {
      try {
        send("{}")
      } catch (expected: java.io.IOException) {
        return
      }
      error("MCP socket remains open after teardown")
    }

    private fun send(body: String): HttpResponse<String> {
      val request =
        HttpRequest.newBuilder(endpoint)
          .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
          .header("Authorization", "Bearer $token")
          .header("Content-Type", "application/json")
          .header("Accept", "application/json, text/event-stream")
          .header("MCP-Protocol-Version", "2025-11-25")
      session?.let { request.header("Mcp-Session-Id", it) }
      return http.send(
        request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
      )
    }

    override fun close() {
      http.close()
    }
  }

  private fun dialog() =
    Window.getWindows().filterIsInstance<JDialog>().firstOrNull {
      it.isShowing && it.title == "Compose Analysis MCP Server"
    }

  private fun descendants(component: Component): List<Component> =
    listOf(component) + (component as? Container)?.components.orEmpty().flatMap(::descendants)

  private fun text() =
    dialog()
      ?.let {
        descendants(it).filterIsInstance<JLabel>().joinToString("\n") { label ->
          label.text.orEmpty()
        }
      }
      .orEmpty()

  private fun stage(value: String) {
    Files.writeString(stageFile, value)
  }

  private suspend fun click(text: String) {
    stage("Click $text")
    val window = edt { requireNotNull(dialog()) }
    WindowCapture.activate(window)
    val pair = edt {
      val window = requireNotNull(dialog())
      val button =
        descendants(window).filterIsInstance<JButton>().single { it.text == text && it.isShowing }
      check(button.isEnabled) { "$text is disabled" }
      window to button.locationOnScreen.apply { translate(button.width / 2, button.height / 2) }
    }
    RobotDriver.synthetic(rootWindow = generateSequence<Window>(pair.first) { it.owner }.last())
      .use { it.click(pair.second.x, pair.second.y) }
  }

  private suspend fun await(condition: suspend () -> Boolean) =
    withTimeout(AWAIT_TIMEOUT_MILLIS) { while (!condition()) delay(POLL_MILLIS) }

  private suspend fun <T> edt(action: () -> T): T {
    val future = CompletableFuture<Result<T>>()
    ApplicationManager.getApplication().invokeLater { future.complete(runCatching(action)) }
    while (!future.isDone) delay(POLL_MILLIS)
    return future.get().getOrThrow()
  }

  companion object {
    private const val HTTP_OK = 200
    private const val HTTP_ACCEPTED = 202
    private const val TOOL_COUNT = 4
    private const val CONNECT_TIMEOUT_SECONDS = 3L
    private const val REQUEST_TIMEOUT_SECONDS = 15L
    private const val AWAIT_TIMEOUT_MILLIS = 30_000L
    private const val POLL_MILLIS = 50L
  }
}

private fun readMcpDiscovery(path: Path) =
  Properties().apply { Files.newBufferedReader(path).use { load(it) } }
