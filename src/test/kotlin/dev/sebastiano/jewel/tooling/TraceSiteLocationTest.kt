package dev.sebastiano.jewel.tooling

import com.intellij.openapi.application.ReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sebastiano.jewel.tooling.recording.CaptureFidelity
import dev.sebastiano.jewel.tooling.recording.CaptureStatus
import dev.sebastiano.jewel.tooling.recording.CaptureTarget
import dev.sebastiano.jewel.tooling.recording.Recording
import dev.sebastiano.jewel.tooling.recording.StopReason
import dev.sebastiano.jewel.tooling.recording.TraceEvent
import dev.sebastiano.jewel.tooling.recording.TraceSite
import dev.sebastiano.jewel.tooling.recording.TraceThread
import dev.sebastiano.jewel.tooling.recording.summarize

class TraceSiteLocationTest : BasePlatformTestCase() {
  fun testParseQualifiedCompilerText() {
    val location = checkNotNull(TraceSiteLocations.parse("example.GreetingRow (Greeting.kt:12)"))
    assertEquals("example.GreetingRow", location.qualifiedName)
    assertEquals("example", location.packageName)
    assertEquals("GreetingRow", location.simpleName)
    assertEquals("Greeting.kt", location.fileName)
    assertEquals(12, location.line)
    assertNull(TraceSiteLocations.parse("<html><b>First</b>"))
    assertNull(TraceSiteLocations.parse("C(Text)"))
  }

  fun testResolveOpensTheMatchingProjectFile() {
    val file =
      myFixture.configureByText(
        "Greeting.kt",
        """
        package example
        annotation class Composable
        @Composable fun GreetingRow() {}
        """
          .trimIndent(),
      )
    val location = checkNotNull(TraceSiteLocations.parse("example.GreetingRow (Greeting.kt:3)"))
    val descriptor =
      ReadAction.compute<com.intellij.openapi.fileEditor.OpenFileDescriptor, RuntimeException> {
        TraceSiteLocations.resolve(project, location)
      }
    assertNotNull(descriptor)
    assertEquals(file.virtualFile, descriptor!!.file)
    assertEquals(2, descriptor.line)
  }

  fun testResolveOpensNestedNameInTheFilePackage() {
    val file =
      myFixture.configureByText(
        "Box.kt",
        """
        package example.layout
        annotation class Composable
        @Composable fun Box() {}
        """
          .trimIndent(),
      )
    val location = checkNotNull(TraceSiteLocations.parse("example.layout.BoxScope.Box (Box.kt:3)"))
    val descriptor =
      ReadAction.compute<com.intellij.openapi.fileEditor.OpenFileDescriptor, RuntimeException> {
        TraceSiteLocations.resolve(project, location)
      }
    assertNotNull(descriptor)
    assertEquals(file.virtualFile, descriptor!!.file)
  }

  fun testResolveOpensTheFileWhenTheRecordedLineIsPastTheEnd() {
    val file =
      myFixture.configureByText(
        "Greeting.kt",
        """
        package example
        annotation class Composable
        @Composable fun GreetingRow() {}
        """
          .trimIndent(),
      )
    val location = checkNotNull(TraceSiteLocations.parse("example.GreetingRow (Greeting.kt:99)"))
    val descriptor =
      ReadAction.compute<com.intellij.openapi.fileEditor.OpenFileDescriptor, RuntimeException> {
        TraceSiteLocations.resolve(project, location)
      }
    assertNotNull(descriptor)
    assertEquals(file.virtualFile, descriptor!!.file)
    assertEquals(2, descriptor.line)
  }

  fun testMapAnnotatesTheRecordedLine() {
    myFixture.configureByText(
      "Greeting.kt",
      """
      package example
      annotation class Composable
      @Composable fun GreetingRow() {}
      """
        .trimIndent(),
    )
    val recording =
      Recording(
        "123e4567-e89b-12d3-a456-426614174000",
        CaptureTarget("fixture"),
        40,
        CaptureStatus.STOPPED,
        StopReason.MANUAL,
        CaptureFidelity(0, 0, 0, 0, false),
        listOf(TraceSite(1, 7, "example.GreetingRow (Greeting.kt:3)")),
        listOf(TraceThread(1, "EDT")),
        listOf(TraceEvent(1, 1, 0, 40, 0, 0)),
      )
    val hints =
      ReadAction.compute<Map<String, Map<Int, LiveEditorHint>>, RuntimeException> {
        TraceSiteLocations.map(project, RecordingReportData(recording, recording.summarize()))
      }
    val byLine = hints.values.single()
    val hint = byLine[2]
    assertNotNull(hint)
    assertEquals(1, hint!!.siteId)
    assertTrue(hint.text.endsWith(" ms"))
    assertTrue(hint.hot)
    assertTrue(hint.tooltip.contains("<html>"))
    assertTrue(hint.tooltip.contains("<b>"))
    assertTrue(hint.tooltip.contains("100"))
    assertTrue(hint.accessible.contains("100"))
  }
}
