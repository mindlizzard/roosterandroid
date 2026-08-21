package nl.roosterandroid.desktop

import nl.roosterandroid.app.Absence
import nl.roosterandroid.app.AbsenceStatus
import nl.roosterandroid.app.AbsenceType
import nl.roosterandroid.app.AppState
import nl.roosterandroid.app.Assignment
import nl.roosterandroid.app.AtwValidator
import nl.roosterandroid.app.Availability
import nl.roosterandroid.app.DayDemand
import nl.roosterandroid.app.DayNote
import nl.roosterandroid.app.DayPartDemand
import nl.roosterandroid.app.Employee
import nl.roosterandroid.app.EmployeeRole
import nl.roosterandroid.app.OperatingHours
import nl.roosterandroid.app.PersonDayMarker
import nl.roosterandroid.app.PlannerSettings
import nl.roosterandroid.app.ResponsibilityRule
import nl.roosterandroid.app.ScheduleEngine
import nl.roosterandroid.app.ShiftKind
import nl.roosterandroid.app.ShiftSwapRecord
import nl.roosterandroid.app.ShiftTemplate
import nl.roosterandroid.app.WeeklyAvailability
import nl.roosterandroid.app.allowsShiftOn
import nl.roosterandroid.app.canWork
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs

data class AutoFixReport(
    val unlockedManualAssignments: Int,
    val errors: Int,
    val unfilled: Int
)

class DesktopController(private val storage: DesktopStorage) {
    private val validator = AtwValidator()
    private val engine = ScheduleEngine(validator)
    private val listeners = mutableListOf<() -> Unit>()
    private val undo = ArrayDeque<DesktopWorkspace>()
    private val redo = ArrayDeque<DesktopWorkspace>()

    var workspace: DesktopWorkspace = storage.load()
        private set
    var violations: List<AtwValidator.Violation> = validator.validate(state)
        private set
    var unfilled: List<String> = emptyList()
        private set
    var plannerWarnings: List<String> = emptyList()
        private set
    var status: String = "Klaar"
        private set

    val state: AppState
        get() = workspace.activeLocation().state

    val activeLocation: LocationWorkspace
        get() = workspace.activeLocation()

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun showStatus(message: String) {
        status = message
        notifyListeners()
    }

    fun generate() {
        val result = engine.generate(state)
        commitActive(
            state.copy(assignments = result.assignments),
            "Rooster opnieuw berekend"
        )
        unfilled = result.unfilled
        plannerWarnings = result.warnings
        notifyListeners()
    }

    fun autoFix(): AutoFixReport {
        val original = state
        val protectLocks = original.settings.protectManualAssignmentsDuringAutoFix
        var working = if (protectLocks) {
            original
        } else {
            original.copy(
                assignments = original.assignments.filterNot { it.source.startsWith("manual") }
            )
        }
        var unlocked = if (protectLocks) 0 else {
            original.assignments.count { it.source.startsWith("manual") }
        }
        var result = engine.generate(working)
        var bestMetric = resultMetric(working, result)

        if (protectLocks && bestMetric > 0) {
            val originalManual = original.assignments
                .filter { it.source.startsWith("manual") }
                .asReversed()
                .toMutableList()
            var rounds = 0

            while (bestMetric > 0 && originalManual.isNotEmpty() && rounds++ < 16) {
                val currentErrors = validator.validate(
                    working.copy(assignments = result.assignments)
                ).filter { it.severity == AtwValidator.Severity.ERROR }
                val prioritized = originalManual.sortedBy { assignment ->
                    val date = runCatching { LocalDate.parse(assignment.date) }.getOrNull()
                    val involved = currentErrors.any { error ->
                        error.employeeId == assignment.employeeId &&
                            (date == null || error.date == null || abs(error.date.toEpochDay() - date.toEpochDay()) <= 1)
                    }
                    if (involved) 0 else 1
                }.take(12)

                var bestRemoval: Assignment? = null
                var bestState: AppState? = null
                var bestResult: ScheduleEngine.Result? = null
                var candidateMetric = bestMetric

                prioritized.forEach { lock ->
                    val candidateState = working.copy(
                        assignments = working.assignments.filterNot { it.id == lock.id }
                    )
                    val candidateResult = engine.generate(candidateState)
                    val metric = resultMetric(candidateState, candidateResult)
                    if (metric < candidateMetric) {
                        candidateMetric = metric
                        bestRemoval = lock
                        bestState = candidateState
                        bestResult = candidateResult
                    }
                }

                if (bestRemoval == null || bestState == null || bestResult == null) break
                originalManual.removeAll { it.id == bestRemoval!!.id }
                working = bestState!!
                result = bestResult!!
                bestMetric = candidateMetric
                unlocked++
            }
        }

        val finalState = working.copy(assignments = result.assignments)
        val finalErrors = validator.validate(finalState)
            .count { it.severity == AtwValidator.Severity.ERROR }
        commitActive(
            finalState,
            buildString {
                append("Auto-fix klaar")
                if (unlocked > 0) append(" • $unlocked handmatige dienst(en) herpland")
                append(" • $finalErrors fout(en)")
            }
        )
        unfilled = result.unfilled
        plannerWarnings = result.warnings
        notifyListeners()
        return AutoFixReport(unlocked, finalErrors, result.unfilled.size)
    }

