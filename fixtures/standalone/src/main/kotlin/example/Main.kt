package example

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Text

private const val WINDOW_WIDTH = 680
private const val WINDOW_HEIGHT = 400
private const val CONTENT_PADDING = 32
private const val BACKGROUND_COLOR = 0xff202124

fun main() {
  SwingUtilities.invokeLater { showApplication() }
}

fun showApplication(manualRecording: Boolean = true): ComposeWindow =
  ComposeWindow().apply {
    if (manualRecording) FixtureRecording.initialize()
    title = "Jewel Tooling — Standalone fixture"
    defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
    setSize(WINDOW_WIDTH, WINDOW_HEIGHT)
    setLocationRelativeTo(null)
    setContent {
      IntUiTheme(isDark = true) {
        var items by remember { mutableStateOf(listOf("First item")) }
        Box(
          Modifier.fillMaxSize().background(Color(BACKGROUND_COLOR)).padding(CONTENT_PADDING.dp)
        ) {
          Column {
            if (manualRecording)
              DefaultButton(onClick = { FixtureRecording.copyLiveConnection() }) {
                Text("Copy Inspection Connection")
              }
            Spacer(Modifier.height(CONTENT_PADDING.dp))
            GreetingRow(Greeting("A Jewel standalone application"), items) {
              items = items + "Another item"
            }
          }
        }
      }
    }
    addWindowListener(
      object : java.awt.event.WindowAdapter() {
        override fun windowClosed(event: java.awt.event.WindowEvent) {
          if (manualRecording) FixtureRecording.closeLiveConnection()
        }
      }
    )
    isVisible = true
  }
