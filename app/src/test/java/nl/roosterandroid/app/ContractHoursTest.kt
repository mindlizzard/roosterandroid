package nl.roosterandroid.app

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ContractHoursTest {

    @Test
    fun normalShiftDurationIsCalculated() {
        val shift =
            ShiftTemplate(
                id = "day",
                name = "Dag",
                kind = ShiftKind.DAY,
                start = "09:00",
                end = "17:00"
            )

        assertEquals(
            8.0,
            shift.planningDurationHours(),
            0.001
        )
    }

    @Test
    fun overnightShiftDurationIsCalculated() {
        val shift =
            ShiftTemplate(
                id = "close",
                name = "Sluit",
                kind = ShiftKind.CLOSE,
                start = "22:00",
                end = "06:00"
            )

        assertEquals(
            8.0,
            shift.planningDurationHours(),
            0.001
        )
    }

    @Test
    fun expectedHoursScaleWithContractDays() {
        val employee =
            Employee(
                name = "Daniel",
                contractedDaysPerWeek = 5,
                contractedHoursPerWeek = 40.0
            )

        assertEquals(
            24.0,
            employee.expectedHoursForContractDays(
                3
            ),
            0.001
        )
    }

    @Test
    fun plannedHoursOnlyCountSelectedWeek() {
        val employee =
            Employee(
                id = "manager-1",
                name = "Daniel"
            )

        val day =
            ShiftTemplate(
                id = "day",
                name = "Dag",
                kind = ShiftKind.DAY,
                start = "09:00",
                end = "17:00"
            )

        val short =
            ShiftTemplate(
                id = "short",
                name = "Kort",
                kind = ShiftKind.DAY,
                start = "09:00",
                end = "15:00"
            )

        val state =
            AppState(
                employees =
                    listOf(employee),
                shiftTemplates =
                    listOf(
                        day,
                        short
                    )
            )

        val assignments =
            listOf(
                Assignment(
                    employeeId =
                        employee.id,
                    date =
                        "2026-09-07",
                    shiftTemplateId =
                        day.id
                ),
                Assignment(
                    employeeId =
                        employee.id,
                    date =
                        "2026-09-08",
                    shiftTemplateId =
                        short.id
                ),
                Assignment(
                    employeeId =
                        employee.id,
                    date =
                        "2026-09-20",
                    shiftTemplateId =
                        day.id
                )
            )

        assertEquals(
            14.0,
            state.plannedHoursFor(
                employeeId =
                    employee.id,
                assignments =
                    assignments,
                startDate =
                    LocalDate.parse(
                        "2026-09-07"
                    ),
                endDate =
                    LocalDate.parse(
                        "2026-09-13"
                    )
            ),
            0.001
        )
    }

    @Test
    fun projectedHoursIncludeCandidateShift() {
        val employee =
            Employee(
                id = "manager-1",
                name = "Daniel"
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
                employees =
                    listOf(employee),
                shiftTemplates =
                    listOf(day)
            )

        assertEquals(
            8.0,
            state.projectedHoursFor(
                employeeId =
                    employee.id,
                assignments =
                    emptyList(),
                startDate =
                    LocalDate.parse(
                        "2026-09-07"
                    ),
                endDate =
                    LocalDate.parse(
                        "2026-09-13"
                    ),
                extraTemplate =
                    day
            ),
            0.001
        )
    }
}