    private fun resultMetric(base: AppState, result: ScheduleEngine.Result): Int {
        val proposed = base.copy(assignments = result.assignments)
        val errors = validator.validate(proposed)
            .count { it.severity == AtwValidator.Severity.ERROR }
        val hardUnfilled = result.unfilled.count {
            !it.contains("contractdagen", ignoreCase = true)
        }
        return errors * 100_000 + hardUnfilled * 1_000 + result.unfilled.size
    }

    fun changeMonth(delta: Long) {
        val current = YearMonth.of(state.year, state.month)
        val target = current.plusMonths(delta)
        val all = (state.assignmentHistory + state.assignments).distinctBy { it.id }
        val currentAssignments = all.filter { assignmentMonth(it) == target }
        val history = all.filterNot { assignmentMonth(it) == target }
        commitActive(
            state.copy(
                year = target.year,
                month = target.monthValue,
                assignments = currentAssignments,
                assignmentHistory = history
            ),
            "${target.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${target.year}"
        )
        unfilled = emptyList()
        plannerWarnings = emptyList()
    }

    fun addEmployee(employee: Employee) {
        commitActive(state.copy(employees = state.employees + employee), "${employee.name} toegevoegd")
    }

    fun updateEmployee(employee: Employee) {
        commitActive(
            state.copy(employees = state.employees.map { if (it.id == employee.id) employee else it }),
            "${employee.name} bijgewerkt"
        )
    }

    fun removeEmployee(id: String) {
        val employee = state.employees.firstOrNull { it.id == id }
        commitActive(
            state.copy(
                employees = state.employees.filterNot { it.id == id },
                assignments = state.assignments.filterNot { it.employeeId == id },
                assignmentHistory = state.assignmentHistory.filterNot { it.employeeId == id },
                availability = state.availability.filterNot { it.employeeId == id },
                weeklyAvailability = state.weeklyAvailability.filterNot { it.employeeId == id },
                absences = state.absences.filterNot { it.employeeId == id },
                responsibilities = state.responsibilities.filterNot { it.employeeId == id },
                personMarkers = state.personMarkers.filterNot { it.employeeId == id }
            ),
            "${employee?.name ?: "Medewerker"} verwijderd"
        )
    }

    fun upsertWeeklyAvailability(rule: WeeklyAvailability) {
        val updated = state.weeklyAvailability.filterNot {
            it.employeeId == rule.employeeId && it.weekday == rule.weekday
        } + rule
        commitActive(state.copy(weeklyAvailability = updated), "Weekbeschikbaarheid opgeslagen")
    }

    fun removeWeeklyAvailability(employeeId: String, weekday: Int) {
        commitActive(
            state.copy(
                weeklyAvailability = state.weeklyAvailability.filterNot {
                    it.employeeId == employeeId && it.weekday == weekday
                }
            ),
            "Weekregel verwijderd"
        )
    }

