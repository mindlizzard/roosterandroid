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
    @Test
    fun automaticPlanningNeverUsesKpiAsOrdinaryShift() {
        val employees = (1..5).map {
            Employee(
                name = "M$it",
                contractedDaysPerWeek = 5,
                contractedHoursPerWeek = 40.0,
                maxShiftsPerWeek = 5
            )
        }
        val state = AppState(
            year = 2026,
            month = 8,
            employees = employees
        )

        val result = ScheduleEngine().generate(state)
        val templates = state.shiftTemplates.associateBy { it.id }

        assertFalse(result.assignments.any {
            templates[it.shiftTemplateId]?.kind == ShiftKind.KPI
        })
    }

    @Test
    fun dayPartDemandAddsManagersWhoseShiftCoversTheWindow() {
        val employees = (1..4).map {
            Employee(
                name = "M$it",
                contractedDaysPerWeek = 0,
                contractedHoursPerWeek = 0.0,
                maxShiftsPerWeek = 7
            )
        }
        val state = AppState(
            year = 2026,
            month = 8,
            employees = employees,
            dayPartDemands = listOf(
                DayPartDemand(
                    date = "2026-08-03",
                    label = "Lunch",
                    start = "11:00",
                    end = "14:00",
                    minimumManagers = 3
                )
            ),
            settings = leanSettings().copy(
                requireSetupDaily = false,
                requireCloseDaily = false
            )
        )

        val result = ScheduleEngine().generate(state)
        val templates = state.shiftTemplates.associateBy { it.id }
        val onDate = result.assignments.filter { it.date == "2026-08-03" }

        assertEquals(3, onDate.size)
        assertTrue(onDate.all { assignment ->
            val template = templates.getValue(assignment.shiftTemplateId)
            !template.startTime().isAfter(java.time.LocalTime.of(11, 0)) &&
                !template.endTime().isBefore(java.time.LocalTime.of(14, 0))
        })
    }

    @Test
    fun closedWeekdayNeverReceivesAutomaticAssignments() {
        val employees = (1..4).map {
            Employee(
                name = "M$it",
                contractedDaysPerWeek = 5,
                contractedHoursPerWeek = 40.0,
                maxShiftsPerWeek = 7
            )
        }
        val state = AppState(
            year = 2026,
            month = 8,
            employees = employees,
            operatingHours = defaultOperatingHours().map {
                if (it.weekday == 1) it.copy(closed = true) else it
            },
            settings = leanSettings()
        )

        val result = ScheduleEngine().generate(state)

        assertFalse(result.assignments.any {
            java.time.LocalDate.parse(it.date).dayOfWeek.value == 1
        })
    }

    @Test
    fun shiftOutsideRestaurantHoursIsNotPlanned() {
        val employee = Employee(
            name = "A",
            contractedDaysPerWeek = 5,
            contractedHoursPerWeek = 40.0,
            maxShiftsPerWeek = 7
        )
        val early = ShiftTemplate(
            name = "Te vroeg",
            kind = ShiftKind.DAY,
            start = "07:00",
            end = "15:00"
        )
        val state = AppState(
            year = 2026,
            month = 8,
            employees = listOf(employee),
            shiftTemplates = listOf(early),
            settings = leanSettings().copy(
                requireSetupDaily = false,
                requireCloseDaily = false
            )
        )

        val result = ScheduleEngine().generate(state)

        assertTrue(result.assignments.isEmpty())
    }

    @Test
    fun kpiTaskIsOverlayOnNormalOperationalShift() {
        val jan = Employee(
            name = "Jan",
            contractedDaysPerWeek = 5,
            contractedHoursPerWeek = 40.0,
            maxShiftsPerWeek = 5
        )
        val others = (1..4).map {
            Employee(
                name = "M$it",
                contractedDaysPerWeek = 5,
                contractedHoursPerWeek = 40.0,
                maxShiftsPerWeek = 5
            )
        }
        val state = AppState(
            year = 2026,
            month = 8,
            employees = listOf(jan) + others,
            responsibilities = listOf(
                ResponsibilityRule(
                    employeeId = jan.id,
                    type = ResponsibilityType.KPI,
                    recurrence = RecurrenceType.WEEKLY,
                    weekday = 1,
                    preferScheduled = true
                )
            )
        )

        val result = ScheduleEngine().generate(state)
        val templates = state.shiftTemplates.associateBy { it.id }
        val mondayAssignments = result.assignments.filter {
            it.employeeId == jan.id &&
                java.time.LocalDate.parse(it.date).dayOfWeek.value == 1
        }

        assertTrue(mondayAssignments.isNotEmpty())
        assertTrue(mondayAssignments.all {
            templates[it.shiftTemplateId]?.kind in setOf(
                ShiftKind.DAY,
                ShiftKind.SETUP,
                ShiftKind.MIDDLE,
                ShiftKind.CLOSE
            )
        })
        assertFalse(mondayAssignments.any {
            templates[it.shiftTemplateId]?.kind == ShiftKind.KPI
        })
    }


    @Test
    fun weeklyUnavailableDayIsRespected() {
        val employee = Employee(
            name = "A",
            contractedDaysPerWeek = 0,
            contractedHoursPerWeek = 0.0,
            maxShiftsPerWeek = 7
        )
        val state = AppState(
            year = 2026,
            month = 6,
            employees = listOf(employee),
            weeklyAvailability = listOf(
                WeeklyAvailability(employeeId = employee.id, weekday = 1, available = false)
            ),
            settings = leanSettings().copy(
                requireSetupDaily = false,
                requireCloseDaily = true
            )
        )
        val result = ScheduleEngine().generate(state)
        assertFalse(result.assignments.any {
            it.employeeId == employee.id && it.date == "2026-06-01"
        })
    }

    @Test
    fun weeklyTimeWindowBlocksShiftOutsideAvailability() {
        val employee = Employee(
            name = "A",
            contractedDaysPerWeek = 0,
            contractedHoursPerWeek = 0.0,
            maxShiftsPerWeek = 7
        )
        val state = AppState(
            year = 2026,
            month = 6,
            employees = listOf(employee),
            weeklyAvailability = listOf(
                WeeklyAvailability(
                    employeeId = employee.id,
                    weekday = 1,
                    available = true,
                    earliestStart = "09:00",
                    latestEnd = "17:00"
                )
            ),
            settings = leanSettings().copy(
                requireSetupDaily = false,
                requireCloseDaily = true
            )
        )
        val result = ScheduleEngine().generate(state)
        assertFalse(result.assignments.any {
            it.employeeId == employee.id && it.date == "2026-06-01"
        })
    }

    @Test
    fun specificDateRuleFullyOverridesWeeklyTimes() {
        val employee = Employee(
            name = "A",
            contractedDaysPerWeek = 0,
            contractedHoursPerWeek = 0.0,
            maxShiftsPerWeek = 7
        )
        val state = AppState(
            year = 2026,
            month = 6,
            employees = listOf(employee),
            weeklyAvailability = listOf(
                WeeklyAvailability(
                    employeeId = employee.id,
                    weekday = 1,
                    available = true,
                    earliestStart = "12:00",
                    latestEnd = "20:00"
                )
            ),
            availability = listOf(
                Availability(
                    employeeId = employee.id,
                    date = "2026-06-01",
                    available = true,
                    fixedShiftKind = ShiftKind.DAY
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
