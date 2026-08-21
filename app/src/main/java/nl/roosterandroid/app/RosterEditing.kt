package nl.roosterandroid.app

import java.time.LocalDate

data class RosterCellSelection(
    val employeeId: String,
    val date: String
)

data class RosterWeekCopyResult(
    val updatedState: AppState,
    val copiedAssignments: Int,
    val copiedDaysOff: Int,
    val skippedAssignments: Int,
    val sourceWasEmpty: Boolean
)

data class RosterDayCopyResult(
    val updatedState: AppState,
    val copiedAssignments: Int,
    val copiedDaysOff: Int,
    val skippedAssignments: Int,
    val sourceWasEmpty: Boolean
)

data class RosterCellClearResult(
    val updatedState: AppState,
    val removedAssignments: Int,
    val removedDaysOff: Int
)

data class RosterLockChangeResult(
    val updatedState: AppState,
    val changedAssignments: Int
)

/** Clears only the active location and displayed month; other months and locations survive. */
fun clearRosterMonth(state: AppState): AppState {
    val month = java.time.YearMonth.of(state.year, state.month)
    fun isInMonth(date: String): Boolean = runCatching {
        java.time.YearMonth.from(LocalDate.parse(date)) == month
    }.getOrDefault(false)

    return state.copy(
        assignments = state.assignments.filterNot {
            it.locationId == state.activeLocationId && isInMonth(it.date)
        },
        manualDaysOff = state.manualDaysOff.filterNot {
            it.locationId == state.activeLocationId && isInMonth(it.date)
        },
        replacementRequests = state.replacementRequests.map { request ->
            if (
                request.locationId == state.activeLocationId &&
                request.status == ReplacementStatus.OPEN &&
                isInMonth(request.date)
            ) {
                request.copy(status = ReplacementStatus.CANCELLED)
            } else {
                request
            }
        }
    )
}

fun clearRosterCells(
    state: AppState,
    cells: Set<RosterCellSelection>
): RosterCellClearResult {
    val keys = cells.map { "${it.employeeId}|${it.date}" }.toSet()
    val removedAssignments = state.assignments.count {
        it.locationId == state.activeLocationId && "${it.employeeId}|${it.date}" in keys
    }
    val removedDaysOff = state.manualDaysOff.count {
        it.locationId == state.activeLocationId && "${it.employeeId}|${it.date}" in keys
    }
    return RosterCellClearResult(
        updatedState = state.copy(
            assignments = state.assignments.filterNot {
                it.locationId == state.activeLocationId && "${it.employeeId}|${it.date}" in keys
            },
            manualDaysOff = state.manualDaysOff.filterNot {
                it.locationId == state.activeLocationId && "${it.employeeId}|${it.date}" in keys
            }
        ),
        removedAssignments = removedAssignments,
        removedDaysOff = removedDaysOff
    )
}

fun changeRosterAssignmentLocks(
    state: AppState,
    cells: Set<RosterCellSelection>,
    lockMode: AssignmentLockMode
): RosterLockChangeResult {
    val keys = cells.map { "${it.employeeId}|${it.date}" }.toSet()
    var changed = 0
    val assignments = state.assignments.map { assignment ->
        if (
            assignment.locationId == state.activeLocationId &&
            "${assignment.employeeId}|${assignment.date}" in keys &&
            assignment.effectiveLockMode() != lockMode
        ) {
            changed += 1
            assignment.copy(lockMode = lockMode)
        } else {
            assignment
        }
    }
    return RosterLockChangeResult(
        updatedState = state.copy(assignments = assignments),
        changedAssignments = changed
    )
}

/**
 * Replaces one complete day for the active location. Work at other locations is
 * retained, and blocked copied shifts restore the original target cell.
 */
fun copyRosterDay(
    state: AppState,
    sourceDate: LocalDate,
    targetDate: LocalDate,
    lockMode: AssignmentLockMode = AssignmentLockMode.PREFERRED
): RosterDayCopyResult {
    require(sourceDate != targetDate) { "Bron- en doeldatum moeten verschillen" }

    val sourceText = sourceDate.toString()
    val targetText = targetDate.toString()
    val sourceAssignments = state.assignments.filter {
        it.locationId == state.activeLocationId &&
            it.date == sourceText &&
            it.source != "replacement"
    }
    val sourceDaysOff = state.manualDaysOff.filter {
        it.locationId == state.activeLocationId && it.date == sourceText
    }
    if (sourceAssignments.isEmpty() && sourceDaysOff.isEmpty()) {
        return RosterDayCopyResult(state, 0, 0, 0, sourceWasEmpty = true)
    }

    val targetOriginalAssignments = state.assignments.filter {
        it.locationId == state.activeLocationId && it.date == targetText
    }.groupBy { it.employeeId }
    val targetOriginalDaysOff = state.manualDaysOff.filter {
        it.locationId == state.activeLocationId && it.date == targetText
    }.groupBy { it.employeeId }
    var assignments = state.assignments.filterNot {
        it.locationId == state.activeLocationId && it.date == targetText
    }
    val copiedDaysOff = sourceDaysOff.map {
        ManualDayOff(it.employeeId, targetText, state.activeLocationId)
    }
    var daysOff = state.manualDaysOff.filterNot {
        it.locationId == state.activeLocationId && it.date == targetText
    } + copiedDaysOff

    var copiedAssignments = 0
    var skippedAssignments = 0
    sourceAssignments.sortedBy { it.employeeId }.forEach { source ->
        val candidateState = state.copy(assignments = assignments, manualDaysOff = daysOff)
        if (manualAssignmentBlockReason(
                candidateState,
                source.employeeId,
                targetText,
                source.shiftTemplateId
            ) == null
        ) {
            assignments = assignments + Assignment(
                employeeId = source.employeeId,
                date = targetText,
                shiftTemplateId = source.shiftTemplateId,
                source = "manual-day-copy",
                locationId = state.activeLocationId,
                lockMode = lockMode
            )
            copiedAssignments += 1
        } else {
            assignments = assignments + targetOriginalAssignments[source.employeeId].orEmpty()
            daysOff = daysOff + targetOriginalDaysOff[source.employeeId].orEmpty()
            skippedAssignments += 1
        }
    }

    return RosterDayCopyResult(
        updatedState = state.copy(
            assignments = assignments.distinctBy { it.id },
            manualDaysOff = daysOff.distinctBy {
                "${it.employeeId}|${it.date}|${it.locationId}"
            }
        ),
        copiedAssignments = copiedAssignments,
        copiedDaysOff = copiedDaysOff.size,
        skippedAssignments = skippedAssignments,
        sourceWasEmpty = false
    )
}

