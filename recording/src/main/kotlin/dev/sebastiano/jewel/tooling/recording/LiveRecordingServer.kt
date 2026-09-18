package dev.sebastiano.jewel.tooling.recording

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.jetbrains.annotations.ApiStatus

/** One session's host operations. Abort must be thread-safe and idempotent. */
@ApiStatus.Experimental
interface LiveRecordingSession {
  fun status(): LiveTargetStatus = LiveTargetStatus.READY

  fun execute(command: LiveCommand): Recording?

  fun abort()
}

/**
 * Owns bounded local connections. The host supplies one exclusive recording sink per connection.
 */
@ApiStatus.Experimental
class LiveRecordingServer(
  private val target: CaptureTarget,
  private val sessionFactory: () -> LiveRecordingSession,
) : AutoCloseable {
  init {
    require(validLabel(target.displayName) && target.displayName.isNotBlank())
  }

  private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
  private val acceptor = Executors.newSingleThreadExecutor { daemon(it, "accept") }
  private val worker = Executors.newSingleThreadExecutor { daemon(it, "client") }
  private val deadlines = Executors.newSingleThreadScheduledExecutor { daemon(it, "deadline") }
  private val lock = Any()
  private val closed = AtomicBoolean()
  private var token = freshToken()
  private var client: Socket? = null
  private var session: LiveRecordingSession? = null

  val endpoint: LiveEndpoint
    get() = synchronized(lock) { LiveEndpoint.create(server.localPort, token) }

  init {
    acceptor.execute(::accept)
  }

  private fun accept() {
    try {
      while (!closed.get()) {
        val incoming = server.accept()
        synchronized(lock) {
          if (closed.get() || client != null) incoming.close()
          else {
            client = incoming
            worker.execute { serve(incoming) }
          }
        }
      }
    } catch (_: IOException) {
      close()
    }
  }

  @Suppress("ThrowsCount") // Invalid protocol boundaries close the untrusted connection.
  private fun serve(socket: Socket) {
    var authenticated = false
    var owned: LiveRecordingSession? = null
    try {
      socket.use {
        socket.soTimeout = LiveWire.IO_TIMEOUT_MS
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        bounded(socket, LiveWire.AUTH_TIMEOUT_MS) {
          if (input.readInt() != LiveWire.MAGIC || input.readInt() != LiveWire.VERSION)
            throw IOException("Unsupported protocol")
          val supplied = ByteArray(TOKEN_BYTES).also(input::readFully)
          synchronized(lock) {
            if (closed.get() || !MessageDigest.isEqual(token, supplied))
              throw IOException("Authentication failed")
            owned = sessionFactory()
            session = owned
            authenticated = true
          }
          LiveWire.writeBytes(output, target.displayName.toByteArray(Charsets.UTF_8))
        }
        while (!closed.get()) {
          bounded(socket, LiveWire.IO_TIMEOUT_MS) { respond(input, output, checkNotNull(owned)) }
        }
      }
    } catch (_: IOException) {
      // Disconnect ends this connection's capture.
    } finally {
      synchronized(lock) {
        owned?.abort()
        session = null
        if (authenticated) token = freshToken()
        client = null
      }
    }
  }

  private fun respond(
    input: DataInputStream,
    output: DataOutputStream,
    owned: LiveRecordingSession,
  ) {
    val command =
      LiveCommand.entries.getOrNull(input.readUnsignedByte())
        ?: throw IOException("Unknown command")
    if (command == LiveCommand.STATUS) {
      output.writeByte(LiveWire.OK)
      LiveWire.writeBytes(output, owned.status().encode())
      return
    }
    val result =
      try {
        owned.execute(command)
      } catch (_: IllegalStateException) {
        output.writeByte(LiveWire.INVALID_STATE)
        LiveWire.writeBytes(output, byteArrayOf())
        return
      }
    output.writeByte(if (result == null) LiveWire.NO_SESSION else LiveWire.OK)
    LiveWire.writeBytes(output, result?.let { RecordingCodec.write(it) } ?: byteArrayOf())
  }

  private fun bounded(socket: Socket, timeoutMs: Int, action: () -> Unit) {
    val timeout = deadlines.schedule({ socket.close() }, timeoutMs.toLong(), TimeUnit.MILLISECONDS)
    try {
      action()
    } finally {
      timeout.cancel(false)
    }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    synchronized(lock) {
      client?.close()
      session?.abort()
      session = null
      server.close()
      acceptor.shutdownNow()
      worker.shutdownNow()
      deadlines.shutdownNow()
    }
  }

  companion object {
    private const val TOKEN_BYTES = 32

    private fun freshToken(): ByteArray = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)

    private fun daemon(task: Runnable, suffix: String): Thread =
      Thread(task, "Compose inspection $suffix").apply { isDaemon = true }
  }
}
