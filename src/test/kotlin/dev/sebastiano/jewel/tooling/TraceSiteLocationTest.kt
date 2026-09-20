package dev.sebastiano.jewel.tooling

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
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
        val location =
            checkNotNull(TraceSiteLocations.parse("example.GreetingRow (Greeting.kt:12)"))
        assertEquals("example.GreetingRow", location.qualifiedName)
        assertEquals("example", location.packageName)
        assertEquals("GreetingRow", location.simpleName)
        assertEquals("Greeting.kt", location.fileName)
        assertEquals(12, location.line)
        val getter =
            checkNotNull(
                TraceSiteLocations.parse(
                    "androidx.compose.runtime.<get-currentCompositeKeyHashCode> (Composables.kt:268)"
                )
            )
        assertEquals("androidx.compose.runtime", getter.packageName)
        assertEquals("<get-currentCompositeKeyHashCode>", getter.simpleName)
        assertEquals("Composables.kt", getter.fileName)
        assertEquals(268, getter.line)
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
            ReadAction.compute<
                com.intellij.openapi.fileEditor.OpenFileDescriptor,
                RuntimeException,
            > {
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
        val location =
            checkNotNull(TraceSiteLocations.parse("example.layout.BoxScope.Box (Box.kt:3)"))
        val descriptor =
            ReadAction.compute<
                com.intellij.openapi.fileEditor.OpenFileDescriptor,
                RuntimeException,
            > {
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
        val location =
            checkNotNull(TraceSiteLocations.parse("example.GreetingRow (Greeting.kt:99)"))
        val descriptor =
            ReadAction.compute<
                com.intellij.openapi.fileEditor.OpenFileDescriptor,
                RuntimeException,
            > {
                TraceSiteLocations.resolve(project, location)
            }
        assertNotNull(descriptor)
        assertEquals(file.virtualFile, descriptor!!.file)
        assertEquals(2, descriptor.line)
    }

    fun testResolveOpensOneFileWhenSeveralShareTheName() {
        myFixture.addFileToProject(
            "common/CompositionLocal.kt",
            """
            package androidx.compose.runtime
            fun CompositionLocalProvider() {}
            """
                .trimIndent(),
        )
        myFixture.addFileToProject(
            "android/CompositionLocal.kt",
            """
            package androidx.compose.runtime
            fun CompositionLocalProvider() {}
            """
                .trimIndent(),
        )
        val location =
            checkNotNull(
                TraceSiteLocations.parse(
                    "androidx.compose.runtime.CompositionLocalProvider (CompositionLocal.kt:2)"
                )
            )
        val descriptor =
            ReadAction.compute<
                com.intellij.openapi.fileEditor.OpenFileDescriptor,
                RuntimeException,
            > {
                TraceSiteLocations.resolve(project, location)
            }
        assertNotNull(descriptor)
        assertEquals("CompositionLocal.kt", descriptor!!.file.name)
    }

    fun testResolveOpensALibrarySourceFile() {
        libraryKotlinRoot()
        val location =
            checkNotNull(
                TraceSiteLocations.parse(
                    "androidx.compose.runtime.<get-currentCompositeKeyHashCode> (Composables.kt:2)"
                )
            )
        val descriptor =
            ReadAction.compute<
                com.intellij.openapi.fileEditor.OpenFileDescriptor,
                RuntimeException,
            > {
                TraceSiteLocations.resolve(project, location)
            }
        assertNotNull(descriptor)
        assertEquals("Composables.kt", descriptor!!.file.name)
        val recording = libraryRecording()
        val links =
            ReadAction.compute<TraceSiteLinks, RuntimeException> {
                TraceSiteLocations.links(
                    project,
                    RecordingReportData(recording, recording.summarize()),
                )
            }
        assertTrue(location in links.openable)
        assertTrue(links.projectSiteIds.orEmpty().isEmpty())
    }

    fun testMapAnnotatesALibrarySourceFile() {
        val root = libraryKotlinRoot()
        val recording = libraryRecording()
        val hints =
            ReadAction.compute<Map<String, Map<Int, LiveEditorHint>>, RuntimeException> {
                TraceSiteLocations.map(
                    project,
                    RecordingReportData(recording, recording.summarize()),
                )
            }
        val byLine = hints[root.findChild("Composables.kt")!!.url]
        assertNotNull(byLine)
        val hint = checkNotNull(byLine?.get(1))
        assertEquals(1, hint.siteId)
    }

    fun testResolveOpensALibraryClassWhenTheKotlinFileIsAbsent() {
        val stdlib = kotlinStdlibJar(testRootDisposable)
        PsiTestUtil.addLibrary(myFixture.module, "kotlin-stdlib", stdlib.parent, stdlib.name)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val location = checkNotNull(TraceSiteLocations.parse("kotlin.Unit (Unit.kt:1)"))
        val descriptor =
            ReadAction.compute<
                com.intellij.openapi.fileEditor.OpenFileDescriptor,
                RuntimeException,
            > {
                TraceSiteLocations.resolve(project, location)
            }
        assertNotNull(descriptor)
        assertTrue(descriptor!!.file.name.startsWith("Unit"))
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
                TraceSiteLocations.map(
                    project,
                    RecordingReportData(recording, recording.summarize()),
                )
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

    fun testMapAnnotatesTheFunctionNameForAMultilineSignature() {
        myFixture.configureByText(
            "Helpers.kt",
            """
            package example
            annotation class Composable
            @Composable
            fun rememberEntranceAnimationState(
              hasSessions: Boolean,
            ): Int {
              return 0
            }
            """
                .trimIndent(),
        )
        val recording =
            Recording(
                "123e4567-e89b-12d3-a456-426614174000",
                CaptureTarget("fixture"),
                3_557_000,
                CaptureStatus.STOPPED,
                StopReason.MANUAL,
                CaptureFidelity(0, 0, 0, 0, false),
                listOf(TraceSite(1, 7, "example.rememberEntranceAnimationState (Helpers.kt:6)")),
                listOf(TraceThread(1, "EDT")),
                listOf(TraceEvent(1, 1, 0, 3_557_000, 0, 0)),
            )
        val hints =
            ReadAction.compute<Map<String, Map<Int, LiveEditorHint>>, RuntimeException> {
                TraceSiteLocations.map(
                    project,
                    RecordingReportData(recording, recording.summarize()),
                )
            }
        val byLine = hints.values.single()
        assertNull(byLine[5])
        val hint = byLine[3]
        assertNotNull(hint)
        assertEquals(
            JewelToolingBundle.message(
                "live.editor.gutter",
                LiveInspectionFormat.milliseconds(3.557),
            ),
            hint!!.text,
        )
    }

    private fun libraryKotlinRoot(): com.intellij.openapi.vfs.VirtualFile {
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
        return root
    }

    private fun libraryRecording(): Recording =
        Recording(
            "123e4567-e89b-12d3-a456-426614174000",
            CaptureTarget("fixture"),
            40,
            CaptureStatus.STOPPED,
            StopReason.MANUAL,
            CaptureFidelity(0, 0, 0, 0, false),
            listOf(
                TraceSite(
                    1,
                    7,
                    "androidx.compose.runtime.<get-currentCompositeKeyHashCode> (Composables.kt:2)",
                )
            ),
            listOf(TraceThread(1, "EDT")),
            listOf(TraceEvent(1, 1, 0, 40, 0, 0)),
        )
}
