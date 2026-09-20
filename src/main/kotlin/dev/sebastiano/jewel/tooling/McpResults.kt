package dev.sebastiano.jewel.tooling

import com.google.gson.Gson

internal object McpResults {
    private val gson = Gson()

    fun success(data: Any): String =
        gson.toJson(
            mapOf("schemaVersion" to 1, "payload" to mapOf("kind" to "result", "data" to data))
        )

    fun failure(code: String): String =
        gson.toJson(
            mapOf(
                "schemaVersion" to 1,
                "payload" to
                    mapOf(
                        "kind" to "error",
                        "code" to code,
                        "message" to JewelToolingBundle.message("mcp.error.$code"),
                        "retryable" to
                            (code in
                                setOf(
                                    "INDEXING",
                                    "STALE_LOCATION",
                                    "BUSY",
                                    "TIMEOUT",
                                    "CANCELLED",
                                )),
                        "remedy" to JewelToolingBundle.message("mcp.remedy.$code"),
                    ),
            )
        )
}

internal class McpFailure(val code: String) : RuntimeException(code)

internal data class McpPosition(val line: Int, val column: Int)

internal data class McpLocation(val file: String, val start: McpPosition, val end: McpPosition)

internal data class McpEvidence(val code: String, val provenance: String)

internal data class McpReason(val code: String, val text: String, val locations: List<McpLocation>)

internal data class McpParameter(
    val name: String,
    val sourceType: String,
    val resolvedType: String,
    val location: McpLocation,
    val stability: String,
    val evidence: List<McpEvidence>,
    val reasons: List<McpReason>,
    val incomplete: List<String>,
)

internal data class McpDeclaration(
    val id: String,
    val name: String,
    val location: McpLocation,
    val parameters: List<McpParameter>,
    val summary: Map<String, Int>,
    val skippability: String = "NOT_ANALYZED",
)

internal data class McpSnapshot(
    val projectId: String,
    val generation: String,
    val file: String,
    val documentRevision: String,
    val contentHash: String,
    val psiRevision: String,
    val declarations: List<McpDeclaration>,
    val incomplete: List<String>,
    val limits: Map<String, Int> = McpAnalysisFacade.LIMITS,
)
