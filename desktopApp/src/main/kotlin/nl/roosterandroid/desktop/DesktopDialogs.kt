package nl.roosterandroid.desktop

import nl.roosterandroid.app.Absence
import nl.roosterandroid.app.AbsenceStatus
import nl.roosterandroid.app.AbsenceType
import nl.roosterandroid.app.AppState
import nl.roosterandroid.app.Availability
import nl.roosterandroid.app.DayPartDemand
import nl.roosterandroid.app.Employee
import nl.roosterandroid.app.EmployeeRole
import nl.roosterandroid.app.OperatingHours
import nl.roosterandroid.app.PersonDayMarker
import nl.roosterandroid.app.PersonMarkerType
import nl.roosterandroid.app.RecurrenceType
import nl.roosterandroid.app.ResponsibilityRule
import nl.roosterandroid.app.ResponsibilityType
import nl.roosterandroid.app.ShiftKind
import nl.roosterandroid.app.ShiftTemplate
import nl.roosterandroid.app.WeeklyAvailability
import nl.roosterandroid.app.canWork
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.border.EmptyBorder

internal object DesktopDialogs {
    private val dayLabels = arrayOf("Ma", "Di", "Wo", "Do", "Vr", "Za", "Zo")

    fun employee(parent: Component, existing: Employee? = null): Employee? {
        val name = JTextField(existing?.name.orEmpty(), 22)
        val role = JComboBox(EmployeeRole.entries.toTypedArray()).apply {
            selectedItem = existing?.role ?: EmployeeRole.MANAGER
            renderer = LabelListRenderer { roleLabel(it as EmployeeRole) }
        }
        val days = JSpinner(SpinnerNumberModel(existing?.contractedDaysPerWeek ?: 5, 0, 7, 1))
        val hours = JSpinner(SpinnerNumberModel(existing?.contractedHoursPerWeek ?: 40.0, 0.0, 60.0, 1.0))
        val maximum = JSpinner(SpinnerNumberModel(existing?.maxShiftsPerWeek ?: 5, 1, 7, 1))
        val setup = JCheckBox("Setup", existing?.canSetup ?: true)
        val day = JCheckBox("Dag", existing?.canDay ?: true)
        val middle = JCheckBox("Tussen", existing?.canMiddle ?: true)
        val close = JCheckBox("Sluit", existing?.canClose ?: true)
        val kpi = JCheckBox("KPI", existing?.canKpi ?: true)
        val active = JCheckBox("Actief", existing?.active ?: true)

        val form = formPanel(
            "Naam" to name,
            "Rol" to role,
            "Contractdagen/week" to days,
            "Contracturen/week" to hours,
            "Max. diensten/week" to maximum,
            "Toegestane diensten" to JPanel().apply { add(setup); add(day); add(middle); add(close); add(kpi) },
            "Status" to active
        )
        if (!confirm(parent, if (existing == null) "Medewerker toevoegen" else "Medewerker wijzigen", form)) return null
        if (name.text.isBlank()) {
            error(parent, "Vul een naam in")
            return null
        }
        return (existing ?: Employee(name = name.text.trim())).copy(
            name = name.text.trim(),
            role = role.selectedItem as EmployeeRole,
            contractedDaysPerWeek = days.value as Int,
            contractedHoursPerWeek = (hours.value as Number).toDouble(),
            maxShiftsPerWeek = maximum.value as Int,
            canSetup = setup.isSelected,
            canDay = day.isSelected,
            canMiddle = middle.isSelected,
            canClose = close.isSelected,
            canKpi = kpi.isSelected,
            active = active.isSelected
        )
    }

