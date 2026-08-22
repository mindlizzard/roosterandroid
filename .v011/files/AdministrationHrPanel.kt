package nl.roosterandroid.desktop

import nl.roosterandroid.app.AtwValidator
import nl.roosterandroid.app.Employee
import nl.roosterandroid.app.EmployeeRole
import nl.roosterandroid.app.employeeMonthStats
import nl.roosterandroid.app.rosterQualityScore
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.border.EmptyBorder
import javax.swing.table.DefaultTableModel

internal class AdministrationHrPanel(
    private val controller: DesktopController
) : JPanel(BorderLayout()), Refreshable {

    private val qualityValue = metricValue()
    private val atwValue = metricValue()
    private val unfilledValue = metricValue()
    private val borrowedValue = metricValue()

    private val hoursModel = hrModel(
        "Naam", "Rol", "Contract/week", "Gepland", "Doel maand", "+/-", "Diensten", "Weekend",
        "Vakantie", "Verlof", "Ziek"
    )
    private val hoursTable = configuredTable(hoursModel)

    private val distributionModel = hrModel(
        "Naam", "SETUP", "DAG", "TUSSEN", "SLUIT", "Weekend", "Totaal"
    )
    private val distributionTable = configuredTable(distributionModel)

    private var visibleEmployeeIds: List<String> = emptyList()
    private var selectedEmployeeId: String? = null
    private var refreshing = false

    init {
        border = EmptyBorder(14, 14, 14, 14)

        val header = JPanel(BorderLayout(10, 10)).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
                add(panelTitle("Administratie / HR"))
                add(secondaryButton("Contract wijzigen") { editSelectedEmployee() })
                add(primaryButton("Afwezigheid toevoegen") {
                    DesktopDialogs.absence(this@AdministrationHrPanel, controller.state)
                        ?.let(controller::upsertAbsence)
                })
            }, BorderLayout.NORTH)

            add(JPanel(GridLayout(1, 4, 10, 0)).apply {
                add(metricCard("Roosterkwaliteit", qualityValue))
                add(metricCard("ATW-fouten", atwValue))
                add(metricCard("Ongevuld", unfilledValue))
                add(metricCard("Leendiensten", borrowedValue))
            }, BorderLayout.CENTER)
        }
        add(header, BorderLayout.NORTH)

        hoursTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        hoursTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting && !refreshing) {
                val row = selectedModelRow(hoursTable)
                selectedEmployeeId = row?.let { visibleEmployeeIds.getOrNull(it) }
            }
        }
        hoursTable.columnModel.getColumn(0).preferredWidth = 180
        hoursTable.columnModel.getColumn(2).preferredWidth = 105
        hoursTable.columnModel.getColumn(4).preferredWidth = 105

        distributionTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)

        val tabs = JTabbedPane().apply {
            putClientProperty("JTabbedPane.tabType", "card")
            putClientProperty("JTabbedPane.showTabSeparators", true)
            addTab("Uren & contract", tableTab(
                "Geplande uren tegenover het indicatieve maanddoel op basis van contracturen.",
                hoursTable
            ))
            addTab("Dienstverdeling", tableTab(
                "Verdeling van SETUP, DAG, TUSSEN, SLUIT en weekenddiensten per medewerker.",
                distributionTable
            ))
        }
        add(tabs, BorderLayout.CENTER)
        preferredSize = Dimension(1150, 650)
        refresh()
    }

    override fun refresh() {
        refreshing = true
        val state = controller.state
        val previous = selectedEmployeeId
        val stats = employeeMonthStats(state)
        visibleEmployeeIds = stats.map { it.employeeId }

        qualityValue.text = rosterQualityScore(
            state,
            controller.unfilled,
            controller.plannerWarnings,
            controller.violations
        ).toString() + "/100"
        atwValue.text = controller.violations
            .count { it.severity == AtwValidator.Severity.ERROR }
            .toString()
        unfilledValue.text = controller.unfilled.size.toString()
        borrowedValue.text = stats.sumOf { it.borrowedShifts }.toString()

        hoursModel.rowCount = 0
        distributionModel.rowCount = 0

        stats.forEach { stat ->
            val employee = state.employees.firstOrNull { it.id == stat.employeeId } ?: return@forEach
            val delta = stat.hours - stat.targetHours
            hoursModel.addRow(arrayOf(
                stat.name,
                hrRoleLabel(employee.role),
                "${formatHours(employee.contractedHoursPerWeek)} u",
                "${formatHours(stat.hours)} u",
                "${formatHours(stat.targetHours)} u",
                signedHours(delta),
                stat.shifts,
                stat.weekend,
                stat.vacationDays,
                stat.leaveDays,
                stat.sickDays
            ))
            distributionModel.addRow(arrayOf(
                stat.name,
                stat.setup,
                stat.day,
                stat.middle,
                stat.close,
                stat.weekend,
                stat.shifts
            ))
        }

        val index = visibleEmployeeIds.indexOf(previous)
            .takeIf { it >= 0 }
            ?: visibleEmployeeIds.indices.firstOrNull()
            ?: -1
        if (index >= 0) {
            hoursTable.setRowSelectionInterval(index, index)
            selectedEmployeeId = visibleEmployeeIds[index]
        } else {
            selectedEmployeeId = null
        }
        refreshing = false
    }

    private fun editSelectedEmployee() {
        val employee = selectedEmployee() ?: return
        DesktopDialogs.employee(this, employee)?.let(controller::updateEmployee)
    }

    private fun selectedEmployee(): Employee? {
        val row = selectedModelRow(hoursTable)
        if (row != null) {
            visibleEmployeeIds.getOrNull(row)?.let { id ->
                controller.state.employees.firstOrNull { it.id == id }?.let { return it }
            }
        }
        val id = selectedEmployeeId ?: return null
        return controller.state.employees.firstOrNull { it.id == id }
    }

    private fun tableTab(description: String, table: JTable): JPanel = JPanel(BorderLayout(0, 8)).apply {
        border = EmptyBorder(12, 4, 4, 4)
        add(JLabel(description).apply { font = font.deriveFont(Font.PLAIN, 13f) }, BorderLayout.NORTH)
        add(tableScroll(table), BorderLayout.CENTER)
    }

    private fun metricCard(title: String, value: JLabel): JPanel = JPanel(BorderLayout(0, 4)).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(
                javax.swing.UIManager.getColor("Component.borderColor") ?: java.awt.Color.LIGHT_GRAY
            ),
            EmptyBorder(10, 12, 10, 12)
        )
        add(JLabel(title).apply { font = font.deriveFont(Font.PLAIN, 12f) }, BorderLayout.NORTH)
        add(value, BorderLayout.CENTER)
    }

    private fun metricValue(): JLabel = JLabel("0").apply { font = font.deriveFont(Font.BOLD, 22f) }
    private fun formatHours(value: Double): String = "%.1f".format(value)
    private fun signedHours(value: Double): String = when {
        value > 0.049 -> "+${formatHours(value)} u"
        value < -0.049 -> "${formatHours(value)} u"
        else -> "0.0 u"
    }
    private fun hrRoleLabel(role: EmployeeRole): String = when (role) {
        EmployeeRole.MANAGER -> "Manager"
        EmployeeRole.RM -> "Restaurant Manager"
        EmployeeRole.TRAINEE -> "Trainee Manager"
        EmployeeRole.BORROWED -> "Leenmanager"
        EmployeeRole.HOST -> "Host(ess)"
    }
}

private fun hrModel(vararg columns: String): DefaultTableModel = object : DefaultTableModel(columns, 0) {
    override fun isCellEditable(row: Int, column: Int): Boolean = false
}