    fun upsertAvailability(rule: Availability) {
        val updated = state.availability.filterNot {
            it.employeeId == rule.employeeId && it.date == rule.date
        } + rule
        commitActive(state.copy(availability = updated), "Beschikbaarheid opgeslagen")
    }

    fun removeAvailability(employeeId: String, date: String) {
        commitActive(
            state.copy(
                availability = state.availability.filterNot {
                    it.employeeId == employeeId && it.date == date
                }
            ),
            "Datumuitzondering verwijderd"
        )
    }

    fun upsertAbsence(absence: Absence) {
        val start = runCatching { LocalDate.parse(absence.startDate) }.getOrNull()
        val end = runCatching { LocalDate.parse(absence.endDate) }.getOrNull()
        if (start == null || end == null || end.isBefore(start)) {
            showStatus("Controleer de afwezigheidsperiode")
            return
        }
        val assignments = if (absence.status == AbsenceStatus.APPROVED) {
            state.assignments.filterNot { assignment ->
                val date = runCatching { LocalDate.parse(assignment.date) }.getOrNull()
                assignment.employeeId == absence.employeeId && date != null &&
                    !date.isBefore(start) && !date.isAfter(end)
            }
        } else state.assignments
        commitActive(
            state.copy(
                absences = state.absences.filterNot { it.id == absence.id } + absence,
                assignments = assignments
            ),
            "Afwezigheid opgeslagen"
        )
    }

    fun removeAbsence(id: String) {
        commitActive(state.copy(absences = state.absences.filterNot { it.id == id }), "Afwezigheid verwijderd")
    }

    fun reportSickAndFindReplacement(employeeId: String, date: LocalDate, note: String): String? {
        val sickEmployee = state.employees.firstOrNull { it.id == employeeId } ?: return null
        val oldAssignment = state.assignments.lastOrNull {
            it.employeeId == employeeId && it.date == date.toString()
        }
        val absence = Absence(
            employeeId = employeeId,
            startDate = date.toString(),
            endDate = date.toString(),
            type = AbsenceType.SICK,
            status = AbsenceStatus.APPROVED,
            note = note
        )
        var proposed = state.copy(
            absences = state.absences + absence,
            assignments = state.assignments.filterNot { it.id == oldAssignment?.id }
        )

        var replacement: Employee? = null
        if (oldAssignment != null) {
            val template = state.shiftTemplates.firstOrNull { it.id == oldAssignment.shiftTemplateId }
            if (template != null) {
                replacement = state.employees
                    .filter { candidate ->
                        candidate.active && candidate.id != employeeId && candidate.canWork(template.kind) &&
                            proposed.assignments.none { it.employeeId == candidate.id && it.date == date.toString() } &&
                            manualBlockReason(candidate.id, date, template.id, proposed) == null
                    }
                    .filter { candidate ->
                        val candidateAssignment = Assignment(
                            employeeId = candidate.id,
                            date = date.toString(),
                            shiftTemplateId = template.id,
                            source = "manual-replacement"
                        )
                        val candidateState = proposed.copy(assignments = proposed.assignments + candidateAssignment)
                        val baseline = validator.validate(proposed)
                            .filter { it.severity == AtwValidator.Severity.ERROR && it.employeeId == candidate.id }
                            .map(::violationKey).toSet()
                        validator.validate(candidateState)
                            .filter { it.severity == AtwValidator.Severity.ERROR && it.employeeId == candidate.id }
                            .none { violationKey(it) !in baseline }
                    }
                    .minByOrNull { monthlyShiftCount(it.id, proposed) }

                if (replacement != null) {
                    proposed = proposed.copy(
                        assignments = proposed.assignments + Assignment(
                            employeeId = replacement.id,
                            date = date.toString(),
                            shiftTemplateId = template.id,
                            source = "manual-replacement"
                        )
                    )
                }
            }
        }

        commitActive(
            proposed,
            if (replacement != null) {
                "${sickEmployee.name} ziekgemeld • ${replacement.name} als vervanger"
            } else {
                "${sickEmployee.name} ziekgemeld • Auto-fix zoekt vervanging"
            }
        )
        autoFix()
        return replacement?.name
    }

