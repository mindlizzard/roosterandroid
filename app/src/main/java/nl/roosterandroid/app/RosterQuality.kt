package nl.roosterandroid.app

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.round

data class RosterQualityRow(
    val employeeId: String,
    val employeeName: String,
    val role: EmployeeRole,
    val plannedHours: Double,
    val targetHours: Double,
    val hourDifference: Double,
    val shifts: Int,
    val weekendShifts: Int,
    val manualShifts: Int,
    val atwErrors: Int,
    val issues: List<String>
) {
    val hoursOnTarget: Boolean
        get() =
            targetHours <= 0.0 ||
                abs(hourDifference) <= 1.0
}

fun AppState.rosterQualityRows(
    violations: List<AtwValidator.Violation> =
        AtwValidator().validate(this)
): List<RosterQualityRow> {
    val ym =
        YearMonth.of(
            year,
            month
        )

    val start =
        ym.atDay(1)

    val end =
        ym.atEndOfMonth()

    val assignedEmployeeIds =
        assignments
            .filter {
                runCatching {
                    YearMonth.from(
                        LocalDate.parse(it.date)
                    ) == ym
                }.getOrDefault(false)
            }
            .map { it.employeeId }
            .toSet()

    return employees
        .filter {
            it.role != EmployeeRole.HOST &&
                (
                    it.active ||
                        it.id in assignedEmployeeIds
                )
        }
        .map { employee ->
            val employeeAssignments =
                assignments.filter { assignment ->
                    if (
                        assignment.employeeId !=
                        employee.id
                    ) {
                        return@filter false
                    }

                    val date =
                        runCatching {
                            LocalDate.parse(
                                assignment.date
                            )
                        }.getOrNull()
                            ?: return@filter false

                    YearMonth.from(date) == ym
                }

            val plannedHours =
                plannedHoursFor(
                    employeeId = employee.id,
                    assignments =
                        employeeAssignments,
                    startDate = start,
                    endDate = end
                )

            val targetHours =
                if (
                    employee.contractedHoursPerWeek <=
                    0.0
                ) {
                    0.0
                } else {
                    employee.contractedHoursPerWeek *
                        ym.lengthOfMonth() /
                        7.0
                }

            val hourDifference =
                plannedHours -
                    targetHours

            val weekendShifts =
                employeeAssignments.count {
                    val date =
                        LocalDate.parse(it.date)

                    date.dayOfWeek ==
                        DayOfWeek.SATURDAY ||
                        date.dayOfWeek ==
                        DayOfWeek.SUNDAY
                }

            val manualShifts =
                employeeAssignments.count {
                    it.source.startsWith(
                        "manual"
                    )
                }

            val atwErrors =
                violations.count {
                    it.employeeId ==
                        employee.id &&
                        it.severity ==
                        AtwValidator.Severity.ERROR &&
                        (
                            it.date == null ||
                                YearMonth.from(
                                    it.date
                                ) == ym
                        )
                }

            val issues =
                buildList {
                    if (
                        targetHours > 0.0 &&
                        hourDifference < -1.0
                    ) {
                        add(
                            "${prettyHours(-hourDifference)} uur onder contractdoel"
                        )
                    }

                    if (
                        targetHours > 0.0 &&
                        hourDifference > 1.0
                    ) {
                        add(
                            "${prettyHours(hourDifference)} uur boven contractdoel"
                        )
                    }

                    if (atwErrors > 0) {
                        add(
                            "$atwErrors ATW-conflict(en)"
                        )
                    }
                }

            RosterQualityRow(
                employeeId =
                    employee.id,
                employeeName =
                    employee.name,
                role =
                    employee.role,
                plannedHours =
                    roundOneDecimal(
                        plannedHours
                    ),
                targetHours =
                    roundOneDecimal(
                        targetHours
                    ),
                hourDifference =
                    roundOneDecimal(
                        hourDifference
                    ),
                shifts =
                    employeeAssignments.size,
                weekendShifts =
                    weekendShifts,
                manualShifts =
                    manualShifts,
                atwErrors =
                    atwErrors,
                issues =
                    issues
            )
        }
        .sortedBy {
            it.employeeName.lowercase()
        }
}

private fun roundOneDecimal(
    value: Double
): Double =
    round(value * 10.0) / 10.0

private fun prettyHours(
    value: Double
): String {
    val rounded =
        roundOneDecimal(value)

    return if (
        rounded % 1.0 == 0.0
    ) {
        rounded.toInt().toString()
    } else {
        rounded.toString()
    }
}