/**
 * Replaces one week for the active location with the contents of another week.
 * Existing work at other locations is never removed. Invalid copied assignments
 * are skipped and the original target cell is restored.
 */
fun copyRosterWeek(
    state: AppState,
    sourceMonday: LocalDate,
    targetMonday: LocalDate,
    lockMode: AssignmentLockMode = AssignmentLockMode.PREFERRED
): RosterWeekCopyResult {
    require(sourceMonday != targetMonday) { "Bron- en doelweek moeten verschillen" }

    val sourceDates = (0L..6L).map(sourceMonday::plusDays)
    val targetDates = (0L..6L).map(targetMonday::plusDays)
    val dateMap = sourceDates.zip(targetDates).associate { (source, target) ->
        source.toString() to target.toString()
    }
    val sourceAssignments = state.assignments.filter {
        it.locationId == state.activeLocationId &&
            it.date in dateMap.keys &&
            it.source != "replacement"
    }
    val sourceDaysOff = state.manualDaysOff.filter {
        it.locationId == state.activeLocationId && it.date in dateMap.keys
    }
    if (sourceAssignments.isEmpty() && sourceDaysOff.isEmpty()) {
        return RosterWeekCopyResult(state, 0, 0, 0, sourceWasEmpty = true)
    }

    val targetDateSet = targetDates.map(LocalDate::toString).toSet()
    val targetOriginalAssignments = state.assignments.filter {
        it.locationId == state.activeLocationId && it.date in targetDateSet
    }.groupBy { "${it.employeeId}|${it.date}" }
    val targetOriginalDaysOff = state.manualDaysOff.filter {
        it.locationId == state.activeLocationId && it.date in targetDateSet
    }.groupBy { "${it.employeeId}|${it.date}" }

    var assignments = state.assignments.filterNot {
        it.locationId == state.activeLocationId && it.date in targetDateSet
    }
    var daysOff = state.manualDaysOff.filterNot {
        it.locationId == state.activeLocationId && it.date in targetDateSet
    }
    val copiedDaysOff = sourceDaysOff.mapNotNull { dayOff ->
        dateMap[dayOff.date]?.let { targetDate ->
            ManualDayOff(dayOff.employeeId, targetDate, state.activeLocationId)
        }
    }
    daysOff = daysOff + copiedDaysOff

    var copiedAssignments = 0
    var skippedAssignments = 0
    sourceAssignments
        .sortedWith(compareBy({ it.date }, { it.employeeId }))
        .forEach { source ->
            val targetDate = dateMap[source.date] ?: return@forEach
            val candidateState = state.copy(assignments = assignments, manualDaysOff = daysOff)
            if (manualAssignmentBlockReason(
                    candidateState,
                    source.employeeId,
                    targetDate,
                    source.shiftTemplateId
                ) == null
            ) {
                assignments = assignments + Assignment(
                    employeeId = source.employeeId,
                    date = targetDate,
                    shiftTemplateId = source.shiftTemplateId,
                    source = "manual-week-copy",
                    locationId = state.activeLocationId,
                    lockMode = lockMode
                )
                copiedAssignments += 1
            } else {
                val key = "${source.employeeId}|$targetDate"
                assignments = assignments + targetOriginalAssignments[key].orEmpty()
                daysOff = daysOff + targetOriginalDaysOff[key].orEmpty()
                skippedAssignments += 1
            }
        }

    return RosterWeekCopyResult(
        updatedState = state.copy(
            assignments = assignments.distinctBy { it.id },
            manualDaysOff = daysOff.distinctBy {
                "${it.employeeId}|${it.date}|${it.locationId}"
            }
        ),
        copiedAssignments = copiedAssignments,
        copiedDaysOff = copiedDaysOff.size,
        skippedAssignments = skippedAssignments,
        sourceWasEmpty = false
    )
}
