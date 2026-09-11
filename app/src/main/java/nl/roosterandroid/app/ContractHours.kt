package nl.roosterandroid.app

import java.time.Duration
import java.time.LocalDate

fun ShiftTemplate.planningDurationHours(): Double {
    var minutes =
        Duration.between(
            startTime(),
            endTime()
        ).toMinutes()

    if (minutes <= 0) {
        minutes += 24L * 60L
    }

    return minutes / 60.0
}

fun Employee.expectedHoursForContractDays(
    targetDays: Int
): Double {
    if (
        targetDays <= 0 ||
        contractedDaysPerWeek <= 0 ||
        contractedHoursPerWeek <= 0.0
    ) {
        return 0.0
    }

    return contractedHoursPerWeek *
        (
            targetDays.toDouble() /
                contractedDaysPerWeek.toDouble()
        )
}

fun AppState.plannedHoursFor(
    employeeId: String,
    assignments: List<Assignment>,
    startDate: LocalDate,
    endDate: LocalDate
): Double {
    if (endDate.isBefore(startDate)) {
        return 0.0
    }

    val templates =
        shiftTemplates.associateBy {
            it.id
        }

    return assignments.sumOf { assignment ->
        if (assignment.employeeId != employeeId) {
            return@sumOf 0.0
        }

        val date =
            runCatching {
                LocalDate.parse(
                    assignment.date
                )
            }.getOrNull()
                ?: return@sumOf 0.0

        if (
            date.isBefore(startDate) ||
            date.isAfter(endDate)
        ) {
            return@sumOf 0.0
        }

        templates[
            assignment.shiftTemplateId
        ]?.planningDurationHours()
            ?: 0.0
    }
}

fun AppState.projectedHoursFor(
    employeeId: String,
    assignments: List<Assignment>,
    startDate: LocalDate,
    endDate: LocalDate,
    extraTemplate: ShiftTemplate
): Double =
    plannedHoursFor(
        employeeId = employeeId,
        assignments = assignments,
        startDate = startDate,
        endDate = endDate
    ) + extraTemplate.planningDurationHours()
