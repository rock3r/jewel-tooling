package dev.sebastiano.jewel.tooling.mcp.runtime

import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.InitializeResult
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCError
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.LATEST_PROTOCOL_VERSION
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import io.modelcontextprotocol.kotlin.sdk.types.SUPPORTED_PROTOCOL_VERSIONS
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class VersionTransport(private val delegate: Transport) : Transport by delegate {
  @Volatile
  var protocolVersion: String = LATEST_PROTOCOL_VERSION
    private set

  @Volatile
  var accepted = false
    private set

  override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
    if (message is JSONRPCResponse && message.result is InitializeResult) accepted = true
    delegate.send(message, options)
  }

  override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) {
    delegate.onMessage { message ->
      if (message is JSONRPCRequest && message.method == "initialize") {
        val requested =
          (message.params as? JsonObject)?.get("protocolVersion")?.jsonPrimitive?.content
        if (requested == "2024-11-05" || requested == "2025-03-26") {
          delegate.send(
            JSONRPCError(
              message.id,
              RPCError(
                RPCError.ErrorCode.INVALID_PARAMS,
                "Protocol 2025-06-18 or newer is required",
              ),
            )
          )
          return@onMessage
        }
        protocolVersion =
          requested?.takeIf { it in SUPPORTED_PROTOCOL_VERSIONS } ?: LATEST_PROTOCOL_VERSION
      }
      block(message)
    }
  }
}
