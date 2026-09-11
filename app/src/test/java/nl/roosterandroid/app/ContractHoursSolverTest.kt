package nl.roosterandroid.app

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ContractHoursSolverTest {

    @Test
    fun solverPrefersShiftThatMatchesContractHours() {
        val employee =
            Employee(
                id = "manager-1",
                name = "Daniel",
                contractedDaysPerWeek = 5,
                contractedHoursPerWeek = 40.0,
                maxShiftsPerWeek = 5
            )

        val shortShift =
            ShiftTemplate(
                id = "short-day",
                name = "Korte dag",
                kind = ShiftKind.DAY,
                start = "09:00",
                end = "15:00"
            )

        val fullShift =
            ShiftTemplate(
                id = "full-day",
                name = "Volle dag",
                kind = ShiftKind.DAY,
                start = "09:00",
                end = "17:00"
            )

        val state =
            AppState(
                year = 2026,
                month = 6,
                employees =
                    listOf(employee),
                shiftTemplates =
                    listOf(
                        shortShift,
                        fullShift
                    ),
                settings =
                    PlannerSettings(
                        requireSetupDaily = false,
                        requireCloseDaily = false,
                        requireMiddleOnBusyDays = false,
                        preferTwoConsecutiveDaysOff = false,
                        minimumTwoDayOffBlocks = 0,
                        preferredTwoDayOffBlocks = 0,
                        monthEndCloseManagers = 1
                    )
            )

        val result =
            ScheduleEngine().generate(state)

        val weekStart =
            LocalDate.parse("2026-06-01")

        val weekEnd =
            LocalDate.parse("2026-06-07")

        val firstWeek =
            result.assignments.filter {
                val date =
                    LocalDate.parse(it.date)

                !date.isBefore(weekStart) &&
                    !date.isAfter(weekEnd) &&
                    it.employeeId == employee.id
            }

        assertEquals(
            5,
            firstWeek.size
        )

        assertEquals(
            40.0,
            state.plannedHoursFor(
                employeeId = employee.id,
                assignments = firstWeek,
                startDate = weekStart,
                endDate = weekEnd
            ),
            0.001
        )

        assertFalse(
            firstWeek.any {
                it.shiftTemplateId ==
                    shortShift.id
            }
        )
    }
}
