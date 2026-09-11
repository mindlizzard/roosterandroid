package nl.roosterandroid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RosterQualityTest {

    @Test
    fun qualityRowShowsHoursAndShiftCounts() {
        val employee =
            Employee(
                id = "manager-1",
                name = "Daniel",
                contractedDaysPerWeek = 5,
                contractedHoursPerWeek = 40.0
            )

        val day =
            ShiftTemplate(
                id = "day",
                name = "Dag",
                kind = ShiftKind.DAY,
                start = "09:00",
                end = "17:00"
            )

        val state =
            AppState(
                year = 2026,
                month = 2,
                employees =
                    listOf(employee),
                shiftTemplates =
                    listOf(day),
                assignments =
                    listOf(
                        Assignment(
                            employeeId =
                                employee.id,
                            date =
                                "2026-02-02",
                            shiftTemplateId =
                                day.id,
                            source =
                                "solver-contract"
                        ),
                        Assignment(
                            employeeId =
                                employee.id,
                            date =
                                "2026-02-07",
                            shiftTemplateId =
                                day.id,
                            source =
                                "manual"
                        ),
                        Assignment(
                            employeeId =
                                employee.id,
                            date =
                                "2026-02-09",
                            shiftTemplateId =
                                day.id,
                            source =
                                "solver-contract"
                        ),
                        Assignment(
                            employeeId =
                                employee.id,
                            date =
                                "2026-02-10",
                            shiftTemplateId =
                                day.id,
                            source =
                                "solver-contract"
                        )
                    )
            )

        val row =
            state.rosterQualityRows(
                violations =
                    emptyList()
            ).single()

        assertEquals(
            32.0,
            row.plannedHours,
            0.001
        )

        assertEquals(
            160.0,
            row.targetHours,
            0.001
        )

        assertEquals(
            -128.0,
            row.hourDifference,
            0.001
        )

        assertEquals(
            4,
            row.shifts
        )

        assertEquals(
            1,
            row.weekendShifts
        )

        assertEquals(
            1,
            row.manualShifts
        )

        assertFalse(
            row.hoursOnTarget
        )

        assertTrue(
            row.issues.any {
                it.contains(
                    "onder contractdoel"
                )
            }
        )
    }

    @Test
    fun fortyHourWeekCanBeExactlyOnTarget() {
        val employee =
            Employee(
                id = "manager-1",
                name = "Daniel",
                contractedHoursPerWeek = 40.0
            )

        val day =
            ShiftTemplate(
                id = "day",
                name = "Dag",
                kind = ShiftKind.DAY,
                start = "09:00",
                end = "17:00"
            )

        val assignments =
            (2..23 step 7)
                .flatMap { monday ->
                    (0..4).map { offset ->
                        Assignment(
                            employeeId =
                                employee.id,
                            date =
                                "2026-02-" +
                                    (monday + offset)
                                        .toString()
                                        .padStart(
                                            2,
                                            '0'
                                        ),
                            shiftTemplateId =
                                day.id
                        )
                    }
                }

        val state =
            AppState(
                year = 2026,
                month = 2,
                employees =
                    listOf(employee),
                shiftTemplates =
                    listOf(day),
                assignments =
                    assignments
            )

        val row =
            state.rosterQualityRows(
                violations =
                    emptyList()
            ).single()

        assertEquals(
            160.0,
            row.plannedHours,
            0.001
        )

        assertEquals(
            0.0,
            row.hourDifference,
            0.001
        )

        assertTrue(
            row.hoursOnTarget
        )
    }

    @Test
    fun hostIsNotShownInManagerQualityOverview() {
        val host =
            Employee(
                name = "Host",
                role = EmployeeRole.HOST
            )

        val state =
            AppState(
                year = 2026,
                month = 2,
                employees =
                    listOf(host)
            )

        assertTrue(
            state.rosterQualityRows(
                violations =
                    emptyList()
            ).isEmpty()
        )
    }
}
