package nl.roosterandroid.app

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

class AtwValidator {
    enum class Severity { ERROR, WARNING, INFO }

    data class Violation(
        val severity: Severity,
        val employeeId: String?,
        val date: LocalDate?,
        val rule: String,
        val message: String
    )

    data class ScheduledShift(
        val assignment: Assignment,
        val employee: Employee,
        val template: ShiftTemplate,
        val start: LocalDateTime,
        val end: LocalDateTime
    ) {
        val durationHours: Double get() = Duration.between(start, end).toMinutes() / 60.0
        val date: LocalDate get() = start.toLocalDate()
    }

    fun validate(state: AppState): List<Violation> {
        if (!state.settings.atwEnabled) return emptyList()
        val employees = state.employees.associateBy { it.id }
        val templates = state.shiftTemplates.associateBy { it.id }
        val allAssignments = (state.assignmentHistory + state.assignments)
            .distinctBy { it.id }

        val shifts = allAssignments.mapNotNull { a ->
            val employee = employees[a.employeeId] ?: return@mapNotNull null
            val template = templates[a.shiftTemplateId] ?: return@mapNotNull null
            toScheduledShift(a, employee, template)
        }.sortedBy { it.start }

        val out = mutableListOf<Violation>()
        shifts.groupBy { it.employee.id }.forEach { (employeeId, employeeShifts) ->
            val sorted = employeeShifts.sortedBy { it.start }
            checkShiftLengthsAndBreaks(sorted, out)
            checkOverlapAndDailyRest(sorted, state.settings, out)
            checkWeeklyHours(sorted, out)
            checkRollingAverages(sorted, out)
            checkWeeklyRest(sorted, out)
            checkNightRules(sorted, state.settings, out)
            checkConsecutiveDays(sorted, state.settings, out)
            checkHistoryCoverage(sorted, state, employeeId, out)
        }
        return out.sortedWith(compareBy<Violation>({ it.date ?: LocalDate.MIN }, { it.severity.ordinal }))
    }

    fun canPlace(
        employee: Employee,
        date: LocalDate,
        template: ShiftTemplate,
        existing: List<ScheduledShift>,
        settings: PlannerSettings
    ): Boolean {
        val candidate = toScheduledShift(
            Assignment(employeeId = employee.id, date = date.toString(), shiftTemplateId = template.id),
            employee,
            template
        )
        if (candidate.durationHours > 12.0) return false
        if (existing.any { overlaps(it.start, it.end, candidate.start, candidate.end) }) return false

        val relevant = (existing + candidate).sortedBy { it.start }
        val idx = relevant.indexOf(candidate)
        val prev = relevant.getOrNull(idx - 1)
        val next = relevant.getOrNull(idx + 1)
        val minRest = settings.strictDailyRestHours.toLong()
        if (prev != null && Duration.between(prev.end, candidate.start).toHours() < minRest) return false
        if (next != null && Duration.between(candidate.end, next.start).toHours() < minRest) return false

        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val sundayExclusive = monday.plusDays(7)
        val weekHours = relevant.filter { !it.start.toLocalDate().isBefore(monday) && it.start.toLocalDate().isBefore(sundayExclusive) }
            .sumOf { it.durationHours }
        if (weekHours > 60.0) return false

        val days = relevant.map { it.date }.distinct().sorted()
        val streak = longestConsecutiveDayStreak(days)
        if (settings.treatMaxConsecutiveDaysAsHardRule && streak > settings.maxConsecutiveWorkDays) return false
        return true
    }

    fun toScheduledShift(a: Assignment, employee: Employee, template: ShiftTemplate): ScheduledShift {
        val date = LocalDate.parse(a.date)
        val start = LocalDateTime.of(date, template.startTime())
        var end = LocalDateTime.of(date, template.endTime())
        if (!end.isAfter(start)) end = end.plusDays(1)
        return ScheduledShift(a, employee, template, start, end)
    }

    private fun checkShiftLengthsAndBreaks(shifts: List<ScheduledShift>, out: MutableList<Violation>) {
        shifts.forEach { s ->
            if (s.durationHours > 12.0) {
                out += Violation(Severity.ERROR, s.employee.id, s.date, "ATW max dienst", "${s.employee.name}: dienst duurt %.1f uur; maximum is 12 uur.".format(s.durationHours))
            }
            if (s.durationHours > 10.0) {
                out += Violation(Severity.INFO, s.employee.id, s.date, "Pauze", "${s.employee.name}: plan minimaal 45 minuten pauze bij een dienst langer dan 10 uur.")
            } else if (s.durationHours > 5.5) {
                out += Violation(Severity.INFO, s.employee.id, s.date, "Pauze", "${s.employee.name}: plan minimaal 30 minuten pauze bij een dienst langer dan 5,5 uur.")
            }
        }
    }

