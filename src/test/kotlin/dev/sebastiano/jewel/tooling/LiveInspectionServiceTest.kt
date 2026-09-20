package dev.sebastiano.jewel.tooling

import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextArea
import dev.sebastiano.jewel.tooling.recording.CaptureFidelity
import dev.sebastiano.jewel.tooling.recording.CaptureStatus
import dev.sebastiano.jewel.tooling.recording.CaptureTarget
import dev.sebastiano.jewel.tooling.recording.Recording
import dev.sebastiano.jewel.tooling.recording.RecordingFiles
import dev.sebastiano.jewel.tooling.recording.StopReason
import dev.sebastiano.jewel.tooling.recording.TraceEvent
import dev.sebastiano.jewel.tooling.recording.TraceSite
import dev.sebastiano.jewel.tooling.recording.TraceThread
import java.awt.Component
import java.awt.Container
import java.nio.file.Files

class LiveInspectionServiceTest : BasePlatformTestCase() {
    fun testOpenRecordingShowsDurationBadgesAndCloseClearsThem() {
        myFixture.configureByText(
            "Greeting.kt",
            """
            package example
            annotation class Composable
            @Composable fun GreetingRow() {}
            """
                .trimIndent(),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val recording = recording()
        val path = Files.createTempDirectory("jewel-recording").resolve("recording.json")
        RecordingFiles.writeNew(path, recording)
        val service = project.getService(LiveInspectionService::class.java)
        service.createComponent()
        service.openRecording(path)
        PlatformTestUtil.waitWithEventsDispatching(
            "imported recording",
            { service.snapshot()?.recording?.sessionId == recording.sessionId },
            10,
        )
        PlatformTestUtil.waitWithEventsDispatching(
            "duration badges",
            { myFixture.editor.inlayModel.getAfterLineEndElementsForLogicalLine(2).isNotEmpty() },
            10,
        )
        service.closeRecording()
        assertNull(service.snapshot())
        PlatformTestUtil.waitWithEventsDispatching(
            "badges cleared",
            { myFixture.editor.inlayModel.getAfterLineEndElementsForLogicalLine(2).isEmpty() },
            10,
        )
    }

    fun testExportNotifiesWithoutReplacingRecordingStatus() {
        val recording = recording()
        val directory = Files.createTempDirectory("jewel-recording")
        val input = directory.resolve("recording.json")
        RecordingFiles.writeNew(input, recording)
        val service = project.getService(LiveInspectionService::class.java)
        val component = service.createComponent()
        service.openRecording(input)
        PlatformTestUtil.waitWithEventsDispatching(
            "imported recording",
            { service.snapshot()?.recording?.sessionId == recording.sessionId },
            10,
        )
        service.export(
            directory.resolve("exported.json"),
            checkNotNull(service.snapshot()).recording,
        )
        PlatformTestUtil.waitWithEventsDispatching(
            "export notification",
            { exportedNotifications().isNotEmpty() },
            10,
        )
        val notifications = exportedNotifications()
        try {
            assertEquals(1, notifications.size)
            assertEquals(
                JewelToolingBundle.message("live.exported"),
                notifications.single().content,
            )
            if (RevealFileAction.isSupported()) {
                assertTrue(
                    notifications.single().actions.any {
                        it.templatePresentation.text == RevealFileAction.getActionName()
                    }
                )
            }
            val description =
                descendants(component).filterIsInstance<JBTextArea>().single {
                    it.name == "jewel-live-description"
                }
            assertEquals(JewelToolingBundle.message("live.opened"), description.text)
        } finally {
            notifications.forEach { it.expire() }
        }
    }

    private fun exportedNotifications(): List<Notification> =
        NotificationsManager.getNotificationsManager()
            .getNotificationsOfType(Notification::class.java, project)
            .filter { it.groupId == LiveInspectionService.NOTIFICATION_GROUP }

    private fun descendants(component: Component): List<Component> =
        listOf(component) +
            ((component as? Container)?.components?.flatMap { descendants(it) }).orEmpty()

    private fun recording(): Recording =
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
}
