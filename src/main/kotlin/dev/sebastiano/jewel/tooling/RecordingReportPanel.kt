package dev.sebastiano.jewel.tooling

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.sebastiano.jewel.tooling.recording.Recording
import dev.sebastiano.jewel.tooling.recording.SiteSummary
import java.awt.BorderLayout
import java.text.NumberFormat
import java.util.Locale
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.text.DefaultCaret

private const val NANOS_PER_MILLISECOND = 1_000_000.0

internal class RecordingReportPanel(private val data: RecordingReportData) {
  private val details = textArea("recording.site.details")
  private val table =
    JBTable(SitesModel(data.sites)).apply {
      name = "jewel-recording-sites"
      accessibleContext.accessibleName = JewelToolingBundle.message("recording.sites")
      setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
      autoCreateRowSorter = true
      columnModel.getColumn(1).preferredWidth = 100
      columnModel.getColumn(2).preferredWidth = 170
      columnModel.getColumn(3).preferredWidth = 170
      columnModel.getColumn(0).apply {
        preferredWidth = 500
        cellRenderer = DefaultTableCellRenderer().apply { putClientProperty("html.disable", true) }
      }
      val numberFormat = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 3 }
      setDefaultRenderer(
        Double::class.javaObjectType,
        object : DefaultTableCellRenderer() {
          override fun setValue(value: Any?) {
            horizontalAlignment = RIGHT
            text = if (value is Number) numberFormat.format(value) else ""
          }
        },
      )
      selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) showSelection() }
    }

  val component: JComponent =
    JPanel(BorderLayout(0, JBUI.scale(10))).apply {
      border = JBUI.Borders.empty(8)
      preferredSize = JBUI.size(960, 640)
      minimumSize = JBUI.size(600, 480)
      add(
        JBScrollPane(
          textArea("recording.summary").apply {
            rows = 6
            text = summary(data.recording)
          }
        ),
        BorderLayout.NORTH,
      )
      add(
        JSplitPane(JSplitPane.VERTICAL_SPLIT, JBScrollPane(table), JBScrollPane(details)).apply {
          resizeWeight = 0.7
          dividerLocation = JBUI.scale(290)
          border = null
        },
        BorderLayout.CENTER,
      )
      add(
        JBScrollPane(
            textArea("recording.limits").apply {
              text = JewelToolingBundle.message("recording.limit.description")
            }
          )
          .apply {
            preferredSize = JBUI.size(900, 100)
            minimumSize = JBUI.size(100, 60)
            border = null
          },
        BorderLayout.SOUTH,
      )
    }

  val focus: JComponent
    get() = table

  init {
    if (data.sites.isNotEmpty()) table.setRowSelectionInterval(0, 0)
    else details.text = JewelToolingBundle.message("recording.empty")
  }

  private fun showSelection() {
    val row = table.selectedRow
    if (row < 0) return
    val site = data.sites[table.convertRowIndexToModel(row)]
    val labels = data.recording.threads.associate { it.id to it.name }
    details.text = buildString {
      appendLine(site.site.info)
      appendLine(JewelToolingBundle.message("recording.site.identity", site.site.id, site.site.key))
      appendLine()
      appendLine(JewelToolingBundle.message("recording.threads"))
      site.threads.toSortedMap().forEach { (id, count) ->
        appendLine(
          JewelToolingBundle.message("recording.thread", labels[id].orEmpty(), id.toString(), count)
        )
      }
    }
    details.caretPosition = 0
  }

  private fun summary(recording: Recording): String = buildString {
    appendLine(JewelToolingBundle.message("recording.target", recording.target.displayName))
    appendLine(JewelToolingBundle.message("recording.session", recording.sessionId))
    appendLine(
      JewelToolingBundle.message(
        "recording.result",
        JewelToolingBundle.message(
          "recording.status.${recording.status.name.lowercase(Locale.ROOT)}"
        ),
        JewelToolingBundle.message(
          "recording.reason.${recording.stopReason.name.lowercase(Locale.ROOT)}"
        ),
        recording.events.size,
        recording.durationNs.toDouble() / NANOS_PER_MILLISECOND,
      )
    )
    val fidelity = recording.fidelity
    appendLine(
      JewelToolingBundle.message(
        "recording.fidelity",
        fidelity.abandonedStarts,
        fidelity.discardedPairs,
        fidelity.unmatchedEnds,
        fidelity.rejectedStarts,
      )
    )
    if (fidelity.laterActivityUnrecorded) appendLine(JewelToolingBundle.message("recording.later"))
    val unknown = JewelToolingBundle.message("recording.unknown")
    append(
      JewelToolingBundle.message(
        "recording.declared",
        recording.target.build ?: unknown,
        recording.target.runtime ?: unknown,
        recording.target.compiler ?: unknown,
      )
    )
  }

  companion object {
    private const val MIN_TEXT_WIDTH = 80
    private const val MIN_TEXT_HEIGHT = 40

    private fun textArea(nameKey: String): JBTextArea =
      JBTextArea().apply {
        isEditable = false
        font = UIUtil.getLabelFont()
        focusTraversalKeysEnabled = true
        minimumSize = JBUI.size(MIN_TEXT_WIDTH, MIN_TEXT_HEIGHT)
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        (caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE
        accessibleContext.accessibleName = JewelToolingBundle.message(nameKey)
      }
  }
}

private class SitesModel(private val sites: List<SiteSummary>) : AbstractTableModel() {
  private val columns =
    listOf(
      "recording.column.site",
      "recording.column.executions",
      "recording.column.total",
      "recording.column.mean",
    )

  override fun getRowCount(): Int = sites.size

  override fun getColumnCount(): Int = columns.size

  override fun getColumnName(column: Int): String = JewelToolingBundle.message(columns[column])

  override fun getColumnClass(columnIndex: Int): Class<*> =
    when (columnIndex) {
      0 -> String::class.java
      1 -> Int::class.javaObjectType
      else -> Double::class.javaObjectType
    }

  override fun getValueAt(rowIndex: Int, columnIndex: Int): Any =
    sites[rowIndex].let { site ->
      when (columnIndex) {
        0 -> site.site.info
        1 -> site.executions
        2 -> site.totalNs.toDouble() / NANOS_PER_MILLISECOND
        else -> site.meanNs / NANOS_PER_MILLISECOND
      }
    }
}
