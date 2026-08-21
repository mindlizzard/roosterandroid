package nl.roosterandroid.desktop

import com.lowagie.text.Chunk
import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.Font
import com.lowagie.text.FontFactory
import com.lowagie.text.PageSize
import com.lowagie.text.Paragraph
import com.lowagie.text.Phrase
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfWriter
import nl.roosterandroid.app.AbsenceStatus
import nl.roosterandroid.app.AppState
import nl.roosterandroid.app.Assignment
import nl.roosterandroid.app.Employee
import nl.roosterandroid.app.ShiftKind
import nl.roosterandroid.app.ShiftTemplate
import nl.roosterandroid.app.employeeMonthStats
import java.awt.Color
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

object DesktopExporters {
    private val locale = Locale("nl", "NL")

    fun writeMatrixCsv(state: AppState, path: Path) {
        val employees = state.employees.filter { it.active }
        val ym = YearMonth.of(state.year, state.month)
        val templates = state.shiftTemplates.associateBy { it.id }
        val lines = mutableListOf<String>()
        lines += (listOf("Datum", "Dag") + employees.map { it.name } + "Bijzonderheden")
            .joinToString(";") { csv(it) }

        for (day in 1..ym.lengthOfMonth()) {
            val date = ym.atDay(day)
            val row = mutableListOf(
                date.toString(),
                date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            )
            employees.forEach { employee ->
                row += csvCellText(state, employee, date, templates)
            }
            row += dayDetails(state, date, ym)
            lines += row.joinToString(";") { csv(it) }
        }

        Files.newBufferedWriter(path, StandardCharsets.UTF_8).use { writer ->
            writer.write("\uFEFF")
            lines.forEach { writer.appendLine(it) }
        }
    }

    fun writePdf(state: AppState, path: Path) {
        val employees = state.employees.filter { it.active }
        val page = if (employees.size > 7) PageSize.A3.rotate() else PageSize.A4.rotate()
        val document = Document(page, 24f, 24f, 24f, 24f)
        Files.newOutputStream(path).use { output ->
            PdfWriter.getInstance(document, output)
            document.open()
            addRosterPage(document, state, employees)
            document.newPage()
            addPayrollPage(document, state)
            document.close()
        }
    }

