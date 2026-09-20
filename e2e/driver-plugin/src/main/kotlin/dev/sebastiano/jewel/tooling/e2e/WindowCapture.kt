package dev.sebastiano.jewel.tooling.e2e

import com.intellij.ui.mac.foundation.Foundation
import com.intellij.ui.mac.foundation.ID
import com.intellij.ui.mac.foundation.MacUtil
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.recording.AutoScreenshotter
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import java.awt.Dialog
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Window
import java.awt.image.BufferedImage
import java.util.concurrent.FutureTask
import javax.swing.SwingUtilities

/** macOS captures exclude occluding applications; Xvfb CI uses its isolated framebuffer. */
internal object WindowCapture {
    @Volatile private var nativeCapture = true

    fun activate(window: Window) {
        edt {
            window.toFront()
            window.requestFocus()
        }
        if (System.getProperty("os.name").startsWith("Mac")) {
            native {
                Foundation.invoke(
                    Foundation.invoke("NSApplication", "sharedApplication"),
                    "activateIgnoringOtherApps:",
                    true,
                )
            }
        }
    }

    fun capture(window: Window, region: Rectangle, robot: RobotDriver): BufferedImage {
        if (!System.getProperty("os.name").startsWith("Mac") || !nativeCapture)
            return robot.screenshotAtDeviceScale(region)
        val target = titled(window)
        val bounds = target.bounds
        val scale = edt { window.graphicsConfiguration.defaultTransform }
        val image =
            try {
                AutoScreenshotter().captureWindow(target)
            } catch (failure: IllegalStateException) {
                if (failure.message.orEmpty().contains("timed out")) {
                    nativeCapture = false
                    return robot.screenshotAtDeviceScale(region)
                }
                throw failure
            }
        check(image.width == (bounds.width * scale.scaleX).toInt()) {
            "Native window width differs from device scale"
        }
        check(image.height == (bounds.height * scale.scaleY).toInt()) {
            "Native window height differs from device scale"
        }
        return image.getSubimage(
            ((region.x - bounds.x) * scale.scaleX).toInt(),
            ((region.y - bounds.y) * scale.scaleY).toInt(),
            (region.width * scale.scaleX).toInt(),
            (region.height * scale.scaleY).toInt(),
        )
    }

    private fun titled(window: Window): TitledWindow {
        // Resolve on EDT before entering AppKit: AppKit must never synchronously call back into
        // EDT.
        val nativeWindow =
            if (window is Frame || window is Dialog) ID.NIL
            else
                edt { MacUtil.getWindowFromJavaWindow(window) }
                    .also { check(it.toLong() != 0L) { "Popup native window is unavailable" } }
        return object : TitledWindow {
            override var title: String?
                get() = edt {
                    when (window) {
                        is Frame -> window.title
                        is Dialog -> window.title
                        else ->
                            native {
                                Foundation.toStringViaUTF8(Foundation.invoke(nativeWindow, "title"))
                            }
                    }
                }
                set(value) {
                    edt {
                        when (window) {
                            is Frame -> window.title = value.orEmpty()
                            is Dialog -> window.title = value.orEmpty()
                            else ->
                                native {
                                    Foundation.invoke(
                                        nativeWindow,
                                        "setTitle:",
                                        Foundation.nsString(value.orEmpty()),
                                    )
                                }
                        }
                    }
                }

            override val bounds: Rectangle
                get() = edt { Rectangle(window.locationOnScreen, window.size) }
        }
    }

    // Heavyweight Swing popups have no Java title. Spectre temporarily disambiguates the
    // backing NSWindow's title and restores it in finally; this never touches other windows.
    private fun <T> native(block: () -> T): T {
        val task = FutureTask(block)
        Foundation.executeOnMainThread(true, true, task)
        return task.get()
    }

    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        val task = FutureTask(block)
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }
}