    fun upsertDayPartDemand(demand: DayPartDemand) {
        val valid = runCatching {
            LocalDate.parse(demand.date)
            demand.startTime()
            demand.endTime()
        }.isSuccess
        if (!valid) {
            showStatus("Gebruik datum JJJJ-MM-DD en tijden UU:mm")
            return
        }
        commitActive(
            state.copy(dayPartDemands = state.dayPartDemands.filterNot { it.id == demand.id } + demand),
            "Bezetting ${demand.label} opgeslagen"
        )
    }

    fun removeDayPartDemand(id: String) {
        commitActive(
            state.copy(dayPartDemands = state.dayPartDemands.filterNot { it.id == id }),
            "Bezettingsregel verwijderd"
        )
    }

    fun upsertDayDemand(demand: DayDemand) {
        commitActive(
            state.copy(dayDemands = state.dayDemands.filterNot { it.date == demand.date } + demand),
            "Dagbezetting opgeslagen"
        )
    }

    fun updateSettings(settings: PlannerSettings) {
        commitActive(state.copy(settings = settings), "Instellingen opgeslagen")
    }

    fun updateOperatingHours(hours: OperatingHours) {
        val valid = runCatching { hours.openTime(); hours.closeTime() }.isSuccess
        if (!valid) {
            showStatus("Gebruik restauranttijden als UU:mm")
            return
        }
        commitActive(
            state.copy(
                operatingHours = state.operatingHours.filterNot { it.weekday == hours.weekday } + hours
            ),
            "Restauranttijden opgeslagen"
        )
    }

    fun alignClosingTemplatesWithOperatingHours() {
        val activeHours = state.operatingHours.filterNot { it.closed }
        if (activeHours.isEmpty()) {
            showStatus("Er zijn geen geopende dagen")
            return
        }
        val newCloseTemplates = activeHours.groupBy { it.close }.map { (close, rules) ->
            val end = LocalTime.parse(close)
            val start = end.minusHours(8)
            val dayLabel = rules.map { dayShort(it.weekday) }.joinToString("/")
            ShiftTemplate(
                name = "Sluit $dayLabel",
                kind = ShiftKind.CLOSE,
                start = start.toString(),
                end = end.toString(),
                enabledWeekdays = rules.map { it.weekday }.toSet()
            )
        }
        val oldCloseIds = state.shiftTemplates.filter { it.kind == ShiftKind.CLOSE }.map { it.id }.toSet()
        fun replacementFor(assignment: Assignment): ShiftTemplate? {
            if (assignment.shiftTemplateId !in oldCloseIds) return null
            val date = runCatching { LocalDate.parse(assignment.date) }.getOrNull() ?: return null
            return newCloseTemplates.firstOrNull { date.dayOfWeek.value in it.enabledWeekdays }
        }
        val remappedCurrent = state.assignments.map { assignment ->
            replacementFor(assignment)?.let { assignment.copy(shiftTemplateId = it.id) } ?: assignment
        }
        val remappedHistory = state.assignmentHistory.map { assignment ->
            replacementFor(assignment)?.let { assignment.copy(shiftTemplateId = it.id) } ?: assignment
        }
        commitActive(
            state.copy(
                shiftTemplates = state.shiftTemplates.filterNot { it.kind == ShiftKind.CLOSE } + newCloseTemplates,
                assignments = remappedCurrent,
                assignmentHistory = remappedHistory
            ),
            "Sluitdiensten bijgewerkt vanuit restauranttijden"
        )
    }

    fun addTemplate(template: ShiftTemplate) {
        commitActive(state.copy(shiftTemplates = state.shiftTemplates + template), "${template.name} toegevoegd")
    }

    fun updateTemplate(template: ShiftTemplate) {
        commitActive(
            state.copy(shiftTemplates = state.shiftTemplates.map { if (it.id == template.id) template else it }),
            "${template.name} opgeslagen"
        )
    }