    private fun addRosterPage(document: Document, state: AppState, employees: List<Employee>) {
        val ym = YearMonth.of(state.year, state.month)
        val templates = state.shiftTemplates.associateBy { it.id }
        val titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16f)
        val small = FontFactory.getFont(FontFactory.HELVETICA, if (employees.size > 8) 6.2f else 7.4f)
        val smallBold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, if (employees.size > 8) 6.2f else 7.4f)
        val title = Paragraph(
            "Rooster ${state.settings.locationName} - " +
                ym.month.getDisplayName(TextStyle.FULL, locale) + " ${ym.year}",
            titleFont
        )
        title.spacingAfter = 10f
        document.add(title)

        val columns = employees.size + 2
        val widths = FloatArray(columns) { index ->
            when (index) {
                0 -> 1.05f
                columns - 1 -> 2.1f
                else -> 1.45f
            }
        }
        val table = PdfPTable(widths)
        table.widthPercentage = 100f
        table.headerRows = 1
        table.addCell(headerCell("Datum", smallBold))
        employees.forEach { table.addCell(headerCell(it.name, smallBold)) }
        table.addCell(headerCell("Bijzonderheden", smallBold))

        for (day in 1..ym.lengthOfMonth()) {
            val date = ym.atDay(day)
            val weekend = date.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
            val dayName = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            table.addCell(bodyCell("$dayName ${date.dayOfMonth}", smallBold, if (weekend) Color(0xE8EBEF) else Color(0xF3F5F7)))
            employees.forEach { employee ->
                table.addCell(rosterCell(state, employee, date, templates, small, smallBold, weekend))
            }
            table.addCell(bodyCell(dayDetails(state, date, ym), small, Color(0xFFF4CF)))
        }
        document.add(table)
        document.add(Paragraph("SETUP / DAG / TUSSEN / SLUIT staat vet; tijden worden altijd volledig als 09:00-17:00 weergegeven.", small).apply {
            spacingBefore = 6f
        })
    }

    private fun addPayrollPage(document: Document, state: AppState) {
        val titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16f)
        val header = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8f)
        val body = FontFactory.getFont(FontFactory.HELVETICA, 8f)
        document.add(Paragraph("Loonadministratie en verdeling", titleFont).apply { spacingAfter = 10f })
        val table = PdfPTable(floatArrayOf(2.2f, 0.8f, 0.9f, 0.8f, 0.8f, 0.8f, 0.9f, 0.8f, 0.8f, 0.8f))
        table.widthPercentage = 100f
        listOf("Medewerker", "Diensten", "Uren", "Setup", "Dag", "Tussen", "Sluit", "Weekend", "Vak", "Ziek")
            .forEach { table.addCell(headerCell(it, header)) }
        employeeMonthStats(state).forEach { stat ->
            listOf(
                stat.name,
                stat.shifts.toString(),
                "%.1f".format(locale, stat.hours),
                stat.setup.toString(),
                stat.day.toString(),
                stat.middle.toString(),
                stat.close.toString(),
                stat.weekend.toString(),
                stat.vacationDays.toString(),
                stat.sickDays.toString()
            ).forEach { table.addCell(bodyCell(it, body, Color.WHITE)) }
        }
        document.add(table)
    }

    private fun rosterCell(
        state: AppState,
        employee: Employee,
        date: LocalDate,
        templates: Map<String, ShiftTemplate>,
        small: Font,
        bold: Font,
        weekend: Boolean
    ): PdfPCell {
        val absence = state.absences.lastOrNull {
            it.employeeId == employee.id && it.status == AbsenceStatus.APPROVED && it.includes(date)
        }
        if (absence != null) {
            return bodyCell(absenceLabel(absence.type), bold, when (absence.type) {
                nl.roosterandroid.app.AbsenceType.SICK -> Color(0xF9CFCF)
                nl.roosterandroid.app.AbsenceType.VACATION -> Color(0xD9F0FF)
                else -> Color(0xFFE7B8)
            })
        }
        val assignment = state.assignments.lastOrNull {
            it.employeeId == employee.id && it.date == date.toString()
        }
        val template = assignment?.let { templates[it.shiftTemplateId] }
        if (assignment != null && template != null) {
            val phrase = Phrase()
            phrase.add(Chunk(shiftKindLabel(template.kind), bold))
            phrase.add(Chunk("\n${template.start}-${template.end}", small))
            val extras = employeeExtras(state, employee, date)
            if (extras.isNotBlank()) phrase.add(Chunk("\n$extras", small))
            return PdfPCell(phrase).apply {
                setPadding(3f)
                horizontalAlignment = Element.ALIGN_CENTER
                verticalAlignment = Element.ALIGN_MIDDLE
                backgroundColor = shiftColor(template.kind)
            }
        }
        return bodyCell("Vrij", small, if (weekend) Color(0xEEEEF1) else Color(0xF7F7F7))
    }

    private fun headerCell(text: String, font: Font): PdfPCell = PdfPCell(Phrase(text, font)).apply {
        setPadding(4f)
        backgroundColor = Color(0xDDE7F5)
        horizontalAlignment = Element.ALIGN_CENTER
        verticalAlignment = Element.ALIGN_MIDDLE
    }

    private fun bodyCell(text: String, font: Font, color: Color): PdfPCell = PdfPCell(Phrase(text, font)).apply {
        setPadding(3f)
        backgroundColor = color
        horizontalAlignment = Element.ALIGN_CENTER
        verticalAlignment = Element.ALIGN_MIDDLE
    }

    private fun csvCellText(
        state: AppState,
        employee: Employee,
        date: LocalDate,
        templates: Map<String, ShiftTemplate>
    ): String {
        val absence = state.absences.lastOrNull {
            it.employeeId == employee.id && it.status == AbsenceStatus.APPROVED && it.includes(date)
        }
        if (absence != null) return absenceLabel(absence.type)
        val assignment = state.assignments.lastOrNull {
            it.employeeId == employee.id && it.date == date.toString()
        }
        val template = assignment?.let { templates[it.shiftTemplateId] } ?: return "Vrij"
        return "${shiftKindLabel(template.kind)} ${template.start}-${template.end}"
    }

    private fun employeeExtras(state: AppState, employee: Employee, date: LocalDate): String {
        val ym = YearMonth.from(date)
        val tasks = state.responsibilities.filter {
            it.active && it.employeeId == employee.id && responsibilityApplies(it, date, ym)
        }.map(::responsibilityShort)
        val markers = state.personMarkers.filter {
            it.employeeId == employee.id && it.date == date.toString()
        }.map { markerLabel(it.type) }
        return (tasks + markers).distinct().joinToString(" / ")
    }

    internal fun dayDetails(state: AppState, date: LocalDate, ym: YearMonth): String {
        val parts = mutableListOf<String>()
        state.dayNotes.firstOrNull { it.date == date.toString() }?.text?.takeIf { it.isNotBlank() }?.let(parts::add)
        state.dayPartDemands.filter { it.date == date.toString() }.forEach {
            parts += "${it.label} ${it.start}-${it.end}: ${it.minimumManagers} mgr"
        }
        state.responsibilities.filter { it.active && responsibilityApplies(it, date, ym) }.forEach { rule ->
            val name = state.employees.firstOrNull { it.id == rule.employeeId }?.name ?: "?"
            parts += "${responsibilityLabel(rule.type)}: $name"
        }
        if (state.settings.showMonthCountOnLastDay && date == ym.atEndOfMonth()) parts += "Maandtelling"
        else if (state.settings.showWeeklyCount && date.dayOfWeek.value == state.settings.weekCountWeekday) parts += "Weektelling"
        return parts.distinct().joinToString(" | ")
    }

    internal fun responsibilityApplies(
        rule: nl.roosterandroid.app.ResponsibilityRule,
        date: LocalDate,
        ym: YearMonth
    ): Boolean = when (rule.recurrence) {
        nl.roosterandroid.app.RecurrenceType.WEEKLY -> date.dayOfWeek.value == rule.weekday
        nl.roosterandroid.app.RecurrenceType.MONTHLY_DAY -> rule.monthDay == date.dayOfMonth
        nl.roosterandroid.app.RecurrenceType.MONTH_END -> date == ym.atEndOfMonth()
        nl.roosterandroid.app.RecurrenceType.SPECIFIC_DATE -> rule.date == date.toString()
    }

    private fun shiftColor(kind: ShiftKind): Color = when (kind) {
        ShiftKind.SETUP -> Color(0xDDF3D8)
        ShiftKind.DAY -> Color(0xD8EBFA)
        ShiftKind.MIDDLE -> Color(0xFFE4B5)
        ShiftKind.CLOSE -> Color(0xE6DCF7)
        ShiftKind.KPI -> Color(0xD7F2EF)
        ShiftKind.CUSTOM -> Color(0xF5DDEC)
    }

    private fun csv(value: String): String =
        if (value.any { it == ';' || it == '"' || it == '\n' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else value
}
