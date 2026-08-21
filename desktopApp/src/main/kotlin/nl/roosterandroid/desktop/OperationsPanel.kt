package nl.roosterandroid.desktop

import nl.roosterandroid.app.Absence
import nl.roosterandroid.app.DayPartDemand
import nl.roosterandroid.app.PersonDayMarker
import nl.roosterandroid.app.RecurrenceType
import nl.roosterandroid.app.ResponsibilityRule
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.border.EmptyBorder
import javax.swing.table.DefaultTableModel

internal class OperationsPanel(private val controller: DesktopController) : JPanel(BorderLayout()), Refreshable {
    private val absenceModel = model("Medewerker", "Type", "Vanaf", "Tot", "Status", "Opmerking")
    private val absenceTable = configuredTable(absenceModel)
    private val dayPartModel = model("Datum", "Dagdeel", "Tijd", "Min. managers", "Gasten", "Opmerking")
    private val dayPartTable = configuredTable(dayPartModel)
    private val taskModel = model("Medewerker", "Taak", "Herhaling", "Wanneer", "Planner voorkeur")
    private val taskTable = configuredTable(taskModel)
    private val markerModel = model("Datum", "Medewerker", "Type", "Opmerking")
    private val markerTable = configuredTable(markerModel)

    init {
        border = EmptyBorder(14, 14, 14, 14)
        add(panelTitle("Afwezigheid, bezetting en taken"), BorderLayout.NORTH)
        val tabs = JTabbedPane().apply {
            putClientProperty("JTabbedPane.tabType", "card")
            addTab("Afwezigheid", absenceTab())
            addTab("Bezetting per dagdeel", dayPartTab())
            addTab("Vaste taken", taskTab())
            addTab("Aanwezig / kantoor", markerTab())
        }
        add(tabs, BorderLayout.CENTER)
        refresh()
    }

    override fun refresh() {
        val state = controller.state
        val names = state.employees.associate { it.id to it.name }

        absenceModel.rowCount = 0
        currentAbsences().forEach { absence ->
            absenceModel.addRow(arrayOf(
                names[absence.employeeId] ?: "?",
                absenceLabel(absence.type),
                absence.startDate,
                absence.endDate,
                absence.status.name.lowercase().replaceFirstChar { it.uppercase() },
                absence.note
            ))
        }

        dayPartModel.rowCount = 0
        currentDayParts().forEach { demand ->
            dayPartModel.addRow(arrayOf(
                demand.date,
                demand.label,
                "${demand.start}-${demand.end}",
                demand.minimumManagers,
                demand.guestCount ?: "",
                demand.note
            ))
        }

        taskModel.rowCount = 0
        currentTasks().forEach { rule ->
            taskModel.addRow(arrayOf(
                names[rule.employeeId] ?: "?",
                responsibilityLabel(rule.type),
                recurrenceLabel(rule.recurrence),
                responsibilityWhen(rule),
                if (rule.preferScheduled) "Ja" else "Nee"
            ))
        }

        markerModel.rowCount = 0
        currentMarkers().forEach { marker ->
            markerModel.addRow(arrayOf(
                marker.date,
                names[marker.employeeId] ?: "?",
                markerLabel(marker.type),
                marker.note
            ))
        }
    }

    private fun absenceTab(): JPanel = tabPanel(
        absenceTable,
        primaryButton("Afwezigheid toevoegen") {
            DesktopDialogs.absence(this, controller.state)?.let(controller::upsertAbsence)
        },
        primaryButton("Ziekmelden + vervanger") {
            DesktopDialogs.sick(this, controller.state)?.let { input ->
                val replacement = controller.reportSickAndFindReplacement(input.employee.id, input.date, input.note)
                if (replacement != null) {
                    JOptionPane.showMessageDialog(
                        this,
                        "$replacement is op dezelfde dienst gezet. Auto-fix heeft de rest gecontroleerd.",
                        "Vervanger gevonden",
                        JOptionPane.INFORMATION_MESSAGE
                    )
                }
            }
        },
        secondaryButton("Wijzigen") {
            val item = currentAbsences().getOrNull(selectedModelRow(absenceTable) ?: return@secondaryButton) ?: return@secondaryButton
            DesktopDialogs.absence(this, controller.state, item)?.let(controller::upsertAbsence)
        },
        secondaryButton("Verwijderen") {
            currentAbsences().getOrNull(selectedModelRow(absenceTable) ?: return@secondaryButton)
                ?.let { controller.removeAbsence(it.id) }
        }
    )

