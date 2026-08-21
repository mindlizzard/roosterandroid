package nl.roosterandroid.app

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V010QualityTest {
    @Test
    fun clearMonthKeepsOtherMonthsAndLocations() {
        fun assignment(id: String, date: String, locationId: String) = Assignment(
            id = id,
            employeeId = "manager",
            date = date,
            shiftTemplateId = "day",
            locationId = locationId
        )
        fun request(id: String, date: String, locationId: String) = ReplacementRequest(
            id = id,
            locationId = locationId,
            date = date,
            shiftTemplateId = "day",
            originalEmployeeId = "manager",
            absenceId = "absence"
        )
        val state = AppState(
            year = 2026,
            month = 6,
            locations = listOf(
                RestaurantLocation(id = "active"),
                RestaurantLocation(id = "other")
            ),
            activeLocationId = "active",
            assignments = listOf(
                assignment("active-june", "2026-06-02", "active"),
                assignment("active-july", "2026-07-02", "active"),
                assignment("other-june", "2026-06-02", "other")
            ),
            manualDaysOff = listOf(
                ManualDayOff("manager", "2026-06-03", "active"),
                ManualDayOff("manager", "2026-07-03", "active"),
                ManualDayOff("manager", "2026-06-03", "other")
            ),
            replacementRequests = listOf(
                request("active-june", "2026-06-04", "active"),
                request("active-july", "2026-07-04", "active"),
                request("other-june", "2026-06-04", "other")
            )
        )

        val cleared = clearRosterMonth(state)

        assertEquals(setOf("active-july", "other-june"), cleared.assignments.map { it.id }.toSet())
        assertEquals(2, cleared.manualDaysOff.size)
        assertEquals(
            ReplacementStatus.CANCELLED,
            cleared.replacementRequests.first { it.id == "active-june" }.status
        )
        assertEquals(
            ReplacementStatus.OPEN,
            cleared.replacementRequests.first { it.id == "active-july" }.status
        )
        assertEquals(
            ReplacementStatus.OPEN,
            cleared.replacementRequests.first { it.id == "other-june" }.status
        )
    }

    @Test
    fun normalizationConnectsLegacyEmployeeToActiveCustomLocation() {
        val active = RestaurantLocation(id = "active", name = "Actief")
        val other = RestaurantLocation(id = "other", name = "Anders")
        val raw = AppState(
            locations = listOf(other, active),
            activeLocationId = active.id,
            employees = listOf(Employee(id = "manager", name = "Manager"))
        )

        val normalized = normalizeAppState(raw)

        assertEquals(setOf(active.id), normalized.employees.single().locationIds)
        assertTrue(normalized.employees.single().worksAt(active.id))
    }

    @Test
    fun validatorReportsInvalidTemplateTimeWithoutCrashing() {
        val employee = Employee(id = "manager", name = "Manager")
        val template = ShiftTemplate(
            id = "invalid",
            name = "Ongeldig",
            kind = ShiftKind.DAY,
            start = "geen-tijd",
            end = "17:00"
        )
        val state = AppState(
            employees = listOf(employee),
            shiftTemplates = listOf(template),
            assignments = listOf(
                Assignment(
                    employeeId = employee.id,
                    date = "2026-08-03",
                    shiftTemplateId = template.id
                )
            )
        )

        val violations = AtwValidator().validate(state)

        assertTrue(violations.any { it.rule == "Ongeldige roosterdata" })
    }

    @Test
    fun matrixIndexUsesLatestCellDataAndKeepsLocationsSeparate() {
        val month = YearMonth.of(2026, 6)
        val employee = Employee(
            id = "manager",
            name = "Manager",
            locationIds = setOf("active", "other")
        )
        val old = Assignment(
            id = "old",
            employeeId = employee.id,
            date = "2026-06-02",
            shiftTemplateId = "old-template",
            locationId = "active"
        )
        val latest = old.copy(id = "latest", shiftTemplateId = "latest-template")
        val elsewhere = old.copy(id = "elsewhere", locationId = "other")
        val state = AppState(
            year = 2026,
            month = 6,
            locations = listOf(
                RestaurantLocation(id = "active"),
                RestaurantLocation(id = "other")
            ),
            activeLocationId = "active",
            employees = listOf(employee),
            assignments = listOf(old, latest, elsewhere),
            availability = listOf(
                Availability(employee.id, "2026-06-02", available = false),
                Availability(employee.id, "2026-06-02", available = true)
            ),
            absences = listOf(
                Absence(
                    employeeId = employee.id,
                    startDate = "2026-05-31",
                    endDate = "2026-06-02",
                    type = AbsenceType.SICK
                )
            ),
            manualDaysOff = listOf(ManualDayOff(employee.id, "2026-06-02", "active")),
            dayNotes = listOf(DayNote(locationId = "active", date = "2026-06-02", text = "Bezoek"))
        )
        val violation = AtwValidator.Violation(
            AtwValidator.Severity.ERROR,
            employee.id,
            LocalDate.of(2026, 6, 2),
            "Test",
            "Test"
        )

        val index = rosterMatrixIndex(state, listOf(violation), month)
        val key = RosterCellSelection(employee.id, "2026-06-02")

        assertEquals(latest.id, index.assignmentsByCell[key]?.id)
        assertEquals(elsewhere.id, index.otherAssignmentsByCell[key]?.id)
        assertEquals(true, index.availabilityByCell[key]?.available)
        assertEquals(AbsenceType.SICK, index.absencesByCell[key]?.type)
        assertTrue(key in index.manualDaysOff)
        assertTrue(key in index.errorCells)
        assertEquals("Bezoek", index.notesByDate["2026-06-02"])
    }

    @Test
    fun coverageIgnoresMalformedTemplateInsteadOfCrashing() {
        val location = RestaurantLocation(
            id = "active",
            openingHours = (1..7).map {
                OpeningHoursRule(it, OpeningMode.OPEN, "09:00", "17:00")
            },
            enforceOpeningCoverage = true,
            minimumManagersWhileOpen = 1
        )
        val employee = Employee(
            id = "manager",
            name = "Manager",
            locationIds = setOf(location.id)
        )
        val template = ShiftTemplate(
            id = "invalid",
            name = "Ongeldig",
            kind = ShiftKind.DAY,
            start = "09:xx",
            end = "17:00",
            locationId = location.id
        )
        val state = AppState(
            year = 2026,
            month = 6,
            locations = listOf(location),
            activeLocationId = location.id,
            employees = listOf(employee),
            shiftTemplates = listOf(template),
            assignments = listOf(
                Assignment(
                    employeeId = employee.id,
                    date = "2026-06-01",
                    shiftTemplateId = template.id,
                    locationId = location.id
                )
            )
        )

        val coverage = dayPartCoverage(state, YearMonth.of(2026, 6))

        assertTrue(coverage.isNotEmpty())
        assertTrue(coverage.all { it.scheduled == 0 })
        assertEquals(0.0, shiftDurationHours(template), 0.0)
    }
}
