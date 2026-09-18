package dev.sebastiano.jewel.tooling

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal class McpSetupFailure(val reason: String) : Exception(reason)

/** Edits one JSONC value while preserving unrelated text and comments. */
internal class McpJsonConfig(private val text: String) {
  private val root = McpJsonParser(text).parse()

  fun objectValue(): JsonObject = root.value.asJsonObject

  fun value(path: List<String>): JsonElement? =
    path.fold(root as McpJsonNode?) { node, key -> node?.fields?.get(key) }?.value

  fun put(path: List<String>, value: JsonElement): String {
    require(path.isNotEmpty())
    return edit(root, path, value)
  }

  private fun edit(node: McpJsonNode, path: List<String>, value: JsonElement): String {
    val fields = node.fields ?: throw McpSetupFailure("invalidConfig")
    val child = fields[path.first()]
    if (child != null) {
      return if (path.size > 1) edit(child, path.drop(1), value)
      else text.replaceRange(child.start, child.end, value.toString())
    }
    val nested =
      path.drop(1).asReversed().fold(value) { result, key ->
        JsonObject().apply { add(key, result) }
      }
    val comma = if (fields.isEmpty()) "" else ","
    return text.replaceRange(
      node.start + 1,
      node.start + 1,
      "\n  ${Gson().toJson(path.first())}: $nested$comma\n",
    )
  }
}

private data class McpJsonNode(
  val start: Int,
  val end: Int,
  val value: JsonElement,
  val fields: Map<String, McpJsonNode>? = null,
)

private class McpJsonParser(private val text: String) {
  private var offset = 0

  fun parse(): McpJsonNode {
    val result = value(0)
    trivia()
    if (offset != text.length || result.fields == null) invalid()
    return result
  }

  private fun value(depth: Int): McpJsonNode {
    if (depth > MAX_DEPTH) invalid()
    trivia()
    val start = offset
    return when (text.getOrNull(offset)) {
      '{' -> obj(depth, start)
      '[' -> array(depth, start)
      '"' -> McpJsonNode(start, stringEnd(), JsonParser.parseString(text.substring(start, offset)))
      else -> scalar(start)
    }
  }

  private fun obj(depth: Int, start: Int): McpJsonNode {
    offset++
    trivia()
    val fields = linkedMapOf<String, McpJsonNode>()
    val result = JsonObject()
    while (text.getOrNull(offset) != '}') {
      val keyStart = offset
      stringEnd()
      val key = JsonParser.parseString(text.substring(keyStart, offset)).asString
      if (key in fields) invalid()
      trivia()
      expect(':')
      val child = value(depth + 1)
      fields[key] = child
      result.add(key, child.value)
      trivia()
      if (text.getOrNull(offset) == '}') break
      expect(',')
      trivia()
    }
    expect('}')
    return McpJsonNode(start, offset, result, fields)
  }

  private fun array(depth: Int, start: Int): McpJsonNode {
    offset++
    trivia()
    val result = JsonArray()
    while (text.getOrNull(offset) != ']') {
      result.add(value(depth + 1).value)
      trivia()
      if (text.getOrNull(offset) == ']') break
      expect(',')
      trivia()
    }
    expect(']')
    return McpJsonNode(start, offset, result)
  }

  private fun scalar(start: Int): McpJsonNode {
    while (text.getOrNull(offset)?.let { !it.isWhitespace() && it !in ",]}/" } == true) offset++
    val token = text.substring(start, offset)
    if (!SCALAR.matches(token)) invalid()
    return McpJsonNode(
      start,
      offset,
      if (token == "null") JsonNull.INSTANCE else JsonParser.parseString(token),
    )
  }

  private fun stringEnd(): Int {
    expect('"')
    while (true) {
      val ch = text.getOrNull(offset++) ?: invalid()
      when {
        ch == '"' -> return offset
        ch == '\\' -> {
          val escaped = text.getOrNull(offset++) ?: invalid()
          if (escaped == 'u') {
            repeat(UNICODE_ESCAPE_DIGITS) {
              if (text.getOrNull(offset++)?.digitToIntOrNull(HEX_RADIX) == null) invalid()
            }
          } else if (escaped !in "\"\\/bfnrt") invalid()
        }
        ch.code < ' '.code -> invalid()
      }
    }
  }

  private fun trivia() {
    while (offset < text.length) {
      when {
        text[offset].isWhitespace() -> offset++
        text.startsWith("//", offset) -> {
          val end = text.indexOf('\n', offset + 2)
          offset = if (end < 0) text.length else end + 1
        }
        text.startsWith("/*", offset) -> {
          val end = text.indexOf("*/", offset + 2)
          if (end < 0) invalid()
          offset = end + 2
        }
        else -> return
      }
    }
  }

  private fun expect(ch: Char) {
    if (text.getOrNull(offset) != ch) invalid()
    offset++
  }

  private fun invalid(): Nothing = throw McpSetupFailure("invalidConfig")

  companion object {
    private const val MAX_DEPTH = 64
    private const val UNICODE_ESCAPE_DIGITS = 4
    private const val HEX_RADIX = 16
    private val SCALAR =
      Regex("(?:true|false|null|-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)")
  }
}
