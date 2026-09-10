package nl.roosterandroid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmployeeStateOpsTest {

    @Test
    fun referencedEmployeeIsArchivedInsteadOfDeleted() {
        val employee =
            Employee(name = "Daniel")

        val historical =
            Assignment(
                employeeId = employee.id,
                date = "2026-08-03",
                shiftTemplateId = "day"
            )

        val state =
            AppState(
                employees = listOf(employee),
                assignmentHistory =
                    listOf(historical)
            )

        val result =
            state.removeEmployeeSafely(
                employee.id
            )

        assertNotNull(result)
        assertTrue(result!!.deactivated)

        val remaining =
            result.state.employees.single()

        assertFalse(remaining.active)

        assertEquals(
            historical,
            result.state
                .assignmentHistory
                .single()
        )
    }

    @Test
    fun unusedEmployeeIsRemovedWithPersonalRules() {
        val employee =
            Employee(name = "Tijdelijk")

        val state =
            AppState(
                employees = listOf(employee),
                availability =
                    listOf(
                        Availability(
                            employeeId =
                                employee.id,
                            date =
                                "2026-09-10"
                        )
                    ),
                weeklyAvailability =
                    listOf(
                        WeeklyAvailability(
                            employeeId =
                                employee.id,
                            weekday = 1
                        )
                    )
            )

        val result =
            state.removeEmployeeSafely(
                employee.id
            )

        assertNotNull(result)
        assertFalse(result!!.deactivated)
        assertTrue(
            result.state.employees.isEmpty()
        )
        assertTrue(
            result.state.availability.isEmpty()
        )
        assertTrue(
            result.state
                .weeklyAvailability
                .isEmpty()
        )
    }

    @Test
    fun swapHistoryAlsoProtectsEmployee() {
        val first =
            Employee(name = "Eerste")

        val second =
            Employee(name = "Tweede")

        val state =
            AppState(
                employees =
                    listOf(first, second),
                swapHistory =
                    listOf(
                        ShiftSwapRecord(
                            firstAssignmentId = "a",
                            secondAssignmentId = "b",
                            firstEmployeeId = first.id,
                            secondEmployeeId = second.id,
                            firstDate = "2026-09-01",
                            secondDate = "2026-09-02"
                        )
                    )
            )

        val result =
            state.removeEmployeeSafely(
                first.id
            )

        assertNotNull(result)
        assertTrue(result!!.deactivated)
        assertFalse(
            result.state.employees
                .first { it.id == first.id }
                .active
        )
    }
}
