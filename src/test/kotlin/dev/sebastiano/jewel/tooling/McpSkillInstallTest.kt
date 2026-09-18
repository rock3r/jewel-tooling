package dev.sebastiano.jewel.tooling

import dev.sebastiano.jewel.tooling.mcp.bootstrap.Discovery
import java.nio.file.Files
import java.nio.file.Path
import junit.framework.TestCase

class McpSkillInstallTest : TestCase() {
  private lateinit var root: Path
  private lateinit var file: Path
  private lateinit var support: Path

  override fun setUp() {
    root = Files.createTempDirectory("jewel-skill-tests").toRealPath()
    file = root.resolve("skills/${McpSkillBundle.ID}/SKILL.md")
    support = root.resolve("support")
  }

  override fun tearDown() {
    Files.walk(root).use { paths ->
      paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
  }

  fun testFreshInstallIsOptionalReadOnlyUntilClickedAndIdempotent() {
    val installer = installer("1.0.0")
    assertEquals("absent", installer.inspect(file).phase)
    assertFalse(Files.exists(file.parent))
    assertFalse(Files.exists(support))
    assertEquals("current", installer.install(file).phase)
    val receipt = Files.readString(file.resolveSibling(McpSkillFiles.RECEIPT))
    assertEquals("current", installer.install(file).phase)
    assertEquals(receipt, Files.readString(file.resolveSibling(McpSkillFiles.RECEIPT)))
    Discovery.checkPrivate(file, false)
    assertEquals("1.0.0", installer.inspect(file).installedVersion)
  }

  fun testUpgradeUsesNumericVersionsAndKeepsOneBackup() {
    val original = installer("1.2.0")
    original.install(file)
    val upgraded = installer("1.10.0")
    assertEquals("update", upgraded.inspect(file).phase)
    upgraded.install(file)
    val backup = support.resolve(Discovery.digest(file.toString().toByteArray()) + ".backup")
    assertEquals(original.bundle.text, Files.readString(backup))
    installer("2.0.0").install(file)
    assertEquals(upgraded.bundle.text, Files.readString(backup))
    Files.list(support).use { assertEquals(1L, it.count()) }
    assertEquals("newer", original.install(file).phase)
    assertEquals("2.0.0", original.inspect(file).installedVersion)
  }

  fun testEditedForeignAndSameVersionChangedContentArePreserved() {
    val original = installer("1.0.0")
    original.install(file)
    val edited = original.bundle.text + "\nUser additions\n"
    Files.writeString(file, edited)
    assertFailure("skillConflict") { installer("2.0.0").install(file) }
    assertEquals(edited, Files.readString(file))
    Files.writeString(file, original.bundle.text)
    val changedBundle = McpSkillInstaller(support, McpSkillBundle(edited))
    assertFailure("skillConflict") { changedBundle.install(file) }
    Files.delete(file.resolveSibling(McpSkillFiles.RECEIPT))
    assertEquals("unmanaged", original.inspect(file).phase)
    assertFailure("skillConflict") { installer("2.0.0").install(file) }
    assertEquals(original.bundle.text, Files.readString(file))
  }

  fun testMalformedReceiptAndDeletedManagedFileFailClosed() {
    val installer = installer("1.0.0")
    installer.install(file)
    val receipt = file.resolveSibling(McpSkillFiles.RECEIPT)
    val original = Files.readString(receipt)
    for (invalid in listOf("{}", "not json", original.replace("\"schema\":1", "\"schema\":2"))) {
      Files.writeString(receipt, invalid)
      assertFailure("skillConflict") { installer.install(file) }
      assertEquals(invalid, Files.readString(receipt))
    }
    Files.writeString(receipt, original)
    Files.delete(file)
    assertFailure("skillConflict") { installer.install(file) }
    assertFalse(Files.exists(file))
  }

  fun testInterruptedInitialInstallCanResumeOnlyItsCandidate() {
    val candidate = installer("1.0.0")
    val interrupted =
      McpSkillInstaller(support, candidate.bundle) { throw InterruptedException("test") }
    try {
      interrupted.install(file)
      fail("Expected injected interruption")
    } catch (_: InterruptedException) {
      assertFalse(Files.exists(file))
    }
    assertEquals("interrupted", candidate.inspect(file).phase)
    assertFailure("skillConflict") { installer("2.0.0").install(file) }
    assertEquals("current", candidate.install(file).phase)
  }

  fun testPendingRecoveryUsesMatchingBytesThenAppliesNewBundle() {
    val old = installer("1.0.0")
    old.install(file)
    val pending = installer("2.0.0")
    val receipt = file.resolveSibling(McpSkillFiles.RECEIPT)
    val journal =
      McpSkillReceipt(
          McpSkillGeneration(old.bundle.version, old.bundle.digest),
          McpSkillGeneration(pending.bundle.version, pending.bundle.digest),
        )
        .text()
    for (bytes in listOf(old.bundle.text, pending.bundle.text)) {
      Files.writeString(file, bytes)
      Files.writeString(receipt, journal)
      val next = installer("3.0.0")
      assertEquals("interrupted", next.inspect(file).phase)
      assertEquals(journal, Files.readString(receipt))
      assertEquals("current", next.install(file).phase)
      assertEquals("3.0.0", next.inspect(file).installedVersion)
    }
  }

  fun testNonCooperatingWriteAndOverlappingInstallArePreserved() {
    installer("1.0.0").install(file)
    val candidate = installer("2.0.0")
    val racing =
      McpSkillInstaller(support, candidate.bundle) { Files.writeString(file, "user edit") }
    assertFailure("changed") { racing.install(file) }
    assertEquals("user edit", Files.readString(file))
    McpConfigUpdate(file, support).use { assertFailure("busy") { candidate.install(file) } }
  }

  fun testSymlinkFilesParentsAndWrongDestinationsAreRejected() {
    val installer = installer("1.0.0")
    installer.install(file)
    val original = Files.readString(file)
    val actual = root.resolve("actual.md")
    Files.move(file, actual)
    Files.createSymbolicLink(file, actual)
    assertFailure("unsafePath") { installer.install(file) }
    assertEquals(original, Files.readString(actual))
    val linked = root.resolve("linked")
    Files.createSymbolicLink(linked, file.parent.parent)
    assertFailure("unsafePath") {
      installer.install(linked.resolve("${McpSkillBundle.ID}/SKILL.md"))
    }
    assertFailure("unsafePath") { installer.install(root.resolve("other/SKILL.md")) }
    assertFailure("unsafePath") { installer.install(Path.of("${McpSkillBundle.ID}/SKILL.md")) }
  }

  fun testBundledSkillVersionAndMalformedVersionValidation() {
    assertEquals("1.0.0", McpSkillBundle.bundled().version.text)
    for (version in listOf("1", "1.0", "01.0.0", "1.0.0-beta", "9999999999.0.0")) {
      try {
        McpSkillVersion(version)
        fail("Expected invalid version: $version")
      } catch (_: IllegalArgumentException) {
        // Invalid versions cannot establish ordering or ownership.
      }
    }
  }

  private fun installer(version: String) =
    McpSkillInstaller(
      support,
      McpSkillBundle(
        McpSkillBundle.bundled().text.replace("version: \"1.0.0\"", "version: \"$version\"")
      ),
    )

  private fun assertFailure(reason: String, action: () -> Unit) {
    try {
      action()
      fail("Expected $reason")
    } catch (failure: McpSetupFailure) {
      assertEquals(reason, failure.reason)
    }
  }
}
