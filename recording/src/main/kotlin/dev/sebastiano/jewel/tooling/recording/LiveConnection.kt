package dev.sebastiano.jewel.tooling.recording

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.jetbrains.annotations.ApiStatus

/** A local capability. Do not display, log, or persist its token. */
@ApiStatus.Experimental
class LiveEndpoint private constructor(val port: Int, internal val token: ByteArray) {
  fun connectionString(): String =
    "jewel-compose://127.0.0.1:$port/" + token.joinToString("") { "%02x".format(it) }

  override fun toString(): String = "LiveEndpoint(port=$port)"

  companion object {
    private const val MAX_PORT = 65535
    private const val MAX_CONNECTION_LENGTH = 128
    private const val HEX_PAIR = 2
    private const val HEX_RADIX = 16

    internal fun create(port: Int, token: ByteArray): LiveEndpoint =
      LiveEndpoint(port, token.copyOf())

    fun parse(value: String): LiveEndpoint {
      require(value.length <= MAX_CONNECTION_LENGTH)
      val uri = URI(value)
      require(uri.scheme == "jewel-compose" && uri.host == "127.0.0.1")
      require(
        uri.port in 1..MAX_PORT &&
          uri.rawUserInfo == null &&
          uri.rawQuery == null &&
          uri.rawFragment == null
      )
      val token = uri.rawPath.removePrefix("/")
      require(token.matches(Regex("[0-9a-f]{64}")))
      require(uri.rawPath == "/$token")
      return LiveEndpoint(
        uri.port,
        token.chunked(HEX_PAIR).map { it.toInt(HEX_RADIX).toByte() }.toByteArray(),
      )
    }
  }
}

@ApiStatus.Experimental
enum class LiveCommand {
  START,
  SNAPSHOT,
  STOP,
  KEEP_ALIVE,
  STATUS,
}

@ApiStatus.Experimental
class LiveCommandRejectedException : IOException("Capture command is unavailable")

/** A serial blocking client. Close from any thread to cancel pending I/O. */
@ApiStatus.Experimental
class LiveConnection(private val endpoint: LiveEndpoint) : AutoCloseable {
  private val socket = Socket()
  private val deadlines = Executors.newSingleThreadScheduledExecutor { task ->
    Thread(task, "Compose inspection client deadline").apply { isDaemon = true }
  }
  private lateinit var input: DataInputStream
  private lateinit var output: DataOutputStream

  fun connect(): String = bounded {
    socket.connect(InetSocketAddress("127.0.0.1", endpoint.port), LiveWire.AUTH_TIMEOUT_MS)
    socket.soTimeout = LiveWire.IO_TIMEOUT_MS
    input = DataInputStream(socket.getInputStream())
    output = DataOutputStream(socket.getOutputStream())
    output.writeInt(LiveWire.MAGIC)
    output.writeInt(LiveWire.VERSION)
    output.write(endpoint.token)
    output.flush()
    LiveWire.readText(input)
  }

  fun status(): LiveTargetStatus = bounded {
    output.writeByte(LiveCommand.STATUS.ordinal)
    output.flush()
    val status = input.readUnsignedByte()
    val bytes = LiveWire.readBytes(input, LiveTargetStatus.WIRE_BYTES)
    if (status != LiveWire.OK) throw IOException("Target status failed")
    LiveTargetStatus.decode(bytes)
  }

  fun request(command: LiveCommand): Recording? = bounded {
    require(command != LiveCommand.STATUS)
    output.writeByte(command.ordinal)
    output.flush()
    val status = input.readUnsignedByte()
    val bytes = LiveWire.readBytes(input, RecordingLimits.FILE_BYTES)
    when (status) {
      LiveWire.OK -> RecordingCodec.read(bytes.inputStream())
      LiveWire.NO_SESSION -> {
        if (bytes.isNotEmpty()) throw IOException("Invalid reply")
        null
      }
      LiveWire.INVALID_STATE -> {
        if (bytes.isNotEmpty()) throw IOException("Invalid reply")
        throw LiveCommandRejectedException()
      }
      else -> throw IOException("Capture command failed")
    }
  }

  private fun <T> bounded(action: () -> T): T {
    val timeout =
      deadlines.schedule({ socket.close() }, LiveWire.IO_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
    try {
      return action()
    } finally {
      timeout.cancel(false)
    }
  }

  override fun close() {
    socket.close()
    deadlines.shutdownNow()
  }
}

internal object LiveWire {
  const val MAGIC = 0x4a435031
  const val VERSION = 2
  const val AUTH_TIMEOUT_MS = 2000
  const val IO_TIMEOUT_MS = 5000
  const val OK = 0
  const val NO_SESSION = 1
  const val INVALID_STATE = 2

  fun readBytes(input: DataInputStream, maximum: Int): ByteArray {
    val length = input.readInt()
    if (length !in 0..maximum) throw IOException("Invalid frame length")
    return ByteArray(length).also(input::readFully)
  }

  fun writeBytes(output: DataOutputStream, bytes: ByteArray) {
    require(bytes.size <= RecordingLimits.FILE_BYTES)
    output.writeInt(bytes.size)
    output.write(bytes)
    output.flush()
  }

  fun readText(input: DataInputStream): String {
    val bytes = readBytes(input, RecordingLimits.LABEL_BYTES)
    val text =
      Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
    if (!validLabel(text) || text.isBlank()) throw IOException("Invalid target label")
    return text
  }
}
