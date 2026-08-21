package nl.roosterandroid.app

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs

data class RosterRepairProposal(
    val assignments: List<Assignment>,
    val changes: List<String>,
    val errorsBefore: Int,
    val errorsAfter: Int,
    val unfilled: List<String>,
    val warnings: List<String>
)

fun manualAssignmentBlockReason(
    state: AppState,
    employeeId: String,
    dateText: String,
    templateId: String
): String? {
    val employee = state.employees.firstOrNull { it.id == employeeId }
        ?: return "manager niet gevonden"
    val template = state.shiftTemplates.firstOrNull { it.id == templateId }
        ?: return "dienst niet gevonden"
    val date = runCatching { LocalDate.parse(dateText) }.getOrNull()
        ?: return "ongeldige datum"

    if (!employee.active) return "${employee.name} is niet actief"
    if (!employee.worksAt(state.activeLocationId)) {
        return "${employee.name} werkt niet op deze vestiging"
    }
    if (template.locationId != state.activeLocationId) {
        return "dienst hoort bij een andere vestiging"
    }
    if (date.dayOfWeek.value !in template.enabledWeekdays) {
        return "${template.name} is niet actief op deze weekdag"
    }
    if (openingBounds(state, date) == null) {
        return "de vestiging is deze dag gesloten"
    }
    if (state.manualDaysOff.any {
            it.employeeId == employeeId &&
                it.date == dateText &&
                it.locationId == state.activeLocationId
        }
    ) {
        return "${employee.name} is handmatig vrijgezet"
    }

    state.assignments.firstOrNull {
        it.employeeId == employeeId && it.date == dateText
    }?.let { existing ->
        val locationName = state.locations.firstOrNull { it.id == existing.locationId }?.name
            ?: "deze of een andere vestiging"
        return "${employee.name} is al ingepland bij $locationName"
    }

    state.absences.firstOrNull {
        it.employeeId == employeeId &&
            it.status == AbsenceStatus.APPROVED &&
            it.includes(date)
    }?.let {
        return "${employee.name} heeft ${it.type.name.lowercase()}"
    }
    if (!employee.canWork(template.kind)) {
        return "${employee.name} mag ${template.kind.name.lowercase()} niet werken"
    }

    val specific = state.availability.lastOrNull {
        it.employeeId == employeeId && it.date == dateText
    }
    val weekly = state.weeklyAvailability.lastOrNull {
        it.employeeId == employeeId && it.weekday == date.dayOfWeek.value
    }
    val available = if (specific != null) specific.available else weekly?.available ?: true
    if (!available) return "${employee.name} is niet beschikbaar"

    val fixedKind = if (specific != null) specific.fixedShiftKind else weekly?.fixedShiftKind
    if (fixedKind != null && fixedKind != template.kind) {
        return "${employee.name} heeft die dag een andere vaste dienst"
    }

    val earliestText = if (specific != null) specific.earliestStart else weekly?.earliestStart
    val latestText = if (specific != null) specific.latestEnd else weekly?.latestEnd
    val earliest = earliestText?.let {
        runCatching { java.time.LocalTime.parse(it) }.getOrNull()
    }
    val latest = latestText?.let {
        runCatching { java.time.LocalTime.parse(it) }.getOrNull()
    }
    val templateStart = runCatching { template.startTime() }.getOrNull()
        ?: return "starttijd van de dienst is ongeldig"
    val templateEnd = runCatching { template.endTime() }.getOrNull()
        ?: return "eindtijd van de dienst is ongeldig"

    if (earliest != null && templateStart.isBefore(earliest)) {
        return "dienst begint vóór beschikbaarheid van ${employee.name}"
    }
    if (latest != null) {
        val start = date.atTime(templateStart)
        var end = date.atTime(templateEnd)
        if (!end.isAfter(start)) end = end.plusDays(1)
        var latestEnd = date.atTime(latest)
        if (!latestEnd.isAfter(start)) latestEnd = latestEnd.plusDays(1)
        if (end.isAfter(latestEnd)) {
            return "dienst eindigt na beschikbaarheid van ${employee.name}"
        }
    }
    return null
}