    fun duplicateTemplate(template: ShiftTemplate) {
        addTemplate(template.copy(id = UUID.randomUUID().toString(), name = "${template.name} kopie"))
    }

    fun removeTemplate(id: String): Boolean {
        val inUse = (state.assignments + state.assignmentHistory).any { it.shiftTemplateId == id }
        if (inUse) {
            showStatus("Diensttemplate is nog in gebruik en kan niet worden verwijderd")
            return false
        }
        val template = state.shiftTemplates.firstOrNull { it.id == id }
        commitActive(
            state.copy(shiftTemplates = state.shiftTemplates.filterNot { it.id == id }),
            "${template?.name ?: "Diensttemplate"} verwijderd"
        )
        return true
    }

    fun setManualAssignment(employeeId: String, date: LocalDate, templateId: String?) {
        val without = state.assignments.filterNot {
            it.employeeId == employeeId && it.date == date.toString()
        }
        if (templateId == null) {
            commitActive(state.copy(assignments = without), "Handmatig vrij gezet")
            if (state.settings.autoFixAfterManualChanges) autoFix()
            return
        }
        val block = manualBlockReason(employeeId, date, templateId, state)
        if (block != null) {
            showStatus("Niet opgeslagen: $block")
            return
        }
        val assignment = Assignment(
            employeeId = employeeId,
            date = date.toString(),
            shiftTemplateId = templateId,
            source = "manual-desktop"
        )
        val proposed = state.copy(assignments = without + assignment)
        val errorCount = validator.validate(proposed)
            .count { it.severity == AtwValidator.Severity.ERROR }
        commitActive(
            proposed,
            if (errorCount == 0) "Handmatige dienst opgeslagen"
            else "Handmatige dienst opgeslagen • $errorCount conflict(en), gebruik Auto-fix"
        )
        if (state.settings.autoFixAfterManualChanges) autoFix()
    }

    fun swapAssignments(firstId: String, secondId: String) {
        if (firstId == secondId) {
            showStatus("Kies twee verschillende diensten")
            return
        }
        val first = state.assignments.firstOrNull { it.id == firstId }
        val second = state.assignments.firstOrNull { it.id == secondId }
        if (first == null || second == null || first.employeeId == second.employeeId) {
            showStatus("Deze diensten kunnen niet worden geruild")
            return
        }
        val firstDate = LocalDate.parse(first.date)
        val secondDate = LocalDate.parse(second.date)
        manualBlockReason(second.employeeId, firstDate, first.shiftTemplateId, state)?.let {
            showStatus("Ruil niet mogelijk: $it")
            return
        }
        manualBlockReason(first.employeeId, secondDate, second.shiftTemplateId, state)?.let {
            showStatus("Ruil niet mogelijk: $it")
            return
        }
        val swappedFirst = first.copy(employeeId = second.employeeId, source = "manual-swap")
        val swappedSecond = second.copy(employeeId = first.employeeId, source = "manual-swap")
        val keep = state.assignments.filterNot { it.id == first.id || it.id == second.id }
        val record = ShiftSwapRecord(
            firstAssignmentId = first.id,
            secondAssignmentId = second.id,
            firstEmployeeId = first.employeeId,
            secondEmployeeId = second.employeeId,
            firstDate = first.date,
            secondDate = second.date,
            createdAt = LocalDateTime.now().toString()
        )
        commitActive(
            state.copy(assignments = keep + swappedFirst + swappedSecond, swapHistory = state.swapHistory + record),
            "Diensten geruild • Auto-fix kan de rest opnieuw leggen"
        )
    }

    fun upsertDayNote(date: LocalDate, text: String) {
        val clean = text.trim()
        val notes = state.dayNotes.filterNot { it.date == date.toString() }
        commitActive(
            state.copy(dayNotes = if (clean.isBlank()) notes else notes + DayNote(date.toString(), clean)),
            if (clean.isBlank()) "Bijzonderheid verwijderd" else "Bijzonderheid opgeslagen"
        )
    }

