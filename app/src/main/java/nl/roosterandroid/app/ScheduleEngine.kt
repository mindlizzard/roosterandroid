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
        if (employees.isEmpty()) return Result(emptyList(), listOf("Voeg eerst minimaal één manager toe."), emptyList())

        val history = (state.assignmentHistory + state.assignments.filterNot { isInMonth(it, ym) }).distinctBy { it.id }
        val templatesById = state.shiftTemplates.associateBy { it.id }
        val employeeById = state.employees.associateBy { it.id }
        val scheduledHistory = history.mapNotNull { a ->
            val e = employeeById[a.employeeId] ?: return@mapNotNull null
            val t = templatesById[a.shiftTemplateId] ?: return@mapNotNull null
            atw.toScheduledShift(a, e, t)
        }.groupBy { it.employee.id }.mapValues { it.value.toMutableList() }.toMutableMap()

        val generated = mutableListOf<Assignment>()
        val unfilled = mutableListOf<String>()

        for (day in 1..ym.lengthOfMonth()) {
            val date = ym.atDay(day)

            val fixedForDay = state.availability.filter { it.date == date.toString() && it.available && it.fixedShiftKind != null }
            fixedForDay.forEach { fixed ->
                val employee = employees.firstOrNull { it.id == fixed.employeeId } ?: return@forEach
                val kind = fixed.fixedShiftKind ?: return@forEach
                val template = chooseTemplate(date, kind, state.shiftTemplates) ?: return@forEach
                val existingForEmployee = scheduledHistory[employee.id].orEmpty()
                if (employee.canWork(kind) && isAvailable(employee, date, template, state.availability) &&
                    withinWeeklyShiftLimit(employee, date, generated + history) &&
                    atw.canPlace(employee, date, template, existingForEmployee, state.settings)) {
                    val assignment = Assignment(employeeId = employee.id, date = date.toString(), shiftTemplateId = template.id, source = "fixed")
                    generated += assignment
                    scheduledHistory.getOrPut(employee.id) { mutableListOf() }.add(atw.toScheduledShift(assignment, employee, template))
                } else {
                    unfilled += "$date ${employee.name}: vaste ${template.name} botst met beschikbaarheid/ATW."
                }
            }

            val required = requiredKinds(date, state.settings)
            for (kind in required) {
                val kindAlreadyFilled = generated.filter { it.date == date.toString() }
                    .any { a -> templatesById[a.shiftTemplateId]?.kind == kind }
                if (kindAlreadyFilled) continue
                val template = chooseTemplate(date, kind, state.shiftTemplates)
                if (template == null) {
                    unfilled += "$date: geen actief diensttemplate voor $kind."
                    continue
                }

                val alreadyToday = generated.filter { it.date == date.toString() }.map { it.employeeId }.toSet()
                val eligible = employees.filter { e ->
                    e.id !in alreadyToday &&
                        e.canWork(kind) &&
                        isAvailable(e, date, template, state.availability) &&
                        withinWeeklyShiftLimit(e, date, generated + history) &&
                        atw.canPlace(e, date, template, scheduledHistory[e.id].orEmpty(), state.settings)
                }

                if (eligible.isEmpty()) {
                    unfilled += "$date ${template.name}: geen beschikbare manager zonder harde conflictregels."
                    continue
                }

                val nonTrainees = eligible.filter { it.role != EmployeeRole.TRAINEE }
                val dayHasNonTrainee = generated.any { a ->
                    a.date == date.toString() && employeeById[a.employeeId]?.role != EmployeeRole.TRAINEE
                }
                val pool = if (!dayHasNonTrainee && nonTrainees.isNotEmpty()) nonTrainees else eligible

                val chosen = pool.minBy { e -> score(e, date, generated + history, state) }
                val assignment = Assignment(
                    employeeId = chosen.id,
                    date = date.toString(),
                    shiftTemplateId = template.id,
                    source = "generated"
                )
                generated += assignment
                scheduledHistory.getOrPut(chosen.id) { mutableListOf() }
                    .add(atw.toScheduledShift(assignment, chosen, template))
            }
        }

        val warnings = mutableListOf<String>()
        if (state.settings.preferTwoConsecutiveDaysOff) {
            employees.forEach { e ->
                if (!hasTwoConsecutiveDaysOff(e, ym, generated + history)) {
                    warnings += "${e.name}: geen blok van 2 opeenvolgende vrije dagen in deze maand."
                }
            }
        }

        generated.groupBy { it.date }.forEach { (date, dayAssignments) ->
            val assignedEmployees = dayAssignments.mapNotNull { employeeById[it.employeeId] }
            if (assignedEmployees.isNotEmpty() && assignedEmployees.all { it.role == EmployeeRole.TRAINEE }) {
                warnings += "$date: trainee(s) staan zonder ervaren manager ingepland."
            }
        }

        return Result(generated, unfilled, warnings)
    }

    private fun requiredKinds(date: LocalDate, settings: PlannerSettings): List<ShiftKind> = buildList {
        if (settings.requireSetupDaily) add(ShiftKind.SETUP)
        if (settings.requireMiddleOnBusyDays && date.dayOfWeek.value in settings.busyWeekdays) add(ShiftKind.MIDDLE)
        if (settings.requireCloseDaily) add(ShiftKind.CLOSE)
    }

    private fun chooseTemplate(date: LocalDate, kind: ShiftKind, templates: List<ShiftTemplate>): ShiftTemplate? {
        val candidates = templates.filter { it.kind == kind && date.dayOfWeek.value in it.enabledWeekdays }
        if (kind == ShiftKind.SETUP) {
            val havi = candidates.firstOrNull { it.name.contains("HAVI", ignoreCase = true) }
            if (havi != null) return havi
            return candidates.firstOrNull { !it.name.contains("HAVI", ignoreCase = true) } ?: candidates.firstOrNull()
        }
        return candidates.firstOrNull()
    }

    private fun isAvailable(employee: Employee, date: LocalDate, template: ShiftTemplate, availability: List<Availability>): Boolean {
        val a = availability.lastOrNull { it.employeeId == employee.id && it.date == date.toString() } ?: return true
        if (!a.available) return false
        if (a.fixedShiftKind != null && a.fixedShiftKind != template.kind) return false
        val earliest = a.earliestStart?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() }
        val latest = a.latestEnd?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() }
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

    private fun withinWeeklyShiftLimit(employee: Employee, date: LocalDate, assignments: List<Assignment>): Boolean {
        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val sunday = monday.plusDays(6)
        val count = assignments.count { a ->
            if (a.employeeId != employee.id) return@count false
            val d = runCatching { LocalDate.parse(a.date) }.getOrNull() ?: return@count false
            !d.isBefore(monday) && !d.isAfter(sunday)
        }
        return count < employee.maxShiftsPerWeek
    }

    private fun score(employee: Employee, date: LocalDate, assignments: List<Assignment>, state: AppState): Double {
        val ym = YearMonth.of(date.year, date.month)
        val inMonth = assignments.filter { it.employeeId == employee.id && isInMonth(it, ym) }
        val templates = state.shiftTemplates.associateBy { it.id }
        val hours = inMonth.sumOf { a -> templates[a.shiftTemplateId]?.let { durationHours(it) } ?: 0.0 }
        val targetMonthlyHours = employee.contractedHoursPerWeek * ym.lengthOfMonth() / 7.0
        val relativeLoad = if (targetMonthlyHours <= 0.0) hours else hours / targetMonthlyHours

        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weeklyCount = assignments.count { a ->
            if (a.employeeId != employee.id) return@count false
            val d = runCatching { LocalDate.parse(a.date) }.getOrNull() ?: return@count false
            !d.isBefore(monday) && d.isBefore(monday.plusDays(7))
        }

        val borrowedPenalty = if (employee.role == EmployeeRole.BORROWED) 0.35 else 0.0
        return relativeLoad * 10.0 + weeklyCount * 1.5 + borrowedPenalty
    }

    private fun hasTwoConsecutiveDaysOff(employee: Employee, ym: YearMonth, assignments: List<Assignment>): Boolean {
        val work = assignments.filter { it.employeeId == employee.id && isInMonth(it, ym) }
            .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
            .toSet()
        for (d in 1 until ym.lengthOfMonth()) {
            val a = ym.atDay(d)
            if (a !in work && a.plusDays(1) !in work) return true
        }
        return false
    }

    private fun durationHours(template: ShiftTemplate): Double {
        var minutes = java.time.Duration.between(template.startTime(), template.endTime()).toMinutes()
        if (minutes <= 0) minutes += 24 * 60
        return minutes / 60.0
    }

    private fun isInMonth(a: Assignment, ym: YearMonth): Boolean {
        val d = runCatching { LocalDate.parse(a.date) }.getOrNull() ?: return false
        return YearMonth.from(d) == ym
    }
}
