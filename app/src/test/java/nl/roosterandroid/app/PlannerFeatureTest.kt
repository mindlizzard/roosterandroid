package nl.roosterandroid.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerFeatureTest {
    @Test
    fun vacationBlocksAutomaticScheduling() {
        val a = Employee(name = "A")
        val b = Employee(name = "B")
        val state = AppState(
            year = 2026,
            month = 8,
            employees = listOf(a, b),
            absences = listOf(
                Absence(employeeId = a.id, startDate = "2026-08-03", endDate = "2026-08-03", type = AbsenceType.VACATION)
            )
        )
        val result = ScheduleEngine().generate(state)
        assertFalse(result.assignments.any { it.employeeId == a.id && it.date == "2026-08-03" })
    }

    @Test
    fun recurringWeekCountCanForceResponsiblePersonOntoDay() {
        val a = Employee(name = "Jan")
        val b = Employee(name = "B")
        val state = AppState(
            year = 2026,
            month = 8,
            employees = listOf(a, b),
            responsibilities = listOf(
                ResponsibilityRule(employeeId = a.id, type = ResponsibilityType.WEEK_COUNT, recurrence = RecurrenceType.WEEKLY, weekday = 1, ensureScheduled = true)
            )
        )
        val result = ScheduleEngine().generate(state)
        assertTrue(result.assignments.any { it.employeeId == a.id && it.date == "2026-08-03" })
    }

    @Test
    fun secondReducedDailyRestInsideSevenDaysIsError() {
        val e = Employee(name = "Test", maxShiftsPerWeek = 7)
        val late = ShiftTemplate(name = "Laat", kind = ShiftKind.CUSTOM, start = "16:00", end = "00:00")
        val early = ShiftTemplate(name = "Vroeg", kind = ShiftKind.CUSTOM, start = "08:00", end = "16:00")
        val state = AppState(
            employees = listOf(e),
            shiftTemplates = listOf(late, early),
            settings = PlannerSettings(allowOneReducedDailyRestPer7Days = true),
            assignments = listOf(
                Assignment(employeeId = e.id, date = "2026-08-03", shiftTemplateId = late.id),
                Assignment(employeeId = e.id, date = "2026-08-04", shiftTemplateId = early.id),
                Assignment(employeeId = e.id, date = "2026-08-05", shiftTemplateId = late.id),
                Assignment(employeeId = e.id, date = "2026-08-06", shiftTemplateId = early.id)
            )
        )
        val violations = AtwValidator().validate(state)
        assertTrue(violations.count { it.rule == "Dagelijkse rust" && it.severity == AtwValidator.Severity.ERROR } >= 1)
    }
}
