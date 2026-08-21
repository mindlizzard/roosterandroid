package nl.roosterandroid.desktop

import nl.roosterandroid.app.AbsenceStatus
import nl.roosterandroid.app.AtwValidator
import nl.roosterandroid.app.Employee
import nl.roosterandroid.app.PersonDayMarker
import nl.roosterandroid.app.ResponsibilityRule
import nl.roosterandroid.app.ShiftKind
import nl.roosterandroid.app.ShiftTemplate
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

internal class SchedulePanel(private val controller: DesktopController) : JPanel(BorderLayout()), Refreshable {
    private val model = ScheduleTableModel(controller)
    private val table = object : JTable(model) {
        override fun getToolTipText(event: MouseEvent): String? {
            val point = event.point
            val row = rowAtPoint(point)
            val column = columnAtPoint(point)
            if (row < 0 || column < 0) return null
            return (getValueAt(row, column) as? ScheduleCell)?.tooltip
        }
    }
    private val hint = JLabel("Dubbelklik op een dienst om hem handmatig te wijzigen. Volledige tijden blijven zichtbaar.")

    init {
        border = EmptyBorder(14, 14, 14, 14)
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(panelTitle("Roostermatrix"))
            add(primaryButton("Dienst aanpassen") { editSelectedCell() })
            add(secondaryButton("Diensten ruilen") { swapAssignments() })
            add(secondaryButton("PDF maken") { exportPdf() })
            add(secondaryButton("CSV voor Excel") { exportCsv() })
        }
        add(toolbar, BorderLayout.NORTH)

        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        table.rowHeight = 68
        table.fillsViewportHeight = true
        table.showVerticalLines = true
        table.showHorizontalLines = true
        table.gridColor = Color(0xDD, 0xE1, 0xE6)
        table.tableHeader.preferredSize = Dimension(10, 42)
        table.tableHeader.reorderingAllowed = false
        table.selectionModel.selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
        table.columnModel.selectionModel.selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
        table.cellSelectionEnabled = true
        table.setDefaultRenderer(ScheduleCell::class.java, ScheduleCellRenderer())
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && event.button == MouseEvent.BUTTON1) editSelectedCell()
            }
        })

        val scroll = JScrollPane(table).apply {
            border = BorderFactory.createLineBorder(Color(0xD8, 0xDD, 0xE5))
            horizontalScrollBar.unitIncrement = 30
            verticalScrollBar.unitIncrement = 24
        }
        add(scroll, BorderLayout.CENTER)
        add(hint.apply { border = EmptyBorder(8, 4, 0, 4) }, BorderLayout.SOUTH)
        refresh()
    }

    override fun refresh() {
        model.refresh()
        configureColumns()
        val errors = controller.violations.count { it.severity == AtwValidator.Severity.ERROR }
        hint.text = if (errors == 0) {
            "Dubbelklik om te bewerken • SETUP / DAG / TUSSEN / SLUIT vet • tijden volledig als 09:00–17:00"
        } else {
            "$errors ATW-conflict(en) zichtbaar in rood • gebruik Auto-fix om opnieuw te puzzelen"
        }
    }

    private fun configureColumns() {
        if (table.columnCount == 0) return
        table.columnModel.getColumn(0).preferredWidth = 92
        for (column in 1 until table.columnCount - 1) {
            table.columnModel.getColumn(column).preferredWidth = 146
        }
        table.columnModel.getColumn(table.columnCount - 1).preferredWidth = 280
    }

    private fun editSelectedCell() {
        val viewRow = table.selectedRow
        val viewColumn = table.selectedColumn
        if (viewRow < 0 || viewColumn < 0) {
            controller.showStatus("Selecteer eerst een dienstvak")
            return
        }
        val row = table.convertRowIndexToModel(viewRow)
        val column = table.convertColumnIndexToModel(viewColumn)
        val date = model.dateAt(row)
        when {
            column == 0 -> controller.showStatus("Kies een medewerkerkolom")
            column == model.columnCount - 1 -> editNote(date)
            else -> {
                val employee = model.employeeAt(column - 1) ?: return
                val choice = DesktopDialogs.assignment(this, controller.state, employee, date) ?: return
                controller.setManualAssignment(employee.id, date, choice.id)
            }
        }
    }

    private fun editNote(date: LocalDate) {
        val current = controller.state.dayNotes.firstOrNull { it.date == date.toString() }?.text.orEmpty()
        val value = JOptionPane.showInputDialog(
            this,
            "Bijzonderheden voor $date",
            current
        ) ?: return
        controller.upsertDayNote(date, value)
    }

    private fun swapAssignments() {
        val assignments = controller.state.assignments.sortedWith(compareBy({ it.date }, { it.employeeId }))
        if (assignments.size < 2) {
            controller.showStatus("Er zijn minimaal twee diensten nodig om te ruilen")
            return
        }
        val employees = controller.state.employees.associateBy { it.id }
        val templates = controller.state.shiftTemplates.associateBy { it.id }
        val choices = assignments.map { assignment ->
            AssignmentChoice(
                assignment.id,
                "${assignment.date} • ${employees[assignment.employeeId]?.name ?: "?"} • " +
                    "${templates[assignment.shiftTemplateId]?.let { "${it.name} ${it.start}-${it.end}" } ?: "?"}"
            )
        }
        val first = JComboBox(choices.toTypedArray()).apply {
            renderer = AssignmentRenderer()
        }
        val second = JComboBox(choices.toTypedArray()).apply {
            selectedIndex = 1
            renderer = AssignmentRenderer()
        }
        val panel = JPanel(java.awt.GridLayout(2, 1, 0, 8)).apply {
            add(first)
            add(second)
        }
        if (JOptionPane.showConfirmDialog(
                this,
                panel,
                "Twee diensten ruilen",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
            ) == JOptionPane.OK_OPTION
        ) {
            controller.swapAssignments(
                (first.selectedItem as AssignmentChoice).id,
                (second.selectedItem as AssignmentChoice).id
            )
        }
    }

    private fun exportPdf() {
        val path = chooseSave("PDF-bestand", "pdf", "rooster-${controller.state.year}-${controller.state.month}.pdf") ?: return
        runCatching { DesktopExporters.writePdf(controller.state, path) }
            .onSuccess { controller.showStatus("PDF opgeslagen: ${path.fileName}") }
            .onFailure { controller.showStatus("PDF maken mislukt: ${it.message}") }
    }

    private fun exportCsv() {
        val path = chooseSave("CSV voor Excel", "csv", "rooster-${controller.state.year}-${controller.state.month}.csv") ?: return
        runCatching { DesktopExporters.writeMatrixCsv(controller.state, path) }
            .onSuccess { controller.showStatus("CSV opgeslagen: ${path.fileName}") }
            .onFailure { controller.showStatus("CSV maken mislukt: ${it.message}") }
    }

    private fun chooseSave(description: String, extension: String, defaultName: String): Path? {
        val chooser = JFileChooser().apply {
            dialogTitle = description
            fileFilter = FileNameExtensionFilter(description, extension)
            selectedFile = java.io.File(defaultName)
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return null
        val selected = chooser.selectedFile.toPath()
        return if (selected.fileName.toString().endsWith(".$extension", ignoreCase = true)) selected
        else selected.resolveSibling("${selected.fileName}.$extension")
    }
}

