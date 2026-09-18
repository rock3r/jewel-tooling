package dev.sebastiano.jewel.tooling

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sebastiano.jewel.tooling.recording.CaptureFidelity
import dev.sebastiano.jewel.tooling.recording.CaptureStatus
import dev.sebastiano.jewel.tooling.recording.CaptureTarget
import dev.sebastiano.jewel.tooling.recording.Recording
import dev.sebastiano.jewel.tooling.recording.SiteSummary
import dev.sebastiano.jewel.tooling.recording.StopReason
import dev.sebastiano.jewel.tooling.recording.TraceEvent
import dev.sebastiano.jewel.tooling.recording.TraceSite
import dev.sebastiano.jewel.tooling.recording.TraceThread
import dev.sebastiano.jewel.tooling.recording.summarize
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.plaf.basic.BasicHTML
import javax.swing.text.JTextComponent

class RecordingReportPanelTest : BasePlatformTestCase() {
  private fun recording(): Recording =
    Recording(
      "123e4567-e89b-12d3-a456-426614174000",
      CaptureTarget("<html>Declared target"),
      50,
      CaptureStatus.STOPPED,
      StopReason.MANUAL,
      CaptureFidelity(0, 0, 0, 0, false),
      listOf(TraceSite(1, 1, "<html><b>First</b>"), TraceSite(2, 2, "Second")),
      listOf(TraceThread(1, "<html>UI")),
      listOf(
        TraceEvent(1, 1, 1, 10, 0, 0),
        TraceEvent(1, 1, 20, 30, 0, 0),
        TraceEvent(2, 1, 0, 40, 0, 0),
      ),
    )

  fun testCompilerTextDoesNotActivateHtmlAndTabLeavesText() {
    val recording = recording()
    val panel = RecordingReportPanel(RecordingReportData(recording, recording.summarize()))
    val components = descendants(panel.component)
    val table = components.filterIsInstance<JTable>().single()
    val row =
      (0 until table.rowCount).single { table.getValueAt(it, 0).toString().startsWith("<html>") }
    val renderer = table.prepareRenderer(table.getCellRenderer(row, 0), row, 0) as JLabel
    assertEquals("<html><b>First</b>", renderer.text)
    assertNull(renderer.getClientProperty(BasicHTML.propertyKey))
    val text = components.filterIsInstance<JTextComponent>()
    assertTrue(text.isNotEmpty())
    assertTrue(text.all { it.focusTraversalKeysEnabled })
    assertTrue(text.any { it.text.contains("<html>Declared target") })
  }

  fun testSelectionUsesTheSortedRowAndShowsThreadCounts() {
    val recording = recording()
    val summaries: List<SiteSummary> = recording.summarize()
    val panel = RecordingReportPanel(RecordingReportData(recording, summaries))
    val components = descendants(panel.component)
    val table = components.filterIsInstance<JTable>().single()
    table.rowSorter.toggleSortOrder(1)
    table.rowSorter.toggleSortOrder(1)
    table.setRowSelectionInterval(0, 0)
    assertEquals(2, table.getValueAt(0, 1))
    val details =
      components.filterIsInstance<JTextComponent>().single {
        it.accessibleContext.accessibleName == JewelToolingBundle.message("recording.site.details")
      }
    assertTrue(details.text.startsWith("<html><b>First</b>"))
    assertTrue(details.text.contains("<html>UI (ID 1): 2"))
  }

  fun testLiteralFilterClearsDetailsAndRestoresRows() {
    val recording = recording()
    val panel = RecordingReportPanel(RecordingReportData(recording, recording.summarize()))
    val components = descendants(panel.component)
    val table = components.filterIsInstance<JTable>().single()
    val field = components.filterIsInstance<JTextField>().single()
    val details =
      components.filterIsInstance<JTextComponent>().single {
        it.accessibleContext.accessibleName == JewelToolingBundle.message("recording.site.details")
      }
    assertSame(field, panel.focus)
    assertTrue(components.filterIsInstance<JLabel>().any { it.labelFor === field })
    table.rowSorter.toggleSortOrder(1)
    field.text = "FIRST"
    assertEquals(1, table.rowCount)
    assertEquals(2, table.getValueAt(0, 1))
    assertTrue(details.text.startsWith("<html><b>First</b>"))
    field.text = ".*"
    assertEquals(0, table.rowCount)
    assertEquals(JewelToolingBundle.message("recording.filter.empty"), details.text)
    assertTrue(
      components.filterIsInstance<JLabel>().any {
        it.text == JewelToolingBundle.message("recording.filter.count", 0, 2)
      }
    )
    components.filterIsInstance<JButton>().single().doClick()
    assertEquals("", field.text)
    assertEquals(2, table.rowCount)
    assertEquals(1, table.getValueAt(0, 1))
    assertTrue(details.text.startsWith("Second"))
  }

