package dev.sebastiano.jewel.tooling.recording

import com.fasterxml.jackson.core.JsonFactoryBuilder
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.CodingErrorAction
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Experimental
enum class RecordingError {
  INVALID_FORMAT,
  UNSUPPORTED_VERSION,
  TOO_LARGE,
}

@ApiStatus.Experimental
class RecordingFormatException(val code: RecordingError, cause: Throwable? = null) :
  IOException(code.name, cause)

/**
 * Reads and writes supported recording schemas. The reader closes its input, including on
 * cancellation.
 */
@ApiStatus.Experimental
object RecordingCodec {
  private const val JSON_DEPTH = 8
  private const val JSON_TOKENS = 400_000L
  private const val JSON_NUMBER = 20
  private const val JSON_STRING = 4_096
  private const val JSON_NAME = 64
  private val factory =
    JsonFactoryBuilder()
      .streamReadConstraints(
        StreamReadConstraints.builder()
          .maxNestingDepth(JSON_DEPTH)
          .maxDocumentLength(RecordingLimits.FILE_BYTES.toLong())
          .maxTokenCount(JSON_TOKENS)
          .maxNumberLength(JSON_NUMBER)
          .maxStringLength(JSON_STRING)
          .maxNameLength(JSON_NAME)
          .build()
      )
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .build()

  fun read(input: InputStream, checkCanceled: () -> Unit = {}): Recording {
    try {
      val decoder =
        Charsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
      InputStreamReader(BoundedInput(input, checkCanceled), decoder).use { reader ->
        factory.createParser(reader).use { parser ->
          parser.nextToken()
          val recording = Reader(parser, checkCanceled).recording()
          requireFormat(parser.nextToken() == null)
          recording.validate(checkCanceled)
          return recording
        }
      }
    } catch (failure: IOException) {
      throw if (failure is RecordingFormatException) failure
      else RecordingFormatException(RecordingError.INVALID_FORMAT, failure)
    } catch (failure: IllegalArgumentException) {
      throw RecordingFormatException(RecordingError.INVALID_FORMAT, failure)
    }
  }

  @Suppress("LongMethod")
  fun write(recording: Recording, checkCanceled: () -> Unit = {}): ByteArray {
    recording.validate(checkCanceled)
    val output = BoundedOutput()
    factory.createGenerator(output).use { writer ->
      writer.writeStartObject()
      writer.writeNumberField("schemaVersion", recording.schemaVersion)
      writer.writeStringField("collectorVersion", recording.collectorVersion)
      writer.writeStringField("clockUnit", recording.clockUnit)
      writer.writeStringField("sessionId", recording.sessionId)
      writer.writeObjectFieldStart("target")
      writer.writeStringField("displayName", recording.target.displayName)
      writer.nullableString("build", recording.target.build)
      writer.nullableString("runtime", recording.target.runtime)
      writer.nullableString("compiler", recording.target.compiler)
      writer.writeEndObject()
      writer.writeNumberField("durationNs", recording.durationNs)
      writer.writeStringField("status", recording.status.name)
      writer.writeStringField("stopReason", recording.stopReason.name)
      writer.writeArrayFieldStart("capabilities")
      recording.capabilities.sorted().forEach(writer::writeString)
      writer.writeEndArray()
      writer.writeObjectFieldStart("fidelity")
      writer.writeNumberField("abandonedStarts", recording.fidelity.abandonedStarts)
      writer.writeNumberField("discardedPairs", recording.fidelity.discardedPairs)
      writer.writeNumberField("unmatchedEnds", recording.fidelity.unmatchedEnds)
      writer.writeNumberField("rejectedStarts", recording.fidelity.rejectedStarts)
      writer.writeBooleanField(
        "laterActivityUnrecorded",
        recording.fidelity.laterActivityUnrecorded,
      )
      writer.writeEndObject()
      writer.writeArrayFieldStart("sites")
      for (site in recording.sites) {
        checkCanceled()
        writer.writeStartObject()
        writer.writeNumberField("id", site.id)
        writer.writeNumberField("key", site.key)
        writer.writeStringField("info", site.info)
        writer.writeEndObject()
      }
      writer.writeEndArray()
      writer.writeArrayFieldStart("threads")
      for (thread in recording.threads) {
        checkCanceled()
        writer.writeStartObject()
        writer.writeNumberField("id", thread.id)
        writer.writeStringField("name", thread.name)
        writer.writeEndObject()
      }
      writer.writeEndArray()
      writer.writeArrayFieldStart("events")
      for (event in recording.events) {
        checkCanceled()
        writer.writeStartObject()
        writer.writeNumberField("siteId", event.siteId)
        writer.writeNumberField("threadId", event.threadId)
        writer.writeNumberField("startNs", event.startNs)
        writer.writeNumberField("endNs", event.endNs)
        writer.writeNumberField("dirty1", event.dirty1)
        writer.writeNumberField("dirty2", event.dirty2)
        writer.writeEndObject()
      }
      writer.writeEndArray()
      writer.writeEndObject()
    }
    return output.bytes()
  }