    fun upsertResponsibility(rule: ResponsibilityRule) {
        commitActive(
            state.copy(responsibilities = state.responsibilities.filterNot { it.id == rule.id } + rule),
            "Verantwoordelijkheid opgeslagen"
        )
    }

    fun removeResponsibility(id: String) {
        commitActive(
            state.copy(responsibilities = state.responsibilities.filterNot { it.id == id }),
            "Verantwoordelijkheid verwijderd"
        )
    }

    fun upsertPersonMarker(marker: PersonDayMarker) {
        commitActive(
            state.copy(personMarkers = state.personMarkers.filterNot { it.id == marker.id } + marker),
            "Markering opgeslagen"
        )
    }

    fun removePersonMarker(id: String) {
        commitActive(
            state.copy(personMarkers = state.personMarkers.filterNot { it.id == id }),
            "Markering verwijderd"
        )
    }

    fun addLocation(name: String, copyCurrent: Boolean) {
        val clean = name.trim()
        if (clean.isBlank()) return
        val copiedState = if (copyCurrent) {
            state.copy(
                assignments = emptyList(),
                assignmentHistory = emptyList(),
                absences = emptyList(),
                dayNotes = emptyList(),
                dayDemands = emptyList(),
                dayPartDemands = emptyList(),
                swapHistory = emptyList(),
                settings = state.settings.copy(locationName = clean)
            )
        } else {
            AppState(
                year = state.year,
                month = state.month,
                settings = PlannerSettings(locationName = clean)
            )
        }
        val location = LocationWorkspace(name = clean, state = copiedState)
        commitWorkspace(
            workspace.copy(
                activeLocationId = location.id,
                locations = workspace.locations + location
            ),
            "Vestiging $clean toegevoegd"
        )
    }

    fun switchLocation(id: String) {
        if (workspace.locations.none { it.id == id }) return
        workspace = workspace.copy(activeLocationId = id)
        storage.save(workspace)
        violations = validator.validate(state)
        unfilled = emptyList()
        plannerWarnings = emptyList()
        status = "Vestiging ${activeLocation.name} geopend"
        notifyListeners()
    }

    fun renameLocation(id: String, name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        commitWorkspace(
            workspace.copy(
                locations = workspace.locations.map { location ->
                    if (location.id == id) {
                        location.copy(
                            name = clean,
                            state = location.state.copy(
                                settings = location.state.settings.copy(locationName = clean)
                            )
                        )
                    } else location
                }
            ),
            "Vestiging hernoemd"
        )
    }

    fun deleteLocation(id: String): Boolean {
        if (workspace.locations.size <= 1) {
            showStatus("Minimaal één vestiging moet blijven bestaan")
            return false
        }
        val remaining = workspace.locations.filterNot { it.id == id }
        val nextActive = if (workspace.activeLocationId == id) remaining.first().id else workspace.activeLocationId
        commitWorkspace(
            workspace.copy(activeLocationId = nextActive, locations = remaining),
            "Vestiging verwijderd"
        )
        return true
    }

    fun importWorkspace(path: Path) {
        val imported = storage.readImport(path)
        commitWorkspace(imported, "Import voltooid")
        unfilled = emptyList()
        plannerWarnings = emptyList()
    }

    fun exportWorkspace(path: Path) = storage.exportWorkspace(workspace, path)

    fun exportLocation(path: Path) = storage.exportLocation(state, path)

    fun undo() {
        if (undo.isEmpty()) return
        redo.addLast(workspace)
        workspace = undo.removeLast()
        storage.save(workspace)
        violations = validator.validate(state)
        status = "Laatste wijziging ongedaan gemaakt"
        notifyListeners()
    }

    fun redo() {
        if (redo.isEmpty()) return
        undo.addLast(workspace)
        workspace = redo.removeLast()
        storage.save(workspace)
        violations = validator.validate(state)
        status = "Wijziging opnieuw uitgevoerd"
        notifyListeners()
    }

