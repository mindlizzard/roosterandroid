package nl.roosterandroid.app

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiLocationPlanningTest {
    @Test
    fun legacyStateIsAttachedToTheDefaultLocation() {
        val employee = Employee(name = "Oude manager")
        val legacy = AppState(
            employees = listOf(employee),
            settings = PlannerSettings(
                locationName = "Restaurant Noord",
                requireSetupDaily = false,
                requireCloseDaily = false
            )
        )

        val normalized = normalizeAppState(legacy)

        assertEquals(DEFAULT_LOCATION_ID, normalized.activeLocationId)
        assertEquals("Restaurant Noord", normalized.locations.single().name)
        assertFalse(normalized.locations.single().requireSetupDaily)
        assertFalse(normalized.locations.single().requireCloseDaily)
        assertEquals(setOf(DEFAULT_LOCATION_ID), normalized.employees.single().locationIds)
    }

    @Test
    fun lateClosingAndTwentyFourHourOpeningUseTheNextCalendarDay() {
        val businessDate = LocalDate.of(2026, 6, 1)
        val late = RestaurantLocation(
            id = "late",
            openingHours = listOf(
                OpeningHoursRule(weekday = 1, open = "10:00", close = "02:00")
            )
        )
        val alwaysOpen = RestaurantLocation(
            id = "always",
            openingHours = listOf(
                OpeningHoursRule(
                    weekday = 1,
                    mode = OpeningMode.OPEN_24_HOURS,
                    open = "00:00",
                    close = "00:00"
                )
            )
        )

        assertEquals(
            businessDate.atTime(10, 0) to businessDate.plusDays(1).atTime(2, 0),
            openingBounds(late, businessDate)
        )
        assertEquals(
            businessDate.atTime(6, 0) to businessDate.plusDays(1).atTime(6, 0),
            openingBounds(alwaysOpen, businessDate)
        )
    }

    @Test
    fun twentyFourHourLocationGetsMorningEveningAndNightTemplates() {
        val location = RestaurantLocation(
            id = "airport",
            name = "Airport 24/7",
            openingHours = defaultOpeningHours().map {
                it.copy(mode = OpeningMode.OPEN_24_HOURS)
            }
        )

        val templates = recommendedShiftTemplates(location)

        assertEquals(setOf(ShiftKind.SETUP, ShiftKind.MIDDLE, ShiftKind.NIGHT), templates.map { it.kind }.toSet())
        assertEquals(setOf("06:00-14:00", "14:00-22:00", "22:00-06:00"), templates.map { "${it.start}-${it.end}" }.toSet())
        assertTrue(templates.all { it.locationId == location.id && it.enabledWeekdays == (1..7).toSet() })
    }

    @Test
    fun analyticsShowsHoursWorkedAtAnotherLocationSeparately() {
        val centre = RestaurantLocation(id = "centre", name = "Centrum")
        val airport = RestaurantLocation(id = "airport", name = "Airport")
        val centreDay = ShiftTemplate(
            id = "centre-day",
            name = "Dag Centrum",
            kind = ShiftKind.DAY,
            start = "09:00",
            end = "17:00",
            locationId = centre.id
        )
        val airportDay = centreDay.copy(
            id = "airport-day",
            name = "Dag Airport",
            locationId = airport.id
        )
        val employee = Employee(
            id = "shared",
            name = "Gedeeld",
            locationIds = setOf(centre.id, airport.id)
        )
        val state = AppState(
            year = 2026,
            month = 6,
            locations = listOf(centre, airport),
            activeLocationId = centre.id,
            employees = listOf(employee),
            shiftTemplates = listOf(centreDay, airportDay),
            assignments = listOf(
                Assignment(
                    employeeId = employee.id,
                    date = "2026-06-01",
                    shiftTemplateId = centreDay.id,
                    locationId = centre.id
                ),
                Assignment(
                    employeeId = employee.id,
                    date = "2026-06-02",
                    shiftTemplateId = airportDay.id,
                    locationId = airport.id
                )
            )
        )

        val stats = employeeMonthStats(state).single()

        assertEquals(8.0, stats.hours, 0.001)
        assertEquals(8.0, stats.otherLocationHours, 0.001)
    }

    @Test
    fun daypartRequirementRaisesCoverageOnlyInsideItsWindow() {
        val location = RestaurantLocation(
            id = "centre",
            openingHours = (1..7).map {
                OpeningHoursRule(weekday = it, open = "09:00", close = "17:00")
            },
            enforceOpeningCoverage = true,
            minimumManagersWhileOpen = 1
        )
        val state = AppState(
            year = 2026,
            month = 6,
            locations = listOf(location),
            activeLocationId = location.id,
            staffingRequirements = listOf(
                StaffingRequirement(
                    locationId = location.id,
                    name = "Lunchpiek",
                    start = "11:00",
                    end = "14:00",
                    minimumManagers = 3
                )
            )
        )

        val points = coveragePoints(state, YearMonth.of(2026, 6))
        val date = LocalDate.of(2026, 6, 1)
        val beforePeak = points.firstOrNull { it.instant == LocalDateTime.of(date, java.time.LocalTime.of(10, 0)) }
        val duringPeak = points.firstOrNull { it.instant == LocalDateTime.of(date, java.time.LocalTime.NOON) }

        assertNotNull(beforePeak)
        assertNotNull(duringPeak)
        assertEquals(1, beforePeak!!.minimumManagers)
        assertEquals(3, duringPeak!!.minimumManagers)
        assertTrue("Lunchpiek" in duringPeak.labels)
    }

    @Test
    fun daypartStartingBeforeOpeningIsClippedInsteadOfMovedToTomorrow() {
        val location = RestaurantLocation(
            id = "centre",
            openingHours = listOf(OpeningHoursRule(weekday = 1, open = "09:00", close = "17:00"))
        )
        val state = AppState(
            locations = listOf(location),
            activeLocationId = location.id,
            staffingRequirements = listOf(
                StaffingRequirement(
                    locationId = location.id,
                    name = "Voorbereiding",
                    weekdays = setOf(1),
                    start = "08:00",
                    end = "12:00",
                    minimumManagers = 2
                )
            )
        )

        val windows = coverageWindows(state, LocalDate.of(2026, 6, 1))

        assertEquals(1, windows.size)
        assertEquals(LocalDateTime.of(2026, 6, 1, 9, 0), windows.single().start)
        assertEquals(LocalDateTime.of(2026, 6, 1, 12, 0), windows.single().end)
    }

    @Test
    fun activeLocationPlanningDoesNotDoubleBookASharedManager() {
        val active = RestaurantLocation(
            id = "centre",
            name = "Centrum",
            openingHours = (1..7).map { weekday ->
                if (weekday == 1) {
                    OpeningHoursRule(weekday = weekday, open = "09:00", close = "17:00")
                } else {
                    OpeningHoursRule(weekday = weekday, mode = OpeningMode.CLOSED)
                }
            },
            requireSetupDaily = true,
            requireMiddleOnBusyDays = false,
            requireCloseDaily = false
        )
        val other = RestaurantLocation(id = "airport", name = "Airport")
        val activeSetup = ShiftTemplate(
            id = "centre-setup",
            name = "Setup Centrum",
            kind = ShiftKind.SETUP,
            start = "09:00",
            end = "17:00",
            enabledWeekdays = setOf(1),
            locationId = active.id
        )
        val airportDay = ShiftTemplate(
            id = "airport-day",
            name = "Dag Airport",
            kind = ShiftKind.DAY,
            start = "09:00",
            end = "17:00",
            locationId = other.id
        )
        val shared = Employee(
            id = "shared",
            name = "Gedeeld",
            contractedDaysPerWeek = 0,
            contractedHoursPerWeek = 0.0,
            maxShiftsPerWeek = 7,
            locationIds = setOf(active.id, other.id)
        )
        val local = Employee(
            id = "local",
            name = "Lokaal",
            contractedDaysPerWeek = 0,
            contractedHoursPerWeek = 0.0,
            maxShiftsPerWeek = 7,
            locationIds = setOf(active.id)
        )
        val state = AppState(
            year = 2026,
            month = 6,
            locations = listOf(active, other),
            activeLocationId = active.id,
            employees = listOf(shared, local),
            shiftTemplates = listOf(activeSetup, airportDay),
            assignments = listOf(
                Assignment(
                    employeeId = shared.id,
                    date = "2026-06-01",
                    shiftTemplateId = airportDay.id,
                    source = "manual",
                    locationId = other.id
                )
            ),
            settings = PlannerSettings(
                minimumTwoDayOffBlocks = 0,
                preferredTwoDayOffBlocks = 0,
                preferTwoConsecutiveDaysOff = false
            )
        )

        val result = ScheduleEngine().generate(state)

        assertTrue(result.assignments.all { it.locationId == active.id })
        assertTrue(result.assignments.all { it.shiftTemplateId == activeSetup.id })
        assertFalse(result.assignments.any { it.employeeId == shared.id && it.date == "2026-06-01" })
        assertTrue(result.assignments.any { it.employeeId == local.id && it.date == "2026-06-01" })
    }

    @Test
    fun customTemplateCanBeChosenManuallyButIsNotAddedAutomatically() {
        val employee = Employee(
            id = "manager",
            name = "Manager",
            contractedDaysPerWeek = 5,
            contractedHoursPerWeek = 40.0,
            maxShiftsPerWeek = 5
        )
        val custom = ShiftTemplate(
            id = "custom-09-17",
            name = "Eigen 09-17",
            kind = ShiftKind.CUSTOM,
            start = "09:00",
            end = "17:00"
        )
        val state = AppState(
            year = 2026,
            month = 6,
            employees = listOf(employee),
            shiftTemplates = listOf(custom),
            settings = PlannerSettings(
                requireSetupDaily = false,
                requireMiddleOnBusyDays = false,
                requireCloseDaily = false,
                minimumTwoDayOffBlocks = 0,
                preferredTwoDayOffBlocks = 0,
                preferTwoConsecutiveDaysOff = false
            )
        )

        assertEquals(
            null,
            manualAssignmentBlockReason(state, employee.id, "2026-06-01", custom.id)
        )
        assertTrue(ScheduleEngine().generate(state).assignments.isEmpty())
    }

    @Test
    fun manuallyLockedFreeDayIsKeptByTheSolver() {
        val employee = Employee(
            id = "manager",
            name = "Manager",
            contractedDaysPerWeek = 5,
            contractedHoursPerWeek = 40.0,
            maxShiftsPerWeek = 5
        )
        val day = ShiftTemplate(
            id = "day",
            name = "Dag",
            kind = ShiftKind.DAY,
            start = "09:00",
            end = "17:00"
        )
        val state = AppState(
            year = 2026,
            month = 6,
            employees = listOf(employee),
            shiftTemplates = listOf(day),
            manualDaysOff = listOf(
                ManualDayOff(employee.id, "2026-06-01")
            ),
            settings = PlannerSettings(
                requireSetupDaily = false,
                requireMiddleOnBusyDays = false,
                requireCloseDaily = false,
                minimumTwoDayOffBlocks = 0,
                preferredTwoDayOffBlocks = 0,
                preferTwoConsecutiveDaysOff = false
            )
        )

        val result = ScheduleEngine().generate(state)

        assertFalse(result.assignments.any {
            it.employeeId == employee.id && it.date == "2026-06-01"
        })
    }

    @Test
    fun manualEditCannotDoubleBookManagerAcrossLocations() {
        val centre = RestaurantLocation(id = "centre", name = "Centrum")
        val airport = RestaurantLocation(id = "airport", name = "Airport")
        val centreShift = ShiftTemplate(
            id = "centre-day",
            name = "Dag Centrum",
            kind = ShiftKind.DAY,
            start = "09:00",
            end = "17:00",
            locationId = centre.id
        )
        val airportShift = centreShift.copy(
            id = "airport-day",
            name = "Dag Airport",
            locationId = airport.id
        )
        val employee = Employee(
            id = "shared",
            name = "Gedeeld",
            locationIds = setOf(centre.id, airport.id)
        )
        val state = AppState(
            year = 2026,
            month = 6,
            locations = listOf(centre, airport),
            activeLocationId = centre.id,
            employees = listOf(employee),
            shiftTemplates = listOf(centreShift, airportShift),
            assignments = listOf(
                Assignment(
                    employeeId = employee.id,
                    date = "2026-06-01",
                    shiftTemplateId = airportShift.id,
                    source = "manual",
                    locationId = airport.id
                )
            )
        )

        val reason = manualAssignmentBlockReason(
            state,
            employee.id,
            "2026-06-01",
            centreShift.id
        )

        assertNotNull(reason)
        assertTrue(reason!!.contains("al ingepland"))
        assertTrue(reason.contains("Airport"))
    }

    @Test
    fun automaticRepairMovesAShiftWhenDailyRestIsTooShort() {
        val close = ShiftTemplate(
            id = "close",
            name = "Sluit",
            kind = ShiftKind.CLOSE,
            start = "16:00",
            end = "00:00",
            enabledWeekdays = setOf(1)
        )
        val setup = ShiftTemplate(
            id = "setup",
            name = "Setup",
            kind = ShiftKind.SETUP,
            start = "09:00",
            end = "17:00",
            enabledWeekdays = setOf(2)
        )
        val first = Employee(
            id = "first",
            name = "Eerste",
            contractedDaysPerWeek = 0,
            contractedHoursPerWeek = 0.0,
            maxShiftsPerWeek = 7
        )
        val second = first.copy(id = "second", name = "Tweede")
        val state = AppState(
            year = 2026,
            month = 6,
            employees = listOf(first, second),
            shiftTemplates = listOf(close, setup),
            assignments = listOf(
                Assignment(
                    employeeId = first.id,
                    date = "2026-06-01",
                    shiftTemplateId = close.id,
                    source = "generated"
                ),
                Assignment(
                    employeeId = first.id,
                    date = "2026-06-02",
                    shiftTemplateId = setup.id,
                    source = "manual"
                )
            ),
            settings = PlannerSettings(
                requireSetupDaily = false,
                requireMiddleOnBusyDays = false,
                requireCloseDaily = false,
                allowOneReducedDailyRestPer7Days = false,
                minimumTwoDayOffBlocks = 0,
                preferredTwoDayOffBlocks = 0,
                preferTwoConsecutiveDaysOff = false
            )
        )

        val proposal = RosterRepairPlanner().propose(state)

        assertNotNull(proposal)
        assertTrue(proposal!!.errorsAfter < proposal.errorsBefore)
        assertTrue(proposal.assignments.any {
            it.employeeId == second.id &&
                it.date == "2026-06-01" &&
                it.shiftTemplateId == close.id
        })
        assertTrue(proposal.assignments.any {
            it.employeeId == first.id &&
                it.date == "2026-06-02" &&
                it.shiftTemplateId == setup.id &&
                it.source == "manual"
        })
        assertFalse(proposal.assignments.any {
            it.employeeId == first.id && it.date == "2026-06-01"
        })
    }
}
