package nl.roosterandroid.app

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V08FeaturesTest {
    private val locationId = "test-location"

    private fun leanSettings() = PlannerSettings(
        requireSetupDaily = false,
        requireMiddleOnBusyDays = false,
        requireCloseDaily = false,
        minimumTwoDayOffBlocks = 0,
        preferredTwoDayOffBlocks = 0,
        preferTwoConsecutiveDaysOff = false
    )

    private fun openingRules(
        openWeekdays: Set<Int> = (1..7).toSet(),
        mode: OpeningMode = OpeningMode.OPEN
    ) = (1..7).map { weekday ->
        if (weekday in openWeekdays) {
            OpeningHoursRule(weekday, mode, "09:00", "17:00")
        } else {
            OpeningHoursRule(weekday, OpeningMode.CLOSED, "09:00", "17:00")
        }
    }

    private fun manager(id: String = "manager") = Employee(
        id = id,
        name = id,
        contractedDaysPerWeek = 0,
        contractedHoursPerWeek = 0.0,
        maxShiftsPerWeek = 7,
        locationIds = setOf(locationId)
    )

    @Test
    fun specialOpeningHoursOverrideWeeklyHoursAndClosure() {
        val monday = LocalDate.of(2026, 6, 1)
        val location = RestaurantLocation(
            id = locationId,
            openingHours = openingRules(setOf(1)),
            enforceOpeningCoverage = true,
            minimumManagersWhileOpen = 1
        )
        val closed = AppState(
            locations = listOf(location),
            activeLocationId = locationId,
            specialOpeningHours = listOf(
                SpecialOpeningHours(
                    locationId = locationId,
                    date = monday.toString(),
                    mode = OpeningMode.CLOSED
                )
            )
        )

        assertNull(openingBounds(closed, monday))
        assertTrue(coverageWindows(closed, monday).isEmpty())

        val extended = closed.copy(
            specialOpeningHours = listOf(
                SpecialOpeningHours(
                    locationId = locationId,
                    date = monday.toString(),
                    mode = OpeningMode.OPEN,
                    open = "10:00",
                    close = "22:00"
                )
            )
        )

        assertEquals(
            LocalDateTime.of(2026, 6, 1, 10, 0) to
                LocalDateTime.of(2026, 6, 1, 22, 0),
            openingBounds(extended, monday)
        )
        assertEquals(OpeningMode.OPEN, effectiveOpeningMode(extended, monday))
    }

    @Test
    fun plannerUsesSpecialTwentyFourHourModeForNightCoverage() {
        val location = RestaurantLocation(
            id = locationId,
            openingHours = openingRules(emptySet()),
            requireSetupDaily = false,
            requireMiddleOnBusyDays = false,
            requireCloseDaily = true
        )
        val night = ShiftTemplate(
            id = "night",
            name = "Nacht",
            kind = ShiftKind.NIGHT,
            start = "22:00",
            end = "06:00",
            enabledWeekdays = setOf(1),
            locationId = locationId
        )
        val close = ShiftTemplate(
            id = "close",
            name = "Sluit",
            kind = ShiftKind.CLOSE,
            start = "14:00",
            end = "22:00",
            enabledWeekdays = setOf(1),
            locationId = locationId
        )
        val state = AppState(
            year = 2026,
            month = 6,
            locations = listOf(location),
            activeLocationId = locationId,
            employees = listOf(manager()),
            shiftTemplates = listOf(night, close),
            specialOpeningHours = listOf(
                SpecialOpeningHours(
                    locationId = locationId,
                    date = "2026-06-01",
                    mode = OpeningMode.OPEN_24_HOURS
                )
            ),
            settings = leanSettings()
        )

        val result = ScheduleEngine().generate(state)

        assertEquals(1, result.assignments.size)
        assertEquals(night.id, result.assignments.single().shiftTemplateId)
        assertEquals("2026-06-01", result.assignments.single().date)
    }

    @Test
    fun specialClosureSuppressesOrdinaryRequiredShift() {
        val location = RestaurantLocation(
            id = locationId,
            openingHours = openingRules(setOf(1)),
            requireSetupDaily = true,
            requireMiddleOnBusyDays = false,
            requireCloseDaily = false
        )
        val setup = ShiftTemplate(
            id = "setup",
            name = "Setup",
            kind = ShiftKind.SETUP,
            start = "09:00",
            end = "17:00",
            enabledWeekdays = setOf(1),
            locationId = locationId
        )
        val state = AppState(
            year = 2026,
            month = 6,
            locations = listOf(location),
            activeLocationId = locationId,
            employees = listOf(manager()),
            shiftTemplates = listOf(setup),
            specialOpeningHours = listOf(
                SpecialOpeningHours(
                    locationId = locationId,
                    date = "2026-06-01",
                    mode = OpeningMode.CLOSED
                )
            ),
            settings = leanSettings()
        )

        val result = ScheduleEngine().generate(state)

        assertFalse(result.assignments.any { it.date == "2026-06-01" })
        assertTrue(result.assignments.any { it.date == "2026-06-08" })
    }

    @Test
    fun lockModesKeepFixedMoveNecessaryPreferenceAndKeepFeasiblePreference() {
        val location = RestaurantLocation(
            id = locationId,
            openingHours = openingRules(setOf(1)),
            requireSetupDaily = true,
            requireMiddleOnBusyDays = false,
            requireCloseDaily = false
        )
        val setup = ShiftTemplate(
            id = "setup",
            name = "Setup",
            kind = ShiftKind.SETUP,
            start = "09:00",
            end = "17:00",
            enabledWeekdays = setOf(1),
            locationId = locationId
        )
        val day = ShiftTemplate(
            id = "day",
            name = "Dag",
            kind = ShiftKind.DAY,
            start = "09:00",
            end = "17:00",
            enabledWeekdays = setOf(1),
            locationId = locationId
        )
        val base = AppState(
            year = 2026,
            month = 6,
            locations = listOf(location),
            activeLocationId = locationId,
            employees = listOf(manager()),
            shiftTemplates = listOf(setup, day),
            settings = leanSettings()
        )

        val fixed = Assignment(
            id = "fixed-day",
            employeeId = "manager",
            date = "2026-06-01",
            shiftTemplateId = day.id,
            source = "manual",
            locationId = locationId,
            lockMode = AssignmentLockMode.FIXED
        )
        val fixedResult = ScheduleEngine().generate(base.copy(assignments = listOf(fixed)))
        assertTrue(fixedResult.assignments.any { it.id == fixed.id })
        assertTrue(fixedResult.unfilled.any { it.contains("2026-06-01") })

        val preferredDay = fixed.copy(id = "preferred-day", lockMode = AssignmentLockMode.PREFERRED)
        val movedResult = ScheduleEngine().generate(base.copy(assignments = listOf(preferredDay)))
        assertFalse(movedResult.assignments.any { it.id == preferredDay.id })
        assertTrue(movedResult.assignments.any {
            it.date == "2026-06-01" && it.shiftTemplateId == setup.id
        })

        val preferredSetup = fixed.copy(
            id = "preferred-setup",
            shiftTemplateId = setup.id,
            lockMode = AssignmentLockMode.PREFERRED
        )
        val keptResult = ScheduleEngine().generate(base.copy(assignments = listOf(preferredSetup)))
        assertTrue(keptResult.assignments.any { it.id == preferredSetup.id })
    }

    @Test
    fun legacyAssignmentsReceiveSafeEffectiveLockModes() {
        val manual = Assignment(
            employeeId = "m",
            date = "2026-06-01",
            shiftTemplateId = "day",
            source = "manual"
        )
        val generated = manual.copy(source = "generated")
        val explicitAuto = manual.copy(lockMode = AssignmentLockMode.AUTO)

        assertEquals(AssignmentLockMode.FIXED, manual.effectiveLockMode())
        assertEquals(AssignmentLockMode.AUTO, generated.effectiveLockMode())
        assertEquals(AssignmentLockMode.AUTO, explicitAuto.effectiveLockMode())
    }

    @Test
    fun solverCanBeCancelledBeforeSearchWorkStarts() {
        val location = RestaurantLocation(
            id = locationId,
            openingHours = openingRules(setOf(1)),
            requireSetupDaily = true,
            requireMiddleOnBusyDays = false,
            requireCloseDaily = false
        )
        val setup = ShiftTemplate(
            id = "setup",
            name = "Setup",
            kind = ShiftKind.SETUP,
            start = "09:00",
            end = "17:00",
            locationId = locationId
        )
        val state = AppState(
            year = 2026,
            month = 6,
            locations = listOf(location),
            activeLocationId = locationId,
            employees = listOf(manager()),
            shiftTemplates = listOf(setup),
            settings = leanSettings()
        )
        var cancellationChecks = 0

        val result = ScheduleEngine().generate(state, searchEffort = 4) {
            cancellationChecks += 1
            true
        }

        assertTrue(cancellationChecks > 0)
        assertTrue(result.assignments.isEmpty())
    }

    @Test
    fun weekCopyReplacesOnlyActiveLocationAndCopiesFreeDays() {
        val otherLocationId = "other-location"
        val location = RestaurantLocation(
            id = locationId,
            openingHours = openingRules(),
            requireSetupDaily = false,
            requireMiddleOnBusyDays = false,
            requireCloseDaily = false
        )
        val otherLocation = RestaurantLocation(id = otherLocationId)
        val day = ShiftTemplate(
            id = "day",
            name = "Dag",
            kind = ShiftKind.DAY,
            start = "09:00",
            end = "17:00",
            locationId = locationId
        )
        val old = day.copy(id = "old", name = "Oud", kind = ShiftKind.CLOSE)
        val otherTemplate = day.copy(id = "other", locationId = otherLocationId)
        val first = manager("first")
        val second = Employee(
            id = "second",
            name = "second",
            locationIds = setOf(locationId, otherLocationId)
        )
        val source = Assignment(
            id = "source",
            employeeId = first.id,
            date = "2026-06-01",
            shiftTemplateId = day.id,
            locationId = locationId
        )
        val oldTarget = Assignment(
            id = "old-target",
            employeeId = first.id,
            date = "2026-06-08",
            shiftTemplateId = old.id,
            locationId = locationId
        )
        val elsewhere = Assignment(
            id = "elsewhere",
            employeeId = second.id,
            date = "2026-06-08",
            shiftTemplateId = otherTemplate.id,
            locationId = otherLocationId
        )
        val state = AppState(
            year = 2026,
            month = 6,
            locations = listOf(location, otherLocation),
            activeLocationId = locationId,
            employees = listOf(first, second),
            shiftTemplates = listOf(day, old, otherTemplate),
            assignments = listOf(source, oldTarget, elsewhere),
            manualDaysOff = listOf(
                ManualDayOff(first.id, "2026-06-02", locationId)
            ),
            settings = leanSettings()
        )

        val result = copyRosterWeek(
            state,
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 8),
            AssignmentLockMode.PREFERRED
        )

        assertFalse(result.sourceWasEmpty)
        assertEquals(1, result.copiedAssignments)
        assertEquals(1, result.copiedDaysOff)
        assertEquals(0, result.skippedAssignments)
        assertFalse(result.updatedState.assignments.any { it.id == oldTarget.id })
        assertTrue(result.updatedState.assignments.any { it.id == elsewhere.id })
        assertTrue(result.updatedState.assignments.any {
            it.employeeId == first.id &&
                it.date == "2026-06-08" &&
                it.shiftTemplateId == day.id &&
                it.lockMode == AssignmentLockMode.PREFERRED
        })
        assertTrue(result.updatedState.manualDaysOff.any {
            it.employeeId == first.id && it.date == "2026-06-09"
        })
    }

    @Test
    fun blockedWeekCopyRestoresOriginalTargetCell() {
        val location = RestaurantLocation(
            id = locationId,
            openingHours = openingRules(),
            requireSetupDaily = false,
            requireMiddleOnBusyDays = false,
            requireCloseDaily = false
        )
        val day = ShiftTemplate(
            id = "day",
            name = "Dag",
            kind = ShiftKind.DAY,
            start = "09:00",
            end = "17:00",
            locationId = locationId
        )
        val old = day.copy(id = "old", name = "Oud")
        val employee = manager()
        val source = Assignment(
            id = "source",
            employeeId = employee.id,
            date = "2026-06-01",
            shiftTemplateId = day.id,
            locationId = locationId
        )
        val originalTarget = Assignment(
            id = "target",
            employeeId = employee.id,
            date = "2026-06-08",
            shiftTemplateId = old.id,
            locationId = locationId
        )
        val state = AppState(
            year = 2026,
            month = 6,
            locations = listOf(location),
            activeLocationId = locationId,
            employees = listOf(employee),
            shiftTemplates = listOf(day, old),
            availability = listOf(
                Availability(employee.id, "2026-06-08", available = false)
            ),
            assignments = listOf(source, originalTarget),
            settings = leanSettings()
        )

        val result = copyRosterWeek(
            state,
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 8)
        )

        assertEquals(0, result.copiedAssignments)
        assertEquals(1, result.skippedAssignments)
        assertTrue(result.updatedState.assignments.any { it.id == originalTarget.id })
    }

    @Test
    fun managementCoverageAndSnapshotExposeOperationalShortages() {
        val location = RestaurantLocation(
            id = locationId,
            openingHours = openingRules(setOf(1)),
            enforceOpeningCoverage = true,
            minimumManagersWhileOpen = 2,
            requireSetupDaily = false,
            requireMiddleOnBusyDays = false,
            requireCloseDaily = false
        )
        val own = manager("own")
        val borrowed = manager("borrowed").copy(role = EmployeeRole.BORROWED)
        val day = ShiftTemplate(
            id = "day",
            name = "Dag",
            kind = ShiftKind.DAY,
            start = "09:00",
            end = "17:00",
            enabledWeekdays = setOf(1),
            locationId = locationId
        )
        val sickness = Absence(
            id = "sick",
            employeeId = own.id,
            startDate = "2026-06-01",
            endDate = "2026-06-02",
            type = AbsenceType.SICK
        )
        val state = AppState(
            year = 2026,
            month = 6,
            locations = listOf(location),
            activeLocationId = locationId,
            employees = listOf(own, borrowed),
            shiftTemplates = listOf(day),
            assignments = listOf(
                Assignment(
                    employeeId = borrowed.id,
                    date = "2026-06-01",
                    shiftTemplateId = day.id,
                    locationId = locationId
                )
            ),
            absences = listOf(sickness),
            replacementRequests = listOf(
                ReplacementRequest(
                    locationId = locationId,
                    date = "2026-06-01",
                    shiftTemplateId = day.id,
                    originalEmployeeId = own.id,
                    absenceId = sickness.id
                )
            ),
            dayDemands = listOf(
                DayDemand("2026-06-01", guestCount = 125, locationId = locationId)
            ),
            settings = leanSettings()
        )

        val coverage = dayPartCoverage(state, YearMonth.of(2026, 6))
        val firstMorning = coverage.first {
            it.date == LocalDate.of(2026, 6, 1) && it.dayPart == ManagementDayPart.MORNING
        }
        val snapshot = managementSnapshot(state, listOf("open 1", "open 2"), emptyList())

        assertEquals(2, firstMorning.required)
        assertEquals(1, firstMorning.scheduled)
        assertEquals(1, firstMorning.shortage)
        assertEquals(1, snapshot.openReplacements)
        assertEquals(1, snapshot.activeSicknessCases)
        assertEquals(1, snapshot.borrowedShifts)
        assertEquals(125, snapshot.forecastGuests)
        assertEquals(2, snapshot.openPlannerPoints)
        assertTrue(snapshot.understaffedDayParts > 0)
    }

    @Test
    fun legacyJsonWithoutV08FieldsStillDecodes() {
        val raw = """
            {
              "year": 2026,
              "month": 6,
              "assignments": [
                {
                  "id": "legacy",
                  "employeeId": "manager",
                  "date": "2026-06-01",
                  "shiftTemplateId": "day",
                  "source": "manual"
                }
              ]
            }
        """.trimIndent()

        val decoded = Json.decodeFromString<AppState>(raw)

        assertTrue(decoded.specialOpeningHours.isEmpty())
        assertNull(decoded.assignments.single().lockMode)
        assertEquals(AssignmentLockMode.FIXED, decoded.assignments.single().effectiveLockMode())
    }

    @Test
    fun rosterPresentationUsesFullBoldLabelsAndNormalClockText() {
        val setup = ShiftTemplate("setup", "Setup HAVI", ShiftKind.SETUP, "08:45", "16:45")
        val middle = ShiftTemplate("middle", "Tussen", ShiftKind.MIDDLE, "09:00", "17:00")
        val close = ShiftTemplate("close", "Sluit", ShiftKind.CLOSE, "16:00", "00:00")

        assertEquals(
            "SETUP HAVI 🔒",
            rosterShiftTitle(
                setup,
                Assignment("a", "m", "2026-06-01", setup.id, lockMode = AssignmentLockMode.FIXED)
            )
        )
        assertEquals(
            "TUSSEN 📌",
            rosterShiftTitle(
                middle,
                Assignment("b", "m", "2026-06-01", middle.id, lockMode = AssignmentLockMode.PREFERRED)
            )
        )
        assertEquals(
            "SLUIT",
            rosterShiftTitle(
                close,
                Assignment("c", "m", "2026-06-01", close.id, lockMode = AssignmentLockMode.AUTO)
            )
        )
        assertEquals("09:00-17:00", rosterShiftTime(middle))
        assertEquals(
            "09:00-17:00",
            rosterShiftTime(middle.copy(start = "09:00:00", end = "17:00:00"))
        )
    }
}
