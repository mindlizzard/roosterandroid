package nl.roosterandroid.app

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

data class EmployeeMonthStats(
    val employeeId: String,
    val name: String,
    val hours: Double,
    val otherLocationHours: Double,
    val targetHours: Double,
    val shifts: Int,
    val setup: Int,
    val day: Int,
    val middle: Int,
    val close: Int,
    val night: Int,
    val weekend: Int,
    val borrowedShifts: Int,
    val vacationDays: Int,
    val leaveDays: Int,
    val sickDays: Int
)

fun employeeMonthStats(state: AppState): List<EmployeeMonthStats> {
    val ym = YearMonth.of(state.year, state.month)
    val templates = state.shiftTemplates.associateBy { it.id }

    fun hours(template: ShiftTemplate): Double = runCatching {
        var minutes = java.time.Duration.between(template.startTime(), template.endTime()).toMinutes()
        if (minutes <= 0) minutes += 24 * 60
        minutes / 60.0
    }.getOrDefault(0.0)

    return state.employees.filter {
        it.active && it.worksAt(state.activeLocationId)
    }.map { employee ->
        val allMonthAssignments = (state.assignments + state.assignmentHistory)
            .distinctBy { it.id }
            .filter {
                it.employeeId == employee.id &&
                    runCatching { YearMonth.from(LocalDate.parse(it.date)) == ym }.getOrDefault(false)
            }
        val assignments = allMonthAssignments.filter {
            it.locationId == state.activeLocationId
        }
        val monthHours = assignments.sumOf { a -> templates[a.shiftTemplateId]?.let(::hours) ?: 0.0 }
        val otherLocationHours = allMonthAssignments
            .filter { it.locationId != state.activeLocationId }
            .sumOf { a -> templates[a.shiftTemplateId]?.let(::hours) ?: 0.0 }
        val kinds = assignments.mapNotNull { templates[it.shiftTemplateId]?.kind }
        val weekend = assignments.count { a ->
            val d = runCatching { LocalDate.parse(a.date) }.getOrNull()
            d?.dayOfWeek == DayOfWeek.SATURDAY || d?.dayOfWeek == DayOfWeek.SUNDAY
        }
        val approved = state.absences.filter {
            it.employeeId == employee.id && it.status == AbsenceStatus.APPROVED
        }
        var vacation = 0
        var leave = 0
        var sick = 0
        for (day in 1..ym.lengthOfMonth()) {
            val date = ym.atDay(day)
            approved.firstOrNull { it.includes(date) }?.let { a ->
                when (a.type) {
                    AbsenceType.VACATION -> vacation++
                    AbsenceType.SICK -> sick++
                    AbsenceType.LEAVE, AbsenceType.SPECIAL_LEAVE, AbsenceType.UNPAID_LEAVE,
                    AbsenceType.COMP_TIME, AbsenceType.MATERNITY -> leave++
                    else -> Unit
                }
            }
        }
        EmployeeMonthStats(
            employeeId = employee.id,
            name = employee.name,
            hours = monthHours,
            otherLocationHours = otherLocationHours,
            targetHours = employee.contractedHoursPerWeek * ym.lengthOfMonth() / 7.0,
            shifts = assignments.size,
            setup = kinds.count { it == ShiftKind.SETUP },
            day = kinds.count { it == ShiftKind.DAY },
            middle = kinds.count { it == ShiftKind.MIDDLE },
            close = kinds.count { it == ShiftKind.CLOSE },
            night = kinds.count { it == ShiftKind.NIGHT },
            weekend = weekend,
            borrowedShifts = if (employee.role == EmployeeRole.BORROWED) assignments.size else 0,
            vacationDays = vacation,
            leaveDays = leave,
            sickDays = sick
        )
    }
}

fun rosterQualityScore(
    state: AppState,
    unfilled: List<String>,
    plannerWarnings: List<String>,
    violations: List<AtwValidator.Violation>
): Int {
    var score = 100
    score -= violations.count { it.severity == AtwValidator.Severity.ERROR } * 12
    score -= unfilled.size * 6
    score -= plannerWarnings.size.coerceAtMost(10) * 2

    val stats = employeeMonthStats(state)
    stats.filter { stat ->
        state.employees.firstOrNull { it.id == stat.employeeId }?.role != EmployeeRole.BORROWED
    }.forEach { stat ->
        if (stat.targetHours > 0) {
            val ratio = (stat.hours + stat.otherLocationHours) / stat.targetHours
            if (ratio < 0.85 || ratio > 1.15) score -= 3
        }
    }

    val borrowed = stats.sumOf { it.borrowedShifts }
    score -= borrowed.coerceAtMost(10)
    return score.coerceIn(0, 100)
}
