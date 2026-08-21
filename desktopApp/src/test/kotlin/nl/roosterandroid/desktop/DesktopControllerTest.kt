package nl.roosterandroid.desktop

import nl.roosterandroid.app.AppState
import nl.roosterandroid.app.Assignment
import nl.roosterandroid.app.Employee
import nl.roosterandroid.app.PlannerSettings
import nl.roosterandroid.app.ShiftKind
import nl.roosterandroid.app.ShiftTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate

class DesktopControllerTest {
    @Test
    fun workspacePersistsMultipleLocations() {
        val directory = Files.createTempDirectory("roosterplanner-locations-")
        val storage = DesktopStorage(directory)
        val controller = DesktopController(storage)

        controller.addLocation("Delft Noord", copyCurrent = true)

        val reloaded = DesktopController(DesktopStorage(directory))
        assertEquals(2, reloaded.workspace.locations.size)
        assertEquals("Delft Noord", reloaded.activeLocation.name)
    }

    @Test
    fun autoFixReleasesOnlyConflictingManualLock() {
        val directory = Files.createTempDirectory("roosterplanner-autofix-")
        val employee = Employee(
            name = "Test",
            contractedDaysPerWeek = 0,
            contractedHoursPerWeek = 0.0,
            maxShiftsPerWeek = 7
        )
        val late = ShiftTemplate("late", "Laat", ShiftKind.CUSTOM, "16:00", "00:00")
        val early = ShiftTemplate("early", "Vroeg", ShiftKind.CUSTOM, "08:00", "16:00")
        val state = AppState(
            year = 2026,
            month = 8,
            employees = listOf(employee),
            shiftTemplates = listOf(late, early),
            settings = quietSettings(),
            assignments = listOf(
                Assignment(employeeId = employee.id, date = "2026-08-03", shiftTemplateId = late.id, source = "manual-test"),
                Assignment(employeeId = employee.id, date = "2026-08-04", shiftTemplateId = early.id, source = "manual-test")
            )
        )
        val storage = DesktopStorage(directory)
        storage.save(DesktopWorkspace.fromAppState(state))
        val controller = DesktopController(storage)

        val report = controller.autoFix()

        assertEquals(1, report.unlockedManualAssignments)
        assertEquals(0, report.errors)
        assertEquals(1, controller.state.assignments.size)
    }

    @Test
    fun sickReportFindsReplacementOnSameShift() {
        val directory = Files.createTempDirectory("roosterplanner-sick-")
        val sick = Employee(name = "Ziek", contractedDaysPerWeek = 0, contractedHoursPerWeek = 0.0)
        val replacement = Employee(name = "Vervanger", contractedDaysPerWeek = 0, contractedHoursPerWeek = 0.0)
        val day = ShiftTemplate("day", "Dag", ShiftKind.DAY, "09:00", "17:00")
        val state = AppState(
            year = 2026,
            month = 8,
            employees = listOf(sick, replacement),
            shiftTemplates = listOf(day),
            settings = quietSettings(),
            assignments = listOf(
                Assignment(employeeId = sick.id, date = "2026-08-03", shiftTemplateId = day.id, source = "manual-test")
            )
        )
        val storage = DesktopStorage(directory)
        storage.save(DesktopWorkspace.fromAppState(state))
        val controller = DesktopController(storage)

        val name = controller.reportSickAndFindReplacement(sick.id, LocalDate.parse("2026-08-03"), "griep")

        assertEquals("Vervanger", name)
        assertTrue(controller.state.assignments.any {
            it.employeeId == replacement.id && it.date == "2026-08-03" && it.shiftTemplateId == day.id
        })
        assertFalse(controller.state.assignments.any { it.employeeId == sick.id && it.date == "2026-08-03" })
    }

    @Test
    fun csvContainsFullNormalTimes() {
        val employee = Employee(name = "Daniel")
        val day = ShiftTemplate("day", "Dag", ShiftKind.DAY, "09:00", "17:00")
        val state = AppState(
            year = 2026,
            month = 8,
            employees = listOf(employee),
            shiftTemplates = listOf(day),
            assignments = listOf(Assignment(employeeId = employee.id, date = "2026-08-03", shiftTemplateId = day.id))
        )
        val output = Files.createTempFile("roosterplanner-", ".csv")

        DesktopExporters.writeMatrixCsv(state, output)
        val csv = Files.readString(output)

        assertTrue(csv.contains("DAG 09:00-17:00"))
    }

    @Test
    fun pdfExportCreatesReadablePdf() {
        val employee = Employee(name = "Daniel")
        val day = ShiftTemplate("day", "Dag", ShiftKind.DAY, "09:00", "17:00")
        val state = AppState(
            year = 2026,
            month = 8,
            employees = listOf(employee),
            shiftTemplates = listOf(day),
            assignments = listOf(
                Assignment(employeeId = employee.id, date = "2026-08-03", shiftTemplateId = day.id)
            )
        )
        val output = Files.createTempFile("roosterplanner-", ".pdf")

        DesktopExporters.writePdf(state, output)
        val bytes = Files.readAllBytes(output)

        assertTrue(bytes.size > 1_000)
        assertEquals("%PDF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
    }

    private fun quietSettings(): PlannerSettings = PlannerSettings(
        requireSetupDaily = false,
        requireCloseDaily = false,
        requireMiddleOnBusyDays = false,
        minimumTwoDayOffBlocks = 0,
        preferredTwoDayOffBlocks = 0,
        preferTwoConsecutiveDaysOff = false,
        protectManualAssignmentsDuringAutoFix = true
    )
}
