package nl.roosterandroid.app

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

class ScheduleEngine(private val atw: AtwValidator = AtwValidator()) {
    data class Result(
        val assignments: List<Assignment>,
        val unfilled: List<String>,
        val warnings: List<String>
    )

    fun generate(state: AppState): Result {
        val ym = YearMonth.of(state.year, state.month)
        val employees = state.employees.filter { it.active }
        if (employees.isEmpty()) {
            return Result(emptyList(), listOf("Voeg eerst minimaal één manager toe."), emptyList())
        }

        val templatesById = state.shiftTemplates.associateBy { it.id }
        val employeeById = state.employees.associateBy { it.id }

        val locked = state.assignments
            .filter { isInMonth(it, ym) && it.source == "manual" }
            .distinctBy { "${it.employeeId}|${it.date}" }
            .toMutableList()

        val history = (state.assignmentHistory + state.assignments.filterNot { isInMonth(it, ym) })
            .distinctBy { it.id }

        val generated = locked.toMutableList()
        val unfilled = mutableListOf<String>()

        for (day in 1..ym.lengthOfMonth()) {
            val date = ym.atDay(day)

            val fixedForDay = state.availability.filter {
                it.date == date.toString() && it.available && it.fixedShiftKind != null
            }
            fixedForDay.forEach { fixed ->
                val employee = employees.firstOrNull { it.id == fixed.employeeId } ?: return@forEach
                if (generated.any { it.employeeId == employee.id && it.date == date.toString() }) return@forEach
                val kind = fixed.fixedShiftKind ?: return@forEach
                val template = chooseTemplate(date, kind, state.shiftTemplates) ?: return@forEach
                if (canAssign(employee, date, template, generated, history, state)) {
                    generated += Assignment(
                        employeeId = employee.id,
                        date = date.toString(),
                        shiftTemplateId = template.id,
                        source = "fixed"
                    )
                } else {
                    unfilled += "$date ${employee.name}: vaste ${template.name} botst met beschikbaarheid/ATW."
                }
            }

            for (kind in requiredKinds(date, state.settings)) {
                val kindAlreadyFilled = generated
                    .filter { it.date == date.toString() }
                    .any { templatesById[it.shiftTemplateId]?.kind == kind }
                if (kindAlreadyFilled) continue

                val template = chooseTemplate(date, kind, state.shiftTemplates)
                if (template == null) {
                    unfilled += "$date: geen actief diensttemplate voor $kind."
                    continue
                }

                val eligible = employees.filter { employee ->
                    canAssign(employee, date, template, generated, history, state)
                }

                if (eligible.isEmpty()) {
                    unfilled += "$date ${template.name}: geen beschikbare manager zonder harde conflictregels."
                    continue
                }

                val dayAlreadyHasExperienced = generated.any { a ->
                    a.date == date.toString() &&
                        employeeById[a.employeeId]?.role != EmployeeRole.TRAINEE
                }

                var pool = eligible

                if (state.settings.traineeMustHaveExperiencedManager && !dayAlreadyHasExperienced) {
                    val experienced = pool.filter { it.role != EmployeeRole.TRAINEE }
                    if (experienced.isEmpty()) {
                        unfilled += "$date ${template.name}: trainee mag niet zonder ervaren manager staan."
                        continue
                    }
                    pool = experienced
                }

                if (state.settings.minimizeBorrowedManagers) {
                    val ownManagers = pool.filter { it.role != EmployeeRole.BORROWED }
                    if (ownManagers.isNotEmpty()) pool = ownManagers
                }

                val chosen = pool.minBy { employee ->
                    score(employee, date, kind, generated + history, state)
                }
                generated += Assignment(
                    employeeId = chosen.id,
                    date = date.toString(),
                    shiftTemplateId = template.id,
                    source = "generated"
                )
            }

            if (date == ym.atEndOfMonth() && state.settings.monthEndCloseManagers > 1) {
                val closeTemplate = chooseTemplate(date, ShiftKind.CLOSE, state.shiftTemplates)
                if (closeTemplate != null) {
                    var closeCount = generated.count { a ->
                        a.date == date.toString() &&
                            templatesById[a.shiftTemplateId]?.kind == ShiftKind.CLOSE
                    }
                    while (closeCount < state.settings.monthEndCloseManagers) {
                        val eligibleExtraClose = employees.filter { employee ->
                            canAssign(employee, date, closeTemplate, generated, history, state)
                        }
                        if (eligibleExtraClose.isEmpty()) {
                            unfilled += "$date maandsluiting: ${state.settings.monthEndCloseManagers} sluitmanagers gevraagd, maar slechts $closeCount mogelijk."
                            break
                        }
                        var pool = eligibleExtraClose
                        if (state.settings.minimizeBorrowedManagers) {
                            val ownManagers = pool.filter { it.role != EmployeeRole.BORROWED }
                            if (ownManagers.isNotEmpty()) pool = ownManagers
                        }
                        val chosen = pool.minBy { employee ->
                            score(employee, date, ShiftKind.CLOSE, generated + history, state)
                        }
                        generated += Assignment(
                            employeeId = chosen.id,
                            date = date.toString(),
                            shiftTemplateId = closeTemplate.id,
                            source = "month-end"
                        )
                        closeCount++
                    }
                }
            }
        }

        fillContractDays(ym, generated, history, state, unfilled)

        val warnings = mutableListOf<String>()

        val borrowedCount = generated.count {
            employeeById[it.employeeId]?.role == EmployeeRole.BORROWED
        }
        if (borrowedCount > 0) {
            val borrowedDates = generated
                .filter { employeeById[it.employeeId]?.role == EmployeeRole.BORROWED }
                .map { it.date }
                .distinct()
                .sorted()
            warnings += "Leenmanager nodig: $borrowedCount dienst(en) op ${borrowedDates.joinToString()}."
        } else if (state.employees.any { it.role == EmployeeRole.BORROWED && it.active }) {
            warnings += "Dit rooster lukt zonder leenmanager."
        }

        employees.filter { it.role != EmployeeRole.BORROWED }.forEach { employee ->
            val blocks = countTwoDayOffBlocks(employee, ym, generated + history)
            if (blocks < state.settings.minimumTwoDayOffBlocks) {
                warnings += "${employee.name}: slechts $blocks blok(ken) van 2 opeenvolgende vrije dagen; minimaal ${state.settings.minimumTwoDayOffBlocks} gewenst."
            } else if (blocks < state.settings.preferredTwoDayOffBlocks) {
                warnings += "${employee.name}: $blocks blok van 2 vrije dagen; ${state.settings.preferredTwoDayOffBlocks} heeft voorkeur."
            }
        }

        weeksTouchingMonth(ym).forEach { monday ->
            employees.filter { it.role != EmployeeRole.BORROWED }.forEach { employee ->
                val target = minOf(employee.contractedDaysPerWeek, employee.maxShiftsPerWeek)
                val actual = weeklyShiftCount(employee, monday, generated + history)
                if (actual < target) {
                    warnings += "${employee.name}: week ${weekNumber(monday)} heeft $actual/$target contractdagen ingepland."
                }
            }
        }

        val finalState = state.copy(assignments = generated, assignmentHistory = history)
        val hardAtw = atw.validate(finalState).filter { it.severity == AtwValidator.Severity.ERROR }
        hardAtw.take(20).forEach { v ->
            warnings += "ATW ${v.date ?: ""}: ${v.message}"
        }

        return Result(
            generated.sortedWith(compareBy<Assignment>({ it.date }, { it.employeeId })),
            unfilled,
            warnings.distinct()
        )
    }

