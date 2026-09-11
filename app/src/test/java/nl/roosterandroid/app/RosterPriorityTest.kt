package nl.roosterandroid.app

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RosterPriorityTest {

    @Test
    fun atwErrorGetsHighestPriority() {
        val first =
            Employee(
                id = "first",
                name = "Daniel",
                contractedHoursPerWeek =
                    0.0
            )

        val second =
            Employee(
                id = "second",
                name = "Kevin",
                contractedHoursPerWeek =
                    0.0
            )

        val state =
            AppState(
                year = 2026,
                month = 2,
                employees =
                    listOf(
                        first,
                        second
                    )
            )

        val violations =
            listOf(
                AtwValidator.Violation(
                    severity =
                        AtwValidator.Severity.ERROR,
                    employeeId =
                        second.id,
                    date =
                        LocalDate.parse(
                            "2026-02-10"
                        ),
                    rule =
                        "test",
                    message =
                        "testfout"
                )
            )

        val rows =
            state.rosterPriorityRows(
                violations
            )

        assertEquals(
            second.id,
            rows.first().employeeId
        )

        assertEquals(
            RosterPriorityLevel.CRITICAL,
            rows.first().level
        )

        assertTrue(
            rows.first()
                .priorityScore >= 100
        )
    }

    @Test
    fun atwWarningRaisesAttentionLevel() {
        val employee =
            Employee(
                id = "manager-1",
                name = "Daniel",
                contractedHoursPerWeek =
                    0.0
            )

        val state =
            AppState(
                year = 2026,
                month = 2,
                employees =
                    listOf(employee)
            )

        val violations =
            listOf(
                AtwValidator.Violation(
                    severity =
                        AtwValidator.Severity.WARNING,
                    employeeId =
                        employee.id,
                    date =
                        LocalDate.parse(
                            "2026-02-12"
                        ),
                    rule =
                        "rust",
                    message =
                        "Let op rusttijd"
                )
            )

        val row =
            state.rosterPriorityRows(
                violations
            ).single()

        assertEquals(
            1,
            row.atwWarnings
        )

        assertEquals(
            RosterPriorityLevel.MEDIUM,
            row.level
        )

        assertTrue(
            row.requiresAttention
        )
    }

    @Test
    fun weekendOverloadIsDetected() {
        val busy =
            Employee(
                id = "busy",
                name = "Daniel",
                contractedHoursPerWeek =
                    0.0
            )

        val quiet =
            Employee(
                id = "quiet",
                name = "Kevin",
                contractedHoursPerWeek =
                    0.0
            )

        val shift =
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
                    listOf(
                        busy,
                        quiet
                    ),
                shiftTemplates =
                    listOf(shift),
                assignments =
                    listOf(
                        "2026-02-01",
                        "2026-02-08",
                        "2026-02-15",
                        "2026-02-22"
                    ).map { date ->
                        Assignment(
                            employeeId =
                                busy.id,
                            date = date,
                            shiftTemplateId =
                                shift.id
                        )
                    }
            )

        val rows =
            state.rosterPriorityRows(
                violations =
                    emptyList()
            )

        val busyRow =
            rows.first {
                it.employeeId ==
                    busy.id
            }

        assertEquals(
            4,
            busyRow.weekendsWorked
        )

        assertEquals(
            2.0,
            busyRow.teamAverageWeekends,
            0.001
        )

        assertEquals(
            2.0,
            busyRow.weekendOverload,
            0.001
        )

        assertTrue(
            busyRow.reasons.any {
                it.contains(
                    "teamgemiddelde"
                )
            }
        )
    }

    @Test
    fun largeContractGapGetsHighPriority() {
        val employee =
            Employee(
                id = "manager-1",
                name = "Daniel",
                contractedHoursPerWeek =
                    40.0
            )

        val state =
            AppState(
                year = 2026,
                month = 2,
                employees =
                    listOf(employee)
            )

        val row =
            state.rosterPriorityRows(
                violations =
                    emptyList()
            ).single()

        assertEquals(
            RosterPriorityLevel.HIGH,
            row.level
        )

        assertTrue(
            row.priorityScore >= 50
        )
    }
}