    fun weeklyAvailability(
        parent: Component,
        employee: Employee,
        weekday: Int,
        existing: WeeklyAvailability?
    ): WeeklyAvailability? {
        val available = JCheckBox("Beschikbaar", existing?.available ?: true)
        val earliest = JTextField(existing?.earliestStart.orEmpty(), 8)
        val latest = JTextField(existing?.latestEnd.orEmpty(), 8)
        val kinds = arrayOf<ShiftKind?>(null, *ShiftKind.entries.toTypedArray())
        val fixed = JComboBox(kinds).apply {
            selectedItem = existing?.fixedShiftKind
            renderer = LabelListRenderer { item -> item?.let { shiftKindLabel(it as ShiftKind) } ?: "Geen vaste dienst" }
        }
        val form = formPanel(
            "Medewerker" to JLabel(employee.name),
            "Weekdag" to JLabel(dayLabels[weekday - 1]),
            "Beschikbaar" to available,
            "Vroegste start" to earliest,
            "Laatste einde" to latest,
            "Vaste dienst" to fixed
        )
        if (!confirm(parent, "Vaste weekbeschikbaarheid", form)) return null
        if (!validOptionalTime(earliest.text) || !validOptionalTime(latest.text)) {
            error(parent, "Gebruik tijden als UU:mm, bijvoorbeeld 09:00")
            return null
        }
        return WeeklyAvailability(
            employeeId = employee.id,
            weekday = weekday,
            available = available.isSelected,
            earliestStart = earliest.text.trim().ifBlank { null },
            latestEnd = latest.text.trim().ifBlank { null },
            fixedShiftKind = fixed.selectedItem as ShiftKind?
        )
    }

    fun dateAvailability(parent: Component, state: AppState, employee: Employee): Availability? {
        val date = JTextField(LocalDate.now().toString(), 12)
        val available = JCheckBox("Beschikbaar", true)
        val earliest = JTextField("", 8)
        val latest = JTextField("", 8)
        val kinds = arrayOf<ShiftKind?>(null, *ShiftKind.entries.toTypedArray())
        val fixed = JComboBox(kinds).apply {
            renderer = LabelListRenderer { item -> item?.let { shiftKindLabel(it as ShiftKind) } ?: "Geen vaste dienst" }
        }
        val form = formPanel(
            "Medewerker" to JLabel(employee.name),
            "Datum (JJJJ-MM-DD)" to date,
            "Beschikbaar" to available,
            "Vroegste start" to earliest,
            "Laatste einde" to latest,
            "Vaste dienst" to fixed
        )
        if (!confirm(parent, "Afwijking op datum", form)) return null
        val parsed = runCatching { LocalDate.parse(date.text.trim()) }.getOrNull()
        if (parsed == null || !validOptionalTime(earliest.text) || !validOptionalTime(latest.text)) {
            error(parent, "Controleer datum en tijden")
            return null
        }
        return Availability(
            employeeId = employee.id,
            date = parsed.toString(),
            available = available.isSelected,
            earliestStart = earliest.text.trim().ifBlank { null },
            latestEnd = latest.text.trim().ifBlank { null },
            fixedShiftKind = fixed.selectedItem as ShiftKind?
        )
    }

    fun template(parent: Component, existing: ShiftTemplate? = null): ShiftTemplate? {
        val name = JTextField(existing?.name.orEmpty(), 20)
        val kind = JComboBox(ShiftKind.entries.toTypedArray()).apply {
            selectedItem = existing?.kind ?: ShiftKind.CUSTOM
            renderer = LabelListRenderer { shiftKindLabel(it as ShiftKind) }
        }
        val start = JTextField(existing?.start ?: "09:00", 8)
        val end = JTextField(existing?.end ?: "17:00", 8)
        val days = (1..7).map { weekday ->
            JCheckBox(dayLabels[weekday - 1], weekday in (existing?.enabledWeekdays ?: (1..7).toSet()))
        }
        val form = formPanel(
            "Naam" to name,
            "Type" to kind,
            "Start" to start,
            "Einde" to end,
            "Actieve dagen" to JPanel().apply { days.forEach(::add) }
        )
        if (!confirm(parent, if (existing == null) "Diensttemplate toevoegen" else "Diensttemplate wijzigen", form)) return null
        if (name.text.isBlank() || !validTime(start.text) || !validTime(end.text) || days.none { it.isSelected }) {
            error(parent, "Vul een naam, geldige tijden en minimaal één dag in")
            return null
        }
        return ShiftTemplate(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.text.trim(),
            kind = kind.selectedItem as ShiftKind,
            start = LocalTime.parse(start.text.trim()).toString(),
            end = LocalTime.parse(end.text.trim()).toString(),
            enabledWeekdays = days.mapIndexedNotNull { index, check -> if (check.isSelected) index + 1 else null }.toSet()
        )
    }

