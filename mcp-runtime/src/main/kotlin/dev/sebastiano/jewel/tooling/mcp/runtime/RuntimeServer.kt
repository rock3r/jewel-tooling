package dev.sebastiano.jewel.tooling.mcp.runtime

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import java.net.URLClassLoader
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import java.util.function.BiFunction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Owns one project endpoint. All public boundary types belong to the JDK. */
class RuntimeServer
private constructor(
  configuration: Map<String, String>,
  toolDefinitionsJson: String,
  callback: BiFunction<String, String, CompletableFuture<String>>,
) : AutoCloseable {
  private val token =
    requireNotNull(configuration["token"])
      .also { require(it.length >= 43) }
      .toByteArray(Charsets.UTF_8)
  private val version = requireNotNull(configuration["pluginVersion"])
  private val tools = McpJson.decodeFromString<List<Tool>>(toolDefinitionsJson)
  private var callback: BiFunction<String, String, CompletableFuture<String>>? = callback
  private val closed = AtomicBoolean()
  private var terminated = false
  private val sessions = LinkedHashSet<RuntimeSession>()
  private val requests = Semaphore(24)
  private val executor =
    Executors.newFixedThreadPool(2) { runnable ->
      Thread(runnable, "Jewel MCP lifecycle").apply {
        isDaemon = true
        contextClassLoader = ClassLoader.getPlatformClassLoader()
      }
    }
  private val dispatcher = executor.asCoroutineDispatcher()
  private val scope = CoroutineScope(SupervisorJob() + dispatcher)
  private val engine =
    embeddedServer(CIO, host = "127.0.0.1", port = 0) {
      install(ContentNegotiation) { json(McpJson) }
      intercept(ApplicationCallPipeline.Plugins) {
        if (!authorize(context)) {
          finish()
          return@intercept
        }
      }
      routing {
        route("/mcp") {
          get { call.respond(HttpStatusCode.MethodNotAllowed) }
          post { dispatch(call) }
          delete { dispatch(call) }
        }
      }
    }
  val port: Int

  init {
    engine.engineConfig.connectionIdleTimeoutSeconds = IDLE_TIMEOUT_SECONDS
    engine.start(wait = false)
    port = runBlocking { engine.engine.resolvedConnectors().single().port }
    scope.launch {
      while (isActive) {
        delay(EXPIRY_POLL_MS)
        val now = System.nanoTime()
        val expired =
          synchronized(sessions) {
            sessions.filter {
              now - (if (it.ready) it.lastUsed else it.created) >
                (if (it.ready) 300_000_000_000L else 10_000_000_000L)
            }
          }
        expired.forEach { retire(it) }
      }
    }
  }

  private suspend fun authorize(call: ApplicationCall): Boolean {
    val authorization = call.request.headers.getAll(HttpHeaders.Authorization)
    val supplied =
      authorization?.singleOrNull()?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
    val valid =
      supplied != null && MessageDigest.isEqual(token, supplied.toByteArray(Charsets.UTF_8))
    val status =
      when {
        !valid -> HttpStatusCode.Unauthorized
        call.request.headers[HttpHeaders.Origin] != null -> HttpStatusCode.Forbidden
        call.request.headers.getAll(HttpHeaders.Host) != listOf("127.0.0.1:$port") ->
          HttpStatusCode.Forbidden
        closed.get() -> HttpStatusCode.ServiceUnavailable
        else -> return true
      }
    call.respond(status)
    return false
  }

  private suspend fun dispatch(call: ApplicationCall) {
    if (!requests.tryAcquire()) {
      call.respond(HttpStatusCode.TooManyRequests)
      return
    }
    var selected: SelectedSession? = null
    try {
      selected = selectSession(call)
      selected?.let { selection ->
        val active = selection.session
        if (selection.created) active.start()
        active.lastUsed = System.nanoTime()
        withTimeout(REQUEST_TIMEOUT_MS) { active.transport.handleRequest(null, call) }
        if (call.request.httpMethod == HttpMethod.Delete || !active.initialized) retire(active)
      }
    } finally {
      try {
        selected?.takeIf { it.created && !it.session.initialized }?.let { retire(it.session) }
      } finally {
        requests.release()
      }
    }
  }

  private suspend fun selectSession(call: ApplicationCall): SelectedSession? {
    val id = call.request.headers["Mcp-Session-Id"]
    val existing =
      synchronized(sessions) { sessions.firstOrNull { it.transport.sessionId == id && id != null } }
    return when {
      id != null && existing == null -> {
        call.respond(HttpStatusCode.NotFound)
        null
      }
      id == null && call.request.httpMethod != HttpMethod.Post -> {
        call.respond(HttpStatusCode.BadRequest)
        null
      }
      existing != null -> SelectedSession(existing, false)
      else -> {
        val admitted =
          synchronized(sessions) {
            if (closed.get() || sessions.size >= SESSION_LIMIT) null
            else RuntimeSession(version, tools, checkNotNull(callback)).also { sessions.add(it) }
          }
        if (admitted == null) call.respond(HttpStatusCode.TooManyRequests)
        admitted?.let { SelectedSession(it, true) }
      }
    }
  }

  private data class SelectedSession(val session: RuntimeSession, val created: Boolean)

  private suspend fun retire(session: RuntimeSession) {
    withContext(NonCancellable) { withTimeout(SESSION_CLOSE_TIMEOUT_MS) { session.close() } }
    synchronized(sessions) { sessions.remove(session) }
  }

  @OptIn(DelicateCoroutinesApi::class)
  @Synchronized
  override fun close() {
    if (terminated) return
    closed.set(true)
    runBlocking {
      withTimeout(CLOSE_TIMEOUT_MS) {
        scope.coroutineContext.job.cancelAndJoin()
        val active = synchronized(sessions) { sessions.toList() }
        active.forEach { retire(it) }
      }
    }
    engine.stop(0, CLOSE_TIMEOUT_MS)
    callback = null
    token.fill(0)
    dispatcher.close()
    check(executor.awaitTermination(CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
      "MCP_LIFECYCLE_TERMINATION_FAILED"
    }
    val loader = javaClass.classLoader
    if (ownsDispatcherPools(loader)) {
      val timers =
        Thread.getAllStackTraces().keys.filter {
          it.name == "kotlinx.coroutines.DefaultExecutor" && it.contextClassLoader === loader
        }
      Dispatchers.shutdown()
      timers.forEach { timer ->
        LockSupport.unpark(timer)
        timer.join(SESSION_CLOSE_TIMEOUT_MS)
        check(!timer.isAlive) { "MCP_TIMER_TERMINATION_FAILED" }
      }
    }
    terminated = true
  }

  private fun ownsDispatcherPools(loader: ClassLoader): Boolean {
    val source = javaClass.protectionDomain.codeSource.location
    val dedicatedJar = loader is URLClassLoader && loader.getURLs().singleOrNull() == source
    return dedicatedJar &&
      source.path.endsWith(".jar") &&
      loader.parent === ClassLoader.getPlatformClassLoader()
  }

  companion object {
    private const val IDLE_TIMEOUT_SECONDS = 15
    private const val EXPIRY_POLL_MS = 1000L
    private const val REQUEST_TIMEOUT_MS = 15000L
    private const val SESSION_CLOSE_TIMEOUT_MS = 3000L
    private const val CLOSE_TIMEOUT_MS = 5000L
    private const val SESSION_LIMIT = 8

    @JvmStatic
    fun start(
      configuration: Map<String, String>,
      toolDefinitionsJson: String,
      callback: BiFunction<String, String, CompletableFuture<String>>,
    ): RuntimeServer {
      val thread = Thread.currentThread()
      val previous = thread.contextClassLoader
      return try {
        thread.contextClassLoader = ClassLoader.getPlatformClassLoader()
        RuntimeServer(configuration, toolDefinitionsJson, callback)
      } finally {
        thread.contextClassLoader = previous
      }
    }
  }
}