    private fun dayPartTab(): JPanel = tabPanel(
        dayPartTable,
        primaryButton("Dagdeel toevoegen") {
            DesktopDialogs.dayPart(this)?.let(controller::upsertDayPartDemand)
        },
        secondaryButton("Wijzigen") {
            val item = currentDayParts().getOrNull(selectedModelRow(dayPartTable) ?: return@secondaryButton) ?: return@secondaryButton
            DesktopDialogs.dayPart(this, item)?.let(controller::upsertDayPartDemand)
        },
        secondaryButton("Verwijderen") {
            currentDayParts().getOrNull(selectedModelRow(dayPartTable) ?: return@secondaryButton)
                ?.let { controller.removeDayPartDemand(it.id) }
        },
        secondaryButton("Rooster bijwerken") { controller.autoFix() }
    )

    private fun taskTab(): JPanel = tabPanel(
        taskTable,
        primaryButton("Taak toevoegen") {
            DesktopDialogs.responsibility(this, controller.state)?.let(controller::upsertResponsibility)
        },
        secondaryButton("Verwijderen") {
            currentTasks().getOrNull(selectedModelRow(taskTable) ?: return@secondaryButton)
                ?.let { controller.removeResponsibility(it.id) }
        }
    )

    private fun markerTab(): JPanel = tabPanel(
        markerTable,
        primaryButton("Markering toevoegen") {
            DesktopDialogs.marker(this, controller.state)?.let(controller::upsertPersonMarker)
        },
        secondaryButton("Verwijderen") {
            currentMarkers().getOrNull(selectedModelRow(markerTable) ?: return@secondaryButton)
                ?.let { controller.removePersonMarker(it.id) }
        }
    )

    private fun tabPanel(table: JTable, vararg buttons: javax.swing.JButton): JPanel = JPanel(BorderLayout(0, 8)).apply {
        border = EmptyBorder(12, 4, 4, 4)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply { buttons.forEach(::add) }, BorderLayout.NORTH)
        add(tableScroll(table), BorderLayout.CENTER)
    }

    private fun currentAbsences(): List<Absence> = controller.state.absences.sortedWith(compareBy({ it.startDate }, { it.employeeId }))
    private fun currentDayParts(): List<DayPartDemand> = controller.state.dayPartDemands.sortedWith(compareBy({ it.date }, { it.start }))
    private fun currentTasks(): List<ResponsibilityRule> = controller.state.responsibilities.filter { it.active }.sortedBy { it.type.name }
    private fun currentMarkers(): List<PersonDayMarker> = controller.state.personMarkers.sortedWith(compareBy({ it.date }, { it.employeeId }))

    private fun recurrenceLabel(type: RecurrenceType): String = when (type) {
        RecurrenceType.WEEKLY -> "Wekelijks"
        RecurrenceType.MONTHLY_DAY -> "Maandelijks"
        RecurrenceType.MONTH_END -> "Maandeinde"
        RecurrenceType.SPECIFIC_DATE -> "Eenmalig"
    }

    private fun responsibilityWhen(rule: ResponsibilityRule): String = when (rule.recurrence) {
        RecurrenceType.WEEKLY -> listOf("Ma", "Di", "Wo", "Do", "Vr", "Za", "Zo")[rule.weekday.coerceIn(1, 7) - 1]
        RecurrenceType.MONTHLY_DAY -> "dag ${rule.monthDay ?: 1}"
        RecurrenceType.MONTH_END -> "laatste dag"
        RecurrenceType.SPECIFIC_DATE -> rule.date.orEmpty()
    }
}

private fun model(vararg columns: String): DefaultTableModel = object : DefaultTableModel(columns, 0) {
    override fun isCellEditable(row: Int, column: Int): Boolean = false
}
