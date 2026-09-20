package dev.sebastiano.jewel.tooling

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.sebastiano.jewel.tooling.recording.Recording
import dev.sebastiano.jewel.tooling.recording.SiteSummary
import dev.sebastiano.jewel.tooling.recording.completedExecutions
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Locale
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.RowFilter
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter
import javax.swing.text.DefaultCaret

private const val NANOS_PER_MILLISECOND = 1_000_000.0

internal class RecordingReportPanel(
    private val project: Project,
    private var data: RecordingReportData,
    embedded: Boolean = false,
) {
    private val summaryText = textArea("recording.summary")
    private val details = RecordingSiteDetailsPanel(::openFile) { it in openable }
    private val model = SitesModel(data.sites)
    private val sorter =
        TableRowSorter(model).apply {
            sortKeys = listOf(RowSorter.SortKey(1, SortOrder.DESCENDING))
        }
    private val completion = RecordingFilterCompletion(completionItems(data.sites))
    private val filter =
        JBTextField().apply {
            name = "jewel-recording-filter"
            accessibleContext.accessibleName =
                JewelToolingBundle.message("recording.filter.accessible")
            putClientProperty("JComponent.roundRect", true)
            completion.install(this)
        }
    private val hideDependencies =
        JBCheckBox(JewelToolingBundle.message("recording.filter.dependencies")).apply {
            name = "jewel-recording-filter-dependencies"
            isOpaque = false
            accessibleContext.accessibleName =
                JewelToolingBundle.message("recording.filter.dependencies.accessible")
            toolTipText = JewelToolingBundle.message("recording.filter.dependencies.description")
            addActionListener { applyFilter() }
        }
    private val count = JLabel().apply { putClientProperty("html.disable", true) }
    private var filtering = false
    private var detailsVisible = true
    private var openable = emptySet<TraceSiteLocation>()
    private var projectSiteIds: Set<Int>? = null
    private var linkGeneration = 0L
    private val table =
        JBTable(model).apply {
            name = "jewel-recording-sites"
            accessibleContext.accessibleName = JewelToolingBundle.message("recording.sites")
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            rowSorter = sorter
            columnModel.getColumn(1).preferredWidth = 100
            columnModel.getColumn(2).preferredWidth = 170
            columnModel.getColumn(3).preferredWidth = 170
            columnModel.getColumn(0).apply {
                preferredWidth = 500
                cellRenderer =
                    DefaultTableCellRenderer().apply { putClientProperty("html.disable", true) }
            }
            setDefaultRenderer(
                Double::class.javaObjectType,
                object : DefaultTableCellRenderer() {
                    override fun setValue(value: Any?) {
                        horizontalAlignment = RIGHT
                        text =
                            if (value is Number) LiveInspectionFormat.milliseconds(value.toDouble())
                            else ""
                    }
                },
            )
            selectionModel.addListSelectionListener {
                if (!it.valueIsAdjusting && !filtering) showSelection()
            }
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(event: MouseEvent) {
                        if (
                            event.clickCount == 2 &&
                                SwingUtilities.isLeftMouseButton(event) &&
                                rowAtPoint(event.point) >= 0
                        ) {
                            val row = selectedRow.takeIf { it >= 0 } ?: return
                            TraceSiteLocations.parse(
                                    data.sites[convertRowIndexToModel(row)].site.info
                                )
                                ?.let(::openFile)
                        }
                    }
                }
            )
        }
    private val splitter =
        OnePixelSplitter(false, SPLIT).apply {
            firstComponent = JBScrollPane(table).apply { border = JBUI.Borders.empty() }
            secondComponent = details.component
            dividerWidth = 1
        }

    val component: JComponent =
        JPanel(BorderLayout(0, JBUI.scale(10))).apply {
            border = JBUI.Borders.empty(8)
            preferredSize = JBUI.size(960, if (embedded) 360 else 640)
            minimumSize = if (embedded) JBUI.size(200, 150) else JBUI.size(600, 480)
            if (!embedded)
                add(
                    JBScrollPane(
                        summaryText.apply {
                            rows = 6
                            text = summary(data.recording)
                        }
                    ),
                    BorderLayout.NORTH,
                )
            add(
                JPanel(BorderLayout(0, JBUI.scale(FILTER_GAP))).apply {
                    add(filterBar(this), BorderLayout.NORTH)
                    add(splitter, BorderLayout.CENTER)
                },
                BorderLayout.CENTER,
            )
            if (!embedded)
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
        get() = filter

    init {
        filter.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(event: DocumentEvent) = applyFilter()

                override fun removeUpdate(event: DocumentEvent) = applyFilter()

                override fun changedUpdate(event: DocumentEvent) = applyFilter()
            }
        )
        requestLinks()
        applyFilter()
    }

    /** Refreshes cumulative measurements while keeping the reader's table context. Call on EDT. */
    fun update(next: RecordingReportData) {
        val selectedId =
            table.selectedRow
                .takeIf { it >= 0 }
                ?.let { data.sites[table.convertRowIndexToModel(it)].site.id }
        val sameSession = next.recording.sessionId == data.recording.sessionId
        val position = (table.parent as? javax.swing.JViewport)?.viewPosition
        filtering = true
        try {
            data = next
            model.replace(next.sites)
            completion.setItems(completionItems(next.sites))
            val index =
                if (sameSession) next.sites.indexOfFirst { it.site.id == selectedId } else -1
            val visible = if (index >= 0) table.convertRowIndexToView(index) else -1
            if (visible >= 0) table.setRowSelectionInterval(visible, visible)
            else if (table.rowCount > 0) table.setRowSelectionInterval(0, 0)
            else table.clearSelection()
            summaryText.text = summary(next.recording)
            count.text =
                JewelToolingBundle.message("recording.filter.count", table.rowCount, model.rowCount)
        } finally {
            filtering = false
        }
        requestLinks()
        applyFilter()
        if (position != null && sameSession)
            (table.parent as? javax.swing.JViewport)?.viewPosition = position
    }

    fun selectSite(id: Int) {
        if (filter.text.isNotEmpty()) filter.text = ""
        if (hideDependencies.isSelected && projectSiteIds?.contains(id) != true) {
            hideDependencies.isSelected = false
            applyFilter()
        }
        val index = data.sites.indexOfFirst { it.site.id == id }
        val visible = if (index >= 0) table.convertRowIndexToView(index) else -1
        if (visible < 0) return
        if (!detailsVisible) setDetailsVisible(true)
        table.setRowSelectionInterval(visible, visible)
        table.scrollRectToVisible(table.getCellRect(visible, 0, true))
    }

    private fun filterBar(host: JComponent): JComponent =
        JPanel(BorderLayout(JBUI.scale(FILTER_GAP), 0)).apply {
            isOpaque = false
            add(
                JLabel(JewelToolingBundle.message("recording.filter")).apply {
                    labelFor = filter
                    verticalAlignment = SwingConstants.CENTER
                    putClientProperty("html.disable", true)
                },
                BorderLayout.WEST,
            )
            add(filter, BorderLayout.CENTER)
            add(
                JPanel(BorderLayout(JBUI.scale(FILTER_GAP), 0)).apply {
                    isOpaque = false
                    add(
                        JButton(JewelToolingBundle.message("recording.filter.clear")).apply {
                            name = "jewel-recording-filter-clear"
                            putClientProperty("html.disable", true)
                            addActionListener {
                                filter.text = ""
                                filter.requestFocusInWindow()
                            }
                        },
                        BorderLayout.WEST,
                    )
                    add(count, BorderLayout.CENTER)
                    add(
                        JPanel(BorderLayout(JBUI.scale(FILTER_GAP), 0)).apply {
                            isOpaque = false
                            add(hideDependencies, BorderLayout.WEST)
                            add(detailsToggle(host), BorderLayout.EAST)
                        },
                        BorderLayout.EAST,
                    )
                },
                BorderLayout.EAST,
            )
        }

    private fun detailsToggle(host: JComponent): JComponent {
        val toggle =
            object :
                ToggleAction(
                    JewelToolingBundle.message("recording.details.toggle"),
                    JewelToolingBundle.message("recording.details.toggle.description"),
                    AllIcons.Actions.PreviewDetails,
                ),
                DumbAware {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT

                override fun isSelected(event: AnActionEvent) = detailsVisible

                override fun setSelected(event: AnActionEvent, state: Boolean) =
                    setDetailsVisible(state)
            }
        val toolbar =
            ActionManager.getInstance()
                .createActionToolbar("JewelRecordingDetails", DefaultActionGroup(toggle), true)
        toolbar.targetComponent = host
        return toolbar.component
    }

    private fun setDetailsVisible(visible: Boolean) {
        detailsVisible = visible
        details.component.isVisible = visible
        splitter.proportion = if (visible) SPLIT else 1.0f
        splitter.revalidate()
        splitter.repaint()
    }

    private fun applyFilter() {
        val selected =
            table.selectedRow.takeIf { it >= 0 }?.let { table.convertRowIndexToModel(it) }
        val query = filter.text.lowercase(Locale.ROOT)
        val hide = hideDependencies.isSelected
        val projectIds = projectSiteIds
        filtering = true
        try {
            sorter.rowFilter =
                if (query.isEmpty() && !(hide && projectIds != null)) null
                else
                    object : RowFilter<SitesModel, Int>() {
                        override fun include(entry: Entry<out SitesModel, out Int>): Boolean {
                            if (
                                hide &&
                                    projectIds != null &&
                                    data.sites[entry.identifier].site.id !in projectIds
                            )
                                return false
                            return query.isEmpty() || model.matches(entry.identifier, query)
                        }
                    }
            val visible = selected?.let { table.convertRowIndexToView(it) } ?: -1
            if (visible >= 0) table.setRowSelectionInterval(visible, visible)
            else if (table.rowCount > 0) table.setRowSelectionInterval(0, 0)
            else table.clearSelection()
        } finally {
            filtering = false
        }
        count.text =
            JewelToolingBundle.message("recording.filter.count", table.rowCount, model.rowCount)
        showSelection()
    }

    private fun showSelection() {
        val row = table.selectedRow
        if (row < 0) {
            details.show(
                null,
                emptyMap(),
                if (table.rowCount == 0)
                    JewelToolingBundle.message(
                        if (
                            data.sites.isEmpty() &&
                                filter.text.isEmpty() &&
                                !hideDependencies.isSelected
                        ) {
                            if (
                                data.recording.status ==
                                    dev.sebastiano.jewel.tooling.recording.CaptureStatus.ACTIVE
                            )
                                "live.empty"
                            else "recording.empty"
                        } else if (
                            hideDependencies.isSelected &&
                                filter.text.isEmpty() &&
                                data.sites.isNotEmpty()
                        )
                            "recording.filter.dependencies.empty"
                        else "recording.filter.empty"
                    )
                else "",
            )
            return
        }
        val site = data.sites[table.convertRowIndexToModel(row)]
        val labels = data.recording.threads.associate { it.id to it.name }
        details.show(site, labels, "")
    }

    private fun openFile(location: TraceSiteLocation) {
        fun resolve(): OpenFileDescriptor? =
            if (project.isDisposed) null else TraceSiteLocations.resolve(project, location)
        fun navigate(descriptor: OpenFileDescriptor?) {
            descriptor?.navigate(true)
        }
        if (ApplicationManager.getApplication().isUnitTestMode) {
            navigate(
                ReadAction.computeBlocking<OpenFileDescriptor?, RuntimeException> { resolve() }
            )
            return
        }
        ReadAction.nonBlocking<OpenFileDescriptor?> { resolve() }
            .expireWhen { project.isDisposed }
            .finishOnUiThread(ModalityState.defaultModalityState(), ::navigate)
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun requestLinks() {
        val snapshot = data
        val generation = ++linkGeneration
        fun compute(): TraceSiteLinks = TraceSiteLocations.links(project, snapshot)
        if (ApplicationManager.getApplication().isUnitTestMode) {
            val links = ReadAction.computeBlocking<TraceSiteLinks, RuntimeException> { compute() }
            openable = links.openable
            projectSiteIds = links.projectSiteIds
            return
        }
        ReadAction.nonBlocking<TraceSiteLinks> { compute() }
            .expireWhen { project.isDisposed || generation != linkGeneration }
            .finishOnUiThread(ModalityState.defaultModalityState()) { links ->
                if (generation != linkGeneration || project.isDisposed) return@finishOnUiThread
                openable = links.openable
                projectSiteIds = links.projectSiteIds
                applyFilter()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
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
                recording.completedExecutions(),
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
        if (fidelity.laterActivityUnrecorded)
            appendLine(JewelToolingBundle.message("recording.later"))
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
        private const val FILTER_GAP = 8
        private const val SPLIT = 0.68f
        private const val MIN_TEXT_WIDTH = 80
        private const val MIN_TEXT_HEIGHT = 40

        private fun completionItems(sites: List<SiteSummary>): Collection<String> {
            val values = LinkedHashSet<String>()
            for (site in sites) {
                values.add(site.site.info)
                TraceSiteLocations.parse(site.site.info)?.let { location ->
                    values.add(location.qualifiedName)
                    values.add(location.fileName)
                }
            }
            return values
        }

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

private class RecordingFilterCompletion(items: Collection<String>) {
    private val items = LinkedHashSet(items)

    fun setItems(next: Collection<String>) {
        items.clear()
        items.addAll(next)
    }

    fun install(field: JBTextField) {
        field.registerKeyboardAction(
            { _ ->
                val query = field.text.lowercase(Locale.ROOT)
                val matches = items.filter { item ->
                    query.isEmpty() || item.lowercase(Locale.ROOT).contains(query)
                }
                if (matches.isEmpty()) return@registerKeyboardAction
                JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(matches)
                    .setRequestFocus(true)
                    .setItemChosenCallback { chosen ->
                        field.text = chosen
                        field.caretPosition = chosen.length
                    }
                    .createPopup()
                    .showUnderneathOf(field)
            },
            KeyStroke.getKeyStroke("control SPACE"),
            JComponent.WHEN_FOCUSED,
        )
    }
}

private class SitesModel(private var sites: List<SiteSummary>) : AbstractTableModel() {
    private var normalized = sites.map { it.site.info.lowercase(Locale.ROOT) }

    fun replace(next: List<SiteSummary>) {
        sites = next
        normalized = next.map { it.site.info.lowercase(Locale.ROOT) }
        fireTableDataChanged()
    }

    fun matches(row: Int, query: String): Boolean = normalized[row].contains(query)

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