private data class AssignmentChoice(val id: String, val label: String)

private class AssignmentRenderer : javax.swing.DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: javax.swing.JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component = super.getListCellRendererComponent(
        list,
        (value as? AssignmentChoice)?.label.orEmpty(),
        index,
        isSelected,
        cellHasFocus
    )
}

private data class ScheduleCell(
    val html: String,
    val tooltip: String,
    val background: Color,
    val foreground: Color = Color(0x22, 0x26, 0x2B)
)

private class ScheduleCellRenderer : DefaultTableCellRenderer() {
    init {
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
        border = EmptyBorder(3, 4, 3, 4)
    }

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val cell = value as ScheduleCell
        super.getTableCellRendererComponent(table, cell.html, isSelected, hasFocus, row, column)
        if (!isSelected) {
            background = cell.background
            foreground = cell.foreground
        }
        return this
    }
}

private class ScheduleTableModel(private val controller: DesktopController) : AbstractTableModel() {
    private val locale = Locale("nl", "NL")
    private var employees: List<Employee> = emptyList()
    private var dates: List<LocalDate> = emptyList()

    fun refresh() {
        employees = controller.state.employees.filter { it.active }
        val ym = YearMonth.of(controller.state.year, controller.state.month)
        dates = (1..ym.lengthOfMonth()).map(ym::atDay)
        fireTableStructureChanged()
    }

    override fun getRowCount(): Int = dates.size
    override fun getColumnCount(): Int = employees.size + 2
    override fun getColumnClass(columnIndex: Int): Class<*> = ScheduleCell::class.java
    override fun getColumnName(column: Int): String = when (column) {
        0 -> "Datum"
        columnCount - 1 -> "Bijzonderheden"
        else -> employees[column - 1].name
    }

    fun dateAt(row: Int): LocalDate = dates[row]
    fun employeeAt(index: Int): Employee? = employees.getOrNull(index)

    override fun getValueAt(row: Int, column: Int): Any {
        val date = dates[row]
        return when (column) {
            0 -> dateCell(date)
            columnCount - 1 -> detailsCell(date)
            else -> employeeCell(employees[column - 1], date)
        }
    }

