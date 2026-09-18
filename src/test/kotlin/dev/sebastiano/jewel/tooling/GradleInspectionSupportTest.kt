package dev.sebastiano.jewel.tooling

import java.nio.file.Path
import junit.framework.TestCase

class GradleInspectionSupportTest : TestCase() {
  fun testInitScriptIgnoresTaskGraphsWithoutTheRequestedTask() {
    val script =
      GradleInspectionScript.create(
        listOf(":run"),
        Path.of("/tmp/inspection-agent.jar"),
        Path.of("/tmp/launch"),
        "nonce-1",
      )
    assertTrue(script, script.contains("def matching = graph.allTasks.findAll"))
    assertTrue(script, script.contains("if (matching.isEmpty()) return"))
    assertTrue(script, script.contains("matching.findAll { it instanceof JavaExec }"))
  }

  fun testInitScriptStillRequiresOneMatchingJavaExec() {
    val script =
      GradleInspectionScript.create(
        listOf(":app:run"),
        Path.of("/tmp/inspection-agent.jar"),
        Path.of("/tmp/launch"),
        "nonce-1",
      )
    assertTrue(script, script.contains("if (selected.size() != 1)"))
    assertTrue(script, script.contains("':app:run'"))
  }
}