  private fun JsonGenerator.nullableString(name: String, value: String?) {
    if (value == null) writeNullField(name) else writeStringField(name, value)
  }

  @Suppress("TooManyFunctions")
  private class Reader(private val parser: JsonParser, private val checkCanceled: () -> Unit) {
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun recording(): Recording {
      var schema = 0
      var collector = ""
      var clock = ""
      var session = ""
      var target: CaptureTarget? = null
      var duration = 0L
      var status: String? = null
      var reason: String? = null
      var capabilities: List<String>? = null
      var fidelity: CaptureFidelity? = null
      var sites: List<TraceSite>? = null
      var threads: List<TraceThread>? = null
      var events: List<TraceEvent>? = null
      fields(
        setOf(
          "schemaVersion",
          "collectorVersion",
          "clockUnit",
          "sessionId",
          "target",
          "durationNs",
          "status",
          "stopReason",
          "capabilities",
          "fidelity",
          "sites",
          "threads",
          "events",
        )
      ) { name ->
        when (name) {
          "schemaVersion" -> schema = integer()
          "collectorVersion" -> collector = string()
          "clockUnit" -> clock = string()
          "sessionId" -> session = string()
          "target" -> target = target()
          "durationNs" -> duration = long()
          "status" -> status = string()
          "stopReason" -> reason = string()
          "capabilities" -> capabilities = array(RecordingLimits.CAPABILITIES.size) { string() }
          "fidelity" -> fidelity = fidelity()
          "sites" -> sites = array(RecordingLimits.SITES) { site() }
          "threads" -> threads = array(RecordingLimits.THREADS) { thread() }
          "events" -> events = array(RecordingLimits.EVENTS) { event() }
        }
      }
      if (
        schema !in 1..RecordingLimits.SCHEMA_VERSION ||
          collector != RecordingLimits.COLLECTOR_VERSION
      )
        throw RecordingFormatException(RecordingError.UNSUPPORTED_VERSION)
      val capabilitiesList = checkNotNull(capabilities)
      requireFormat(capabilitiesList.distinct().size == capabilitiesList.size)
      return Recording(
        session,
        checkNotNull(target),
        duration,
        CaptureStatus.valueOf(checkNotNull(status)),
        StopReason.valueOf(checkNotNull(reason)),
        checkNotNull(fidelity),
        checkNotNull(sites),
        checkNotNull(threads),
        checkNotNull(events),
        schema,
        collector,
        clock,
        java.util.Set.copyOf(capabilitiesList),
      )
    }

    private fun target(): CaptureTarget {
      var name = ""
      var build: String? = null
      var runtime: String? = null
      var compiler: String? = null
      fields(setOf("displayName", "build", "runtime", "compiler")) {
        when (it) {
          "displayName" -> name = string()
          "build" -> build = nullableString()
          "runtime" -> runtime = nullableString()
          "compiler" -> compiler = nullableString()
        }
      }
      return CaptureTarget(name, build, runtime, compiler)
    }

    private fun fidelity(): CaptureFidelity {
      var abandoned = 0
      var discarded = 0
      var unmatched = 0
      var rejected = 0
      var later = false
      fields(
        setOf(
          "abandonedStarts",
          "discardedPairs",
          "unmatchedEnds",
          "rejectedStarts",
          "laterActivityUnrecorded",
        )
      ) {
        when (it) {
          "abandonedStarts" -> abandoned = integer()
          "discardedPairs" -> discarded = integer()
          "unmatchedEnds" -> unmatched = integer()
          "rejectedStarts" -> rejected = integer()
          "laterActivityUnrecorded" -> {
            requireFormat(
              parser.currentToken() == JsonToken.VALUE_TRUE ||
                parser.currentToken() == JsonToken.VALUE_FALSE
            )
            later = parser.booleanValue
          }
        }
      }
      return CaptureFidelity(abandoned, discarded, unmatched, rejected, later)
    }