class RosterRepairPlanner(
    private val validator: AtwValidator = AtwValidator(),
    private val engine: ScheduleEngine = ScheduleEngine(validator)
) {
    private data class Candidate(
        val state: AppState,
        val description: String,
        val errorCount: Int,
        val manualChangePenalty: Int,
        val borrowedPenalty: Int,
        val monthlyLoad: Int
    )

    fun propose(state: AppState): RosterRepairProposal? = proposeInternal(
        state = state,
        maxRepairAttempts = 12,
        problemCandidateLimit = 4,
        engineSearchEffort = 1,
        shouldCancel = { false },
        onProgress = { _, _ -> }
    )

    fun proposeDeep(
        state: AppState,
        shouldCancel: () -> Boolean = { false },
        onProgress: (Int, String) -> Unit = { _, _ -> }
    ): RosterRepairProposal? = proposeInternal(
        state = state,
        maxRepairAttempts = 48,
        problemCandidateLimit = 12,
        engineSearchEffort = 3,
        shouldCancel = shouldCancel,
        onProgress = onProgress
    )

    private fun proposeInternal(
        state: AppState,
        maxRepairAttempts: Int,
        problemCandidateLimit: Int,
        engineSearchEffort: Int,
        shouldCancel: () -> Boolean,
        onProgress: (Int, String) -> Unit
    ): RosterRepairProposal? {
        onProgress(5, "Conflicten analyseren")
        val errorsBefore = hardErrors(state).size
        var working = state
        val directChanges = mutableListOf<String>()

        var repairAttempts = 0
        while (repairAttempts < maxRepairAttempts && !shouldCancel()) {
            repairAttempts += 1
            onProgress(
                5 + (repairAttempts * 45 / maxRepairAttempts.coerceAtLeast(1)),
                "Herstelcombinaties proberen"
            )
            val currentErrors = hardErrors(working)
            if (currentErrors.isEmpty()) break
            val currentCount = currentErrors.size
            val chosen = currentErrors
                .flatMap { violation ->
                    problemAssignments(working, violation, problemCandidateLimit)
                }
                .distinctBy { it.id }
                .flatMap { assignment ->
                    val without = working.copy(
                        assignments = working.assignments.filterNot { it.id == assignment.id }
                    )
                    replacementCandidates(without, assignment) +
                        alternativeShiftCandidates(without, assignment)
                }
                .filter { it.errorCount < currentCount }
                .minWithOrNull(
                    compareBy<Candidate>(
                        { it.errorCount },
                        { it.manualChangePenalty },
                        { it.borrowedPenalty },
                        { it.monthlyLoad }
                    )
                ) ?: break
            working = chosen.state
            directChanges += chosen.description
        }

        if (shouldCancel()) return null

        val ym = YearMonth.of(state.year, state.month)
        onProgress(55, "Volledig rooster opnieuw combineren")
        val generated = if (working.employees.any {
                it.active && it.worksAt(working.activeLocationId)
            }
        ) {
            engine.generate(
                working,
                searchEffort = engineSearchEffort,
                shouldCancel = shouldCancel
            )
        } else {
            null
        }

        if (shouldCancel()) return null
        onProgress(90, "Beste oplossing controleren")

        var proposed = working
        var unfilled = emptyList<String>()
        var warnings = emptyList<String>()
        if (generated != null) {
            val activeOutsideMonth = working.assignments.filter {
                it.locationId == working.activeLocationId && !isInMonth(it, ym)
            }
            val combined = working.assignments.filter {
                it.locationId != working.activeLocationId
            } + activeOutsideMonth + generated.assignments
            val generatedState = working.copy(assignments = combined.distinctBy { it.id })
            if (hardErrors(generatedState).size <= hardErrors(working).size) {
                proposed = generatedState
                unfilled = generated.unfilled
                warnings = generated.warnings
            }
        }

        val errorsAfter = hardErrors(proposed).size
        val scheduleChanged = semanticAssignments(state, ym) != semanticAssignments(proposed, ym)
        val useful = if (errorsBefore > 0) {
            errorsAfter < errorsBefore
        } else {
            scheduleChanged
        }
        if (!useful) return null

        val changes = (directChanges + describeChanges(state, proposed, ym)).distinct()
        val result = RosterRepairProposal(
            assignments = proposed.assignments,
            changes = changes.ifEmpty { listOf("Rooster opnieuw geoptimaliseerd") },
            errorsBefore = errorsBefore,
            errorsAfter = errorsAfter,
            unfilled = unfilled,
            warnings = warnings
        )
        onProgress(100, "Oplossing gereed")
        return result
    }

    private fun replacementCandidates(
        without: AppState,
        assignment: Assignment
    ): List<Candidate> {
        val original = without.employees.firstOrNull { it.id == assignment.employeeId }
            ?: return emptyList()
        val template = without.shiftTemplates.firstOrNull { it.id == assignment.shiftTemplateId }
            ?: return emptyList()
        val ym = runCatching { YearMonth.from(LocalDate.parse(assignment.date)) }.getOrNull()

        return without.employees
            .filter {
                it.active &&
                    it.id != original.id &&
                    it.worksAt(without.activeLocationId)
            }
            .filter { traineeHasSupport(it, assignment, without) }
            .mapNotNull { employee ->
                manualAssignmentBlockReason(
                    without,
                    employee.id,
                    assignment.date,
                    template.id
                )?.let { return@mapNotNull null }
                val repaired = assignment.copy(
                    employeeId = employee.id,
                    source = "manual-auto-repair"
                )
                val candidateState = without.copy(
                    assignments = without.assignments + repaired
                )
                Candidate(
                    state = candidateState,
                    description = "${assignment.date}: ${original.name} vervangen door ${employee.name} " +
                        "(${template.name} ${template.start}-${template.end})",
                    errorCount = hardErrors(candidateState).size,
                    manualChangePenalty = if (
                        assignment.effectiveLockMode() == AssignmentLockMode.PREFERRED
                    ) 1 else 0,
                    borrowedPenalty = if (employee.role == EmployeeRole.BORROWED) 1 else 0,
                    monthlyLoad = candidateState.assignments.count {
                        it.employeeId == employee.id &&
                            (ym == null || runCatching {
                                YearMonth.from(LocalDate.parse(it.date)) == ym
                            }.getOrDefault(false))
                    }
                )
            }
    }

    private fun alternativeShiftCandidates(
        without: AppState,
        assignment: Assignment
    ): List<Candidate> {
        val employee = without.employees.firstOrNull { it.id == assignment.employeeId }
            ?: return emptyList()
        val original = without.shiftTemplates.firstOrNull { it.id == assignment.shiftTemplateId }
            ?: return emptyList()
        val date = runCatching { LocalDate.parse(assignment.date) }.getOrNull()
            ?: return emptyList()

        return without.shiftTemplates
            .filter {
                it.locationId == without.activeLocationId &&
                    it.id != original.id &&
                    date.dayOfWeek.value in it.enabledWeekdays
            }
            .mapNotNull { template ->
                manualAssignmentBlockReason(
                    without,
                    employee.id,
                    assignment.date,
                    template.id
                )?.let { return@mapNotNull null }
                val repaired = assignment.copy(
                    shiftTemplateId = template.id,
                    source = "manual-auto-repair"
                )
                val candidateState = without.copy(
                    assignments = without.assignments + repaired
                )
                Candidate(
                    state = candidateState,
                    description = "${assignment.date}: ${employee.name} van " +
                        "${original.start}-${original.end} naar ${template.start}-${template.end}",
                    errorCount = hardErrors(candidateState).size,
                    manualChangePenalty = if (
                        assignment.effectiveLockMode() == AssignmentLockMode.PREFERRED
                    ) 1 else 0,
                    borrowedPenalty = 0,
                    monthlyLoad = if (template.kind == original.kind) 0 else 1
                )
            }
    }

    private fun traineeHasSupport(
        employee: Employee,
        assignment: Assignment,
        state: AppState
    ): Boolean {
        if (employee.role != EmployeeRole.TRAINEE || !state.settings.traineeMustHaveExperiencedManager) {
            return true
        }
        val target = state.shiftTemplates.firstOrNull { it.id == assignment.shiftTemplateId }
            ?: return false
        val date = runCatching { LocalDate.parse(assignment.date) }.getOrNull()
            ?: return false
        val employees = state.employees.associateBy { it.id }
        val templates = state.shiftTemplates.associateBy { it.id }
        val targetStart = date.atTime(target.startTime())
        var targetEnd = date.atTime(target.endTime())
        if (!targetEnd.isAfter(targetStart)) targetEnd = targetEnd.plusDays(1)

        return state.assignments.any { other ->
            if (other.date != assignment.date) return@any false
            val colleague = employees[other.employeeId] ?: return@any false
            if (colleague.role == EmployeeRole.TRAINEE) return@any false
            val template = templates[other.shiftTemplateId] ?: return@any false
            val start = date.atTime(template.startTime())
            var end = date.atTime(template.endTime())
            if (!end.isAfter(start)) end = end.plusDays(1)
            !start.isAfter(targetStart) && !end.isBefore(targetEnd)
        }
    }

    private fun problemAssignments(
        state: AppState,
        violation: AtwValidator.Violation,
        candidateLimit: Int
    ): List<Assignment> {
        val employeeId = violation.employeeId ?: return emptyList()
        val date = violation.date ?: return emptyList()
        val candidates = state.assignments.filter {
                it.locationId == state.activeLocationId &&
                it.employeeId == employeeId &&
                it.source != "replacement" &&
                it.effectiveLockMode() != AssignmentLockMode.FIXED
        }
        return candidates.mapNotNull { assignment ->
                val assignmentDate = runCatching { LocalDate.parse(assignment.date) }.getOrNull()
                    ?: return@mapNotNull null
                assignment to abs(ChronoUnit.DAYS.between(date, assignmentDate))
            }.filter { it.second <= 7 }
            .sortedWith(
                compareBy<Pair<Assignment, Long>>(
                    {
                        if (it.first.effectiveLockMode() == AssignmentLockMode.PREFERRED) 1 else 0
                    },
                    { it.second }
                )
            )
            .take(candidateLimit)
            .map { it.first }
    }

    private fun hardErrors(state: AppState): List<AtwValidator.Violation> =
        validator.validate(state).filter { it.severity == AtwValidator.Severity.ERROR }

    private fun semanticAssignments(state: AppState, ym: YearMonth): Set<String> =
        state.assignments.filter {
            it.locationId == state.activeLocationId && isInMonth(it, ym)
        }.map { "${it.employeeId}|${it.date}|${it.shiftTemplateId}" }.toSet()

    private fun describeChanges(
        before: AppState,
        after: AppState,
        ym: YearMonth
    ): List<String> {
        val beforeRows = before.assignments.filter {
            it.locationId == before.activeLocationId && isInMonth(it, ym)
        }.associateBy { "${it.employeeId}|${it.date}|${it.shiftTemplateId}" }
        val afterRows = after.assignments.filter {
            it.locationId == after.activeLocationId && isInMonth(it, ym)
        }.associateBy { "${it.employeeId}|${it.date}|${it.shiftTemplateId}" }
        val removed = beforeRows.keys - afterRows.keys
        val added = afterRows.keys - beforeRows.keys
        val rows = mutableListOf<String>()

        removed.take(4).forEach { key ->
            beforeRows[key]?.let { rows += "Vrijgemaakt: ${assignmentLabel(before, it)}" }
        }
        added.take(6).forEach { key ->
            afterRows[key]?.let { rows += "Ingepland: ${assignmentLabel(after, it)}" }
        }
        val hidden = removed.size + added.size - rows.size
        if (hidden > 0) rows += "Nog $hidden aanvullende roosterwijziging(en)"
        return rows
    }

    private fun assignmentLabel(state: AppState, assignment: Assignment): String {
        val employee = state.employees.firstOrNull { it.id == assignment.employeeId }?.name ?: "?"
        val template = state.shiftTemplates.firstOrNull { it.id == assignment.shiftTemplateId }
        return if (template == null) {
            "${assignment.date} • $employee"
        } else {
            "${assignment.date} • $employee • ${template.start}-${template.end}"
        }
    }

    private fun isInMonth(assignment: Assignment, ym: YearMonth): Boolean =
        runCatching { YearMonth.from(LocalDate.parse(assignment.date)) == ym }.getOrDefault(false)
}