    fun absence(parent: Component, state: AppState, existing: Absence? = null): Absence? {
        if (state.employees.isEmpty()) return null
        val employees = state.employees.filter { it.active }
        val employee = JComboBox(employees.toTypedArray()).apply {
            selectedItem = employees.firstOrNull { it.id == existing?.employeeId } ?: employees.first()
            renderer = LabelListRenderer { (it as Employee).name }
        }
        val type = JComboBox(AbsenceType.entries.toTypedArray()).apply {
            selectedItem = existing?.type ?: AbsenceType.VACATION
            renderer = LabelListRenderer { absenceLabel(it as AbsenceType) }
        }
        val status = JComboBox(AbsenceStatus.entries.toTypedArray()).apply { selectedItem = existing?.status ?: AbsenceStatus.APPROVED }
        val start = JTextField(existing?.startDate ?: LocalDate.now().toString(), 12)
        val end = JTextField(existing?.endDate ?: start.text, 12)
        val note = JTextField(existing?.note.orEmpty(), 24)
        val form = formPanel(
            "Medewerker" to employee,
            "Type" to type,
            "Status" to status,
            "Vanaf" to start,
            "Tot en met" to end,
            "Opmerking" to note
        )
        if (!confirm(parent, "Afwezigheid", form)) return null
        val startDate = runCatching { LocalDate.parse(start.text.trim()) }.getOrNull()
        val endDate = runCatching { LocalDate.parse(end.text.trim()) }.getOrNull()
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            error(parent, "Controleer de periode")
            return null
        }
        return Absence(
            id = existing?.id ?: UUID.randomUUID().toString(),
            employeeId = (employee.selectedItem as Employee).id,
            startDate = startDate.toString(),
            endDate = endDate.toString(),
            type = type.selectedItem as AbsenceType,
            status = status.selectedItem as AbsenceStatus,
            note = note.text.trim()
        )
    }

    data class SickInput(val employee: Employee, val date: LocalDate, val note: String)

    fun sick(parent: Component, state: AppState): SickInput? {
        val employees = state.employees.filter { it.active }
        if (employees.isEmpty()) return null
        val employee = JComboBox(employees.toTypedArray()).apply {
            renderer = LabelListRenderer { (it as Employee).name }
        }
        val date = JTextField(LocalDate.now().toString(), 12)
        val note = JTextField("", 24)
        val form = formPanel(
            "Medewerker" to employee,
            "Datum" to date,
            "Opmerking" to note
        )
        if (!confirm(parent, "Ziekmelden en vervanger zoeken", form)) return null
        val parsed = runCatching { LocalDate.parse(date.text.trim()) }.getOrNull()
        if (parsed == null) {
            error(parent, "Gebruik datum JJJJ-MM-DD")
            return null
        }
        return SickInput(employee.selectedItem as Employee, parsed, note.text.trim())
    }

    fun dayPart(parent: Component, existing: DayPartDemand? = null): DayPartDemand? {
        val date = JTextField(existing?.date ?: LocalDate.now().toString(), 12)
        val label = JTextField(existing?.label ?: "Lunch", 18)
        val start = JTextField(existing?.start ?: "11:00", 8)
        val end = JTextField(existing?.end ?: "14:00", 8)
        val minimum = JSpinner(SpinnerNumberModel(existing?.minimumManagers ?: 2, 0, 20, 1))
        val guests = JTextField(existing?.guestCount?.toString().orEmpty(), 8)
        val note = JTextField(existing?.note.orEmpty(), 24)
        val form = formPanel(
            "Datum" to date,
            "Dagdeel / label" to label,
            "Vanaf" to start,
            "Tot" to end,
            "Minimum managers" to minimum,
            "Gastenaantal" to guests,
            "Opmerking" to note
        )
        if (!confirm(parent, "Bezetting per dagdeel", form)) return null
        val parsed = runCatching { LocalDate.parse(date.text.trim()) }.getOrNull()
        if (parsed == null || label.text.isBlank() || !validTime(start.text) || !validTime(end.text)) {
            error(parent, "Controleer datum, label en tijden")
            return null
        }
        return DayPartDemand(
            id = existing?.id ?: UUID.randomUUID().toString(),
            date = parsed.toString(),
            label = label.text.trim(),
            start = LocalTime.parse(start.text.trim()).toString(),
            end = LocalTime.parse(end.text.trim()).toString(),
            minimumManagers = minimum.value as Int,
            guestCount = guests.text.filter(Char::isDigit).toIntOrNull(),
            note = note.text.trim()
        )
    }

    fun operatingHours(parent: Component, existing: OperatingHours): OperatingHours? {
        val open = JTextField(existing.open, 8)
        val close = JTextField(existing.close, 8)
        val closed = JCheckBox("Gesloten", existing.closed)
        val form = formPanel(
            "Weekdag" to JLabel(dayLabels[existing.weekday - 1]),
            "Open / planning start" to open,
            "Dicht / planning einde" to close,
            "Status" to closed
        )
        if (!confirm(parent, "Restauranttijden", form)) return null
        if (!validTime(open.text) || !validTime(close.text)) {
            error(parent, "Gebruik tijden als UU:mm")
            return null
        }
        return existing.copy(
            open = LocalTime.parse(open.text.trim()).toString(),
            close = LocalTime.parse(close.text.trim()).toString(),
            closed = closed.isSelected
        )
    }

    fun responsibility(parent: Component, state: AppState): ResponsibilityRule? {
        val employees = state.employees.filter { it.active }
        if (employees.isEmpty()) return null
        val employee = JComboBox(employees.toTypedArray()).apply { renderer = LabelListRenderer { (it as Employee).name } }
        val type = JComboBox(ResponsibilityType.entries.toTypedArray()).apply {
            renderer = LabelListRenderer { responsibilityLabel(it as ResponsibilityType) }
        }
        val recurrence = JComboBox(RecurrenceType.entries.toTypedArray())
        val weekday = JComboBox(dayLabels)
        val monthDay = JSpinner(SpinnerNumberModel(1, 1, 31, 1))
        val date = JTextField(LocalDate.now().toString(), 12)
        val label = JTextField("", 18)
        val prefer = JCheckBox("Planner zet deze persoon bij voorkeur op een gewone dienst", true)
        val form = formPanel(
            "Medewerker" to employee,
            "Taak" to type,
            "Herhaling" to recurrence,
            "Weekdag" to weekday,
            "Dag van maand" to monthDay,
            "Specifieke datum" to date,
            "Eigen label" to label,
            "Planning" to prefer
        )
        if (!confirm(parent, "Taak of verantwoordelijkheid", form)) return null
        val selectedRecurrence = recurrence.selectedItem as RecurrenceType
        val parsedDate = runCatching { LocalDate.parse(date.text.trim()) }.getOrNull()
        if (selectedRecurrence == RecurrenceType.SPECIFIC_DATE && parsedDate == null) {
            error(parent, "Controleer de specifieke datum")
            return null
        }
        return ResponsibilityRule(
            employeeId = (employee.selectedItem as Employee).id,
            type = type.selectedItem as ResponsibilityType,
            recurrence = selectedRecurrence,
            weekday = weekday.selectedIndex + 1,
            monthDay = monthDay.value as Int,
            date = parsedDate?.toString(),
            label = label.text.trim(),
            preferScheduled = prefer.isSelected
        )
    }

    fun marker(parent: Component, state: AppState): PersonDayMarker? {
        val employees = state.employees.filter { it.active }
        if (employees.isEmpty()) return null
        val employee = JComboBox(employees.toTypedArray()).apply { renderer = LabelListRenderer { (it as Employee).name } }
        val type = JComboBox(PersonMarkerType.entries.toTypedArray()).apply {
            renderer = LabelListRenderer { markerLabel(it as PersonMarkerType) }
        }
        val date = JTextField(LocalDate.now().toString(), 12)
        val note = JTextField("", 22)
        val form = formPanel(
            "Medewerker" to employee,
            "Type" to type,
            "Datum" to date,
            "Opmerking" to note
        )
        if (!confirm(parent, "Aanwezigheid of markering", form)) return null
        val parsed = runCatching { LocalDate.parse(date.text.trim()) }.getOrNull()
        if (parsed == null) {
            error(parent, "Controleer de datum")
            return null
        }
        return PersonDayMarker(
            employeeId = (employee.selectedItem as Employee).id,
            date = parsed.toString(),
            type = type.selectedItem as PersonMarkerType,
            note = note.text.trim()
        )
    }

    fun assignment(parent: Component, state: AppState, employee: Employee, date: LocalDate): TemplateChoice? {
        val templates = state.shiftTemplates.filter {
            date.dayOfWeek.value in it.enabledWeekdays && employee.canWork(it.kind)
        }
        val choices = mutableListOf<TemplateChoice>(TemplateChoice(null, "Vrij"))
        choices += templates.map { TemplateChoice(it.id, "${shiftKindLabel(it.kind)} • ${it.name} • ${it.start}-${it.end}") }
        val combo = JComboBox(choices.toTypedArray()).apply { renderer = LabelListRenderer { (it as TemplateChoice).label } }
        val current = state.assignments.lastOrNull { it.employeeId == employee.id && it.date == date.toString() }
        combo.selectedItem = choices.firstOrNull { it.id == current?.shiftTemplateId } ?: choices.first()
        val form = formPanel(
            "Medewerker" to JLabel(employee.name),
            "Datum" to JLabel(date.toString()),
            "Dienst" to combo
        )
        if (!confirm(parent, "Rooster handmatig bewerken", form)) return null
        return combo.selectedItem as TemplateChoice
    }

    data class TemplateChoice(val id: String?, val label: String)

    private fun formPanel(vararg rows: Pair<String, Component>): JPanel = JPanel(GridBagLayout()).apply {
        border = EmptyBorder(6, 6, 6, 6)
        rows.forEachIndexed { index, (label, component) ->
            add(JLabel(label), GridBagConstraints().apply {
                gridx = 0
                gridy = index
                anchor = GridBagConstraints.NORTHWEST
                insets = Insets(5, 0, 5, 12)
            })
            add(component, GridBagConstraints().apply {
                gridx = 1
                gridy = index
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
                insets = Insets(3, 0, 3, 0)
            })
        }
    }

    private fun confirm(parent: Component, title: String, content: Component): Boolean =
        JOptionPane.showConfirmDialog(
            parent,
            content,
            title,
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        ) == JOptionPane.OK_OPTION

    private fun error(parent: Component, message: String) {
        JOptionPane.showMessageDialog(parent, message, "Controleer invoer", JOptionPane.ERROR_MESSAGE)
    }

    private fun validOptionalTime(value: String): Boolean = value.isBlank() || validTime(value)
    private fun validTime(value: String): Boolean = runCatching { LocalTime.parse(value.trim()) }.isSuccess
}

private class LabelListRenderer(private val text: (Any?) -> String) : javax.swing.DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: javax.swing.JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component = super.getListCellRendererComponent(list, text(value), index, isSelected, cellHasFocus)
}
