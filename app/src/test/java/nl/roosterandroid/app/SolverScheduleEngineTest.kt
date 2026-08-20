package nl.roosterandroid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SolverScheduleEngineTest {
    private fun leanSettings(): PlannerSettings = PlannerSettings(
        requireSetupDaily = true,
        requireCloseDaily = true,
        requireMiddleOnBusyDays = false,
        monthEndCloseManagers = 1,
        minimumTwoDayOffBlocks = 0,
        preferredTwoDayOffBlocks = 0,
        preferTwoConsecutiveDaysOff = false
    )

    @Test
    fun weekCountIsOverlayAndDoesNotAddHeadcount() {
        val jan = Employee(
            name = "Jan",
            contractedDaysPerWeek = 0,
            contractedHoursPerWeek = 0.0,
            maxShiftsPerWeek = 7
        )
        val b = Employee(
            name = "B",
            contractedDaysPerWeek = 0,
            contractedHoursPerWeek = 0.0,
            maxShiftsPerWeek = 7
        )
        val c = Employee(
            name = "C",
            contractedDaysPerWeek = 0,
            contractedHoursPerWeek = 0.0,
            maxShiftsPerWeek = 7
        )

        val state = AppState(
            year = 2026,
            month = 6,
            employees = listOf(jan, b, c),
            responsibilities = listOf(
                ResponsibilityRule(
                    employeeId = jan.id,
                    type = ResponsibilityType.WEEK_COUNT,
                    recurrence = RecurrenceType.WEEKLY,
                    weekday = 1,
                    preferScheduled = true
                )
            ),
            settings = leanSettings()
        )

        val result = ScheduleEngine().generate(state)
        val monday = "2026-06-01"

        assertTrue(result.assignments.any {
            it.employeeId == jan.id && it.date == monday
        })
        assertEquals(
            2,
            result.assignments.count { it.date == monday }
        )
    }

    @Test
    fun borrowedManagerIsNotUsedWhenOwnTeamCanCover() {
        val own = (1..3).map {
            Employee(
                name = "Eigen $it",
                contractedDaysPerWeek = 0,
                contractedHoursPerWeek = 0.0,
                maxShiftsPerWeek = 7
            )
        }
        val borrowed = Employee(
            name = "Leen",
            role = EmployeeRole.BORROWED,
            contractedDaysPerWeek = 0,
            contractedHoursPerWeek = 0.0,
            maxShiftsPerWeek = 7
        )

        val state = AppState(
            year = 2026,
            month = 6,
            employees = own + borrowed,
            settings = leanSettings()
        )

        val result = ScheduleEngine().generate(state)

        assertFalse(result.assignments.any {
            it.employeeId == borrowed.id
        })
    }

    @Test
    fun fixedTraineeGetsExperiencedCoverageWithoutOrderingBug() {
        val trainee = Employee(
            name = "Trainee",
            role = EmployeeRole.TRAINEE,
            contractedDaysPerWeek = 0,
            contractedHoursPerWeek = 0.0,
            maxShiftsPerWeek = 5,
            canSetup = true,
            canDay = false,
            canMiddle = false,
            canClose = false
        )
        val manager = Employee(
            name = "Manager",
            contractedDaysPerWeek = 0,
            contractedHoursPerWeek = 0.0,
            maxShiftsPerWeek = 5
        )

        val state = AppState(
            year = 2026,
            month = 6,
            employees = listOf(trainee, manager),
            availability = listOf(
                Availability(
                    employeeId = trainee.id,
                    date = "2026-06-01",
                    available = true,
                    fixedShiftKind = ShiftKind.SETUP
                )
            ),
            settings = leanSettings().copy(
                requireSetupDaily = false,
                requireCloseDaily = false
            )
        )

        val result = ScheduleEngine().generate(state)
        val day = result.assignments.filter {
            it.date == "2026-06-01"
        }

        assertTrue(day.any { it.employeeId == trainee.id })
        assertTrue(day.any { it.employeeId == manager.id })
        assertEquals(2, day.size)
    }

    @Test
    fun approvedVacationBlocksAutomaticScheduling() {
        val employee = Employee(
            name = "A",
            contractedDaysPerWeek = 5,
            contractedHoursPerWeek = 40.0
        )
        val state = AppState(
            year = 2026,
            month = 6,
            employees = listOf(employee),
            absences = listOf(
                Absence(
                    employeeId = employee.id,
                    startDate = "2026-06-08",
                    endDate = "2026-06-10",
                    type = AbsenceType.VACATION,
                    status = AbsenceStatus.APPROVED
                )
            ),
            settings = leanSettings().copy(
                requireSetupDaily = false,
                requireCloseDaily = false
            )
        )

        val result = ScheduleEngine().generate(state)

        assertFalse(result.assignments.any {
            it.employeeId == employee.id &&
                it.date in setOf("2026-06-08", "2026-06-09", "2026-06-10")
        })
    }

    @Test
    fun requestedLeaveDoesNotBlockUntilApproved() {
        val employee = Employee(
            name = "A",
            contractedDaysPerWeek = 5,
            contractedHoursPerWeek = 40.0
        )
        val state = AppState(
            year = 2026,
            month = 6,
            employees = listOf(employee),
            availability = listOf(
                Availability(
                    employeeId = employee.id,
                    date = "2026-06-01",
                    available = true,
                    fixedShiftKind = ShiftKind.DAY
                )
            ),
            absences = listOf(
                Absence(
                    employeeId = employee.id,
                    startDate = "2026-06-01",
                    endDate = "2026-06-01",
                    type = AbsenceType.LEAVE,
                    status = AbsenceStatus.REQUESTED
                )
            ),
            settings = leanSettings().copy(
                requireSetupDaily = false,
                requireCloseDaily = false
            )
        )

        val result = ScheduleEngine().generate(state)

        assertTrue(result.assignments.any {
            it.employeeId == employee.id && it.date == "2026-06-01"
        })
    }
}