    private fun fillContractDays(
        ym: YearMonth,
        generated: MutableList<Assignment>,
        history: List<Assignment>,
        state: AppState,
        unfilled: MutableList<String>
    ) {
        val regularEmployees = state.employees.filter { it.active && it.role != EmployeeRole.BORROWED }

        for (monday in weeksTouchingMonth(ym)) {
            for (employee in regularEmployees.sortedByDescending { it.contractedDaysPerWeek }) {
                val target = minOf(employee.contractedDaysPerWeek, employee.maxShiftsPerWeek)
                var safety = 0
                while (weeklyShiftCount(employee, monday, generated + history) < target && safety++ < 10) {
                    val weekDates = (0L..6L)
                        .map { monday.plusDays(it) }
                        .filter { YearMonth.from(it) == ym }
                        .filter { date ->
                            generated.none { it.employeeId == employee.id && it.date == date.toString() }
                        }

                    val candidates = weekDates.mapNotNull { date ->
                        val template = chooseTemplate(date, ShiftKind.DAY, state.shiftTemplates)
                            ?: return@mapNotNull null
                        if (!canAssign(employee, date, template, generated, history, state)) {
                            return@mapNotNull null
                        }
                        date to template
                    }

                    if (candidates.isEmpty()) {
                        unfilled += "${employee.name}: contractdagen week ${weekNumber(monday)} niet volledig te vullen zonder conflict."
                        break
                    }

                    val best = candidates.minBy { (date, _) ->
                        contractFillDateScore(employee, date, generated + history, state)
                    }
                    generated += Assignment(
                        employeeId = employee.id,
                        date = best.first.toString(),
                        shiftTemplateId = best.second.id,
                        source = "contract"
                    )
                }
            }
        }
    }

