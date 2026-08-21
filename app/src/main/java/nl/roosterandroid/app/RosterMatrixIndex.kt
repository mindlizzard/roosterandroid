package nl.roosterandroid.app

import java.time.LocalDate
import java.time.YearMonth

data class RosterMatrixIndex(
    val assignmentsByCell: Map<RosterCellSelection, Assignment>,
    val otherAssignmentsByCell: Map<RosterCellSelection, Assignment>,
    val availabilityByCell: Map<RosterCellSelection, Availability>,
    val weeklyAvailabilityByEmployeeDay: Map<Pair<String, Int>, WeeklyAvailability>,
    val absencesByCell: Map<RosterCellSelection, Absence>,
    val manualDaysOff: Set<RosterCellSelection>,
    val errorCells: Set<RosterCellSelection>,
    val responsibilitiesByEmployee: Map<String, List<ResponsibilityRule>>,
    val markersByCell: Map<RosterCellSelection, List<PersonDayMarker>>,
    val notesByDate: Map<String, String>
)

/** Builds the lookup tables used by every visible matrix cell once per state change. */
fun rosterMatrixIndex(
    state: AppState,
    violations: List<AtwValidator.Violation>,
    month: YearMonth
): RosterMatrixIndex {
    val activeLocationId = state.activeLocationId
    fun cell(employeeId: String, date: String) = RosterCellSelection(employeeId, date)
    fun inMonth(date: String): Boolean = runCatching {
        YearMonth.from(LocalDate.parse(date)) == month
    }.getOrDefault(false)

    val activeAssignments = state.assignments
        .filter { it.locationId == activeLocationId && inMonth(it.date) }
        .associateBy { cell(it.employeeId, it.date) }
    val otherAssignments = state.assignments
        .filter { it.locationId != activeLocationId && inMonth(it.date) }
        .associateBy { cell(it.employeeId, it.date) }
    val availability = state.availability
        .filter { inMonth(it.date) }
        .associateBy { cell(it.employeeId, it.date) }
    val weeklyAvailability = state.weeklyAvailability.associateBy {
        it.employeeId to it.weekday
    }

    val monthStart = month.atDay(1)
    val monthEnd = month.atEndOfMonth()
    val absences = linkedMapOf<RosterCellSelection, Absence>()
    state.absences.filter { it.status == AbsenceStatus.APPROVED }.forEach { absence ->
        val rawStart = runCatching { LocalDate.parse(absence.startDate) }.getOrNull()
            ?: return@forEach
        val rawEnd = runCatching { LocalDate.parse(absence.endDate) }.getOrNull()
            ?: return@forEach
        var date = maxOf(rawStart, monthStart)
        val end = minOf(rawEnd, monthEnd)
        while (!date.isAfter(end)) {
            absences[cell(absence.employeeId, date.toString())] = absence
            date = date.plusDays(1)
        }
    }

    val daysOff = state.manualDaysOff
        .filter { it.locationId == activeLocationId && inMonth(it.date) }
        .mapTo(linkedSetOf()) { cell(it.employeeId, it.date) }
    val errors = violations
        .asSequence()
        .filter { it.severity == AtwValidator.Severity.ERROR }
        .mapNotNull { violation ->
            val employeeId = violation.employeeId ?: return@mapNotNull null
            val date = violation.date ?: return@mapNotNull null
            if (YearMonth.from(date) == month) cell(employeeId, date.toString()) else null
        }
        .toSet()
    val responsibilities = state.responsibilities
        .filter { it.active && it.locationId == activeLocationId }
        .groupBy { it.employeeId }
    val markers = state.personMarkers
        .filter { it.locationId == activeLocationId && inMonth(it.date) }
        .groupBy { cell(it.employeeId, it.date) }
    val notes = state.dayNotes
        .filter { it.locationId == activeLocationId && inMonth(it.date) }
        .associate { it.date to it.text }

    return RosterMatrixIndex(
        assignmentsByCell = activeAssignments,
        otherAssignmentsByCell = otherAssignments,
        availabilityByCell = availability,
        weeklyAvailabilityByEmployeeDay = weeklyAvailability,
        absencesByCell = absences,
        manualDaysOff = daysOff,
        errorCells = errors,
        responsibilitiesByEmployee = responsibilities,
        markersByCell = markers,
        notesByDate = notes
    )
}