    private fun dateCell(date: LocalDate): ScheduleCell {
        val weekend = date.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        val day = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).replaceFirstChar { it.uppercase() }
        val week = date.get(WeekFields.ISO.weekOfWeekBasedYear())
        return ScheduleCell(
            html = "<html><div style='text-align:center'><b>$day ${date.dayOfMonth}</b><br>week $week</div></html>",
            tooltip = date.toString(),
            background = if (weekend) UiColors.grayWeekend else UiColors.graySoft
        )
    }

    private fun employeeCell(employee: Employee, date: LocalDate): ScheduleCell {
        val state = controller.state
        val dateString = date.toString()
        val weekend = date.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        val absence = state.absences.lastOrNull {
            it.employeeId == employee.id && it.status == AbsenceStatus.APPROVED && it.includes(date)
        }
        val assignment = state.assignments.lastOrNull {
            it.employeeId == employee.id && it.date == dateString
        }
        val template = assignment?.let { a -> state.shiftTemplates.firstOrNull { it.id == a.shiftTemplateId } }
        val specific = state.availability.lastOrNull { it.employeeId == employee.id && it.date == dateString }
        val weekly = state.weeklyAvailability.lastOrNull {
            it.employeeId == employee.id && it.weekday == date.dayOfWeek.value
        }
        val unavailable = specific?.available == false || (specific == null && weekly?.available == false)
        val conflict = controller.violations.any {
            it.severity == AtwValidator.Severity.ERROR && it.employeeId == employee.id && it.date == date
        }
        val extras = employeeExtras(employee, date)

        val (content, tooltip, color) = when {
            absence != null -> Triple(
                "<b>${htmlEscape(absenceLabel(absence.type))}</b>",
                "${employee.name}: ${absenceLabel(absence.type)}${absence.note.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()}",
                if (absence.type == nl.roosterandroid.app.AbsenceType.SICK) UiColors.redSoft else UiColors.blueSoft
            )
            assignment != null && template != null -> {
                val lock = if (assignment.source.startsWith("manual")) " <span style='font-size:8px'>• VAST</span>" else ""
                val extraHtml = extras.takeIf { it.isNotBlank() }?.let { "<br><span style='font-size:9px'>${htmlEscape(it)}</span>" }.orEmpty()
                Triple(
                    "<b>${shiftKindLabel(template.kind)}</b>$lock<br>${template.start}&ndash;${template.end}$extraHtml",
                    "${employee.name} • ${template.name} • ${template.start}-${template.end}" +
                        extras.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty(),
                    shiftColor(template.kind)
                )
            }
            unavailable -> Triple("<b>NIET BESCH.</b>", "${employee.name} is niet beschikbaar", UiColors.redSoft)
            extras.isNotBlank() -> Triple("<b>${htmlEscape(extras)}</b>", extras, UiColors.greenSoft)
            else -> Triple("Vrij", "${employee.name} is vrij", if (weekend) UiColors.grayWeekend else Color.WHITE)
        }
        return ScheduleCell(
            html = "<html><div style='text-align:center'>$content</div></html>",
            tooltip = tooltip,
            background = if (conflict) UiColors.redSoft else color,
            foreground = if (conflict) UiColors.red else Color(0x22, 0x26, 0x2B)
        )
    }

    private fun detailsCell(date: LocalDate): ScheduleCell {
        val state = controller.state
        val ym = YearMonth.from(date)
        val details = DesktopExporters.dayDetails(state, date, ym)
        val conflict = controller.violations.any { it.severity == AtwValidator.Severity.ERROR && it.date == date }
        val text = details.ifBlank { " " }
        return ScheduleCell(
            html = "<html><div style='text-align:left'>${htmlEscape(text)}</div></html>",
            tooltip = text,
            background = if (conflict) UiColors.redSoft else UiColors.yellowSoft,
            foreground = if (conflict) UiColors.red else Color(0x22, 0x26, 0x2B)
        )
    }

    private fun employeeExtras(employee: Employee, date: LocalDate): String {
        val state = controller.state
        val ym = YearMonth.from(date)
        val tasks = state.responsibilities.filter {
            it.active && it.employeeId == employee.id && DesktopExporters.responsibilityApplies(it, date, ym)
        }.map(::responsibilityShort)
        val markers = state.personMarkers.filter {
            it.employeeId == employee.id && it.date == date.toString()
        }.map { markerLabel(it.type) }
        return (tasks + markers).distinct().joinToString(" / ")
    }

    private fun shiftColor(kind: ShiftKind): Color = when (kind) {
        ShiftKind.SETUP -> UiColors.greenSoft
        ShiftKind.DAY -> UiColors.blueSoft
        ShiftKind.MIDDLE -> UiColors.orangeSoft
        ShiftKind.CLOSE -> UiColors.purpleSoft
        ShiftKind.KPI -> UiColors.cyanSoft
        ShiftKind.CUSTOM -> Color(0xF5, 0xDD, 0xEC)
    }
}
