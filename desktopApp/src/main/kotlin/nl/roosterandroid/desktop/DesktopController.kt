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
import nl.roosterandroid.app.SmartShiftTemplates
import nl.roosterandroid.app.WeeklyAvailability
import nl.roosterandroid.app.allowsShiftOn
import nl.roosterandroid.app.canWork
import nl.roosterandroid.app.countsAsManager
import nl.roosterandroid.app.isExperiencedManager
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
    var status: String = storage.lastLoadNotice ?: "Klaar"
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
        val prepared = preparePlannerState(state)
        val result = engine.generate(prepared)
        commitActive(
            prepared.copy(assignments = result.assignments),
            "Rooster opnieuw berekend"
        )
        unfilled = result.unfilled
        plannerWarnings = result.warnings
        notifyListeners()
    }

    fun autoFix(
        recordUndo: Boolean = true
    ): AutoFixReport {
        val original = preparePlannerState(state)
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
                if (unlocked > 0)
                    append(
                        " • $unlocked handmatige dienst(en) herpland"
                    )
                append(" • $finalErrors fout(en)")
            },
            recordUndo = recordUndo
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
        commitActive(
            state.copy(
                employees = state.employees + employee
            ),
            "${employee.name} toegevoegd"
        )
    }

    fun borrowEmployeeFromLocation(
        sourceLocationId: String,
        sourceEmployeeId: String
    ): Boolean {
        val sourceLocation =
            workspace.locations.firstOrNull {
                it.id == sourceLocationId
            }

        if (
            sourceLocation == null ||
            sourceLocation.id ==
                activeLocation.id
        ) {
            showStatus(
                "Bronvestiging niet gevonden"
            )
            return false
        }

        val sourceEmployee =
            sourceLocation.state.employees
                .firstOrNull {
                    it.id == sourceEmployeeId &&
                        it.active &&
                        it.isExperiencedManager() &&
                        it.role !=
                            EmployeeRole.BORROWED
                }

        if (sourceEmployee == null) {
            showStatus(
                "Manager niet beschikbaar om te lenen"
            )
            return false
        }

        val alreadyPresent =
            state.employees.any {
                it.active &&
                    it.role ==
                        EmployeeRole.BORROWED &&
                    it.loanSourceLocationId ==
                        sourceLocation.id &&
                    it.loanSourceEmployeeId ==
                        sourceEmployee.id
            }

        if (alreadyPresent) {
            showStatus(
                "${sourceEmployee.name} staat al als leenmanager in ${activeLocation.name}"
            )
            return false
        }

        val borrowed =
            sourceEmployee.copy(
                id =
                    UUID.randomUUID()
                        .toString(),
                role =
                    EmployeeRole.BORROWED,
                loanSourceLocationId =
                    sourceLocation.id,
                loanSourceEmployeeId =
                    sourceEmployee.id,
                loanSourceLocationName =
                    sourceLocation.name,
                contractedDaysPerWeek = 0,
                contractedHoursPerWeek = 0.0,
                maxShiftsPerWeek = 3,
                active = true
            )

        commitActive(
            state.copy(
                employees =
                    state.employees + borrowed
            ),
            "${borrowed.name} geleend van ${sourceLocation.name}"
        )

        return true
    }

    fun returnBorrowedManager(
        employeeId: String,
        removeCurrentAssignments: Boolean = false
    ): Boolean {
        val employee =
            state.employees.firstOrNull {
                it.id == employeeId &&
                    it.role == EmployeeRole.BORROWED
            }

        if (employee == null) {
            showStatus(
                "Selecteer een actieve leenmanager"
            )
            return false
        }

        val currentAssignments =
            state.assignments.filter {
                it.employeeId == employee.id
            }

        if (
            currentAssignments.isNotEmpty() &&
            !removeCurrentAssignments
        ) {
            showStatus(
                "${employee.name} heeft nog " +
                    "${currentAssignments.size} dienst(en) in het rooster"
            )
            return false
        }

        val hasHistory =
            state.assignmentHistory.any {
                it.employeeId == employee.id
            } ||
                state.swapHistory.any {
                    it.firstEmployeeId == employee.id ||
                        it.secondEmployeeId == employee.id
                }

        val remainingAssignments =
            if (removeCurrentAssignments) {
                state.assignments.filterNot {
                    it.employeeId == employee.id
                }
            } else {
                state.assignments
            }

        val nextEmployees =
            if (hasHistory) {
                state.employees.map {
                    if (it.id == employee.id) {
                        it.copy(active = false)
                    } else {
                        it
                    }
                }
            } else {
                state.employees.filterNot {
                    it.id == employee.id
                }
            }

        val nextState =
            state.copy(
                employees = nextEmployees,
                assignments = remainingAssignments,
                availability =
                    if (hasHistory) {
                        state.availability
                    } else {
                        state.availability.filterNot {
                            it.employeeId == employee.id
                        }
                    },
                weeklyAvailability =
                    if (hasHistory) {
                        state.weeklyAvailability
                    } else {
                        state.weeklyAvailability.filterNot {
                            it.employeeId == employee.id
                        }
                    },
                absences =
                    if (hasHistory) {
                        state.absences
                    } else {
                        state.absences.filterNot {
                            it.employeeId == employee.id
                        }
                    },
                responsibilities =
                    if (hasHistory) {
                        state.responsibilities
                    } else {
                        state.responsibilities.filterNot {
                            it.employeeId == employee.id
                        }
                    },
                personMarkers =
                    if (hasHistory) {
                        state.personMarkers
                    } else {
                        state.personMarkers.filterNot {
                            it.employeeId == employee.id
                        }
                    }
            )

        val sourceName =
            employee.loanSourceLocationName
                ?.takeIf { it.isNotBlank() }
                ?: "bronvestiging"

        commitActive(
            nextState,
            if (hasHistory) {
                "${employee.name} terug naar $sourceName • historie behouden"
            } else {
                "${employee.name} terug naar $sourceName"
            }
        )

        return true
    }

    fun updateEmployee(employee: Employee) {
        commitActive(
            state.copy(employees = state.employees.map { if (it.id == employee.id) employee else it }),
            "${employee.name} bijgewerkt"
        )
    }

    fun removeEmployee(id: String) {
        val employee = state.employees
            .firstOrNull { it.id == id }
            ?: return

        val referenced =
            (state.assignments + state.assignmentHistory)
                .any { it.employeeId == id }

        if (referenced) {
            commitActive(
                state.copy(
                    employees = state.employees.map {
                        if (it.id == id)
                            it.copy(active = false)
                        else
                            it
                    }
                ),
                "${employee.name} gedeactiveerd • roosterhistorie behouden"
            )
            return
        }

        commitActive(
            state.copy(
                employees = state.employees
                    .filterNot { it.id == id },
                availability = state.availability
                    .filterNot { it.employeeId == id },
                weeklyAvailability = state.weeklyAvailability
                    .filterNot { it.employeeId == id },
                absences = state.absences
                    .filterNot { it.employeeId == id },
                responsibilities = state.responsibilities
                    .filterNot { it.employeeId == id },
                personMarkers = state.personMarkers
                    .filterNot { it.employeeId == id }
            ),
            "${employee.name} verwijderd"
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
                    .sortedWith(
                        compareBy<Employee>(
                            {
                                if (it.isExperiencedManager())
                                    0
                                else
                                    1
                            },
                            {
                                if (
                                    state.settings.minimizeBorrowedManagers &&
                                    it.role == EmployeeRole.BORROWED
                                )
                                    1
                                else
                                    0
                            },
                            {
                                monthlyShiftCount(
                                    it.id,
                                    proposed
                                )
                            },
                            {
                                it.name.lowercase()
                            }
                        )
                    )
                    .firstOrNull()

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
        autoFix(recordUndo = false)
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
        val activeHours = state.operatingHours
            .filterNot {
                it.closed || it.isTwentyFourHours()
            }

        if (activeHours.isEmpty()) {
            showStatus(
                "Geen sluitmomenten: 24/7-dagen hebben geen sluitdienst nodig"
            )
            return
        }

        val newCloseTemplates =
            activeHours
                .groupBy { it.close }
                .map { (close, rules) ->
                    val end = LocalTime.parse(close)
                    val start = end.minusHours(8)

                    val dayLabel = rules
                        .map { dayShort(it.weekday) }
                        .joinToString("/")

                    ShiftTemplate(
                        name = "Sluit $dayLabel",
                        kind = ShiftKind.CLOSE,
                        start = start.toString(),
                        end = end.toString(),
                        enabledWeekdays =
                            rules.map { it.weekday }.toSet()
                    )
                }

        val oldCloseTemplates =
            state.shiftTemplates.filter {
                it.kind == ShiftKind.CLOSE &&
                    !it.archived
            }

        val oldCloseIds =
            oldCloseTemplates.map { it.id }.toSet()

        fun replacementFor(
            assignment: Assignment
        ): ShiftTemplate? {
            if (
                assignment.shiftTemplateId !in oldCloseIds
            ) {
                return null
            }

            val date = runCatching {
                LocalDate.parse(assignment.date)
            }.getOrNull() ?: return null

            return newCloseTemplates.firstOrNull {
                date.dayOfWeek.value in
                    it.enabledWeekdays
            }
        }

        val remappedCurrent =
            state.assignments.map { assignment ->
                replacementFor(assignment)?.let {
                    assignment.copy(
                        shiftTemplateId = it.id
                    )
                } ?: assignment
            }

        val templatesWithArchive =
            state.shiftTemplates.map { template ->
                if (template.id in oldCloseIds) {
                    template.copy(archived = true)
                } else {
                    template
                }
            } + newCloseTemplates

        commitActive(
            state.copy(
                shiftTemplates = templatesWithArchive,
                assignments = remappedCurrent,
                assignmentHistory =
                    state.assignmentHistory
            ),
            "Sluitdiensten bijgewerkt • oude roosterhistorie ongewijzigd"
        )
    }

    fun addTemplate(template: ShiftTemplate) {
        commitActive(state.copy(shiftTemplates = state.shiftTemplates + template), "${template.name} toegevoegd")
    }

    fun updateTemplate(template: ShiftTemplate) {
        val current = state.shiftTemplates.firstOrNull { it.id == template.id }
        if (current == null) {
            addTemplate(template.copy(archived = false))
            return
        }
        val referenced = (state.assignments + state.assignmentHistory).any {
            it.shiftTemplateId == current.id
        }
        if (referenced) {
            val replacement = ShiftTemplate(
                name = template.name,
                kind = template.kind,
                start = template.start,
                end = template.end,
                enabledWeekdays = template.enabledWeekdays,
                autoGenerated = false,
                archived = false
            )
            val updated = state.shiftTemplates.map {
                if (it.id == current.id) it.copy(archived = true) else it
            } + replacement
            commitActive(
                state.copy(shiftTemplates = updated),
                "Diensttemplate aangepast • oude roosters blijven intact"
            )
        } else {
            val updated = state.shiftTemplates.map {
                if (it.id == current.id)
                    template.copy(autoGenerated = false, archived = false)
                else it
            }
            commitActive(
                state.copy(shiftTemplates = updated),
                "Diensttemplate aangepast"
            )
        }
    }

    fun duplicateTemplate(template: ShiftTemplate) {
        addTemplate(template.copy(id = UUID.randomUUID().toString(), name = "${template.name} kopie"))
    }

    fun removeTemplate(templateId: String) {
        val template = state.shiftTemplates.firstOrNull {
            it.id == templateId && !it.archived
        }
        if (template == null) {
            showStatus("Diensttemplate niet gevonden")
            return
        }

        val updated = state.shiftTemplates.map {
            if (it.id == templateId)
                template.copy(archived = true)
            else it
        }

        commitActive(
            state.copy(shiftTemplates = updated),
            "${template.name} verwijderd • oude roosters blijven intact"
        )
    }

    fun setManualAssignment(
        employeeId: String,
        date: LocalDate,
        templateId: String?,
        allowOperationalOverride: Boolean = false
    ) {
        val without = state.assignments.filterNot {
            it.employeeId == employeeId && it.date == date.toString()
        }

        if (templateId == null) {
            commitActive(
                state.copy(assignments = without),
                "Dienst op vrij gezet"
            )
            if (
                state.settings.autoFixAfterManualChanges
            ) {
                autoFix(recordUndo = false)
            }
            return
        }

        val template = state.shiftTemplates.firstOrNull {
            it.id == templateId && !it.archived
        }

        if (template == null) {
            showStatus("Niet opgeslagen: diensttemplate is verwijderd")
            return
        }

        hardManualBlockReason(
            employeeId,
            date,
            templateId,
            state
        )?.let {
            showStatus("Niet opgeslagen: $it")
            return
        }

        val overrideWarnings =
            manualOverrideWarnings(
                employeeId,
                date,
                templateId,
                state
            )

        if (
            overrideWarnings.isNotEmpty() &&
            !allowOperationalOverride
        ) {
            showStatus(
                "Niet opgeslagen: " +
                    overrideWarnings.joinToString("; ") +
                    " • bevestig handmatige override"
            )
            return
        }

        val candidate = Assignment(
            employeeId = employeeId,
            date = date.toString(),
            shiftTemplateId = templateId,
            source =
                if (overrideWarnings.isNotEmpty())
                    "manual-override"
                else
                    "manual"
        )

        val proposed = state.copy(assignments = without + candidate)

        val baselineKeys = validator.validate(state)
            .filter {
                it.severity == AtwValidator.Severity.ERROR &&
                    it.employeeId == employeeId
            }
            .map(::violationKey)
            .toSet()

        val newErrors = validator.validate(proposed)
            .filter {
                it.severity == AtwValidator.Severity.ERROR &&
                    it.employeeId == employeeId
            }
            .filter { violationKey(it) !in baselineKeys }

        if (newErrors.isNotEmpty()) {
            showStatus("Niet opgeslagen: ${newErrors.first().message}")
            return
        }

        commitActive(
            proposed,
            if (overrideWarnings.isNotEmpty()) {
                "Handmatige dienst opgeslagen • bewuste override"
            } else {
                "Handmatige dienst opgeslagen"
            }
        )

        if (
            state.settings.autoFixAfterManualChanges
        ) {
            autoFix(recordUndo = false)
        }
    }

    fun swapAssignments(
        firstId: String,
        secondId: String
    ) {
        if (firstId == secondId) {
            showStatus(
                "Kies twee verschillende diensten"
            )
            return
        }

        val first =
            state.assignments.firstOrNull {
                it.id == firstId
            }

        val second =
            state.assignments.firstOrNull {
                it.id == secondId
            }

        if (
            first == null ||
            second == null ||
            first.employeeId == second.employeeId
        ) {
            showStatus(
                "Deze diensten kunnen niet worden geruild"
            )
            return
        }

        val firstDate = runCatching {
            LocalDate.parse(first.date)
        }.getOrNull()

        val secondDate = runCatching {
            LocalDate.parse(second.date)
        }.getOrNull()

        if (firstDate == null || secondDate == null) {
            showStatus(
                "Ruil niet mogelijk: ongeldige datum"
            )
            return
        }

        manualBlockReason(
            second.employeeId,
            firstDate,
            first.shiftTemplateId,
            state
        )?.let {
            showStatus("Ruil niet mogelijk: $it")
            return
        }

        manualBlockReason(
            first.employeeId,
            secondDate,
            second.shiftTemplateId,
            state
        )?.let {
            showStatus("Ruil niet mogelijk: $it")
            return
        }

        val swappedFirst =
            first.copy(
                employeeId = second.employeeId,
                source = "manual-swap"
            )

        val swappedSecond =
            second.copy(
                employeeId = first.employeeId,
                source = "manual-swap"
            )

        val keep =
            state.assignments.filterNot {
                it.id == first.id ||
                    it.id == second.id
            }

        val record = ShiftSwapRecord(
            firstAssignmentId = first.id,
            secondAssignmentId = second.id,
            firstEmployeeId = first.employeeId,
            secondEmployeeId = second.employeeId,
            firstDate = first.date,
            secondDate = second.date,
            createdAt = LocalDateTime.now()
                .toString()
        )

        val proposed = state.copy(
            assignments =
                keep + swappedFirst + swappedSecond,
            swapHistory =
                state.swapHistory + record
        )

        val involvedEmployees =
            setOf(
                first.employeeId,
                second.employeeId
            )

        val baselineErrors =
            validator.validate(state)
                .filter {
                    it.severity ==
                        AtwValidator.Severity.ERROR &&
                        it.employeeId in involvedEmployees
                }
                .map(::violationKey)
                .toSet()

        val introducedErrors =
            validator.validate(proposed)
                .filter {
                    it.severity ==
                        AtwValidator.Severity.ERROR &&
                        it.employeeId in involvedEmployees
                }
                .filter {
                    violationKey(it) !in baselineErrors
                }

        if (introducedErrors.isNotEmpty()) {
            showStatus(
                "Ruil niet mogelijk: " +
                    introducedErrors.first().message
            )
            return
        }

        commitActive(
            proposed,
            "Diensten veilig geruild"
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
                availability = emptyList(),
                absences = emptyList(),
                personMarkers = emptyList(),
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

    private fun hardManualBlockReason(
        employeeId: String,
        date: LocalDate,
        templateId: String,
        base: AppState
    ): String? {
        val employee =
            base.employees.firstOrNull {
                it.id == employeeId
            } ?: return "medewerker niet gevonden"

        val template =
            base.shiftTemplates.firstOrNull {
                it.id == templateId
            } ?: return "dienst niet gevonden"

        if (!employee.active) {
            return "${employee.name} is niet actief"
        }

        if (!employee.canWork(template.kind)) {
            return "${employee.name} mag deze dienst niet werken"
        }

        if (!base.allowsShiftOn(date, template)) {
            val hours =
                base.operatingHours.lastOrNull {
                    it.weekday ==
                        date.dayOfWeek.value
                }

            return if (hours?.closed == true) {
                "de locatie is op deze weekdag gesloten"
            } else {
                "dienst valt buiten de restauranttijden"
            }
        }

        base.absences.firstOrNull {
            it.employeeId == employeeId &&
                it.status ==
                    AbsenceStatus.APPROVED &&
                it.includes(date)
        }?.let {
            return "${employee.name} is afwezig (${it.type.name.lowercase()})"
        }

        return null
    }

    fun manualOverrideWarnings(
        employeeId: String,
        date: LocalDate,
        templateId: String,
        base: AppState = state
    ): List<String> {
        val employee =
            base.employees.firstOrNull {
                it.id == employeeId
            } ?: return emptyList()

        val template =
            base.shiftTemplates.firstOrNull {
                it.id == templateId
            } ?: return emptyList()

        val warnings =
            mutableListOf<String>()

        if (
            date.dayOfWeek.value !in
            template.enabledWeekdays
        ) {
            warnings +=
                "diensttemplate is normaal niet actief op deze weekdag"
        }

        val specific =
            base.availability.lastOrNull {
                it.employeeId == employeeId &&
                    it.date == date.toString()
            }

        val weekly =
            base.weeklyAvailability.lastOrNull {
                it.employeeId == employeeId &&
                    it.weekday ==
                        date.dayOfWeek.value
            }

        val available =
            if (specific != null) {
                specific.available
            } else {
                weekly?.available ?: true
            }

        if (!available) {
            warnings +=
                "${employee.name} staat als niet beschikbaar"
        }

        val fixedKind =
            if (specific != null) {
                specific.fixedShiftKind
            } else {
                weekly?.fixedShiftKind
            }

        if (
            fixedKind != null &&
            fixedKind != template.kind
        ) {
            warnings +=
                "wijkt af van vaste dienst ${fixedKind.name.lowercase()}"
        }

        val earliestText =
            if (specific != null) {
                specific.earliestStart
            } else {
                weekly?.earliestStart
            }

        val latestText =
            if (specific != null) {
                specific.latestEnd
            } else {
                weekly?.latestEnd
            }

        val earliest =
            earliestText?.let {
                runCatching {
                    LocalTime.parse(it)
                }.getOrNull()
            }

        val latest =
            latestText?.let {
                runCatching {
                    LocalTime.parse(it)
                }.getOrNull()
            }

        if (
            earliest != null &&
            template.startTime().isBefore(earliest)
        ) {
            warnings +=
                "dienst begint vóór beschikbaarheid $earliestText"
        }

        if (latest != null) {
            val start =
                date.atTime(
                    template.startTime()
                )

            var end =
                date.atTime(
                    template.endTime()
                )

            if (!end.isAfter(start)) {
                end = end.plusDays(1)
            }

            var latestEnd =
                date.atTime(latest)

            if (!latestEnd.isAfter(start)) {
                latestEnd =
                    latestEnd.plusDays(1)
            }

            if (end.isAfter(latestEnd)) {
                warnings +=
                    "dienst eindigt na beschikbaarheid $latestText"
            }
        }

        return warnings.distinct()
    }

    private fun manualBlockReason(
        employeeId: String,
        date: LocalDate,
        templateId: String,
        base: AppState
    ): String? {
        hardManualBlockReason(
            employeeId,
            date,
            templateId,
            base
        )?.let {
            return it
        }

        return manualOverrideWarnings(
            employeeId,
            date,
            templateId,
            base
        ).firstOrNull()
    }

    fun refreshSmartTemplates() {
        val before = state.shiftTemplates.size
        val updated = SmartShiftTemplates.augment(state)
        val added = (updated.shiftTemplates.size - before).coerceAtLeast(0)
        commitActive(updated, "Slimme diensttemplates bijgewerkt • $added nieuw")
    }

    private fun preparePlannerState(base: AppState): AppState =
        if (base.settings.autoGenerateSmartTemplates) SmartShiftTemplates.augment(base) else base

    private fun monthlyShiftCount(employeeId: String, base: AppState): Int =
        base.assignments.count { it.employeeId == employeeId }

    private fun violationKey(v: AtwValidator.Violation): String =
        "${v.employeeId}|${v.date}|${v.rule}|${v.message}"

    private fun commitActive(
        newState: AppState,
        message: String,
        recordUndo: Boolean = true
    ) {
        val location = activeLocation
        val normalized = newState.copy(
            settings = newState.settings.copy(locationName = location.name)
        )
        commitWorkspace(
            workspace.copy(
                locations = workspace.locations.map {
                    if (it.id == location.id)
                        it.copy(state = normalized)
                    else
                        it
                }
            ),
            message,
            recordUndo = recordUndo
        )
    }

    private fun commitWorkspace(
        next: DesktopWorkspace,
        message: String,
        recordUndo: Boolean = true
    ) {
        if (recordUndo) {
            undo.addLast(workspace)

            while (undo.size > 30) {
                undo.removeFirst()
            }

            redo.clear()
        }

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


    fun setManualCustomAssignment(
        employeeId: String,
        date: String,
        start: String,
        end: String,
        kind: ShiftKind,
        allowOperationalOverride: Boolean = false
    ) {
        val parsed = runCatching {
            LocalDate.parse(date)
        }.getOrNull()

        if (parsed == null) {
            showStatus("Ongeldige datum")
            return
        }

        val existing =
            state.shiftTemplates.firstOrNull {
                !it.archived &&
                    !it.autoGenerated &&
                    it.kind == kind &&
                    it.start == start &&
                    it.end == end &&
                    parsed.dayOfWeek.value in
                        it.enabledWeekdays
            }

        val template =
            existing ?: ShiftTemplate(
                name = "Aangepast $start-$end",
                kind = kind,
                start = start,
                end = end,
                enabledWeekdays =
                    setOf(parsed.dayOfWeek.value),
                autoGenerated = false,
                archived = false
            )

        val working =
            if (existing == null) {
                state.copy(
                    shiftTemplates =
                        state.shiftTemplates + template
                )
            } else {
                state
            }

        hardManualBlockReason(
            employeeId,
            parsed,
            template.id,
            working
        )?.let {
            showStatus("Niet opgeslagen: $it")
            return
        }

        val overrideWarnings =
            manualOverrideWarnings(
                employeeId,
                parsed,
                template.id,
                working
            )

        if (
            overrideWarnings.isNotEmpty() &&
            !allowOperationalOverride
        ) {
            showStatus(
                "Niet opgeslagen: " +
                    overrideWarnings.joinToString("; ") +
                    " • bevestig handmatige override"
            )
            return
        }

        val without =
            working.assignments.filterNot {
                it.employeeId == employeeId &&
                    it.date == parsed.toString()
            }

        val candidate = Assignment(
            employeeId = employeeId,
            date = parsed.toString(),
            shiftTemplateId = template.id,
            source =
                if (overrideWarnings.isNotEmpty())
                    "manual-custom-override"
                else
                    "manual-custom"
        )

        val proposed =
            working.copy(
                assignments = without + candidate
            )

        val baselineErrors =
            validator.validate(working)
                .filter {
                    it.severity ==
                        AtwValidator.Severity.ERROR &&
                        it.employeeId == employeeId
                }
                .map(::violationKey)
                .toSet()

        val introducedErrors =
            validator.validate(proposed)
                .filter {
                    it.severity ==
                        AtwValidator.Severity.ERROR &&
                        it.employeeId == employeeId
                }
                .filter {
                    violationKey(it) !in baselineErrors
                }

        if (introducedErrors.isNotEmpty()) {
            showStatus(
                "Niet opgeslagen: " +
                    introducedErrors.first().message
            )
            return
        }

        commitActive(
            proposed,
            if (overrideWarnings.isNotEmpty()) {
                "Aangepaste dienst opgeslagen • bewuste override"
            } else {
                "Aangepaste dienst opgeslagen"
            }
        )

        if (
            state.settings.autoFixAfterManualChanges
        ) {
            autoFix(recordUndo = false)
        }
    }
}