    private fun checkOverlapAndDailyRest(shifts: List<ScheduledShift>, settings: PlannerSettings, out: MutableList<Violation>) {
        for (i in 0 until shifts.lastIndex) {
            val a = shifts[i]
            val b = shifts[i + 1]
            if (overlaps(a.start, a.end, b.start, b.end)) {
                out += Violation(Severity.ERROR, a.employee.id, b.date, "Overlap", "${a.employee.name}: twee diensten overlappen.")
                continue
            }
            val restMinutes = Duration.between(a.end, b.start).toMinutes()
            val normalMin = settings.strictDailyRestHours * 60L
            if (restMinutes < normalMin) {
                val reducedAllowed = settings.allowOneReducedDailyRestPer7Days && restMinutes >= 8 * 60L
                out += Violation(
                    if (reducedAllowed) Severity.WARNING else Severity.ERROR,
                    a.employee.id,
                    b.date,
                    "Dagelijkse rust",
                    if (reducedAllowed) "${a.employee.name}: rust is %.1f uur. Dit gebruikt mogelijk de 1× per 7 dagen toegestane verkorting tot minimaal 8 uur.".format(restMinutes / 60.0)
                    else "${a.employee.name}: slechts %.1f uur rust tussen diensten; minimaal %d uur ingesteld.".format(restMinutes / 60.0, settings.strictDailyRestHours)
                )
            }
        }
    }