  fun testFilterSearchesFullTextAndPreservesVisibleSelection() {
    val recording =
      recording().let { original ->
        original.copy(
          sites =
            listOf(original.sites[0].copy(info = "x".repeat(800) + "Needle[.*]"), original.sites[1])
        )
      }
    val panel = RecordingReportPanel(RecordingReportData(recording, recording.summarize()))
    val components = descendants(panel.component)
    val table = components.filterIsInstance<JTable>().single()
    val field = components.filterIsInstance<JTextField>().single()
    val summary =
      components.filterIsInstance<JTextComponent>().single {
        it.accessibleContext.accessibleName == JewelToolingBundle.message("recording.summary")
      }
    val before = summary.text
    field.text = "needle[.*]"
    assertEquals(1, table.rowCount)
    field.text = ""
    assertEquals(2, table.getValueAt(table.selectedRow, 1))
    assertEquals(before, summary.text)
    table.clearSelection()
    val details =
      components.filterIsInstance<JTextComponent>().single {
        it.accessibleContext.accessibleName == JewelToolingBundle.message("recording.site.details")
      }
    assertEquals("", details.text)
  }

  fun testFilterHandlesTheSiteLimitAndEmptyRecording() {
    val sites = (1..1024).map { TraceSite(it, it, "site-$it") }
    val full =
      recording().copy(sites = sites, events = sites.map { TraceEvent(it.id, 1, 0, 1, 0, 0) })
    val panel = RecordingReportPanel(RecordingReportData(full, full.summarize()))
    val components = descendants(panel.component)
    val table = components.filterIsInstance<JTable>().single()
    val field = components.filterIsInstance<JTextField>().single()
    assertEquals(1024, table.rowCount)
    field.text = "SITE-1024"
    assertEquals(1, table.rowCount)
    field.text = ""
    assertEquals(1024, table.rowCount)
    val empty = recording().copy(sites = emptyList(), events = emptyList())
    val emptyPanel = RecordingReportPanel(RecordingReportData(empty, empty.summarize()))
    assertTrue(
      descendants(emptyPanel.component).filterIsInstance<JTextComponent>().any {
        it.text == JewelToolingBundle.message("recording.empty")
      }
    )
  }

  fun testLiveRefreshPreservesSelectionFilterAndNumericSort() {
    val first = recording()
    val panel = RecordingReportPanel(RecordingReportData(first, first.summarize()))
    val components = descendants(panel.component)
    val table = components.filterIsInstance<JTable>().single()
    val filter = components.filterIsInstance<JTextField>().single()
    table.rowSorter.toggleSortOrder(1)
    table.setRowSelectionInterval(0, 0)
    assertEquals("Second", table.getValueAt(table.selectedRow, 0))
    val next = first.copy(events = first.events + TraceEvent(2, 1, 41, 49, 0, 0))
    panel.update(RecordingReportData(next, next.summarize()))
    assertEquals("Second", table.getValueAt(table.selectedRow, 0))
    assertEquals(2, table.getValueAt(table.selectedRow, 1))
    filter.text = "Second"
    panel.update(RecordingReportData(first, first.summarize()))
    assertEquals("Second", filter.text)
    assertEquals(1, table.rowCount)
    assertEquals(1, table.getValueAt(0, 1))
  }

  private fun descendants(component: Component): List<Component> =
    listOf(component) +
      ((component as? Container)?.components?.flatMap { descendants(it) }).orEmpty()
}