    private fun contractFillDateScore(
        employee: Employee,
        date: LocalDate,
        assignments: List<Assignment>,
        state: AppState
    ): Double {
        var score = 0.0
        val yesterdayWorked = assignments.any {
            it.employeeId == employee.id && it.date == date.minusDays(1).toString()
        }
        val dayBeforeWorked = assignments.any {
            it.employeeId == employee.id && it.date == date.minusDays(2).toString()
        }

        if (!yesterdayWorked && dayBeforeWorked) score += 6.0

        val dayLoad = assignments.count { it.date == date.toString() }
        score += dayLoad * 2.0

        val weekend = date.dayOfWeek == DayOfWeek.SATURDAY ||
            date.dayOfWeek == DayOfWeek.SUNDAY
        if (weekend) {
            val monthWeekendShifts = assignments.count {
                if (it.employeeId != employee.id) return@count false
                val d = runCatching { LocalDate.parse(it.date) }.getOrNull() ?: return@count false
                d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY
            }
            score += monthWeekendShifts * 1.5
        }

        if (date.dayOfWeek.value in state.settings.busyWeekdays) score -= 0.5
        return score
    }

    private fun requiredKinds(date: LocalDate, settings: PlannerSettings): List<ShiftKind> = buildList {
        if (settings.requireSetupDaily) add(ShiftKind.SETUP)
        if (settings.requireMiddleOnBusyDays && date.dayOfWeek.value in settings.busyWeekdays) {
            add(ShiftKind.MIDDLE)
        }
        if (settings.requireCloseDaily) add(ShiftKind.CLOSE)
    }

    private fun chooseTemplate(
        date: LocalDate,
        kind: ShiftKind,
        templates: List<ShiftTemplate>
    ): ShiftTemplate? {
        val candidates = templates.filter {
            it.kind == kind && date.dayOfWeek.value in it.enabledWeekdays
        }
        if (kind == ShiftKind.SETUP) {
            val havi = candidates.firstOrNull { it.name.contains("HAVI", ignoreCase = true) }
            if (havi != null) return havi
            return candidates.firstOrNull { !it.name.contains("HAVI", ignoreCase = true) }
                ?: candidates.firstOrNull()
        }
        return candidates.firstOrNull()
    }

    private fun canAssign(
        employee: Employee,
        date: LocalDate,
        template: ShiftTemplate,
        generated: List<Assignment>,
        history: List<Assignment>,
        state: AppState
    ): Boolean {
        if (!employee.canWork(template.kind)) return false
        if (generated.any { it.employeeId == employee.id && it.date == date.toString() }) return false
        if (!isAvailable(employee, date, template, state)) return false
        if (!withinWeeklyShiftLimit(employee, date, generated + history)) return false

        if (
            state.settings.traineeMustHaveExperiencedManager &&
            employee.role == EmployeeRole.TRAINEE
        ) {
            val experiencedPresent = generated.any { a ->
                a.date == date.toString() &&
                    state.employees.firstOrNull { it.id == a.employeeId }?.role != EmployeeRole.TRAINEE
            }
            if (!experiencedPresent) return false
        }

        val candidate = Assignment(
            employeeId = employee.id,
            date = date.toString(),
            shiftTemplateId = template.id,
            source = "candidate"
        )

        val baseline = state.copy(
            assignments = generated,
            assignmentHistory = history
        )
        val proposed = state.copy(
            assignments = generated + candidate,
            assignmentHistory = history
        )

        fun key(v: AtwValidator.Violation): String =
            "${v.employeeId}|${v.date}|${v.rule}|${v.message}"

        val baselineErrors = atw.validate(baseline)
            .filter {
                it.severity == AtwValidator.Severity.ERROR &&
                    it.employeeId == employee.id
            }
            .map(::key)
            .toSet()

        return atw.validate(proposed)
            .filter {
                it.severity == AtwValidator.Severity.ERROR &&
                    it.employeeId == employee.id
            }
            .none { key(it) !in baselineErrors }
    }

    private fun isAvailable(
        employee: Employee,
        date: LocalDate,
        template: ShiftTemplate,
        state: AppState
    ): Boolean {
        val specific = state.availability.lastOrNull {
            it.employeeId == employee.id && it.date == date.toString()
        }
        val weekly = state.weeklyAvailability.lastOrNull {
            it.employeeId == employee.id && it.weekday == date.dayOfWeek.value
        }

        val available = specific?.available ?: weekly?.available ?: true
        if (!available) return false

        val fixedKind = specific?.fixedShiftKind ?: weekly?.fixedShiftKind
        if (fixedKind != null && fixedKind != template.kind) return false

        val earliestRaw = specific?.earliestStart ?: weekly?.earliestStart
        val latestRaw = specific?.latestEnd ?: weekly?.latestEnd
        val earliest = earliestRaw?.let {
            runCatching { java.time.LocalTime.parse(it) }.getOrNull()
        }
        val latest = latestRaw?.let {
            runCatching { java.time.LocalTime.parse(it) }.getOrNull()
        }

        if (earliest != null && template.startTime().isBefore(earliest)) return false

        if (latest != null) {
            val startDt = date.atTime(template.startTime())
            var endDt = date.atTime(template.endTime())
            if (!endDt.isAfter(startDt)) endDt = endDt.plusDays(1)
            var latestDt = date.atTime(latest)
            if (!latestDt.isAfter(startDt)) latestDt = latestDt.plusDays(1)
            if (endDt.isAfter(latestDt)) return false
        }
        return true
    }