    private fun checkWeeklyHours(shifts: List<ScheduledShift>, out: MutableList<Violation>) {
        val byWeek = shifts.groupBy { it.date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
        byWeek.forEach { (monday, weekShifts) ->
            val hours = weekShifts.sumOf { it.durationHours }
            if (hours > 60.0) {
                out += Violation(Severity.ERROR, weekShifts.first().employee.id, monday, "60 uur/week", "${weekShifts.first().employee.name}: %.1f uur in week vanaf $monday; maximum is 60 uur.".format(hours))
            }
        }
    }

    private fun checkRollingAverages(shifts: List<ScheduledShift>, out: MutableList<Violation>) {
        if (shifts.isEmpty()) return
        val firstDate = shifts.first().date
        val lastDate = shifts.last().date
        var cursor = firstDate
        while (!cursor.isAfter(lastDate)) {
            val hours4 = hoursInWindow(shifts, cursor, cursor.plusDays(28))
            if (hours4 / 4.0 > 55.0) {
                out += Violation(Severity.ERROR, shifts.first().employee.id, cursor, "4-weken gemiddelde", "${shifts.first().employee.name}: gemiddeld %.1f uur/week over 4 weken; maximum is 55.".format(hours4 / 4.0))
            }
            val hours16 = hoursInWindow(shifts, cursor, cursor.plusDays(112))
            if (hours16 / 16.0 > 48.0) {
                out += Violation(Severity.ERROR, shifts.first().employee.id, cursor, "16-weken gemiddelde", "${shifts.first().employee.name}: gemiddeld %.1f uur/week over 16 weken; maximum is 48.".format(hours16 / 16.0))
            }
            cursor = cursor.plusDays(7)
        }
    }

    private fun checkWeeklyRest(shifts: List<ScheduledShift>, out: MutableList<Violation>) {
        if (shifts.size < 2) return
        val employee = shifts.first().employee
        val firstMonday = shifts.first().date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val lastDate = shifts.last().date
        var weekStart = firstMonday
        while (!weekStart.isAfter(lastDate)) {
            val weekEnd = weekStart.plusDays(7)
            val gaps7 = restGaps(shifts, weekStart.atStartOfDay(), weekEnd.atStartOfDay())
            if (gaps7.maxOrNull() ?: 168.0 < 36.0) {
                val fortnightEnd = weekStart.plusDays(14)
                val gaps14 = restGaps(shifts, weekStart.atStartOfDay(), fortnightEnd.atStartOfDay()).filter { it >= 32.0 }
                if (gaps14.size < 2 || gaps14.sortedDescending().take(2).sum() < 72.0) {
                    out += Violation(Severity.ERROR, employee.id, weekStart, "Wekelijkse rust", "${employee.name}: geen 36 uur aaneengesloten rust in 7 dagen en ook geen geldige 72-uursvariant over 14 dagen gevonden.")
                }
            }
            weekStart = weekStart.plusDays(7)
        }
    }

    private fun checkNightRules(shifts: List<ScheduledShift>, settings: PlannerSettings, out: MutableList<Violation>) {
        val nights = shifts.filter { nightMinutes(it) > 60 }
        if (nights.isEmpty()) return
        val employee = shifts.first().employee
        nights.forEach { s ->
            val maxHours = if (settings.allowIncidentalTwelveHourNightShift) 12.0 else 10.0
            if (s.durationHours > maxHours) {
                out += Violation(Severity.ERROR, employee.id, s.date, "Nachtdienstduur", "${employee.name}: nachtdienst duurt %.1f uur; ingestelde grens is %.0f uur.".format(s.durationHours, maxHours))
            }
            val next = shifts.firstOrNull { it.start.isAfter(s.start) }
            if (next != null) {
                val required = if (s.end.toLocalTime().isAfter(LocalTime.of(2, 0))) 14 else 11
                val rest = Duration.between(s.end, next.start).toMinutes() / 60.0
                if (rest < required) {
                    val reduced = settings.allowOneReducedDailyRestPer7Days && rest >= 8.0
                    out += Violation(if (reduced) Severity.WARNING else Severity.ERROR, employee.id, next.date, "Rust na nachtdienst", "${employee.name}: %.1f uur rust na nachtdienst; normaal minimaal %d uur.".format(rest, required))
                }
            }
        }

        val first = nights.first().date
        val last = nights.last().date
        var cursor = first
        while (!cursor.isAfter(last)) {
            val n16 = nights.count { !it.date.isBefore(cursor) && it.date.isBefore(cursor.plusDays(112)) }
            if (n16 > 36) {
                out += Violation(Severity.ERROR, employee.id, cursor, "Aantal nachtdiensten", "${employee.name}: $n16 nachtdiensten in 16 weken; algemene grens is 36.")
            }
            if (n16 >= 16) {
                val hours = hoursInWindow(shifts, cursor, cursor.plusDays(112))
                if (hours / 16.0 > 40.0) {
                    out += Violation(Severity.ERROR, employee.id, cursor, "Gemiddelde bij nachtwerk", "${employee.name}: bij regelmatig nachtwerk gemiddeld %.1f uur/week over 16 weken; grens is 40.".format(hours / 16.0))
                }
            }
            cursor = cursor.plusDays(7)
        }

        var seriesStart = 0
        while (seriesStart < nights.size) {
            var seriesEnd = seriesStart
            while (seriesEnd + 1 < nights.size && nights[seriesEnd + 1].date == nights[seriesEnd].date.plusDays(1)) seriesEnd++
            val seriesLength = seriesEnd - seriesStart + 1
            if (seriesLength >= 3) {
                val lastNight = nights[seriesEnd]
                val nextAfterSeries = shifts.firstOrNull { it.start.isAfter(lastNight.start) && it !in nights.subList(seriesStart, seriesEnd + 1) }
                if (nextAfterSeries != null) {
                    val rest = Duration.between(lastNight.end, nextAfterSeries.start).toMinutes() / 60.0
                    if (rest < 46.0) {
                        out += Violation(Severity.ERROR, employee.id, nextAfterSeries.date, "Rust na reeks nachten", "${employee.name}: na $seriesLength opeenvolgende nachtdiensten is minimaal 46 uur rust nodig.")
                    }
                }
            }
            seriesStart = seriesEnd + 1
        }

        nights.groupBy { it.date.year }.forEach { (year, yearNights) ->
            if (yearNights.size > 140) out += Violation(Severity.ERROR, employee.id, LocalDate.of(year, 1, 1), "Jaarmaximum nachtdiensten", "${employee.name}: ${yearNights.size} nachtdiensten in $year; algemene grens is 140.")
            val longNights = yearNights.filter { it.durationHours > 10.0 }
            if (longNights.size > 22) out += Violation(Severity.ERROR, employee.id, LocalDate.of(year, 1, 1), "12-uurs nachtdiensten", "${employee.name}: meer dan 22 incidentele nachtdiensten langer dan 10 uur in $year.")
            longNights.forEach { longNight ->
                val count14 = longNights.count { !it.date.isBefore(longNight.date) && it.date.isBefore(longNight.date.plusDays(14)) }
                if (count14 > 5) {
                    out += Violation(Severity.ERROR, employee.id, longNight.date, "12-uurs nachtdiensten per 2 weken", "${employee.name}: $count14 incidentele lange nachtdiensten in 14 dagen; algemene grens is 5.")
                }
            }
        }

        val orderedDays = shifts.map { it.date }.distinct().sorted()
        for (i in orderedDays.indices) {
            var end = i
            while (end + 1 < orderedDays.size && orderedDays[end + 1] == orderedDays[end].plusDays(1)) end++
            if (end - i + 1 > 7) {
                val rangeStart = orderedDays[i]
                val rangeEnd = orderedDays[end]
                if (nights.any { !it.date.isBefore(rangeStart) && !it.date.isAfter(rangeEnd) }) {
                    out += Violation(Severity.ERROR, employee.id, rangeStart, "Opeenvolgende diensten met nachtwerk", "${employee.name}: meer dan 7 opeenvolgende werkdagen in een reeks waarin nachtwerk voorkomt.")
                }
            }
        }
    }

    private fun checkConsecutiveDays(shifts: List<ScheduledShift>, settings: PlannerSettings, out: MutableList<Violation>) {
        val days = shifts.map { it.date }.distinct().sorted()
        if (days.isEmpty()) return
        var start = 0
        while (start < days.size) {
            var end = start
            while (end + 1 < days.size && days[end + 1] == days[end].plusDays(1)) end++
            val count = end - start + 1
            if (count > settings.maxConsecutiveWorkDays) {
                out += Violation(
                    if (settings.treatMaxConsecutiveDaysAsHardRule) Severity.ERROR else Severity.WARNING,
                    shifts.first().employee.id,
                    days[start],
                    "Max opeenvolgende werkdagen",
                    "${shifts.first().employee.name}: $count dagen achter elkaar; ingestelde grens is ${settings.maxConsecutiveWorkDays}."
                )
            }
            start = end + 1
        }
    }

    private fun checkHistoryCoverage(shifts: List<ScheduledShift>, state: AppState, employeeId: String, out: MutableList<Violation>) {
        val monthStart = LocalDate.of(state.year, state.month, 1)
        val first = shifts.minOfOrNull { it.date } ?: return
        if (first.isAfter(monthStart.minusDays(111))) {
            out += Violation(Severity.WARNING, employeeId, monthStart, "Historie", "16-wekencontrole is nog niet volledig: importeer/bewaar eerdere roosters voor volledige ATW-dekking.")
        }
    }

    private fun hoursInWindow(shifts: List<ScheduledShift>, start: LocalDate, endExclusive: LocalDate): Double =
        shifts.filter { !it.date.isBefore(start) && it.date.isBefore(endExclusive) }.sumOf { it.durationHours }

    private fun restGaps(shifts: List<ScheduledShift>, start: LocalDateTime, end: LocalDateTime): List<Double> {
        val intervals = shifts.mapNotNull { s ->
            val a = if (s.start.isBefore(start)) start else s.start
            val b = if (s.end.isAfter(end)) end else s.end
            if (b.isAfter(a)) a to b else null
        }.sortedBy { it.first }
        if (intervals.isEmpty()) return listOf(Duration.between(start, end).toMinutes() / 60.0)
        val gaps = mutableListOf<Double>()
        var cursor = start
        intervals.forEach { (a, b) ->
            if (a.isAfter(cursor)) gaps += Duration.between(cursor, a).toMinutes() / 60.0
            if (b.isAfter(cursor)) cursor = b
        }
        if (end.isAfter(cursor)) gaps += Duration.between(cursor, end).toMinutes() / 60.0
        return gaps
    }

    private fun nightMinutes(s: ScheduledShift): Long {
        var date = s.start.toLocalDate().minusDays(1)
        val last = s.end.toLocalDate()
        var total = 0L
        while (!date.isAfter(last)) {
            val nightStart = date.atStartOfDay()
            val nightEnd = date.atTime(6, 0)
            val a = if (s.start.isAfter(nightStart)) s.start else nightStart
            val b = if (s.end.isBefore(nightEnd)) s.end else nightEnd
            if (b.isAfter(a)) total += Duration.between(a, b).toMinutes()
            date = date.plusDays(1)
        }
        return total
    }

    private fun longestConsecutiveDayStreak(days: List<LocalDate>): Int {
        if (days.isEmpty()) return 0
        var best = 1
        var cur = 1
        for (i in 1 until days.size) {
            if (days[i] == days[i - 1].plusDays(1)) cur++ else cur = 1
            if (cur > best) best = cur
        }
        return best
    }

    private fun overlaps(aStart: LocalDateTime, aEnd: LocalDateTime, bStart: LocalDateTime, bEnd: LocalDateTime): Boolean = aStart < bEnd && bStart < aEnd
}
