package nl.roosterandroid.desktop

import nl.roosterandroid.app.AtwValidator
import nl.roosterandroid.app.EmployeeRole
import nl.roosterandroid.app.employeeMonthStats
import nl.roosterandroid.app.rosterQualityScore
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder
import javax.swing.table.DefaultTableModel

internal interface Refreshable {
    fun refresh()
}

internal class DashboardPanel(private val controller: DesktopController) : JPanel(BorderLayout()), Refreshable {
    private val statsCards = JPanel(GridLayout(1, 5, 10, 0))
    private val teamValue = JLabel("0")
    private val shiftsValue = JLabel("0")
    private val qualityValue = JLabel("0")
    private val errorsValue = JLabel("0")
    private val borrowedValue = JLabel("0")
    private val issueModel = DefaultListModel<IssueRow>()
    private val issueList = JList(issueModel)
    private val tableModel = object : DefaultTableModel(
        arrayOf("Medewerker", "Diensten", "Uren / doel", "Setup", "Dag", "Tussen", "Sluit", "Weekend", "Vak", "Ziek"),
        0
    ) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val statsTable = configuredTable(tableModel)

    init {
        border = EmptyBorder(16, 16, 16, 16)
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(panelTitle("Overzicht"), BorderLayout.WEST)
            add(
                JLabel("Versie 0.10 • lokaal opgeslagen • geen account nodig").apply {
                    horizontalAlignment = SwingConstants.RIGHT
                },
                BorderLayout.EAST
            )
        }
        add(header, BorderLayout.NORTH)

        statsCards.isOpaque = false
        statsCards.add(statCard("Actieve managers", teamValue, UiColors.blueSoft))
        statsCards.add(statCard("Diensten", shiftsValue, UiColors.greenSoft))
        statsCards.add(statCard("Kwaliteit", qualityValue, UiColors.cyanSoft))
        statsCards.add(statCard("ATW-fouten", errorsValue, UiColors.redSoft))
        statsCards.add(statCard("Leendiensten", borrowedValue, UiColors.yellowSoft))

        issueList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        issueList.cellRenderer = IssueRenderer()
        issueList.fixedCellHeight = 38

        val issuePanel = JPanel(BorderLayout(0, 8)).apply {
            border = EmptyBorder(14, 0, 0, 8)
            add(JLabel("Aandachtspunten").apply { font = font.deriveFont(Font.BOLD, 15f) }, BorderLayout.NORTH)
            add(JScrollPane(issueList).apply { border = BorderFactory.createEmptyBorder() }, BorderLayout.CENTER)
        }
        val statsPanel = JPanel(BorderLayout(0, 8)).apply {
            border = EmptyBorder(14, 8, 0, 0)
            add(JLabel("Verdeling per medewerker").apply { font = font.deriveFont(Font.BOLD, 15f) }, BorderLayout.NORTH)
            add(tableScroll(statsTable), BorderLayout.CENTER)
        }
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, issuePanel, statsPanel).apply {
            resizeWeight = 0.38
            border = BorderFactory.createEmptyBorder()
            dividerSize = 8
        }

        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(statsCards, BorderLayout.NORTH)
            add(split, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        preferredSize = Dimension(1100, 650)
        refresh()
    }

    override fun refresh() {
        val state = controller.state
        val errors = controller.violations.count { it.severity == AtwValidator.Severity.ERROR }
        val borrowedIds = state.employees.filter { it.role == EmployeeRole.BORROWED }.map { it.id }.toSet()
        val quality = rosterQualityScore(state, controller.unfilled, controller.plannerWarnings, controller.violations)
        teamValue.text = state.employees.count { it.active }.toString()
        shiftsValue.text = state.assignments.size.toString()
        qualityValue.text = "$quality/100"
        errorsValue.text = errors.toString()
        borrowedValue.text = state.assignments.count { it.employeeId in borrowedIds }.toString()
        errorsValue.foreground = if (errors > 0) UiColors.red else UiColors.green

        issueModel.clear()
        controller.violations.filter { it.severity == AtwValidator.Severity.ERROR }.forEach {
            issueModel.addElement(IssueRow("ATW", it.message, true))
        }
        controller.unfilled.forEach { issueModel.addElement(IssueRow("Open", it, true)) }
        controller.plannerWarnings.forEach { issueModel.addElement(IssueRow("Let op", it, false)) }
        controller.violations.filter { it.severity != AtwValidator.Severity.ERROR }.take(30).forEach {
            issueModel.addElement(IssueRow(it.rule, it.message, false))
        }
        if (issueModel.isEmpty) issueModel.addElement(IssueRow("Goed", "Geen open fouten in het huidige rooster", false))

        tableModel.rowCount = 0
        employeeMonthStats(state).forEach { stat ->
            tableModel.addRow(arrayOf(
                stat.name,
                stat.shifts,
                "%.1f / %.1f".format(stat.hours, stat.targetHours),
                stat.setup,
                stat.day,
                stat.middle,
                stat.close,
                stat.weekend,
                stat.vacationDays,
                stat.sickDays
            ))
        }
    }

    private fun statCard(label: String, value: JLabel, background: Color): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(background.darker(), 1),
            EmptyBorder(12, 14, 12, 14)
        )
        this.background = background
        add(JLabel(label).apply { font = font.deriveFont(Font.PLAIN, 12f) }, BorderLayout.NORTH)
        value.font = value.font.deriveFont(Font.BOLD, 24f)
        add(value, BorderLayout.CENTER)
        preferredSize = Dimension(150, 78)
    }
}

private data class IssueRow(val label: String, val message: String, val error: Boolean)

private class IssueRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val row = value as IssueRow
        val component = super.getListCellRendererComponent(
            list,
            "<html><b>${htmlEscape(row.label)}</b>&nbsp;&nbsp;${htmlEscape(row.message)}</html>",
            index,
            isSelected,
            cellHasFocus
        ) as JLabel
        component.border = EmptyBorder(4, 8, 4, 8)
        if (!isSelected) component.foreground = if (row.error) UiColors.red else component.foreground
        return component
    }
}