    private fun withinWeeklyShiftLimit(
        employee: Employee,
        date: LocalDate,
        assignments: List<Assignment>
    ): Boolean {
        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return weeklyShiftCount(employee, monday, assignments) < employee.maxShiftsPerWeek
    }

    private fun weeklyShiftCount(
        employee: Employee,
        monday: LocalDate,
        assignments: List<Assignment>
    ): Int {
        val sunday = monday.plusDays(6)
        return assignments.count { a ->
            if (a.employeeId != employee.id) return@count false
            val d = runCatching { LocalDate.parse(a.date) }.getOrNull() ?: return@count false
            !d.isBefore(monday) && !d.isAfter(sunday)
        }
    }

    private fun score(
        employee: Employee,
        date: LocalDate,
        kind: ShiftKind,
        assignments: List<Assignment>,
        state: AppState
    ): Double {
        val ym = YearMonth.from(date)
        val inMonth = assignments.filter {
            it.employeeId == employee.id && isInMonth(it, ym)
        }
        val templates = state.shiftTemplates.associateBy { it.id }

        val hours = inMonth.sumOf { a ->
            templates[a.shiftTemplateId]?.let { durationHours(it) } ?: 0.0
        }
        val targetMonthlyHours =
            employee.contractedHoursPerWeek * ym.lengthOfMonth() / 7.0
        val relativeLoad =
            if (targetMonthlyHours <= 0.0) hours else hours / targetMonthlyHours

        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weeklyCount = weeklyShiftCount(employee, monday, assignments)
        val targetDays = maxOf(1, employee.contractedDaysPerWeek)
        val weekDeficit = targetDays - weeklyCount

        val sameKindCount = inMonth.count {
            templates[it.shiftTemplateId]?.kind == kind
        }
        val weekendCount = inMonth.count {
            val d = runCatching { LocalDate.parse(it.date) }.getOrNull()
            d?.dayOfWeek == DayOfWeek.SATURDAY || d?.dayOfWeek == DayOfWeek.SUNDAY
        }

        val weekendPenalty =
            if (date.dayOfWeek == DayOfWeek.SATURDAY ||
                date.dayOfWeek == DayOfWeek.SUNDAY
            ) weekendCount * 0.8 else 0.0

        val borrowedPenalty =
            if (state.settings.minimizeBorrowedManagers &&
                employee.role == EmployeeRole.BORROWED
            ) 100.0 else 0.0

        val traineePenalty =
            if (employee.role == EmployeeRole.TRAINEE) 0.4 else 0.0

        return relativeLoad * 12.0 +
            weeklyCount * 1.7 +
            sameKindCount * 0.9 +
            weekendPenalty +
            borrowedPenalty +
            traineePenalty -
            weekDeficit * 1.3
    }

    private fun countTwoDayOffBlocks(
        employee: Employee,
        ym: YearMonth,
        assignments: List<Assignment>
    ): Int {
        val worked = assignments
            .filter { it.employeeId == employee.id }
            .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
            .toSet()

        var blocks = 0
        var d = ym.atDay(1)
        val end = ym.atEndOfMonth()
        while (d.isBefore(end)) {
            if (d !in worked && d.plusDays(1) !in worked) {
                blocks++
                d = d.plusDays(2)
            } else {
                d = d.plusDays(1)
            }
        }
        return blocks
    }

    private fun weeksTouchingMonth(ym: YearMonth): List<LocalDate> {
        val first = ym.atDay(1)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val last = ym.atEndOfMonth()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val out = mutableListOf<LocalDate>()
        var d = first
        while (!d.isAfter(last)) {
            out += d
            d = d.plusDays(7)
        }
        return out
    }

    private fun weekNumber(date: LocalDate): Int =
        date.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())

    private fun durationHours(template: ShiftTemplate): Double {
        var minutes = java.time.Duration
            .between(template.startTime(), template.endTime())
            .toMinutes()
        if (minutes <= 0) minutes += 24 * 60
        return minutes / 60.0
    }

    private fun isInMonth(a: Assignment, ym: YearMonth): Boolean {
        val d = runCatching { LocalDate.parse(a.date) }.getOrNull() ?: return false
        return YearMonth.from(d) == ym
    }
}
