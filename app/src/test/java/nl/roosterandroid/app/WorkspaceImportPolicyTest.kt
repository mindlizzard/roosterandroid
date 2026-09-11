package nl.roosterandroid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceImportPolicyTest {

    @Test
    fun emptyWorkspaceIsRejected() {
        val workspace =
            RosterWorkspace(
                activeLocationId = "",
                locations = emptyList()
            )

        val problem =
            workspace.importValidationProblem()

        assertNotNull(problem)
        assertTrue(
            problem!!.contains(
                "geen vestigingen"
            )
        )
    }

    @Test
    fun newerSchemaIsRejected() {
        val location =
            LocationWorkspace(
                name = "Delft"
            )

        val workspace =
            RosterWorkspace(
                schemaVersion =
                    SUPPORTED_WORKSPACE_SCHEMA + 1,
                activeLocationId =
                    location.id,
                locations =
                    listOf(location)
            )

        val problem =
            workspace.importValidationProblem()

        assertNotNull(problem)
        assertTrue(
            problem!!.contains(
                "ondersteunt maximaal"
            )
        )
    }

    @Test
    fun duplicateLocationIdsAreRejected() {
        val first =
            LocationWorkspace(
                id = "same-id",
                name = "Delft"
            )

        val second =
            LocationWorkspace(
                id = "same-id",
                name = "Delft Noord"
            )

        val workspace =
            RosterWorkspace(
                activeLocationId =
                    first.id,
                locations =
                    listOf(
                        first,
                        second
                    )
            )

        val problem =
            workspace.importValidationProblem()

        assertNotNull(problem)
        assertTrue(
            problem!!.contains(
                "dubbele vestiging"
            )
        )
    }

    @Test
    fun invalidActiveLocationAndBlankNameAreRepaired() {
        val location =
            LocationWorkspace(
                id = "location-1",
                name = "   ",
                state =
                    AppState(
                        settings =
                            PlannerSettings(
                                locationName =
                                    "Verkeerde naam"
                            )
                    )
            )

        val workspace =
            RosterWorkspace(
                activeLocationId =
                    "bestaat-niet",
                locations =
                    listOf(location)
            )

        val prepared =
            workspace.preparedForImport()

        assertEquals(
            "location-1",
            prepared.activeLocationId
        )

        assertEquals(
            "Vestiging 1",
            prepared.locations.single().name
        )

        assertEquals(
            "Vestiging 1",
            prepared.locations
                .single()
                .state
                .settings
                .locationName
        )

        assertEquals(
            SUPPORTED_WORKSPACE_SCHEMA,
            prepared.schemaVersion
        )
    }

    @Test
    fun duplicateEmployeeIdsAreRejected() {
        val employees =
            listOf(
                Employee(
                    id = "same-employee",
                    name = "Kevin"
                ),
                Employee(
                    id = "same-employee",
                    name = "Daniel"
                )
            )

        val location =
            LocationWorkspace(
                name = "Delft",
                state =
                    AppState(
                        employees = employees
                    )
            )

        val workspace =
            RosterWorkspace(
                activeLocationId =
                    location.id,
                locations =
                    listOf(location)
            )

        val problem =
            workspace.importValidationProblem()

        assertNotNull(problem)
        assertTrue(
            problem!!.contains(
                "dubbele medewerker"
            )
        )
    }
}
