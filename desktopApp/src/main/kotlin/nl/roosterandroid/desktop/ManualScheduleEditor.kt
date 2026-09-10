package nl.roosterandroid.desktop

import nl.roosterandroid.app.AbsenceStatus
import nl.roosterandroid.app.AppState
import nl.roosterandroid.app.Employee
import nl.roosterandroid.app.ShiftKind
import nl.roosterandroid.app.ShiftTemplate
import nl.roosterandroid.app.allowsShiftOn
import nl.roosterandroid.app.canWork
import java.awt.Component
import java.awt.GridLayout
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Handmatige rooster-editor. Beschikbaarheid is een venster, geen dienst.
 * De gebruiker kiest hier de echte dienst die binnen dat venster valt.
 */
internal object ManualScheduleEditor {
    private data class Choice<T>(val label: String, val value: T) {
        override fun toString(): String = label
    }

    private data class ActionChoice(
        val label: String,
        val template: ShiftTemplate? = null,
        val custom: Boolean = false,
        val keepCurrent: Boolean = false
    ) {
        override fun toString(): String = label
    }

    fun open(parent: Component, controller: DesktopController) {
        val state = controller.state
        val employees = state.employees.filter { it.active }
        if (employees.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "Voeg eerst een medewerker toe.", "Dienst aanpassen", JOptionPane.INFORMATION_MESSAGE)
            return
        }

