package nl.roosterandroid.desktop

import nl.roosterandroid.app.AppState
import nl.roosterandroid.app.Availability
import nl.roosterandroid.app.Employee
import nl.roosterandroid.app.ShiftKind
import nl.roosterandroid.app.ShiftTemplate
import nl.roosterandroid.app.WeeklyAvailability
import nl.roosterandroid.app.manualOperationalWarnings
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedManualOverridePolicyTest {

    @Test
    fun specificDateRuleOverridesWeeklyRuleCompletely() {
        val employee =
            Employee(name = "Daniel")

        val template =
            ShiftTemplate(
                id = "day",
                name = "Dag",
                kind = ShiftKind.DAY,
                start = "09:00",
                end = "17:00",
                enabledWeekdays = setOf(4)
            )

        val state =
            AppState(
                employees =
                    listOf(employee),
                shiftTemplates =
                    listOf(template),
                weeklyAvailability =
                    listOf(
                        WeeklyAvailability(
                            employeeId =
                                employee.id,
                            weekday = 4,
                            available = false,
                            earliestStart =
                                "12:00",
                            latestEnd =
                                "14:00",
                            fixedShiftKind =
                                ShiftKind.CLOSE
                        )
                    ),
                availability =
                    listOf(
                        Availability(
                            employeeId =
                                employee.id,
                            date =
                                "2026-09-10",
                            available = true
                        )
                    )
            )

        val warnings =
            state.manualOperationalWarnings(
                employee.id,
                "2026-09-10",
                template.id
            )

        assertTrue(
            warnings.isEmpty()
        )
    }

    @Test
    fun operationalConflictsAreWarnings() {
        val employee =
            Employee(name = "Daniel")

        val template =
            ShiftTemplate(
                id = "day",
                name = "Dag",
                kind = ShiftKind.DAY,
                start = "09:00",
                end = "17:00",
                enabledWeekdays = setOf(1)
            )

        val state =
            AppState(
                employees =
                    listOf(employee),
                shiftTemplates =
                    listOf(template),
                availability =
                    listOf(
                        Availability(
                            employeeId =
                                employee.id,
                            date =
                                "2026-09-10",
                            available = false,
                            earliestStart =
                                "10:00",
                            latestEnd =
                                "16:00",
                            fixedShiftKind =
                                ShiftKind.CLOSE
                        )
                    )
            )

        val warnings =
            state.manualOperationalWarnings(
                employee.id,
                "2026-09-10",
                template.id
            )

        assertTrue(
            warnings.any {
                it.contains(
                    "normaal niet actief"
                )
            }
        )

        assertTrue(
            warnings.any {
                it.contains(
                    "niet beschikbaar"
                )
            }
        )

        assertTrue(
            warnings.any {
                it.contains(
                    "vaste dienst"
                )
            }
        )

        assertTrue(
            warnings.any {
                it.contains(
                    "begint vóór"
                )
            }
        )

        assertTrue(
            warnings.any {
                it.contains(
                    "eindigt na"
                )
            }
        )
    }
}
