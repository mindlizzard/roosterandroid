package nl.roosterandroid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DemandOrderTest {
    @Test
    fun timeWindowCoveragePrecedesContractAndGenericHeadcount() {
        val manager = Employee(
            id = "manager", name = "Manager",
            contractedDaysPerWeek = 1, contractedHoursPerWeek = 8.0,
            maxShiftsPerWeek = 1
        )
        val day = ShiftTemplate(
            id = "day", name = "Dag", kind = ShiftKind.DAY,
            start = "09:00", end = "17:00"
        )
        val evening = ShiftTemplate(
            id = "evening", name = "Avond", kind = ShiftKind.DAY,
            start = "14:00", end = "22:00"
        )
        val state = AppState(
            year = 2026, month = 6,
            employees = listOf(manager),
            shiftTemplates = listOf(day, evening),
            weeklyAvailability = (2..7).map {
                WeeklyAvailability(employeeId = manager.id, weekday = it, available = false)
            },
            dayDemands = listOf(DayDemand(date = "2026-06-01", minimumManagers = 1)),
            dayPartDemands = listOf(DayPartDemand(
                date = "2026-06-01", label = "Avondpiek",
                start = "20:00", end = "22:00", minimumManagers = 1
            )),
            settings = PlannerSettings(
                requireSetupDaily = false, requireCloseDaily = false,
                requireMiddleOnBusyDays = false,
                preferTwoConsecutiveDaysOff = false,
                minimumTwoDayOffBlocks = 0, preferredTwoDayOffBlocks = 0
            )
        )

        val result = ScheduleEngine().generate(state)
        val assignment = result.assignments.single { it.date == "2026-06-01" }
        assertEquals(evening.id, assignment.shiftTemplateId)
        assertEquals("solver-daypart", assignment.source)
        assertFalse(result.unfilled.any { it.contains("Avondpiek") })
        assertFalse(AtwValidator().validate(state.copy(assignments = result.assignments))
            .any { it.severity == AtwValidator.Severity.ERROR })
    }
}
