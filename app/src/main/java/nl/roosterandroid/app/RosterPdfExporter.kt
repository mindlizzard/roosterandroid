package nl.roosterandroid.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object RosterPdfExporter {
    private const val PAGE_WIDTH = 1191
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 18f

    fun write(state: AppState, output: OutputStream) {
        val document = PdfDocument()
        try {
            val rosterPage = document.startPage(
                PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
            )
            drawRosterPage(rosterPage.canvas, state)
            document.finishPage(rosterPage)

            val payrollPage = document.startPage(
                PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 2).create()
            )
            drawPayrollPage(payrollPage.canvas, state)
            document.finishPage(payrollPage)

            document.writeTo(output)
        } finally {
            document.close()
        }
    }

    private fun drawRosterPage(canvas: Canvas, state: AppState) {
        val ym = YearMonth.of(state.year, state.month)
        val location = state.activeLocation()
        val employees = state.employees.filter { it.active && it.worksAt(location.id) }
        val templates = state.shiftTemplates.filter { it.locationId == location.id }.associateBy { it.id }
        val locale = Locale("nl", "NL")

        val titlePaint = paint(15f, true, Color.BLACK)
        val subPaint = paint(8f, false, Color.DKGRAY)
        canvas.drawText("Rooster - ${monthLabel(ym, locale)}", MARGIN, 24f, titlePaint)
        canvas.drawText(location.name, MARGIN, 38f, subPaint)

        val tableTop = 52f
        val tableBottom = PAGE_HEIGHT - 18f
        val rows = ym.lengthOfMonth() + 1
        val rowHeight = (tableBottom - tableTop) / rows.toFloat()
        val dateWidth = 58f
        val noteWidth = 172f
        val managerArea = PAGE_WIDTH - (MARGIN * 2) - dateWidth - noteWidth
        val managerWidth = if (employees.isEmpty()) 0f else managerArea / employees.size.toFloat()

        var x = MARGIN
        drawCell(canvas, RectF(x, tableTop, x + dateWidth, tableTop + rowHeight), "Datum", rgb("dde7f5"), true, 7f)
        x += dateWidth
        employees.forEach { employee ->
            drawCell(
                canvas,
                RectF(x, tableTop, x + managerWidth, tableTop + rowHeight),
                employee.name,
                roleColor(employee.role),
                true,
                managerFontSize(managerWidth)
            )
            x += managerWidth
        }
        drawCell(
            canvas,
            RectF(x, tableTop, x + noteWidth, tableTop + rowHeight),
            "Bijzonderheden",
            rgb("ffe6a8"),
            true,
            7f
        )

        for (day in 1..ym.lengthOfMonth()) {
            val date = ym.atDay(day)
            val y = tableTop + rowHeight * day
            val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
            val dateBg = if (weekend) rgb("e7e9ee") else rgb("f3f5f7")
            val dow = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).replaceFirstChar { it.uppercase() }
            val week = date.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())

            x = MARGIN
            drawCell(
                canvas,
                RectF(x, y, x + dateWidth, y + rowHeight),
                "$dow ${date.dayOfMonth} W$week",
                dateBg,
                true,
                6.5f
            )
            x += dateWidth

            employees.forEach { employee ->
                val absence = state.absences.lastOrNull {
                    it.employeeId == employee.id &&
                        it.status == AbsenceStatus.APPROVED &&
                        it.includes(date)
                }
                val assignment = state.assignments.lastOrNull {
                    it.locationId == location.id &&
                        it.employeeId == employee.id &&
                        it.date == date.toString()
                }
                val template = assignment?.let { templates[it.shiftTemplateId] }
                val specific = state.availability.lastOrNull {
                    it.employeeId == employee.id && it.date == date.toString()
                }
                val weekly = state.weeklyAvailability.lastOrNull {
                    it.employeeId == employee.id && it.weekday == date.dayOfWeek.value
                }
                val unavailable = specific?.available == false || (specific == null && weekly?.available == false)
                val extras = mutableListOf<String>()
                state.responsibilities.filter {
                    it.active &&
                        it.locationId == location.id &&
                        it.employeeId == employee.id &&
                        responsibilityAppliesPdf(it, date, ym)
                }.forEach { extras += responsibilityShortPdf(it) }
                state.personMarkers.filter {
                    it.locationId == location.id &&
                        it.employeeId == employee.id &&
                        it.date == date.toString()
                }.forEach { extras += markerShortPdf(it.type) }

                val text = when {
                    absence != null -> absenceShortPdf(absence.type)
                    template != null -> {
                        val base = "${shiftCode(template.kind)} ${template.start}-${template.end}"
                        if (extras.isEmpty()) base else "$base ${extras.distinct().joinToString("/")}" 
                    }
                    unavailable -> "NIET BESCH."
                    extras.isNotEmpty() -> extras.distinct().joinToString("/")
                    else -> "VRIJ"
                }
                val bg = when {
                    absence != null -> absenceColorPdf(absence.type)
                    unavailable -> rgb("f6d7d7")
                    template != null -> shiftColorPdf(template.kind)
                    extras.isNotEmpty() -> rgb("e0f3e8")
                    weekend -> rgb("eeeeF1")
                    else -> rgb("f7f7f7")
                }
                drawCell(
                    canvas,
                    RectF(x, y, x + managerWidth, y + rowHeight),
                    text,
                    bg,
                    template != null || absence != null || unavailable,
                    managerFontSize(managerWidth)
                )
                x += managerWidth
            }

            val details = dayDetailsPdf(state, date, ym)
            drawCell(
                canvas,
                RectF(x, y, x + noteWidth, y + rowHeight),
                details,
                rgb("fff4cf"),
                false,
                6.2f,
                maxLines = 2
            )
        }
    }

    private fun drawPayrollPage(canvas: Canvas, state: AppState) {
        val ym = YearMonth.of(state.year, state.month)
        val location = state.activeLocation()
        val employees = state.employees.filter { it.active && it.worksAt(location.id) }
        val templates = state.shiftTemplates.filter { it.locationId == location.id }.associateBy { it.id }
        val locale = Locale("nl", "NL")

        canvas.drawText(
            "Loonadministratie - ${monthLabel(ym, locale)}",
            MARGIN,
            24f,
            paint(15f, true, Color.BLACK)
        )
        canvas.drawText(
            "${location.name} • geplande uren komen uit dit vestigingsrooster. Afwezigheidsuren zijn indicatief.",
            MARGIN,
            39f,
            paint(7f, false, Color.DKGRAY)
        )

        val summaryColumns = listOf(
            Col("Naam", 120f),
            Col("Rol", 42f),
            Col("Contract", 68f),
            Col("Hier", 50f),
            Col("Elders", 50f),
            Col("Doel", 58f),
            Col("+/-", 48f),
            Col("Dnst", 34f),
            Col("Wknd", 34f),
            Col("SET", 30f),
            Col("DAG", 30f),
            Col("TUS", 30f),
            Col("SLU", 30f),
            Col("NAC", 30f),
            Col("Vak", 34f),
            Col("Snip", 34f),
            Col("Ver", 34f),
            Col("Bijz", 34f),
            Col("Onb", 34f),
            Col("TVT", 34f),
            Col("Ziek", 34f),
            Col("ZWV", 34f),
            Col("Aang", 34f),
            Col("Train", 34f),
            Col("Over", 34f)
        )

        var y = 54f
        val summaryHeaderH = 22f
        val summaryRowH = if (employees.size <= 12) 22f else max(13f, 264f / max(1, employees.size).toFloat())
        drawTableHeader(canvas, MARGIN, y, summaryHeaderH, summaryColumns)
        y += summaryHeaderH

        employees.forEach { employee ->
            val allMonthAssignments = (state.assignments + state.assignmentHistory)
                .distinctBy { it.id }
                .filter {
                    it.employeeId == employee.id && isInMonth(it.date, ym)
                }
            val assignments = allMonthAssignments.filter { it.locationId == location.id }
            val plannedHours = assignments.sumOf { assignment ->
                templates[assignment.shiftTemplateId]?.let(::durationHours) ?: 0.0
            }
            val allTemplates = state.shiftTemplates.associateBy { it.id }
            val otherLocationHours = allMonthAssignments
                .filter { it.locationId != location.id }
                .sumOf { assignment ->
                    allTemplates[assignment.shiftTemplateId]?.let(::durationHours) ?: 0.0
                }
            val targetHours = employee.contractedHoursPerWeek * ym.lengthOfMonth() / 7.0
            val kinds = assignments.mapNotNull { templates[it.shiftTemplateId]?.kind }
            val weekend = assignments.count { assignment ->
                val d = runCatching { LocalDate.parse(assignment.date) }.getOrNull()
                d?.dayOfWeek == DayOfWeek.SATURDAY || d?.dayOfWeek == DayOfWeek.SUNDAY
            }
            val approvedDays = approvedAbsenceDaysByType(state, employee.id, ym)
            val values = listOf(
                employee.name,
                roleShort(employee.role),
                "${fmt(employee.contractedHoursPerWeek)}u/${employee.contractedDaysPerWeek}d",
                "${fmt(plannedHours)}u",
                "${fmt(otherLocationHours)}u",
                "${fmt(targetHours)}u",
                signedHours(plannedHours + otherLocationHours - targetHours),
                assignments.size.toString(),
                weekend.toString(),
                kinds.count { it == ShiftKind.SETUP }.toString(),
                kinds.count { it == ShiftKind.DAY }.toString(),
                kinds.count { it == ShiftKind.MIDDLE }.toString(),
                kinds.count { it == ShiftKind.CLOSE }.toString(),
                kinds.count { it == ShiftKind.NIGHT }.toString(),
                approvedDays[AbsenceType.VACATION].orZero().toString(),
                approvedDays[AbsenceType.SNIPPER_DAY].orZero().toString(),
                approvedDays[AbsenceType.LEAVE].orZero().toString(),
                approvedDays[AbsenceType.SPECIAL_LEAVE].orZero().toString(),
                approvedDays[AbsenceType.UNPAID_LEAVE].orZero().toString(),
                approvedDays[AbsenceType.COMP_TIME].orZero().toString(),
                approvedDays[AbsenceType.SICK].orZero().toString(),
                approvedDays[AbsenceType.MATERNITY].orZero().toString(),
                approvedDays[AbsenceType.ADAPTED_WORK].orZero().toString(),
                approvedDays[AbsenceType.TRAINING].orZero().toString(),
                approvedDays[AbsenceType.OTHER].orZero().toString()
            )
            drawTableRow(canvas, MARGIN, y, summaryRowH, summaryColumns, values, if (((y - 76f) / summaryRowH).toInt() % 2 == 0) Color.WHITE else rgb("f7f8fa"))
            y += summaryRowH
        }

        y += 14f
        canvas.drawText("Afwezigheden en verlofregistratie", MARGIN, y, paint(10f, true, Color.BLACK))
        y += 8f

        val detailColumns = listOf(
            Col("Naam", 125f),
            Col("Type", 110f),
            Col("Van", 72f),
            Col("Tot", 72f),
            Col("Dagen", 44f),
            Col("Est.u", 48f),
            Col("Status", 72f),
            Col("Opmerking", 500f)
        )
        val records = state.absences
            .filter { absence ->
                absence.employeeId in employees.map { it.id }.toSet() && overlapsMonth(absence, ym)
            }
            .sortedWith(compareBy<Absence>({ employeeName(state, it.employeeId) }, { it.startDate }, { it.type.name }))
        val detailHeaderH = 20f
        drawTableHeader(canvas, MARGIN, y, detailHeaderH, detailColumns)
        y += detailHeaderH

        val availableHeight = PAGE_HEIGHT - 32f - y
        val detailRowH = if (records.isEmpty()) 18f else min(20f, max(10f, availableHeight / records.size.toFloat()))
        if (records.isEmpty()) {
            drawTableRow(canvas, MARGIN, y, detailRowH, detailColumns, listOf("Geen registraties", "", "", "", "", "", "", ""), Color.WHITE)
            y += detailRowH
        } else {
            records.forEachIndexed { index, absence ->
                val days = overlapDays(absence, ym)
                val employee = state.employees.firstOrNull { it.id == absence.employeeId }
                val avgDayHours = if (employee == null || employee.contractedDaysPerWeek <= 0) 0.0
                else employee.contractedHoursPerWeek / employee.contractedDaysPerWeek.toDouble()
                val estHours = if (absence.status == AbsenceStatus.APPROVED) days * avgDayHours else 0.0
                val values = listOf(
                    employee?.name ?: "?",
                    absenceLabelPdf(absence.type),
                    maxDate(absence.startDate, ym.atDay(1)).toString(),
                    minDate(absence.endDate, ym.atEndOfMonth()).toString(),
                    days.toString(),
                    if (estHours > 0) fmt(estHours) else "-",
                    absenceStatusLabel(absence.status),
                    absence.note.ifBlank { "-" }
                )
                drawTableRow(
                    canvas,
                    MARGIN,
                    y,
                    detailRowH,
                    detailColumns,
                    values,
                    if (index % 2 == 0) Color.WHITE else rgb("f7f8fa"),
                    fontSize = if (detailRowH < 14f) 5.3f else 6.2f
                )
                y += detailRowH
            }
        }

        val allTemplates = state.shiftTemplates.associateBy { it.id }
        val totalPlanned = employees.sumOf { employee ->
            (state.assignments + state.assignmentHistory).distinctBy { it.id }.filter {
                it.locationId == location.id &&
                    it.employeeId == employee.id &&
                    isInMonth(it.date, ym)
            }
                .sumOf { a -> templates[a.shiftTemplateId]?.let(::durationHours) ?: 0.0 }
        }
        val totalOther = employees.sumOf { employee ->
            (state.assignments + state.assignmentHistory).distinctBy { it.id }.filter {
                it.locationId != location.id &&
                    it.employeeId == employee.id &&
                    isInMonth(it.date, ym)
            }.sumOf { a -> allTemplates[a.shiftTemplateId]?.let(::durationHours) ?: 0.0 }
        }
        val totalTarget = employees.filter { it.role != EmployeeRole.BORROWED }
            .sumOf { it.contractedHoursPerWeek * ym.lengthOfMonth() / 7.0 }
        canvas.drawText(
            "Totaal hier: ${fmt(totalPlanned)}u    Elders: ${fmt(totalOther)}u    " +
                "Contractdoel eigen team: ${fmt(totalTarget)}u    " +
                "Verschil totaal: ${signedHours(totalPlanned + totalOther - totalTarget)}",
            MARGIN,
            PAGE_HEIGHT - 12f,
            paint(7f, true, Color.DKGRAY)
        )
    }

    private data class Col(val label: String, val width: Float)

    private fun drawTableHeader(canvas: Canvas, x: Float, y: Float, h: Float, columns: List<Col>) {
        var left = x
        columns.forEach { col ->
            drawCell(canvas, RectF(left, y, left + col.width, y + h), col.label, rgb("dde7f5"), true, 6.3f, maxLines = 1)
            left += col.width
        }
    }

    private fun drawTableRow(
        canvas: Canvas,
        x: Float,
        y: Float,
        h: Float,
        columns: List<Col>,
        values: List<String>,
        bg: Int,
        fontSize: Float = 6.2f
    ) {
        var left = x
        columns.forEachIndexed { index, col ->
            drawCell(canvas, RectF(left, y, left + col.width, y + h), values.getOrElse(index) { "" }, bg, false, fontSize, maxLines = 1)
            left += col.width
        }
    }

    private fun drawCell(
        canvas: Canvas,
        rect: RectF,
        text: String,
        background: Int,
        bold: Boolean,
        fontSize: Float,
        maxLines: Int = 2
    ) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = background
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.55f
            color = rgb("c7cbd1")
        }
        canvas.drawRect(rect, fill)
        canvas.drawRect(rect, border)

        val textPaint = paint(fontSize, bold, Color.BLACK)
        val lines = wrap(text.replace("\n", " "), textPaint, rect.width() - 5f, maxLines)
        val fm = textPaint.fontMetrics
        val lineHeight = (fm.descent - fm.ascent) * 0.92f
        val totalHeight = lineHeight * lines.size
        var baseline = rect.centerY() - totalHeight / 2f - fm.ascent
        lines.forEach { line ->
            val tx = rect.centerX() - textPaint.measureText(line) / 2f
            canvas.drawText(line, max(rect.left + 2f, tx), baseline, textPaint)
            baseline += lineHeight
        }
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
        if (text.isBlank()) return listOf("")
        if (maxWidth <= 4f) return listOf("")
        val words = text.trim().split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var current = ""
        var index = 0
        while (index < words.size) {
            val word = words[index]
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth || current.isEmpty()) {
                current = candidate
                index++
            } else {
                lines += fitSingle(current, paint, maxWidth)
                current = ""
                if (lines.size == maxLines) break
            }
        }
        if (lines.size < maxLines && current.isNotEmpty()) lines += fitSingle(current, paint, maxWidth)
        if (index < words.size && lines.isNotEmpty()) {
            lines[lines.lastIndex] = fitSingle(lines.last() + "...", paint, maxWidth)
        }
        return lines.take(maxLines)
    }

    private fun fitSingle(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var value = text
        while (value.length > 1 && paint.measureText(value + "...") > maxWidth) {
            value = value.dropLast(1)
        }
        return if (value == text) value else value.trimEnd() + "..."
    }

    private fun paint(size: Float, bold: Boolean, color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun managerFontSize(width: Float): Float = when {
        width >= 90f -> 6.5f
        width >= 65f -> 5.8f
        width >= 48f -> 5.1f
        else -> 4.4f
    }

    private fun shiftCode(kind: ShiftKind): String = when (kind) {
        ShiftKind.SETUP -> "SET"
        ShiftKind.DAY -> "DAG"
        ShiftKind.MIDDLE -> "TUS"
        ShiftKind.CLOSE -> "SLU"
        ShiftKind.NIGHT -> "NAC"
        ShiftKind.KPI -> "KPI"
        ShiftKind.CUSTOM -> "DST"
    }

    private fun shiftColorPdf(kind: ShiftKind): Int = when (kind) {
        ShiftKind.SETUP -> rgb("ddf3d8")
        ShiftKind.DAY -> rgb("d8ebfa")
        ShiftKind.MIDDLE -> rgb("ffe4b5")
        ShiftKind.CLOSE -> rgb("e6dcf7")
        ShiftKind.NIGHT -> rgb("cdd3f6")
        ShiftKind.KPI -> rgb("d7f2ef")
        ShiftKind.CUSTOM -> rgb("f5ddec")
    }

    private fun roleColor(role: EmployeeRole): Int = when (role) {
        EmployeeRole.RM -> rgb("d1e6ff")
        EmployeeRole.TRAINEE -> rgb("ffe2c6")
        EmployeeRole.BORROWED -> rgb("fff0b8")
        EmployeeRole.MANAGER -> rgb("dde7f5")
    }

    private fun absenceColorPdf(type: AbsenceType): Int = when (type) {
        AbsenceType.VACATION -> rgb("d9f0ff")
        AbsenceType.SNIPPER_DAY,
        AbsenceType.LEAVE,
        AbsenceType.SPECIAL_LEAVE,
        AbsenceType.UNPAID_LEAVE,
        AbsenceType.COMP_TIME,
        AbsenceType.MATERNITY -> rgb("ffe7b8")
        AbsenceType.SICK -> rgb("f9cfcf")
        AbsenceType.ADAPTED_WORK,
        AbsenceType.TRAINING -> rgb("e0f3e8")
        AbsenceType.OTHER -> rgb("eeeeee")
    }

    private fun absenceShortPdf(type: AbsenceType): String = when (type) {
        AbsenceType.VACATION -> "VAK"
        AbsenceType.SNIPPER_DAY -> "SNIP"
        AbsenceType.LEAVE -> "VER"
        AbsenceType.SPECIAL_LEAVE -> "BV"
        AbsenceType.UNPAID_LEAVE -> "ONBET"
        AbsenceType.COMP_TIME -> "TVT"
        AbsenceType.SICK -> "ZIEK"
        AbsenceType.MATERNITY -> "ZWV"
        AbsenceType.ADAPTED_WORK -> "AANG"
        AbsenceType.TRAINING -> "TR"
        AbsenceType.OTHER -> "AFW"
    }

    private fun absenceLabelPdf(type: AbsenceType): String = when (type) {
        AbsenceType.VACATION -> "Vakantie"
        AbsenceType.SNIPPER_DAY -> "Snipperdag"
        AbsenceType.LEAVE -> "Verlof"
        AbsenceType.SPECIAL_LEAVE -> "Bijz. verlof"
        AbsenceType.UNPAID_LEAVE -> "Onbet. verlof"
        AbsenceType.COMP_TIME -> "Tijd voor tijd"
        AbsenceType.SICK -> "Ziek"
        AbsenceType.MATERNITY -> "Zwangerschapsverlof"
        AbsenceType.ADAPTED_WORK -> "Aangepast werk"
        AbsenceType.TRAINING -> "Training/scholing"
        AbsenceType.OTHER -> "Overig"
    }

    private fun roleShort(role: EmployeeRole): String = when (role) {
        EmployeeRole.MANAGER -> "Mgr"
        EmployeeRole.RM -> "RM"
        EmployeeRole.TRAINEE -> "Trainee"
        EmployeeRole.BORROWED -> "Leen"
    }

    private fun markerShortPdf(type: PersonMarkerType): String = when (type) {
        PersonMarkerType.PRESENT -> "AANW"
        PersonMarkerType.OFFICE -> "KTR"
        PersonMarkerType.TRAINING -> "TR"
        PersonMarkerType.MEETING -> "MTG"
        PersonMarkerType.MAINTENANCE -> "OND"
        PersonMarkerType.ADMIN -> "ADM"
        PersonMarkerType.OTHER -> "INFO"
    }

    private fun responsibilityShortPdf(rule: ResponsibilityRule): String = rule.label.ifBlank {
        when (rule.type) {
            ResponsibilityType.WEEK_COUNT -> "WT"
            ResponsibilityType.MONTH_COUNT -> "MT"
            ResponsibilityType.MAINTENANCE -> "OND"
            ResponsibilityType.ADMIN -> "ADM"
            ResponsibilityType.KPI -> "KPI"
            ResponsibilityType.HACCP -> "HAC"
            ResponsibilityType.STOCK -> "VRD"
            ResponsibilityType.HAVI -> "HAVI"
            ResponsibilityType.TRAINING -> "TR"
            ResponsibilityType.MEETING -> "MTG"
            ResponsibilityType.OFFICE -> "KTR"
            ResponsibilityType.INTERVIEW -> "SOL"
            ResponsibilityType.CREW_PLANNING -> "PLN"
            ResponsibilityType.CUSTOM -> "TAK"
        }
    }.take(6).uppercase()

    private fun responsibilityAppliesPdf(rule: ResponsibilityRule, date: LocalDate, ym: YearMonth): Boolean =
        when (rule.recurrence) {
            RecurrenceType.WEEKLY -> date.dayOfWeek.value == rule.weekday
            RecurrenceType.MONTHLY_DAY -> rule.monthDay == date.dayOfMonth
            RecurrenceType.MONTH_END -> date == ym.atEndOfMonth()
            RecurrenceType.SPECIFIC_DATE -> rule.date == date.toString()
        }

    private fun dayDetailsPdf(state: AppState, date: LocalDate, ym: YearMonth): String {
        val parts = mutableListOf<String>()
        val locationId = state.activeLocationId
        state.dayNotes.firstOrNull {
            it.locationId == locationId && it.date == date.toString()
        }?.text?.takeIf { it.isNotBlank() }?.let(parts::add)
        state.responsibilities.filter {
            it.active && it.locationId == locationId && responsibilityAppliesPdf(it, date, ym)
        }.forEach { rule ->
            val name = employeeName(state, rule.employeeId)
            parts += "${responsibilityShortPdf(rule)}: $name"
        }
        state.personMarkers.filter {
            it.locationId == locationId && it.date == date.toString()
        }.forEach { marker ->
            parts += "${markerShortPdf(marker.type)}: ${employeeName(state, marker.employeeId)}"
        }
        if (state.settings.showMonthCountOnLastDay && date == ym.atEndOfMonth() &&
            state.responsibilities.none {
                it.active && it.locationId == locationId && it.type == ResponsibilityType.MONTH_COUNT && responsibilityAppliesPdf(it, date, ym)
            }
        ) parts += "Maandtelling"
        if (state.settings.showWeeklyCount && date.dayOfWeek.value == state.settings.weekCountWeekday &&
            state.responsibilities.none {
                it.active && it.locationId == locationId && it.type == ResponsibilityType.WEEK_COUNT && responsibilityAppliesPdf(it, date, ym)
            }
        ) parts += "Weektelling"
        return parts.distinct().joinToString(" | ")
    }

    private fun approvedAbsenceDaysByType(state: AppState, employeeId: String, ym: YearMonth): Map<AbsenceType, Int> {
        val result = mutableMapOf<AbsenceType, Int>()
        state.absences.filter {
            it.employeeId == employeeId && it.status == AbsenceStatus.APPROVED && overlapsMonth(it, ym)
        }.forEach { absence ->
            result[absence.type] = result.getOrDefault(absence.type, 0) + overlapDays(absence, ym)
        }
        return result
    }

    private fun overlapsMonth(absence: Absence, ym: YearMonth): Boolean {
        val start = runCatching { LocalDate.parse(absence.startDate) }.getOrNull() ?: return false
        val end = runCatching { LocalDate.parse(absence.endDate) }.getOrNull() ?: return false
        return !end.isBefore(ym.atDay(1)) && !start.isAfter(ym.atEndOfMonth())
    }

    private fun overlapDays(absence: Absence, ym: YearMonth): Int {
        val start = runCatching { LocalDate.parse(absence.startDate) }.getOrNull() ?: return 0
        val end = runCatching { LocalDate.parse(absence.endDate) }.getOrNull() ?: return 0
        val from = if (start.isBefore(ym.atDay(1))) ym.atDay(1) else start
        val to = if (end.isAfter(ym.atEndOfMonth())) ym.atEndOfMonth() else end
        if (to.isBefore(from)) return 0
        return Duration.between(from.atStartOfDay(), to.plusDays(1).atStartOfDay()).toDays().toInt()
    }

    private fun employeeName(state: AppState, employeeId: String): String =
        state.employees.firstOrNull { it.id == employeeId }?.name ?: "?"

    private fun durationHours(template: ShiftTemplate): Double = runCatching {
        var minutes = Duration.between(template.startTime(), template.endTime()).toMinutes()
        if (minutes <= 0) minutes += 24 * 60
        minutes / 60.0
    }.getOrDefault(0.0)

    private fun isInMonth(dateString: String, ym: YearMonth): Boolean =
        runCatching { YearMonth.from(LocalDate.parse(dateString)) == ym }.getOrDefault(false)

    private fun monthLabel(ym: YearMonth, locale: Locale): String =
        "${ym.month.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.uppercase() }} ${ym.year}"

    private fun fmt(value: Double): String = "%.1f".format(Locale.US, value)

    private fun signedHours(value: Double): String = if (value >= 0) "+${fmt(value)}u" else "${fmt(value)}u"

    private fun absenceStatusLabel(status: AbsenceStatus): String = when (status) {
        AbsenceStatus.REQUESTED -> "Aangevraagd"
        AbsenceStatus.APPROVED -> "Goedgekeurd"
        AbsenceStatus.REJECTED -> "Afgewezen"
    }

    private fun maxDate(raw: String, floor: LocalDate): LocalDate {
        val d = runCatching { LocalDate.parse(raw) }.getOrNull() ?: return floor
        return if (d.isBefore(floor)) floor else d
    }

    private fun minDate(raw: String, ceiling: LocalDate): LocalDate {
        val d = runCatching { LocalDate.parse(raw) }.getOrNull() ?: return ceiling
        return if (d.isAfter(ceiling)) ceiling else d
    }

    private fun Int?.orZero(): Int = this ?: 0

    private fun rgb(hex: String): Int {
        val clean = hex.lowercase().removePrefix("#")
        val r = clean.substring(0, 2).toInt(16)
        val g = clean.substring(2, 4).toInt(16)
        val b = clean.substring(4, 6).toInt(16)
        return Color.rgb(r, g, b)
    }
}
