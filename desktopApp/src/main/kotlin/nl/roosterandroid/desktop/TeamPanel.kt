package nl.roosterandroid.desktop

import nl.roosterandroid.app.Employee
import nl.roosterandroid.app.WeeklyAvailability
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.border.EmptyBorder
import javax.swing.table.DefaultTableModel

internal class TeamPanel(private val controller: DesktopController) : JPanel(BorderLayout()), Refreshable {
    private val employeeModel = readOnlyModel(
        "Naam", "Rol", "Dagen", "Uren", "Max/week", "Setup", "Dag", "Tussen", "Sluit", "Actief"
    )
    private val employeeTable = configuredTable(employeeModel)
    private val weeklyModel = readOnlyModel("Dag", "Beschikbaar", "Vanaf", "Tot", "Vaste dienst")
    private val weeklyTable = configuredTable(weeklyModel)
    private val exceptionModel = readOnlyModel("Datum", "Beschikbaar", "Vanaf", "Tot", "Vaste dienst")
    private val exceptionTable = configuredTable(exceptionModel)
    private var selectedEmployeeId: String? = null
    private var refreshing = false

    init {
        border = EmptyBorder(14, 14, 14, 14)
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(panelTitle("Team en beschikbaarheid"))
            add(primaryButton("Medewerker toevoegen") { addEmployee() })
            add(secondaryButton("Wijzigen") { editEmployee() })
            add(secondaryButton("Verwijderen") { removeEmployee() })
        }
        add(toolbar, BorderLayout.NORTH)

        employeeTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        employeeTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting && !refreshing) {
                selectedEmployeeId = selectedEmployee()?.id
                refreshAvailability()
            }
        }
        employeeTable.columnModel.getColumn(0).preferredWidth = 170
        employeeTable.columnModel.getColumn(1).preferredWidth = 140

        weeklyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        weeklyTable.tableHeader.reorderingAllowed = false
        exceptionTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)

        val left = JPanel(BorderLayout(0, 8)).apply {
            border = EmptyBorder(6, 0, 0, 8)
            add(JLabel("Medewerkers").apply { font = font.deriveFont(Font.BOLD, 15f) }, BorderLayout.NORTH)
            add(tableScroll(employeeTable), BorderLayout.CENTER)
        }

        val availabilityToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(primaryButton("Weekdag instellen") { editWeeklyAvailability() })
            add(secondaryButton("Weekregel resetten") { resetWeeklyAvailability() })
            add(secondaryButton("Datumuitzondering") { addDateException() })
            add(secondaryButton("Uitzondering verwijderen") { removeDateException() })
        }
        val right = JPanel(BorderLayout(0, 8)).apply {
            border = EmptyBorder(6, 8, 0, 0)
            add(JPanel(BorderLayout()).apply {
                add(JLabel("Beschikbaarheid geselecteerde medewerker").apply {
                    font = font.deriveFont(Font.BOLD, 15f)
                }, BorderLayout.NORTH)
                add(availabilityToolbar, BorderLayout.SOUTH)
            }, BorderLayout.NORTH)
            add(JPanel(GridLayout(2, 1, 0, 10)).apply {
                add(JPanel(BorderLayout(0, 4)).apply {
                    add(JLabel("Vaste week"), BorderLayout.NORTH)
                    add(tableScroll(weeklyTable), BorderLayout.CENTER)
                })
                add(JPanel(BorderLayout(0, 4)).apply {
                    add(JLabel("Afwijkingen op specifieke datum"), BorderLayout.NORTH)
                    add(tableScroll(exceptionTable), BorderLayout.CENTER)
                })
            }, BorderLayout.CENTER)
        }

        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right).apply {
            resizeWeight = 0.55
            dividerLocation = 690
            border = BorderFactory.createEmptyBorder()
            dividerSize = 8
        }, BorderLayout.CENTER)
        preferredSize = Dimension(1150, 650)
        refresh()
    }

    override fun refresh() {
        refreshing = true
        val previous = selectedEmployeeId
        employeeModel.rowCount = 0
        controller.state.employees.forEach { employee ->
            employeeModel.addRow(arrayOf(
                employee.name,
                roleLabel(employee.role),
                employee.contractedDaysPerWeek,
                "%.1f".format(employee.contractedHoursPerWeek),
                employee.maxShiftsPerWeek,
                yesNo(employee.canSetup),
                yesNo(employee.canDay),
                yesNo(employee.canMiddle),
                yesNo(employee.canClose),
                yesNo(employee.active)
            ))
        }
        val index = controller.state.employees.indexOfFirst { it.id == previous }
            .takeIf { it >= 0 } ?: controller.state.employees.indices.firstOrNull() ?: -1
        if (index >= 0) {
            employeeTable.setRowSelectionInterval(index, index)
            selectedEmployeeId = controller.state.employees[index].id
        } else selectedEmployeeId = null
        refreshing = false
        refreshAvailability()
    }

    private fun refreshAvailability() {
        weeklyModel.rowCount = 0
        exceptionModel.rowCount = 0
        val employee = selectedEmployee() ?: return
        val labels = listOf("Maandag", "Dinsdag", "Woensdag", "Donderdag", "Vrijdag", "Zaterdag", "Zondag")
        (1..7).forEach { weekday ->
            val rule = controller.state.weeklyAvailability.lastOrNull {
                it.employeeId == employee.id && it.weekday == weekday
            }
            weeklyModel.addRow(arrayOf(
                labels[weekday - 1],
                if (rule == null) "Standaard: ja" else yesNo(rule.available),
                rule?.earliestStart.orEmpty(),
                rule?.latestEnd.orEmpty(),
                rule?.fixedShiftKind?.let(::shiftKindLabel).orEmpty()
            ))
        }
        controller.state.availability.filter { it.employeeId == employee.id }
            .sortedBy { it.date }
            .forEach { rule ->
                exceptionModel.addRow(arrayOf(
                    rule.date,
                    yesNo(rule.available),
                    rule.earliestStart.orEmpty(),
                    rule.latestEnd.orEmpty(),
                    rule.fixedShiftKind?.let(::shiftKindLabel).orEmpty()
                ))
            }
    }

    private fun addEmployee() {
        DesktopDialogs.employee(this)?.let(controller::addEmployee)
    }

    private fun editEmployee() {
        val employee = selectedEmployee() ?: return
        DesktopDialogs.employee(this, employee)?.let(controller::updateEmployee)
    }

    private fun removeEmployee() {
        val employee = selectedEmployee() ?: return
        if (JOptionPane.showConfirmDialog(
                this,
                "${employee.name} en gekoppelde roosterdata verwijderen?",
                "Medewerker verwijderen",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            ) == JOptionPane.YES_OPTION
        ) controller.removeEmployee(employee.id)
    }

    private fun editWeeklyAvailability() {
        val employee = selectedEmployee() ?: return
        val row = weeklyTable.selectedRow.takeIf { it >= 0 } ?: 0
        val weekday = weeklyTable.convertRowIndexToModel(row) + 1
        val existing = controller.state.weeklyAvailability.lastOrNull {
            it.employeeId == employee.id && it.weekday == weekday
        }
        DesktopDialogs.weeklyAvailability(this, employee, weekday, existing)
            ?.let(controller::upsertWeeklyAvailability)
    }

    private fun resetWeeklyAvailability() {
        val employee = selectedEmployee() ?: return
        val row = weeklyTable.selectedRow.takeIf { it >= 0 } ?: return
        controller.removeWeeklyAvailability(employee.id, weeklyTable.convertRowIndexToModel(row) + 1)
    }

    private fun addDateException() {
        val employee = selectedEmployee() ?: return
        DesktopDialogs.dateAvailability(this, controller.state, employee)?.let(controller::upsertAvailability)
    }

    private fun removeDateException() {
        val employee = selectedEmployee() ?: return
        val row = selectedModelRow(exceptionTable) ?: return
        val rules = controller.state.availability.filter { it.employeeId == employee.id }.sortedBy { it.date }
        val rule = rules.getOrNull(row) ?: return
        controller.removeAvailability(employee.id, rule.date)
    }

    private fun selectedEmployee(): Employee? {
        val id = selectedEmployeeId
        if (id != null) controller.state.employees.firstOrNull { it.id == id }?.let { return it }
        val row = selectedModelRow(employeeTable) ?: return null
        return controller.state.employees.getOrNull(row)
    }

    private fun yesNo(value: Boolean): String = if (value) "Ja" else "Nee"
}

private fun readOnlyModel(vararg columns: String): DefaultTableModel = object : DefaultTableModel(columns, 0) {
    override fun isCellEditable(row: Int, column: Int): Boolean = false
}
