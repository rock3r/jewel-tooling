package dev.sebastiano.jewel.tooling

import com.intellij.openapi.diagnostic.rethrowControlFlowException
import dev.sebastiano.jewel.tooling.mcp.bootstrap.Discovery
import java.nio.file.Path

internal data class McpSkillStatus(val phase: String, val installedVersion: String = "")

internal class McpSkillInstaller(
  private val support: Path,
  val bundle: McpSkillBundle = McpSkillBundle.bundled(),
  private val beforeCommit: () -> Unit = {},
) {
  fun inspect(destination: Path): McpSkillStatus {
    val path = McpSkillFiles.destination(destination)
    return classify(McpConfigFiles.read(path), readReceipt(path))
  }

  fun install(destination: Path): McpSkillStatus {
    val path = McpSkillFiles.destination(destination)
    McpConfigUpdate(path, support).use { update ->
      val receiptPath = path.resolveSibling(McpSkillFiles.RECEIPT)
      var receipt = readReceipt(path)
      val initial = classify(update.before, receipt)
      if (initial.phase == "interrupted") {
        val matching = matching(update.before, receipt)
        if (matching != null) {
          receipt = McpSkillReceipt(matching, null)
          McpConfigFiles.privateWrite(receiptPath, receipt.text().toByteArray())
        }
      }
      val status = classify(update.before, receipt, recovering = true)
      if (status.phase == "current" || status.phase == "unmanaged" || status.phase == "newer")
        return status
      val candidate = McpSkillGeneration(bundle.version, bundle.digest)
      val journal =
        McpSkillReceipt(matching(update.before, receipt), candidate).text().toByteArray()
      McpConfigFiles.privateWrite(receiptPath, journal)
      beforeCommit()
      verifyReceipt(receiptPath, journal)
      update.commit(bundle.text)
      verifyReceipt(receiptPath, journal)
      McpConfigFiles.privateWrite(
        receiptPath,
        McpSkillReceipt(candidate, null).text().toByteArray(),
      )
      return McpSkillStatus("current", bundle.version.text)
    }
  }

  private fun classify(
    bytes: ByteArray?,
    receipt: McpSkillReceipt?,
    recovering: Boolean = false,
  ): McpSkillStatus {
    val generation = matching(bytes, receipt)
    if (bytes == null || receipt == null) return unowned(bytes, receipt, recovering)
    if (generation == null) conflict()
    return if (receipt.pending != null && !recovering)
      McpSkillStatus("interrupted", generation.version.text)
    else compare(generation)
  }

  private fun compare(generation: McpSkillGeneration): McpSkillStatus {
    val comparison = generation.version.compareTo(bundle.version)
    if (comparison == 0 && generation.digest != bundle.digest) conflict()
    return McpSkillStatus(
      when {
        comparison > 0 -> "newer"
        comparison < 0 -> "update"
        else -> "current"
      },
      generation.version.text,
    )
  }

  private fun unowned(
    bytes: ByteArray?,
    receipt: McpSkillReceipt?,
    recovering: Boolean,
  ): McpSkillStatus {
    if (bytes == null) {
      if (
        receipt != null && (receipt.installed != null || receipt.pending?.digest != bundle.digest)
      )
        conflict()
      return McpSkillStatus(if (receipt == null || recovering) "absent" else "interrupted")
    }
    if (Discovery.digest(bytes) != bundle.digest) conflict()
    return McpSkillStatus("unmanaged", bundle.version.text)
  }

  private fun matching(bytes: ByteArray?, receipt: McpSkillReceipt?): McpSkillGeneration? {
    if (bytes == null || receipt == null) return null
    val digest = Discovery.digest(bytes)
    return listOfNotNull(receipt.pending, receipt.installed).firstOrNull { it.digest == digest }
  }

  @Suppress(
    "TooGenericExceptionCaught"
  ) // Reject malformed ownership metadata without trusting its version.
  private fun readReceipt(path: Path): McpSkillReceipt? =
    try {
      McpSkillReceipt.parse(
        McpConfigFiles.read(path.resolveSibling(McpSkillFiles.RECEIPT), McpSkillFiles.RECEIPT_LIMIT)
      )
    } catch (failure: Exception) {
      rethrowControlFlowException(failure)
      conflict()
    }

  private fun verifyReceipt(path: Path, expected: ByteArray) {
    if (!expected.contentEquals(McpConfigFiles.read(path, McpSkillFiles.RECEIPT_LIMIT)))
      throw McpSetupFailure("changed")
  }

  private fun conflict(): Nothing = throw McpSetupFailure("skillConflict")
}
