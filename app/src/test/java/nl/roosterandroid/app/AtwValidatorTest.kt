package nl.roosterandroid.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtwValidatorTest {
    @Test
    fun flagsShiftLongerThanTwelveHours() {
        val employee = Employee(name = "Test")
        val template = ShiftTemplate(name = "Lang", kind = ShiftKind.CUSTOM, start = "08:00", end = "21:00")
        val state = AppState(
            employees = listOf(employee),
            shiftTemplates = listOf(template),
            assignments = listOf(Assignment(employeeId = employee.id, date = "2026-08-03", shiftTemplateId = template.id))
        )
        val violations = AtwValidator().validate(state)
        assertTrue(violations.any { it.rule == "ATW max dienst" && it.severity == AtwValidator.Severity.ERROR })
    }

    @Test
    fun acceptsTwoEightHourShiftsWithEnoughRest() {
        val employee = Employee(name = "Test")
        val template = ShiftTemplate(name = "Dag", kind = ShiftKind.DAY, start = "09:00", end = "17:00")
        val state = AppState(
            employees = listOf(employee),
            shiftTemplates = listOf(template),
            assignments = listOf(
                Assignment(employeeId = employee.id, date = "2026-08-03", shiftTemplateId = template.id),
                Assignment(employeeId = employee.id, date = "2026-08-04", shiftTemplateId = template.id)
            )
        )
        val violations = AtwValidator().validate(state)
        assertFalse(violations.any { it.rule == "Dagelijkse rust" && it.severity == AtwValidator.Severity.ERROR })
    }
}
