package dev.sebastiano.jewel.tooling

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.project.Project
import dev.sebastiano.jewel.tooling.mcp.bootstrap.Discovery
import java.net.URLClassLoader
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.function.BiFunction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class McpServerState(
  val phase: String = "disabled",
  val configuration: String = "",
  val target: String = "",
)

@Service(Service.Level.PROJECT)
internal class McpServerService(project: Project, scope: CoroutineScope) : Disposable {
  private val controller = McpServerController(project, scope)
  val state = controller.state
  val clientSetup = controller.clientSetup

  fun enable() = controller.enable()

  fun disable() = controller.disable()

  fun rotate() = controller.rotate()

  override fun dispose() = controller.dispose()
}

internal class McpServerController(
  private val project: Project,
  private val scope: CoroutineScope,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Disposable {
  private val mutableState = MutableStateFlow(McpServerState())
  val state = mutableState.asStateFlow()
  private val mutex = Mutex()
  @Volatile private var disposed = false
  @Volatile private var accepting = false
  @Volatile private var active: Running? = null
  private val version = resource("/mcp/jewel-tooling-version.txt").toString(Charsets.UTF_8).trim()
  private val requests =
    McpRequestHandler(project, version) { discovery ->
      !disposed && accepting && !project.isDisposed && active?.discovery === discovery
    }

  val clientSetup =
    McpClientSetupController(scope, ioDispatcher) { request ->
      mutex.withLock {
        val launch = active?.launch ?: throw McpSetupFailure("enableFirst")
        if (!accepting || disposed) throw McpSetupFailure("enableFirst")
        installer(launch).install(request, launch)
      }
    }

  init {
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        awaitCancellation()
      } finally {
        withContext(NonCancellable + ioDispatcher) { mutex.withLock { stopOwned() } }
      }
    }
  }

  fun enable() {
    scope.launch(ioDispatcher) {
      mutex.withLock {
        if (disposed || active != null) return@withLock
        enableOwned()
      }
    }
  }

  fun disable() {
    clientSetup.cancel()
    scope.launch(ioDispatcher) { mutex.withLock { stopOwned() } }
  }

  fun rotate() {
    clientSetup.cancel()
    scope.launch(ioDispatcher) {
      mutex.withLock {
        stopOwned()
        if (!disposed) enableOwned()
      }
    }
  }

  // Startup crosses a private classloader. Preserve IDE cancellation before showing a local error.
  @Suppress("TooGenericExceptionCaught")
  private fun enableOwned() {
    mutableState.value = McpServerState("starting")
    try {
      startOwned()
    } catch (failure: Exception) {
      rethrowControlFlowException(failure)
      stopOwned()
      mutableState.value = McpServerState("error")
    }
  }

  // Release all acquired resources even when reflection or a dependency fails during startup.
  @Suppress("TooGenericExceptionCaught")
  private fun startOwned() {
    if (disposed || project.isDisposed) return
    val root = Path.of(project.basePath ?: throw McpFailure("PROJECT_UNAVAILABLE")).toRealPath()
    val privateRoot = Path.of(PathManager.getConfigPath()).resolve("jewel-tooling-mcp")
    val discovery = Discovery.open(privateRoot, root)
    var loader: URLClassLoader? = null
    var server: AutoCloseable? = null
    try {
      val bytes = resource("/mcp/runtime.jar")
      val digest = Discovery.digest(bytes)
      val runtime = Discovery.install(privateRoot, "runtime-$digest.jar", bytes)
      val bootstrap =
        Discovery.install(privateRoot, "bootstrap.jar", resource("/mcp/bootstrap.jar"))
      loader =
        URLClassLoader(arrayOf(runtime.toUri().toURL()), ClassLoader.getPlatformClassLoader())
      check(loader.loadClass("kotlin.Unit").classLoader === loader)
      val entry = loader.loadClass("dev.sebastiano.jewel.tooling.mcp.runtime.RuntimeServer")
      val token =
        Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) })
      val facade =
        McpAnalysisFacade(project, discovery.projectId(), discovery.generation(), root) {
          !disposed && accepting && active?.discovery === discovery
        }
      val callback =
        BiFunction<String, String, CompletableFuture<String>> { tool, request ->
          scope.future(analysisDispatcher) { requests.call(tool, request, discovery, root, facade) }
        }
      server = startRuntime(entry, token, callback)
      val port = entry.getMethod("getPort").invoke(server) as Int
      val launch = McpClientLaunch.create(bootstrap, discovery, root)
      active = Running(discovery, loader, server, launch)
      accepting = true
      discovery.publish(
        mapOf(
          "endpoint" to "http://127.0.0.1:$port/mcp",
          "token" to token,
          "runtimePath" to runtime.toString(),
          "runtimeDigest" to digest,
          "projectRoot" to root.toString(),
        )
      )
      mutableState.value = McpServerState("ready", configuration(launch), root.toString())
      refreshStudio(launch, true)
    } catch (failure: Throwable) {
      try {
        server?.close()
      } finally {
        try {
          loader?.close()
        } finally {
          discovery.close()
        }
      }
      active = null
      throw failure
    }
  }

  private fun startRuntime(
    entry: Class<*>,
    token: String,
    callback: BiFunction<String, String, CompletableFuture<String>>,
  ): AutoCloseable {
    val thread = Thread.currentThread()
    val previousLoader = thread.contextClassLoader
    try {
      thread.contextClassLoader = ClassLoader.getPlatformClassLoader()
      return entry
        .getMethod("start", Map::class.java, String::class.java, BiFunction::class.java)
        .invoke(
          null,
          mapOf("token" to token, "pluginVersion" to version),
          resource("/mcp/tools.json").toString(Charsets.UTF_8),
          callback,
        ) as AutoCloseable
    } finally {
      thread.contextClassLoader = previousLoader
    }
  }

  private fun configuration(launch: McpClientLaunch): String {
    val command = launch.command.joinToString(" ", transform = ::shellQuote)
    return "codex mcp add ${launch.name} -- $command\n\nclaude mcp add --transport stdio ${launch.name} -- $command"
  }

  private fun installer(launch: McpClientLaunch) =
    McpClientInstaller(launch.descriptor.parent.resolve("clients-${launch.projectId}"))

  @Suppress(
    "TooGenericExceptionCaught"
  ) // A client settings failure must not prevent endpoint revocation.
  private fun refreshStudio(launch: McpClientLaunch, enabled: Boolean) {
    try {
      installer(launch).refreshStudio(launch, enabled)
    } catch (failure: Exception) {
      rethrowControlFlowException(failure)
      clientSetup.refreshFailed()
    }
  }

  // Keep ownership when a dependency fails to close, so the user can retry teardown.
  @Suppress("TooGenericExceptionCaught")
  private fun stopOwned() {
    accepting = false
    val running = active
    if (running == null) {
      mutableState.value = McpServerState("disabled")
      return
    }
    mutableState.value = McpServerState("stopping")
    try {
      try {
        running.discovery.close()
      } finally {
        try {
          running.server.close()
        } finally {
          running.loader.close()
        }
      }
      active = null
      refreshStudio(running.launch, false)
      mutableState.value = McpServerState("disabled")
    } catch (failure: Exception) {
      mutableState.value = McpServerState("cleanupFailed")
      throw failure
    }
  }

  override fun dispose() {
    disposed = true
    accepting = false
  }

  private data class Running(
    val discovery: Discovery,
    val loader: URLClassLoader,
    val server: AutoCloseable,
    val launch: McpClientLaunch,
  )

  companion object {
    private const val TOKEN_BYTES = 32

    // The baseline IDE predates the platform's shared control-flow helper.
    private fun rethrowControlFlowException(failure: Throwable) {
      if (failure is ControlFlowException || failure is CancellationException) throw failure
    }

    private fun resource(path: String): ByteArray =
      McpServerService::class.java.getResourceAsStream(path)?.use { it.readAllBytes() }
        ?: error("MCP support resource is unavailable")

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
  }
}
