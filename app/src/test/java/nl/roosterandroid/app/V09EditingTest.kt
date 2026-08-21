package nl.roosterandroid.app

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V09EditingTest {
    private val activeId = "active"
    private val otherId = "other"

    private fun location(id: String) = RestaurantLocation(
        id = id,
        openingHours = (1..7).map {
            OpeningHoursRule(it, OpeningMode.OPEN, "09:00", "22:00")
        },
        requireSetupDaily = false,
        requireMiddleOnBusyDays = false,
        requireCloseDaily = false
    )

    private fun employee(id: String) = Employee(
        id = id,
        name = id,
        contractedDaysPerWeek = 0,
        contractedHoursPerWeek = 0.0,
        maxShiftsPerWeek = 7,
        locationIds = setOf(activeId, otherId)
    )

    private fun template(id: String, locationId: String = activeId) = ShiftTemplate(
        id = id,
        name = id,
        kind = ShiftKind.DAY,
        start = "09:00",
        end = "17:00",
        locationId = locationId
    )

    private fun state(
        employees: List<Employee>,
        templates: List<ShiftTemplate>,
        assignments: List<Assignment> = emptyList(),
        manualDaysOff: List<ManualDayOff> = emptyList(),
        availability: List<Availability> = emptyList()
    ) = AppState(
        year = 2026,
        month = 6,
        locations = listOf(location(activeId), location(otherId)),
        activeLocationId = activeId,
        employees = employees,
        shiftTemplates = templates,
        assignments = assignments,
        manualDaysOff = manualDaysOff,
        availability = availability,
        settings = PlannerSettings(
            requireSetupDaily = false,
            requireMiddleOnBusyDays = false,
            requireCloseDaily = false,
            minimumTwoDayOffBlocks = 0,
            preferredTwoDayOffBlocks = 0,
            preferTwoConsecutiveDaysOff = false
        )
    )

    @Test
    fun clearingSelectionOnlyRemovesActiveLocationCells() {
        val manager = employee("manager")
        val activeTemplate = template("active-day")
        val otherTemplate = template("other-day", otherId)
        val selectedActive = Assignment(
            id = "selected-active",
            employeeId = manager.id,
            date = "2026-06-01",
            shiftTemplateId = activeTemplate.id,
            locationId = activeId
        )
        val selectedOther = selectedActive.copy(
            id = "selected-other",
            shiftTemplateId = otherTemplate.id,
            locationId = otherId
        )
        val unselected = selectedActive.copy(id = "unselected", date = "2026-06-02")
        val appState = state(
            employees = listOf(manager),
            templates = listOf(activeTemplate, otherTemplate),
            assignments = listOf(selectedActive, selectedOther, unselected),
            manualDaysOff = listOf(
                ManualDayOff(manager.id, "2026-06-01", activeId),
                ManualDayOff(manager.id, "2026-06-01", otherId)
            )
        )

        val result = clearRosterCells(
            appState,
            setOf(RosterCellSelection(manager.id, "2026-06-01"))
        )

        assertEquals(1, result.removedAssignments)
        assertEquals(1, result.removedDaysOff)
        assertFalse(result.updatedState.assignments.any { it.id == selectedActive.id })
        assertTrue(result.updatedState.assignments.any { it.id == selectedOther.id })
        assertTrue(result.updatedState.assignments.any { it.id == unselected.id })
        assertTrue(result.updatedState.manualDaysOff.any { it.locationId == otherId })
    }

    @Test
    fun changingLocksOnlyTouchesSelectedExistingAssignments() {
        val first = employee("first")
        val second = employee("second")
        val activeTemplate = template("active-day")
        val otherTemplate = template("other-day", otherId)
        val selected = Assignment(
            id = "selected",
            employeeId = first.id,
            date = "2026-06-01",
            shiftTemplateId = activeTemplate.id,
            source = "manual",
            locationId = activeId
        )
        val untouched = selected.copy(id = "untouched", employeeId = second.id)
        val elsewhere = selected.copy(
            id = "elsewhere",
            shiftTemplateId = otherTemplate.id,
            locationId = otherId
        )
        val appState = state(
            employees = listOf(first, second),
            templates = listOf(activeTemplate, otherTemplate),
            assignments = listOf(selected, untouched, elsewhere)
        )

        val result = changeRosterAssignmentLocks(
            appState,
            setOf(RosterCellSelection(first.id, "2026-06-01")),
            AssignmentLockMode.AUTO
        )

        assertEquals(1, result.changedAssignments)
        assertEquals(
            AssignmentLockMode.AUTO,
            result.updatedState.assignments.first { it.id == selected.id }.effectiveLockMode()
        )
        assertEquals(
            AssignmentLockMode.FIXED,
            result.updatedState.assignments.first { it.id == untouched.id }.effectiveLockMode()
        )
        assertEquals(
            AssignmentLockMode.FIXED,
            result.updatedState.assignments.first { it.id == elsewhere.id }.effectiveLockMode()
        )
    }

    @Test
    fun dayCopyReplacesActiveTargetAndPreservesOtherLocation() {
        val first = employee("first")
        val second = employee("second")
        val activeTemplate = template("active-day")
        val oldTemplate = template("old-day")
        val otherTemplate = template("other-day", otherId)
        val source = Assignment(
            id = "source",
            employeeId = first.id,
            date = "2026-06-01",
            shiftTemplateId = activeTemplate.id,
            locationId = activeId
        )
        val oldTarget = source.copy(
            id = "old-target",
            date = "2026-06-08",
            shiftTemplateId = oldTemplate.id
        )
        val oldUnmatchedTarget = source.copy(
            id = "old-unmatched",
            employeeId = second.id,
            date = "2026-06-08",
            shiftTemplateId = oldTemplate.id
        )
        val elsewhere = source.copy(
            id = "elsewhere",
            employeeId = second.id,
            date = "2026-06-08",
            shiftTemplateId = otherTemplate.id,
            locationId = otherId
        )
        val appState = state(
            employees = listOf(first, second),
            templates = listOf(activeTemplate, oldTemplate, otherTemplate),
            assignments = listOf(source, oldTarget, oldUnmatchedTarget, elsewhere),
            manualDaysOff = listOf(ManualDayOff(second.id, "2026-06-01", activeId))
        )

        val result = copyRosterDay(
            appState,
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 8),
            AssignmentLockMode.PREFERRED
        )

        assertFalse(result.sourceWasEmpty)
        assertEquals(1, result.copiedAssignments)
        assertEquals(1, result.copiedDaysOff)
        assertEquals(0, result.skippedAssignments)
        assertFalse(result.updatedState.assignments.any { it.id == oldTarget.id })
        assertFalse(result.updatedState.assignments.any { it.id == oldUnmatchedTarget.id })
        assertTrue(result.updatedState.assignments.any { it.id == elsewhere.id })
        assertTrue(result.updatedState.assignments.any {
            it.employeeId == first.id &&
                it.date == "2026-06-08" &&
                it.shiftTemplateId == activeTemplate.id &&
                it.lockMode == AssignmentLockMode.PREFERRED
        })
        assertTrue(result.updatedState.manualDaysOff.any {
            it.employeeId == second.id && it.date == "2026-06-08" && it.locationId == activeId
        })
    }

    @Test
    fun blockedDayCopyRestoresOriginalTargetCell() {
        val manager = employee("manager")
        val sourceTemplate = template("source-day")
        val targetTemplate = template("target-day")
        val source = Assignment(
            id = "source",
            employeeId = manager.id,
            date = "2026-06-01",
            shiftTemplateId = sourceTemplate.id,
            locationId = activeId
        )
        val target = source.copy(
            id = "target",
            date = "2026-06-08",
            shiftTemplateId = targetTemplate.id
        )
        val appState = state(
            employees = listOf(manager),
            templates = listOf(sourceTemplate, targetTemplate),
            assignments = listOf(source, target),
            availability = listOf(
                Availability(manager.id, "2026-06-08", available = false)
            )
        )

        val result = copyRosterDay(
            appState,
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 8)
        )

        assertEquals(0, result.copiedAssignments)
        assertEquals(1, result.skippedAssignments)
        assertTrue(result.updatedState.assignments.any { it.id == target.id })
    }

    @Test
    fun emptySourceDayDoesNotReplaceTarget() {
        val manager = employee("manager")
        val day = template("day")
        val target = Assignment(
            id = "target",
            employeeId = manager.id,
            date = "2026-06-08",
            shiftTemplateId = day.id,
            locationId = activeId
        )
        val appState = state(
            employees = listOf(manager),
            templates = listOf(day),
            assignments = listOf(target)
        )

        val result = copyRosterDay(
            appState,
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 8)
        )

        assertTrue(result.sourceWasEmpty)
        assertTrue(result.updatedState.assignments.any { it.id == target.id })
    }
}