    private fun site(): TraceSite {
      var id = 0
      var key = 0
      var info = ""
      fields(setOf("id", "key", "info")) {
        when (it) {
          "id" -> id = integer()
          "key" -> key = integer()
          "info" -> info = string()
        }
      }
      return TraceSite(id, key, info)
    }

    private fun thread(): TraceThread {
      var id = 0L
      var name = ""
      fields(setOf("id", "name")) {
        when (it) {
          "id" -> id = long()
          "name" -> name = string()
        }
      }
      return TraceThread(id, name)
    }

    private fun event(): TraceEvent {
      var site = 0
      var thread = 0L
      var start = 0L
      var end = 0L
      var dirty1 = 0
      var dirty2 = 0
      fields(setOf("siteId", "threadId", "startNs", "endNs", "dirty1", "dirty2")) {
        when (it) {
          "siteId" -> site = integer()
          "threadId" -> thread = long()
          "startNs" -> start = long()
          "endNs" -> end = long()
          "dirty1" -> dirty1 = integer()
          "dirty2" -> dirty2 = integer()
        }
      }
      return TraceEvent(site, thread, start, end, dirty1, dirty2)
    }

    private fun fields(expected: Set<String>, read: (String) -> Unit) {
      requireFormat(parser.currentToken() == JsonToken.START_OBJECT)
      val seen = HashSet<String>()
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        checkCanceled()
        requireFormat(parser.currentToken() == JsonToken.FIELD_NAME)
        val name = parser.currentName()
        requireFormat(name in expected && seen.add(name))
        requireFormat(parser.nextToken() != null)
        read(name)
      }
      requireFormat(seen == expected)
    }

    private fun <T> array(limit: Int, read: () -> T): List<T> {
      requireFormat(parser.currentToken() == JsonToken.START_ARRAY)
      val values = ArrayList<T>()
      while (parser.nextToken() != JsonToken.END_ARRAY) {
        checkCanceled()
        requireFormat(parser.currentToken() != null && values.size < limit)
        values.add(read())
      }
      return java.util.List.copyOf(values)
    }

    private fun string(): String {
      requireFormat(parser.currentToken() == JsonToken.VALUE_STRING)
      return parser.text
    }

    private fun nullableString(): String? =
      if (parser.currentToken() == JsonToken.VALUE_NULL) null else string()

    private fun integer(): Int {
      requireFormat(parser.currentToken() == JsonToken.VALUE_NUMBER_INT)
      return parser.intValue
    }

    private fun long(): Long {
      requireFormat(parser.currentToken() == JsonToken.VALUE_NUMBER_INT)
      return parser.longValue
    }
  }

  private class BoundedInput(
    private val delegate: InputStream,
    private val checkCanceled: () -> Unit,
  ) : InputStream() {
    private var remaining = RecordingLimits.FILE_BYTES

    override fun read(): Int {
      checkCanceled()
      val value = delegate.read()
      if (value >= 0 && --remaining < 0) throw RecordingFormatException(RecordingError.TOO_LARGE)
      return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      checkCanceled()
      if (length == 0) return 0
      val count = delegate.read(buffer, offset, minOf(length, remaining + 1))
      if (count == 0) throw IOException("Input made no progress")
      if (count > 0) {
        remaining -= count
        if (remaining < 0) throw RecordingFormatException(RecordingError.TOO_LARGE)
      }
      return count
    }

    override fun close() = delegate.close()
  }

  private class BoundedOutput : OutputStream() {
    private val delegate = ByteArrayOutputStream()

    override fun write(value: Int) {
      reserve(1)
      delegate.write(value)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
      reserve(length)
      delegate.write(buffer, offset, length)
    }

    private fun reserve(length: Int) {
      if (length > RecordingLimits.FILE_BYTES - delegate.size())
        throw RecordingFormatException(RecordingError.TOO_LARGE)
    }

    fun bytes(): ByteArray = delegate.toByteArray()
  }

  private fun requireFormat(value: Boolean) {
    if (!value) throw RecordingFormatException(RecordingError.INVALID_FORMAT)
  }
}
