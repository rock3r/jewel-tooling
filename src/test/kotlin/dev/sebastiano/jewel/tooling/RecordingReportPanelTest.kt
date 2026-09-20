package dev.sebastiano.jewel.tooling

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBTextField
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
import java.awt.event.MouseEvent
import javax.swing.AbstractButton
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JTable
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
        val panel =
            RecordingReportPanel(project, RecordingReportData(recording, recording.summarize()))
        val components = descendants(panel.component)
        val table = components.filterIsInstance<JTable>().single()
        val row =
            (0 until table.rowCount).single {
                table.getValueAt(it, 0).toString().startsWith("<html>")
            }
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
        val panel = RecordingReportPanel(project, RecordingReportData(recording, summaries))
        val components = descendants(panel.component)
        val table = components.filterIsInstance<JTable>().single()
        table.rowSorter.toggleSortOrder(1)
        table.rowSorter.toggleSortOrder(1)
        table.setRowSelectionInterval(0, 0)
        assertEquals(2, table.getValueAt(0, 1))
        val details = detailsText(panel.component)
        assertTrue(details.contains("<html><b>First</b>"))
        assertTrue(details.contains("<html>UI (ID 1)"))
        assertTrue(details.contains("2"))
    }

    fun testLiteralFilterClearsDetailsAndRestoresRows() {
        val recording = recording()
        val panel =
            RecordingReportPanel(project, RecordingReportData(recording, recording.summarize()))
        val components = descendants(panel.component)
        val table = components.filterIsInstance<JTable>().single()
        val field = filterField(components)
        assertSame(field, panel.focus)
        assertTrue(components.filterIsInstance<JLabel>().any { it.labelFor === field })
        table.rowSorter.toggleSortOrder(1)
        field.text = "FIRST"
        assertEquals(1, table.rowCount)
        assertEquals(2, table.getValueAt(0, 1))
        assertTrue(detailsText(panel.component).contains("<html><b>First</b>"))
        field.text = ".*"
        assertEquals(0, table.rowCount)
        assertTrue(
            detailsText(panel.component)
                .contains(JewelToolingBundle.message("recording.filter.empty"))
        )
        assertTrue(
            components.filterIsInstance<JLabel>().any {
                it.text == JewelToolingBundle.message("recording.filter.count", 0, 2)
            }
        )
        components
            .filterIsInstance<JButton>()
            .single { it.name == "jewel-recording-filter-clear" }
            .doClick()
        assertEquals("", field.text)
        assertEquals(2, table.rowCount)
        assertEquals(1, table.getValueAt(0, 1))
        assertTrue(detailsText(panel.component).contains("Second"))
    }

    fun testFilterSearchesFullTextAndPreservesVisibleSelection() {
        val recording =
            recording().let { original ->
                original.copy(
                    sites =
                        listOf(
                            original.sites[0].copy(info = "x".repeat(800) + "Needle[.*]"),
                            original.sites[1],
                        )
                )
            }
        val panel =
            RecordingReportPanel(project, RecordingReportData(recording, recording.summarize()))
        val components = descendants(panel.component)
        val table = components.filterIsInstance<JTable>().single()
        val field = filterField(components)
        val summary =
            components.filterIsInstance<JTextComponent>().single {
                it.accessibleContext.accessibleName ==
                    JewelToolingBundle.message("recording.summary")
            }
        val before = summary.text
        field.text = "needle[.*]"
        assertEquals(1, table.rowCount)
        field.text = ""
        assertEquals(2, table.getValueAt(table.selectedRow, 1))
        assertEquals(before, summary.text)
        table.clearSelection()
        assertEquals("", detailsText(panel.component).trim())
    }

    fun testFilterHandlesTheSiteLimitAndEmptyRecording() {
        val sites = (1..1024).map { TraceSite(it, it, "site-$it") }
        val full =
            recording().copy(sites = sites, events = sites.map { TraceEvent(it.id, 1, 0, 1, 0, 0) })
        val panel = RecordingReportPanel(project, RecordingReportData(full, full.summarize()))
        val components = descendants(panel.component)
        val table = components.filterIsInstance<JTable>().single()
        val field = filterField(components)
        assertEquals(1024, table.rowCount)
        field.text = "SITE-1024"
        assertEquals(1, table.rowCount)
        field.text = ""
        assertEquals(1024, table.rowCount)
        val empty = recording().copy(sites = emptyList(), events = emptyList())
        val emptyPanel =
            RecordingReportPanel(project, RecordingReportData(empty, empty.summarize()))
        assertTrue(
            detailsText(emptyPanel.component)
                .contains(JewelToolingBundle.message("recording.empty"))
        )
    }

    fun testLiveRefreshPreservesSelectionFilterAndNumericSort() {
        val first = recording()
        val panel = RecordingReportPanel(project, RecordingReportData(first, first.summarize()))
        val components = descendants(panel.component)
        val table = components.filterIsInstance<JTable>().single()
        val filter = filterField(components)
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

    fun testDefaultSortsByExecutionsDescending() {
        val recording = recording()
        val panel =
            RecordingReportPanel(project, RecordingReportData(recording, recording.summarize()))
        val table = descendants(panel.component).filterIsInstance<JTable>().single()
        assertEquals(2, table.getValueAt(0, 1))
        assertTrue(table.getValueAt(0, 0).toString().startsWith("<html>"))
    }

    fun testFilterFieldUsesRoundedCorners() {
        val recording = recording()
        val panel =
            RecordingReportPanel(project, RecordingReportData(recording, recording.summarize()))
        val field = filterField(descendants(panel.component))
        assertEquals(true, field.getClientProperty("JComponent.roundRect"))
    }

    fun testHideDependenciesKeepsProjectSites() {
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
            recording()
                .copy(
                    sites =
                        listOf(
                            TraceSite(1, 7, "example.GreetingRow (Greeting.kt:3)"),
                            TraceSite(
                                2,
                                8,
                                "androidx.compose.runtime.CompositionLocalProvider (CompositionLocal.kt:405)",
                            ),
                        ),
                    events = listOf(TraceEvent(1, 1, 0, 40, 0, 0), TraceEvent(2, 1, 0, 10, 0, 0)),
                )
        val panel =
            RecordingReportPanel(project, RecordingReportData(recording, recording.summarize()))
        val components = descendants(panel.component)
        val table = components.filterIsInstance<JTable>().single()
        assertEquals(2, table.rowCount)
        val hide =
            components.filterIsInstance<JCheckBox>().single {
                it.name == "jewel-recording-filter-dependencies"
            }
        hide.doClick()
        assertEquals(1, table.rowCount)
        assertTrue(table.getValueAt(0, 0).toString().contains("GreetingRow"))
        panel.selectSite(2)
        assertFalse(hide.isSelected)
        assertEquals(2, table.rowCount)
        assertTrue(table.getValueAt(table.selectedRow, 0).toString().contains("CompositionLocal"))
    }

    fun testDurationBadgePressConsumesTheClick() {
        myFixture.configureByText("Greeting.kt", "fun Greeting() {}\n")
        val renderer =
            LiveInspectionDurationRenderer(
                LiveEditorHint(1, "0.812 ms", "tip", "accessible", false),
                myFixture.editor,
            )
        val event =
            MouseEvent(
                myFixture.editor.contentComponent,
                MouseEvent.MOUSE_PRESSED,
                0L,
                0,
                0,
                0,
                1,
                false,
                MouseEvent.BUTTON1,
            )
        renderer.mousePressed(event, java.awt.Point())
        assertTrue(event.isConsumed)
        renderer.mouseMoved(event, java.awt.Point())
        renderer.mouseExited()
        val inlay =
            checkNotNull(
                myFixture.editor.inlayModel.addAfterLineEndElement(
                    myFixture.editor.document.getLineEndOffset(0),
                    com.intellij.openapi.editor
                        .InlayProperties()
                        .relatesToPrecedingText(true)
                        .disableSoftWrapping(true),
                    renderer,
                )
            )
        myFixture.editor.contentComponent.setSize(800, 400)
        val bounds = inlay.bounds
        if (bounds != null) {
            assertSame(
                renderer,
                LiveInspectionGutter.rendererAt(
                    myFixture.editor,
                    java.awt.Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2),
                ),
            )
        }
    }

    fun testDoubleClickOpensTheResolvedFile() {
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
        val recording =
            recording()
                .copy(sites = listOf(TraceSite(1, 7, "example.GreetingRow (Greeting.kt:3)")))
                .let { it.copy(events = listOf(TraceEvent(1, 1, 0, 40, 0, 0))) }
        val panel =
            RecordingReportPanel(project, RecordingReportData(recording, recording.summarize()))
        val table = descendants(panel.component).filterIsInstance<JTable>().single()
        val cell = table.getCellRect(0, 0, true)
        table.dispatchEvent(
            MouseEvent(
                table,
                MouseEvent.MOUSE_CLICKED,
                0L,
                0,
                cell.centerX.toInt(),
                cell.centerY.toInt(),
                2,
                false,
                MouseEvent.BUTTON1,
            )
        )
        assertEquals(file.virtualFile, FileEditorManager.getInstance(project).openFiles.single())
    }

    fun testDetailsShowFileLinkForCompilerLocations() {
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
            recording()
                .copy(sites = listOf(TraceSite(1, 7, "example.GreetingRow (Greeting.kt:3)")))
                .let { it.copy(events = listOf(TraceEvent(1, 1, 0, 40, 0, 0))) }
        val panel =
            RecordingReportPanel(project, RecordingReportData(recording, recording.summarize()))
        val details = detailsText(panel.component)
        assertTrue(details.contains("example.GreetingRow"))
        assertTrue(details.contains("Greeting.kt:3"))
        val link =
            descendants(panel.component).filterIsInstance<ActionLink>().single {
                it.text == "Greeting.kt:3"
            }
        assertEquals(
            JewelToolingBundle.message("recording.details.file.accessible", "Greeting.kt:3"),
            link.accessibleContext.accessibleName,
        )
    }

    fun testDetailsShowFileLinkForLibraryLocations() {
        val dir = FileUtil.createTempDirectory("compose-runtime", "src")
        com.intellij.openapi.util.Disposer.register(testRootDisposable) { FileUtil.delete(dir) }
        FileUtil.writeToFile(
            java.io.File(dir, "Composables.kt"),
            """
            package androidx.compose.runtime
            val currentCompositeKeyHashCode = 1
            """
                .trimIndent(),
        )
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!
        ModuleRootModificationUtil.addModuleLibrary(
            myFixture.module,
            "compose-runtime",
            emptyList(),
            listOf(root.url),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val recording =
            recording()
                .copy(
                    sites =
                        listOf(
                            TraceSite(
                                1,
                                7,
                                "androidx.compose.runtime.<get-currentCompositeKeyHashCode> (Composables.kt:2)",
                            )
                        )
                )
                .let { it.copy(events = listOf(TraceEvent(1, 1, 0, 40, 0, 0))) }
        val panel =
            RecordingReportPanel(project, RecordingReportData(recording, recording.summarize()))
        val link =
            descendants(panel.component).filterIsInstance<ActionLink>().single {
                it.text == "Composables.kt:2"
            }
        assertEquals(
            JewelToolingBundle.message("recording.details.file.accessible", "Composables.kt:2"),
            link.accessibleContext.accessibleName,
        )
    }

    fun testDetailsShowPlainFileNameWhenTheLocationDoesNotResolve() {
        val recording =
            recording()
                .copy(
                    sites = listOf(TraceSite(1, 7, "androidx.compose.material3.Text (Text.kt:12)"))
                )
                .let { it.copy(events = listOf(TraceEvent(1, 1, 0, 40, 0, 0))) }
        val panel =
            RecordingReportPanel(project, RecordingReportData(recording, recording.summarize()))
        val details = detailsText(panel.component)
        assertTrue(details.contains("Text.kt:12"))
        assertTrue(
            descendants(panel.component).filterIsInstance<ActionLink>().none {
                it.text.contains("Text.kt")
            }
        )
    }

    private fun filterField(components: List<Component>): JBTextField =
        components.single { it.name == "jewel-recording-filter" } as JBTextField

    private fun detailsText(root: Component): String =
        descendants(descendants(root).single { it.name == "jewel-recording-details" }).joinToString(
            "\n"
        ) { component ->
            when (component) {
                is JTextComponent -> component.text
                is JLabel -> component.text.orEmpty()
                is AbstractButton -> component.text.orEmpty()
                else -> ""
            }
        }

    private fun descendants(component: Component): List<Component> =
        listOf(component) +
            ((component as? Container)?.components?.flatMap { descendants(it) }).orEmpty()
}
