package nl.roosterandroid.app

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceModelTest {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun workspaceRoundTripPreservesLocations() {
        val first = LocationWorkspace(name = "Delft")
        val second = LocationWorkspace(name = "Delft Noord")

        val workspace = RosterWorkspace(
            activeLocationId = second.id,
            locations = listOf(first, second)
        )

        val decoded =
            json.decodeFromString<RosterWorkspace>(
                json.encodeToString(workspace)
            )

        assertEquals(2, decoded.locations.size)
        assertEquals(
            "Delft Noord",
            decoded.activeLocation().name
        )
    }

    @Test
    fun copyingLocationKeepsTeamButClearsDatedData() {
        val employee = Employee(name = "Daniel")

        val original = AppState(
            employees = listOf(employee),
            availability = listOf(
                Availability(
                    employeeId = employee.id,
                    date = "2026-09-10"
                )
            ),
            weeklyAvailability = listOf(
                WeeklyAvailability(
                    employeeId = employee.id,
                    weekday = 1
                )
            ),
            assignments = listOf(
                Assignment(
                    employeeId = employee.id,
                    date = "2026-09-10",
                    shiftTemplateId = "day"
                )
            ),
            responsibilities = listOf(
                ResponsibilityRule(
                    employeeId = employee.id,
                    type = ResponsibilityType.ADMIN,
                    recurrence = RecurrenceType.SPECIFIC_DATE,
                    date = "2026-09-10"
                ),
                ResponsibilityRule(
                    employeeId = employee.id,
                    type = ResponsibilityType.WEEK_COUNT,
                    recurrence = RecurrenceType.WEEKLY,
                    weekday = 1
                )
            ),
            settings = PlannerSettings(
                locationName = "Delft"
            )
        )

        val copied =
            original.copyForNewLocation("Delft Noord")

        assertEquals(
            "Delft Noord",
            copied.settings.locationName
        )
        assertEquals(listOf(employee), copied.employees)
        assertTrue(copied.assignments.isEmpty())
        assertTrue(copied.availability.isEmpty())
        assertEquals(1, copied.weeklyAvailability.size)

        assertEquals(
            1,
            copied.responsibilities.size
        )

        assertEquals(
            RecurrenceType.WEEKLY,
            copied.responsibilities.single().recurrence
        )
    }

    @Test
    fun invalidActiveLocationIsNormalized() {
        val location = LocationWorkspace(name = "Delft")

        val workspace = RosterWorkspace(
            activeLocationId = "bestaat-niet",
            locations = listOf(location)
        ).normalized()

        assertEquals(
            location.id,
            workspace.activeLocationId
        )
    }
}