    private fun manualBlockReason(
        employeeId: String,
        date: LocalDate,
        templateId: String,
        base: AppState
    ): String? {
        val employee = base.employees.firstOrNull { it.id == employeeId } ?: return "medewerker niet gevonden"
        val template = base.shiftTemplates.firstOrNull { it.id == templateId } ?: return "dienst niet gevonden"
        if (!employee.active) return "${employee.name} is niet actief"
        if (!employee.canWork(template.kind)) return "${employee.name} mag deze dienst niet werken"
        if (date.dayOfWeek.value !in template.enabledWeekdays) return "dienst is niet actief op deze weekdag"
        if (!base.allowsShiftOn(date, template)) {
            val hours = base.operatingHours.lastOrNull { it.weekday == date.dayOfWeek.value }
            return if (hours?.closed == true) {
                "de locatie is op deze weekdag gesloten"
            } else {
                "dienst valt buiten de restauranttijden"
            }
        }
        base.absences.firstOrNull {
            it.employeeId == employeeId && it.status == AbsenceStatus.APPROVED && it.includes(date)
        }?.let { return "${employee.name} is afwezig (${it.type.name.lowercase()})" }

        val specific = base.availability.lastOrNull {
            it.employeeId == employeeId && it.date == date.toString()
        }
        val weekly = base.weeklyAvailability.lastOrNull {
            it.employeeId == employeeId && it.weekday == date.dayOfWeek.value
        }
        val available = specific?.available ?: weekly?.available ?: true
        if (!available) return "${employee.name} is niet beschikbaar"
        val fixedKind = specific?.fixedShiftKind ?: weekly?.fixedShiftKind
        if (fixedKind != null && fixedKind != template.kind) return "er staat een andere vaste dienst ingesteld"
        val earliest = (specific?.earliestStart ?: weekly?.earliestStart)?.let {
            runCatching { LocalTime.parse(it) }.getOrNull()
        }
        val latest = (specific?.latestEnd ?: weekly?.latestEnd)?.let {
            runCatching { LocalTime.parse(it) }.getOrNull()
        }
        if (earliest != null && template.startTime().isBefore(earliest)) return "dienst begint vóór beschikbaarheid"
        if (latest != null) {
            val start = date.atTime(template.startTime())
            var end = date.atTime(template.endTime())
            if (!end.isAfter(start)) end = end.plusDays(1)
            var latestEnd = date.atTime(latest)
            if (!latestEnd.isAfter(start)) latestEnd = latestEnd.plusDays(1)
            if (end.isAfter(latestEnd)) return "dienst eindigt na beschikbaarheid"
        }
        return null
    }

    private fun monthlyShiftCount(employeeId: String, base: AppState): Int =
        base.assignments.count { it.employeeId == employeeId }

    private fun violationKey(v: AtwValidator.Violation): String =
        "${v.employeeId}|${v.date}|${v.rule}|${v.message}"

    private fun commitActive(newState: AppState, message: String) {
        val location = activeLocation
        val normalized = newState.copy(
            settings = newState.settings.copy(locationName = location.name)
        )
        commitWorkspace(
            workspace.copy(
                locations = workspace.locations.map {
                    if (it.id == location.id) it.copy(state = normalized) else it
                }
            ),
            message
        )
    }

    private fun commitWorkspace(next: DesktopWorkspace, message: String) {
        undo.addLast(workspace)
        while (undo.size > 30) undo.removeFirst()
        redo.clear()
        workspace = next
        storage.save(workspace)
        violations = validator.validate(state)
        status = message
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.toList().forEach { listener -> listener() }
    }

    private fun assignmentMonth(assignment: Assignment): YearMonth? = runCatching {
        YearMonth.from(LocalDate.parse(assignment.date))
    }.getOrNull()

    private fun dayShort(weekday: Int): String = when (DayOfWeek.of(weekday.coerceIn(1, 7))) {
        DayOfWeek.MONDAY -> "ma"
        DayOfWeek.TUESDAY -> "di"
        DayOfWeek.WEDNESDAY -> "wo"
        DayOfWeek.THURSDAY -> "do"
        DayOfWeek.FRIDAY -> "vr"
        DayOfWeek.SATURDAY -> "za"
        DayOfWeek.SUNDAY -> "zo"
    }
}