        val ym = runCatching { YearMonth.of(state.year, state.month) }.getOrNull() ?: return
        val locale = Locale.forLanguageTag("nl-NL")
        val employeeChoices = employees.map { Choice(it.name, it) }.toTypedArray()
        val dateChoices = (1..ym.lengthOfMonth()).map { day ->
            val date = ym.atDay(day)
            val dow = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).replaceFirstChar { it.uppercase() }
            Choice("$dow ${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.SHORT, locale)}", date)
        }.toTypedArray()

        val employeeBox = JComboBox(employeeChoices)
        val dateBox = JComboBox(dateChoices)
        val selectPanel = JPanel(GridLayout(0, 2, 8, 8)).apply {
            add(JLabel("Medewerker")); add(employeeBox)
            add(JLabel("Datum")); add(dateBox)
        }
        if (JOptionPane.showConfirmDialog(
                parent,
                selectPanel,
                "Dienst aanpassen",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
            ) != JOptionPane.OK_OPTION
        ) return

        val employee = (employeeBox.selectedItem as? Choice<*>)?.value as? Employee ?: return
        val date = (dateBox.selectedItem as? Choice<*>)?.value as? LocalDate ?: return
        editDay(parent, controller, employee, date)
    }

    fun open(
        parent: Component,
        controller: DesktopController,
        employee: Employee,
        date: LocalDate
    ) {
        editDay(parent, controller, employee, date)
    }

    private fun editDay(
        parent: Component,
        controller: DesktopController,
        employee: Employee,
        date: LocalDate
    ) {
        val state = controller.state

        val currentAssignment = state.assignments.lastOrNull {
            it.employeeId == employee.id &&
                it.date == date.toString()
        }

        val currentTemplate = currentAssignment?.let { assignment ->
            state.shiftTemplates.firstOrNull {
                it.id == assignment.shiftTemplateId
            }
        }

        val availableText = availabilityText(
            state,
            employee,
            date
        )

        val currentText = currentTemplate?.let {
            "${it.name} ${it.start}-${it.end}"
        } ?: "Vrij"

        val fitting = state.shiftTemplates
            .asSequence()
            .filterNot { it.archived }
            .filter {
                date.dayOfWeek.value in it.enabledWeekdays
            }
            .filter { employee.canWork(it.kind) }
            .filter { state.allowsShiftOn(date, it) }
            .filter {
                fitsAvailability(
                    state,
                    employee,
                    date,
                    it
                )
            }
            .sortedWith(
                compareBy<ShiftTemplate>(
                    { it.start },
                    { it.end },
                    { it.name }
                )
            )
            .toList()

        val actions = buildList {
            if (currentAssignment != null) {
                add(
                    ActionChoice(
                        "Huidige dienst behouden   $currentText",
                        keepCurrent = true
                    )
                )
            }

            add(ActionChoice("Vrij / geen dienst"))

            fitting.forEach {
                add(
                    ActionChoice(
                        "${it.name}   ${it.start}-${it.end}",
                        template = it
                    )
                )
            }

            add(
                ActionChoice(
                    "Aangepaste tijd...",
                    custom = true
                )
            )
        }.toTypedArray()

        val message = arrayOf(
            "${employee.name} • $date",
            "Beschikbaar: $availableText",
            "Huidige dienst: $currentText",
            " ",
            "Kies de echte dienst. Beschikbaarheid blijft alleen het toegestane tijdvenster."
        )

        val selected = JOptionPane.showInputDialog(
            parent,
            message,
            "Dienst aanpassen",
            JOptionPane.PLAIN_MESSAGE,
            null,
            actions,
            actions.first()
        ) as? ActionChoice ?: return

        when {
            selected.keepCurrent -> return

            selected.custom ->
                customTime(
                    parent,
                    controller,
                    employee,
                    date
                )

            selected.template == null ->
                controller.setManualAssignment(
                    employee.id,
                    date,
                    null
                )

            else ->
                controller.setManualAssignment(
                    employee.id,
                    date,
                    selected.template.id
                )
        }
    }

    private fun customTime(parent: Component, controller: DesktopController, employee: Employee, date: LocalDate) {
        val state = controller.state
        val rule = specificOrWeeklyRule(state, employee, date)
        val defaultStart = rule?.first ?: "09:00"
        val defaultEnd = rule?.second ?: "17:00"
        val start = JTextField(defaultStart, 8)
        val end = JTextField(defaultEnd, 8)
        val kinds = ShiftKind.entries.filter { employee.canWork(it) }.toTypedArray()
        val kind = JComboBox(kinds).apply {
            selectedItem = if (ShiftKind.MIDDLE in kinds && defaultStart >= "12:00") ShiftKind.MIDDLE else ShiftKind.DAY
        }
        val form = JPanel(GridLayout(0, 2, 8, 8)).apply {
            add(JLabel("Van")); add(start)
            add(JLabel("Tot")); add(end)
            add(JLabel("Type")); add(kind)
        }
        if (JOptionPane.showConfirmDialog(
                parent,
                form,
                "Aangepaste dienst",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
            ) != JOptionPane.OK_OPTION
        ) return

        val startValue = normalizeTime(start.text)
        val endValue = normalizeTime(end.text)
        if (startValue == null || endValue == null) {
            JOptionPane.showMessageDialog(parent, "Gebruik tijden als 14:00 of 22:00.", "Ongeldige tijd", JOptionPane.WARNING_MESSAGE)
            return
        }
        val selectedKind = kind.selectedItem as? ShiftKind ?: return
        val probe = ShiftTemplate(
            name = "Aangepast $startValue-$endValue",
            kind = selectedKind,
            start = startValue,
            end = endValue,
            enabledWeekdays = setOf(date.dayOfWeek.value)
        )
        if (!state.allowsShiftOn(date, probe)) {
            JOptionPane.showMessageDialog(parent, "Deze dienst valt buiten de restauranttijden.", "Dienst niet toegestaan", JOptionPane.WARNING_MESSAGE)
            return
        }
        if (!fitsAvailability(state, employee, date, probe)) {
            JOptionPane.showMessageDialog(
                parent,
                "Deze dienst valt buiten de opgeslagen beschikbaarheid (${availabilityText(state, employee, date)}). Pas eerst de beschikbaarheid aan of kies een passende dienst.",
                "Buiten beschikbaarheid",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }
        controller.setManualCustomAssignment(employee.id, date.toString(), startValue, endValue, selectedKind)
    }

    private fun normalizeTime(raw: String): String? = runCatching {
        LocalTime.parse(raw.trim(), DateTimeFormatter.ofPattern("H:mm")).format(DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrNull()

    private fun specificOrWeeklyRule(
        state: AppState,
        employee: Employee,
        date: LocalDate
    ): Pair<String?, String?>? {
        val specific = state.availability.lastOrNull {
            it.employeeId == employee.id && it.date == date.toString()
        }
        if (specific != null) {
            return specific.earliestStart to specific.latestEnd
        }

        val weekly = state.weeklyAvailability.lastOrNull {
            it.employeeId == employee.id &&
                it.weekday == date.dayOfWeek.value
        }
        if (weekly != null) {
            return weekly.earliestStart to weekly.latestEnd
        }

        return null
    }

    private fun availabilityText(
        state: AppState,
        employee: Employee,
        date: LocalDate
    ): String {
        val specific = state.availability.lastOrNull {
            it.employeeId == employee.id &&
                it.date == date.toString()
        }

        val weekly = state.weeklyAvailability.lastOrNull {
            it.employeeId == employee.id &&
                it.weekday == date.dayOfWeek.value
        }

        val available =
            if (specific != null) {
                specific.available
            } else {
                weekly?.available ?: true
            }

        if (!available) return "niet beschikbaar"

        val absence = state.absences.firstOrNull {
            it.employeeId == employee.id &&
                it.status == AbsenceStatus.APPROVED &&
                it.includes(date)
        }

        if (absence != null) {
            return "afwezig (${absence.type.name.lowercase()})"
        }

        val from =
            if (specific != null) {
                specific.earliestStart
            } else {
                weekly?.earliestStart
            }

        val until =
            if (specific != null) {
                specific.latestEnd
            } else {
                weekly?.latestEnd
            }

        return when {
            from != null && until != null ->
                "$from-$until"

            from != null ->
                "vanaf $from"

            until != null ->
                "tot $until"

            else ->
                "hele dag"
        }
    }

    private fun fitsAvailability(
        state: AppState,
        employee: Employee,
        date: LocalDate,
        template: ShiftTemplate
    ): Boolean {
        if (state.absences.any {
                it.employeeId == employee.id &&
                    it.status == AbsenceStatus.APPROVED &&
                    it.includes(date)
            }
        ) {
            return false
        }

        val specific = state.availability.lastOrNull {
            it.employeeId == employee.id &&
                it.date == date.toString()
        }

        val weekly = state.weeklyAvailability.lastOrNull {
            it.employeeId == employee.id &&
                it.weekday == date.dayOfWeek.value
        }

        val available =
            if (specific != null) {
                specific.available
            } else {
                weekly?.available ?: true
            }

        if (!available) return false

        val fixedShiftKind =
            if (specific != null) {
                specific.fixedShiftKind
            } else {
                weekly?.fixedShiftKind
            }

        if (
            fixedShiftKind != null &&
            fixedShiftKind != template.kind
        ) {
            return false
        }

        val earliestText =
            if (specific != null) {
                specific.earliestStart
            } else {
                weekly?.earliestStart
            }

        val latestText =
            if (specific != null) {
                specific.latestEnd
            } else {
                weekly?.latestEnd
            }

        val earliest = earliestText?.let {
            runCatching {
                LocalTime.parse(it)
            }.getOrNull()
        }

        val latest = latestText?.let {
            runCatching {
                LocalTime.parse(it)
            }.getOrNull()
        }

        val anchor = LocalDate.of(2000, 1, 3)
        val shiftStart =
            anchor.atTime(template.startTime())

        var shiftEnd =
            anchor.atTime(template.endTime())

        if (!shiftEnd.isAfter(shiftStart)) {
            shiftEnd = shiftEnd.plusDays(1)
        }

        if (earliest != null) {
            val minimumStart =
                anchor.atTime(earliest)

            if (shiftStart.isBefore(minimumStart)) {
                return false
            }
        }

        if (latest != null) {
            var maximumEnd =
                anchor.atTime(latest)

            if (
                earliest != null &&
                !maximumEnd.isAfter(
                    anchor.atTime(earliest)
                )
            ) {
                maximumEnd =
                    maximumEnd.plusDays(1)
            }

            if (shiftEnd.isAfter(maximumEnd)) {
                return false
            }
        }

        return true
    }
}
