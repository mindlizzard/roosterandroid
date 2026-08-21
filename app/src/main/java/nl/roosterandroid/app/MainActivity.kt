package nl.roosterandroid.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RoosterAndroidTheme {
                val storage = remember { ScheduleStorage(applicationContext) }
                val controller = remember { AppController(storage) }
                DisposableEffect(controller) {
                    onDispose(controller::close)
                }
                RoosterApp(controller)
            }
        }
    }
}

data class ReplacementCandidate(
    val employeeId: String,
    val name: String,
    val role: EmployeeRole,
    val plannedShiftsThisMonth: Int
)

private data class AutoFixOutcome(
    val proposal: RosterRepairProposal?,
    val diagnostic: ScheduleEngine.Result?
)

class AppController(private val storage: ScheduleStorage) {
    private val validator = AtwValidator()
    private val engine = ScheduleEngine(validator)
    private val repairPlanner = RosterRepairPlanner(validator, engine)
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val autoFixCancellation = AtomicBoolean(false)
    private var autoFixJob: Job? = null
    private val undoStates = ArrayDeque<AppState>()
    private val redoStates = ArrayDeque<AppState>()

    var state by mutableStateOf(storage.load())
        private set
    private var editCheckpoint: AppState = state
    var violations by mutableStateOf(validator.validate(state))
        private set
    var unfilled by mutableStateOf(emptyList<String>())
        private set
    var plannerWarnings by mutableStateOf(emptyList<String>())
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var scenarioSummaries by mutableStateOf(emptyList<String>())
        private set
    var repairProposal by mutableStateOf<RosterRepairProposal?>(null)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var pendingEditCount by mutableIntStateOf(0)
        private set
    var isAutoFixRunning by mutableStateOf(false)
        private set
    var autoFixProgress by mutableIntStateOf(0)
        private set
    var autoFixPhase by mutableStateOf("")
        private set

    private fun commit(
        newState: AppState,
        message: String? = null,
        recordHistory: Boolean = true
    ) {
        if (newState == state) {
            if (message != null) status = message
            return
        }
        if (isAutoFixRunning) cancelAutoFix(silent = true)
        val checkedViolations = runCatching { validator.validate(newState) }
            .getOrElse { error ->
                status = "Wijziging niet toegepast: ${error.message ?: "ongeldige roosterdata"}"
                return
            }
        runCatching { storage.save(newState) }
            .getOrElse { error ->
                status = "Wijziging niet opgeslagen: ${error.message ?: "opslagfout"}"
                return
            }
        if (recordHistory) {
            undoStates.addLast(state)
            while (undoStates.size > 40) undoStates.removeFirst()
            redoStates.clear()
            pendingEditCount += 1
        }
        state = newState
        violations = checkedViolations
        repairProposal = null
        updateHistoryState()
        if (message != null) status = message
    }

    private fun applyHistoryState(newState: AppState, message: String) {
        state = newState
        storage.save(state)
        violations = validator.validate(state)
        repairProposal = null
        unfilled = emptyList()
        plannerWarnings = emptyList()
        updateHistoryState()
        status = message
    }

    private fun updateHistoryState() {
        canUndo = undoStates.isNotEmpty()
        canRedo = redoStates.isNotEmpty()
    }

    fun undo() {
        if (undoStates.isEmpty()) return
        cancelAutoFix(silent = true)
        val previous = undoStates.removeLast()
        redoStates.addLast(state)
        pendingEditCount = if (previous == editCheckpoint) {
            0
        } else {
            (pendingEditCount - 1).coerceAtLeast(1)
        }
        applyHistoryState(previous, "Laatste wijziging ongedaan gemaakt")
    }

    fun redo() {
        if (redoStates.isEmpty()) return
        cancelAutoFix(silent = true)
        undoStates.addLast(state)
        val next = redoStates.removeLast()
        pendingEditCount = if (next == editCheckpoint) 0 else pendingEditCount + 1
        applyHistoryState(next, "Wijziging opnieuw uitgevoerd")
    }

    fun saveEditCheckpoint() {
        editCheckpoint = state
        pendingEditCount = 0
        undoStates.clear()
        redoStates.clear()
        updateHistoryState()
        storage.save(state)
        status = "Wijzigingen opgeslagen als nieuw herstelpunt"
    }

    fun revertEditSession() {
        if (state == editCheckpoint) {
            status = "Geen wijzigingen om terug te draaien"
            return
        }
        cancelAutoFix(silent = true)
        undoStates.addLast(state)
        redoStates.clear()
        pendingEditCount = 0
        applyHistoryState(editCheckpoint, "Alle wijzigingen sinds het herstelpunt teruggedraaid")
    }

    private fun checkRosterAfterEdit() {
        val message = status
        repairProposal = repairPlanner.propose(state)
        val hardErrors = violations.count { it.severity == AtwValidator.Severity.ERROR }
        status = when {
            repairProposal != null -> {
                listOfNotNull(message, "automatische oplossing beschikbaar").joinToString(" • ")
            }
            hardErrors > 0 -> {
                listOfNotNull(
                    message,
                    "rooster gecontroleerd: $hardErrors fout(en), geen automatische oplossing gevonden"
                ).joinToString(" • ")
            }
            else -> {
                listOfNotNull(message, "rooster gecontroleerd: geen herstel nodig").joinToString(" • ")
            }
        }
    }

    fun runDeepAutoFix() {
        if (isAutoFixRunning) {
            status = "Auto-fix is al bezig"
            return
        }
        val snapshot = state
        val previousProposal = repairProposal
        autoFixCancellation.set(false)
        isAutoFixRunning = true
        autoFixProgress = 1
        autoFixPhase = "Auto-fix starten"
        status = "Auto-fix puzzelt op de achtergrond; je kunt annuleren"

        autoFixJob = controllerScope.launch {
            try {
                val outcome = withContext(Dispatchers.Default) {
                    val proposal = repairPlanner.proposeDeep(
                        state = snapshot,
                        shouldCancel = autoFixCancellation::get,
                        onProgress = { progress, phase ->
                            controllerScope.launch {
                                if (!autoFixCancellation.get()) {
                                    autoFixProgress = progress
                                    autoFixPhase = phase
                                }
                            }
                        }
                    )
                    val diagnostic = if (proposal == null && !autoFixCancellation.get()) {
                        engine.generate(
                            state = snapshot,
                            searchEffort = 3,
                            shouldCancel = autoFixCancellation::get
                        )
                    } else {
                        null
                    }
                    AutoFixOutcome(proposal, diagnostic)
                }

                if (autoFixCancellation.get()) return@launch
                if (state != snapshot) {
                    status = "Auto-fix gestopt omdat het rooster ondertussen is gewijzigd"
                    return@launch
                }
                if (outcome.proposal != null) {
                    repairProposal = outcome.proposal
                    status = "Auto-fix heeft verder gepuzzeld: controleer de voorgestelde oplossing"
                } else {
                    repairProposal = previousProposal
                    outcome.diagnostic?.let { diagnostic ->
                        unfilled = diagnostic.unfilled
                        plannerWarnings = diagnostic.warnings
                    }
                    val hardErrors = violations.count {
                        it.severity == AtwValidator.Severity.ERROR
                    }
                    status = if (hardErrors == 0 && unfilled.isEmpty()) {
                        "Auto-fix: het rooster is al kloppend"
                    } else {
                        "Auto-fix vond geen betere oplossing; controleer de resterende punten handmatig"
                    }
                }
            } catch (error: Throwable) {
                if (!autoFixCancellation.get()) {
                    status = "Auto-fix is gestopt: ${error.message ?: "onbekende fout"}"
                }
            } finally {
                isAutoFixRunning = false
                autoFixProgress = 0
                autoFixPhase = ""
                autoFixJob = null
            }
        }
    }

    fun cancelAutoFix(silent: Boolean = false) {
        if (!isAutoFixRunning) return
        autoFixCancellation.set(true)
        autoFixJob?.cancel()
        autoFixJob = null
        isAutoFixRunning = false
        autoFixProgress = 0
        autoFixPhase = ""
        if (!silent) status = "Auto-fix geannuleerd"
    }

    fun close() {
        autoFixCancellation.set(true)
        autoFixJob?.cancel()
        autoFixJob = null
        controllerScope.cancel()
    }

    fun applyRepairProposal() {
        val proposal = repairProposal ?: return
        commit(
            state.copy(assignments = proposal.assignments),
            "Automatische roosteroplossing toegepast"
        )
        unfilled = proposal.unfilled
        plannerWarnings = proposal.warnings
    }

    fun dismissRepairProposal() {
        repairProposal = null
        status = "Automatische oplossing niet toegepast"
    }

    fun activeLocation(): RestaurantLocation = state.activeLocation()

    fun activeEmployees(): List<Employee> = state.employees.filter {
        it.active && it.worksAt(state.activeLocationId)
    }

    fun activeAssignments(): List<Assignment> = state.assignments.filter {
        it.locationId == state.activeLocationId
    }

    fun addEmployee(name: String, role: EmployeeRole) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val employee = Employee(
            name = clean,
            role = role,
            contractedDaysPerWeek = if (role == EmployeeRole.BORROWED) 3 else 5,
            contractedHoursPerWeek = if (role == EmployeeRole.BORROWED) 24.0 else 40.0,
            maxShiftsPerWeek = if (role == EmployeeRole.BORROWED) 3 else 5,
            canSetup = role != EmployeeRole.BORROWED,
            locationIds = setOf(state.activeLocationId)
        )
        commit(state.copy(employees = state.employees + employee), "$clean toegevoegd")
    }

    fun updateEmployee(employee: Employee) {
        commit(state.copy(employees = state.employees.map { if (it.id == employee.id) employee else it }))
        if (state.assignments.isNotEmpty()) checkRosterAfterEdit()
    }

    fun upsertAvailability(availability: Availability) {
        val updated = state.availability.filterNot { it.employeeId == availability.employeeId && it.date == availability.date } + availability
        commit(state.copy(availability = updated), "Beschikbaarheid opgeslagen")
        if (state.assignments.isNotEmpty()) checkRosterAfterEdit()
    }

    fun removeAvailability(employeeId: String, date: String) {
        commit(state.copy(availability = state.availability.filterNot { it.employeeId == employeeId && it.date == date }))
    }

    fun upsertWeeklyAvailability(rule: WeeklyAvailability) {
        val updated = state.weeklyAvailability.filterNot {
            it.employeeId == rule.employeeId && it.weekday == rule.weekday
        } + rule
        commit(state.copy(weeklyAvailability = updated), "Vaste weekbeschikbaarheid opgeslagen")
        if (state.assignments.isNotEmpty()) checkRosterAfterEdit()
    }

    fun removeWeeklyAvailability(employeeId: String, weekday: Int) {
        commit(state.copy(
            weeklyAvailability = state.weeklyAvailability.filterNot {
                it.employeeId == employeeId && it.weekday == weekday
            }
        ), "Vaste weekregel verwijderd")
    }

    fun upsertAbsence(absence: Absence) {
        val start = runCatching { LocalDate.parse(absence.startDate) }.getOrNull()
        val end = runCatching { LocalDate.parse(absence.endDate) }.getOrNull()
        if (start == null || end == null || end.isBefore(start)) {
            status = "Ongeldige afwezigheidsperiode"
            return
        }
        val updated = state.absences.filterNot { it.id == absence.id } + absence
        val removedAssignments = if (absence.status == AbsenceStatus.APPROVED) {
            state.assignments.filter { a ->
                if (a.employeeId != absence.employeeId) return@filter false
                val d = runCatching { LocalDate.parse(a.date) }.getOrNull() ?: return@filter false
                !d.isBefore(start) && !d.isAfter(end)
            }
        } else {
            emptyList()
        }
        val removedIds = removedAssignments.map { it.id }.toSet()
        val keepAssignments = state.assignments.filterNot { it.id in removedIds }
        val replacements = removedAssignments.map { assignment ->
            ReplacementRequest(
                locationId = assignment.locationId,
                date = assignment.date,
                shiftTemplateId = assignment.shiftTemplateId,
                originalEmployeeId = assignment.employeeId,
                absenceId = absence.id,
                absenceType = absence.type
            )
        }
        val message = if (replacements.isEmpty()) {
            "${absence.type.name.lowercase()} opgeslagen"
        } else {
            "Afwezigheid opgeslagen • ${replacements.size} vervanging(en) nodig"
        }
        commit(
            state.copy(
                absences = updated,
                assignments = keepAssignments,
                replacementRequests = state.replacementRequests + replacements
            ),
            message
        )
    }

    fun removeAbsence(id: String) {
        commit(
            state.copy(
                absences = state.absences.filterNot { it.id == id },
                replacementRequests = state.replacementRequests.map { request ->
                    if (request.absenceId == id && request.status == ReplacementStatus.OPEN) {
                        request.copy(status = ReplacementStatus.CANCELLED)
                    } else {
                        request
                    }
                }
            ),
            "Afwezigheid verwijderd"
        )
    }

    fun upsertResponsibility(rule: ResponsibilityRule) {
        val located = rule.copy(locationId = state.activeLocationId)
        val updated = state.responsibilities.filterNot { it.id == rule.id } + located
        commit(state.copy(responsibilities = updated), "Vaste verantwoordelijkheid opgeslagen")
    }

    fun removeResponsibility(id: String) {
        commit(state.copy(responsibilities = state.responsibilities.filterNot { it.id == id }), "Verantwoordelijkheid verwijderd")
    }

    fun upsertPersonMarker(marker: PersonDayMarker) {
        val located = marker.copy(locationId = state.activeLocationId)
        val updated = state.personMarkers.filterNot { it.id == marker.id } + located
        commit(state.copy(personMarkers = updated), "Marker opgeslagen")
    }

    fun removePersonMarker(id: String) {
        commit(state.copy(personMarkers = state.personMarkers.filterNot { it.id == id }), "Marker verwijderd")
    }

    fun upsertDayNote(date: String, text: String) {
        val clean = text.trim()
        val keep = state.dayNotes.filterNot {
            it.date == date && it.locationId == state.activeLocationId
        }
        commit(
            state.copy(
                dayNotes = if (clean.isEmpty()) keep else keep + DayNote(
                    date = date,
                    text = clean,
                    locationId = state.activeLocationId
                )
            ),
            if (clean.isEmpty()) "Bijzonderheid verwijderd" else "Bijzonderheid opgeslagen"
        )
    }

    fun setManualAssignment(
        employeeId: String,
        date: String,
        templateId: String?,
        lockMode: AssignmentLockMode = AssignmentLockMode.FIXED
    ) {
        val without = state.assignments.filterNot {
            it.employeeId == employeeId &&
                it.date == date &&
                it.locationId == state.activeLocationId
        }
        val withoutDayOff = state.manualDaysOff.filterNot {
            it.employeeId == employeeId &&
                it.date == date &&
                it.locationId == state.activeLocationId
        }
        if (templateId == null) {
            commit(
                state.copy(
                    assignments = without,
                    manualDaysOff = withoutDayOff + ManualDayOff(
                        employeeId = employeeId,
                        date = date,
                        locationId = state.activeLocationId
                    )
                ),
                "Vrije dag handmatig vastgezet"
            )
            checkRosterAfterEdit()
            return
        }

        val baseState = state.copy(
            assignments = without,
            manualDaysOff = withoutDayOff
        )
        manualBlockReason(employeeId, date, templateId, baseState)?.let {
            status = "Niet opgeslagen: $it"
            return
        }

        val candidate = Assignment(
            employeeId = employeeId,
            date = date,
            shiftTemplateId = templateId,
            source = "manual",
            locationId = state.activeLocationId,
            lockMode = lockMode
        )
        val proposed = state.copy(
            assignments = without + candidate,
            manualDaysOff = withoutDayOff
        )
        commit(proposed, "Handmatige dienst opgeslagen")
        checkRosterAfterEdit()
    }

    fun setManualAssignments(
        cells: Set<RosterCellSelection>,
        templateId: String?,
        lockMode: AssignmentLockMode = AssignmentLockMode.FIXED
    ) {
        if (cells.isEmpty()) return
        val keys = cells.map { "${it.employeeId}|${it.date}" }.toSet()
        val originalAssignments = state.assignments.filter {
            it.locationId == state.activeLocationId && "${it.employeeId}|${it.date}" in keys
        }.groupBy { "${it.employeeId}|${it.date}" }
        val originalDaysOff = state.manualDaysOff.filter {
            it.locationId == state.activeLocationId && "${it.employeeId}|${it.date}" in keys
        }.groupBy { "${it.employeeId}|${it.date}" }
        var assignments = state.assignments.filterNot {
            it.locationId == state.activeLocationId && "${it.employeeId}|${it.date}" in keys
        }
        var daysOff = state.manualDaysOff.filterNot {
            it.locationId == state.activeLocationId && "${it.employeeId}|${it.date}" in keys
        }

        if (templateId == null) {
            daysOff = daysOff + cells.map {
                ManualDayOff(it.employeeId, it.date, state.activeLocationId)
            }
            commit(
                state.copy(assignments = assignments, manualDaysOff = daysOff),
                "${cells.size} roostervak(ken) als vrij vastgezet"
            )
            checkRosterAfterEdit()
            return
        }

        var accepted = 0
        var blocked = 0
        cells.sortedWith(compareBy({ it.date }, { it.employeeId })).forEach { cell ->
            val candidateState = state.copy(assignments = assignments, manualDaysOff = daysOff)
            if (manualAssignmentBlockReason(
                    candidateState,
                    cell.employeeId,
                    cell.date,
                    templateId
                ) == null
            ) {
                assignments = assignments + Assignment(
                    employeeId = cell.employeeId,
                    date = cell.date,
                    shiftTemplateId = templateId,
                    source = "manual-bulk",
                    locationId = state.activeLocationId,
                    lockMode = lockMode
                )
                accepted += 1
            } else {
                val key = "${cell.employeeId}|${cell.date}"
                assignments = assignments + originalAssignments[key].orEmpty()
                daysOff = daysOff + originalDaysOff[key].orEmpty()
                blocked += 1
            }
        }
        if (accepted == 0) {
            status = "Geen geselecteerde vakken konden worden ingepland"
            return
        }
        commit(
            state.copy(assignments = assignments, manualDaysOff = daysOff),
            "$accepted vak(ken) aangepast" + if (blocked > 0) " • $blocked overgeslagen" else ""
        )
        checkRosterAfterEdit()
    }

    fun clearSelectedRosterCells(cells: Set<RosterCellSelection>) {
        if (cells.isEmpty()) return
        val result = clearRosterCells(state, cells)
        val removed = result.removedAssignments + result.removedDaysOff
        if (removed == 0) {
            status = "De geselecteerde vakken waren al leeg"
            return
        }
        commit(
            result.updatedState,
            "${result.removedAssignments} dienst(en) en " +
                "${result.removedDaysOff} vrije blokkade(s) verwijderd"
        )
        checkRosterAfterEdit()
    }

    fun changeSelectedLockMode(
        cells: Set<RosterCellSelection>,
        lockMode: AssignmentLockMode
    ) {
        if (cells.isEmpty()) return
        val result = changeRosterAssignmentLocks(state, cells, lockMode)
        if (result.changedAssignments == 0) {
            status = "Geen bestaande geselecteerde diensten hoefden te worden gewijzigd"
            return
        }
        commit(
            result.updatedState,
            "${result.changedAssignments} dienst(en) ingesteld op ${assignmentLockLabel(lockMode)}"
        )
        checkRosterAfterEdit()
    }

    fun copyDay(
        sourceDate: LocalDate,
        targetDate: LocalDate,
        lockMode: AssignmentLockMode = AssignmentLockMode.PREFERRED
    ) {
        if (sourceDate == targetDate) {
            status = "Kies twee verschillende datums"
            return
        }
        val shownMonth = YearMonth.of(state.year, state.month)
        if (YearMonth.from(sourceDate) != shownMonth || YearMonth.from(targetDate) != shownMonth) {
            status = "Kies twee datums binnen de getoonde maand"
            return
        }
        val result = copyRosterDay(state, sourceDate, targetDate, lockMode)
        if (result.sourceWasEmpty) {
            status = "De brondag bevat geen rooster om te kopiëren"
            return
        }
        commit(
            result.updatedState,
            "Dag gekopieerd: ${result.copiedAssignments} dienst(en)" +
                if (result.copiedDaysOff > 0) {
                    " • ${result.copiedDaysOff} vrije dag(en)"
                } else {
                    ""
                } +
                if (result.skippedAssignments > 0) {
                    " • ${result.skippedAssignments} overgeslagen"
                } else {
                    ""
                }
        )
        checkRosterAfterEdit()
    }

    fun copyWeek(
        sourceMonday: LocalDate,
        targetMonday: LocalDate,
        lockMode: AssignmentLockMode = AssignmentLockMode.PREFERRED
    ) {
        if (sourceMonday == targetMonday) {
            status = "Kies twee verschillende weken"
            return
        }
        val result = copyRosterWeek(state, sourceMonday, targetMonday, lockMode)
        if (result.sourceWasEmpty) {
            status = "De bronweek bevat geen rooster om te kopiëren"
            return
        }
        commit(
            result.updatedState,
            "Week gekopieerd: ${result.copiedAssignments} dienst(en)" +
                if (result.copiedDaysOff > 0) {
                    " • ${result.copiedDaysOff} vrije dag(en)"
                } else {
                    ""
                } +
                if (result.skippedAssignments > 0) {
                    " • ${result.skippedAssignments} overgeslagen"
                } else {
                    ""
                }
        )
        checkRosterAfterEdit()
    }

    fun swapAssignments(date: String, firstEmployeeId: String, secondEmployeeId: String) {
        if (firstEmployeeId == secondEmployeeId) {
            status = "Kies twee verschillende managers"
            return
        }
        val parsed = runCatching { LocalDate.parse(date) }.getOrNull()
        if (parsed == null) {
            status = "Controleer datum"
            return
        }
        val first = state.assignments.lastOrNull {
            it.employeeId == firstEmployeeId &&
                it.date == date &&
                it.locationId == state.activeLocationId
        }
        val second = state.assignments.lastOrNull {
            it.employeeId == secondEmployeeId &&
                it.date == date &&
                it.locationId == state.activeLocationId
        }
        if (first == null && second == null) {
            status = "Geen diensten om te ruilen op $date"
            return
        }

        val keep = state.assignments.filterNot {
            it.date == date &&
                it.locationId == state.activeLocationId &&
                (it.employeeId == firstEmployeeId || it.employeeId == secondEmployeeId)
        }
        val swapBase = state.copy(assignments = keep)
        if (first != null) manualBlockReason(
            secondEmployeeId,
            date,
            first.shiftTemplateId,
            swapBase
        )?.let {
            status = "Ruil niet mogelijk: $it"
            return
        }
        if (second != null) manualBlockReason(
            firstEmployeeId,
            date,
            second.shiftTemplateId,
            swapBase
        )?.let {
            status = "Ruil niet mogelijk: $it"
            return
        }

        val swapped = buildList {
            if (first != null) add(Assignment(employeeId = secondEmployeeId, date = date, shiftTemplateId = first.shiftTemplateId, source = "manual-swap", locationId = state.activeLocationId))
            if (second != null) add(Assignment(employeeId = firstEmployeeId, date = date, shiftTemplateId = second.shiftTemplateId, source = "manual-swap", locationId = state.activeLocationId))
        }
        val proposed = state.copy(assignments = keep + swapped)
        commit(proposed, "Diensten geruild")
        checkRosterAfterEdit()
    }

    private fun manualBlockReason(
        employeeId: String,
        date: String,
        templateId: String,
        baseState: AppState = state
    ): String? = manualAssignmentBlockReason(baseState, employeeId, date, templateId)

    fun upsertDayDemand(demand: DayDemand) {
        val located = demand.copy(locationId = state.activeLocationId)
        val updated = state.dayDemands.filterNot {
            it.date == demand.date && it.locationId == state.activeLocationId
        } + located
        commit(state.copy(dayDemands = updated), "Bezetting opgeslagen")
        if (state.assignments.isNotEmpty()) checkRosterAfterEdit()
    }

    fun switchLocation(locationId: String) {
        val location = state.locations.firstOrNull { it.id == locationId && it.active }
        if (location == null) {
            status = "Vestiging niet gevonden"
            return
        }
        commit(
            state.copy(
                activeLocationId = location.id,
                settings = state.settings.copy(
                    locationName = location.name,
                    busyWeekdays = location.busyWeekdays,
                    requireSetupDaily = location.requireSetupDaily,
                    requireMiddleOnBusyDays = location.requireMiddleOnBusyDays,
                    requireCloseDaily = location.requireCloseDaily,
                    monthEndCloseManagers = location.monthEndCloseManagers
                )
            ),
            "Vestiging ${location.name} geselecteerd"
        )
        unfilled = emptyList()
        plannerWarnings = emptyList()
        scenarioSummaries = emptyList()
    }

    fun selectNextLocation() {
        val active = state.locations.filter { it.active }
        if (active.size < 2) return
        val current = active.indexOfFirst { it.id == state.activeLocationId }.coerceAtLeast(0)
        switchLocation(active[(current + 1) % active.size].id)
    }

    fun addLocation(name: String, open24Hours: Boolean) {
        val clean = name.trim()
        if (clean.isEmpty()) {
            status = "Vul een vestigingsnaam in"
            return
        }
        val location = RestaurantLocation(
            name = clean,
            openingHours = defaultOpeningHours(open24Hours),
            enforceOpeningCoverage = true,
            minimumManagersWhileOpen = 1
        )
        val templates = recommendedShiftTemplates(location)
        commit(
            state.copy(
                locations = state.locations + location,
                activeLocationId = location.id,
                shiftTemplates = state.shiftTemplates + templates,
                settings = state.settings.copy(locationName = location.name)
            ),
            "$clean toegevoegd met ${templates.size} passende diensten"
        )
        unfilled = emptyList()
        plannerWarnings = emptyList()
    }

    fun updateLocation(location: RestaurantLocation) {
        val normalizedHours = (1..7).map { weekday ->
            location.openingHours.lastOrNull { it.weekday == weekday }
                ?: defaultOpeningHours().first { it.weekday == weekday }
        }
        val invalid = normalizedHours.firstOrNull { rule ->
            rule.mode == OpeningMode.OPEN && (
                runCatching { java.time.LocalTime.parse(rule.open) }.isFailure ||
                    runCatching { java.time.LocalTime.parse(rule.close) }.isFailure ||
                    rule.open == rule.close
                )
        }
        if (invalid != null) {
            status = "Controleer openingstijden van dag ${invalid.weekday}; gebruik HH:mm of kies 24 uur"
            return
        }
        val clean = location.copy(
            name = location.name.trim().ifBlank { "Naamloze vestiging" },
            openingHours = normalizedHours,
            minimumManagersWhileOpen = location.minimumManagersWhileOpen.coerceIn(0, 10),
            monthEndCloseManagers = location.monthEndCloseManagers.coerceIn(1, 6)
        )
        val settings = if (clean.id == state.activeLocationId) {
            state.settings.copy(
                locationName = clean.name,
                busyWeekdays = clean.busyWeekdays,
                requireSetupDaily = clean.requireSetupDaily,
                requireMiddleOnBusyDays = clean.requireMiddleOnBusyDays,
                requireCloseDaily = clean.requireCloseDaily,
                monthEndCloseManagers = clean.monthEndCloseManagers
            )
        } else {
            state.settings
        }
        commit(
            state.copy(
                locations = state.locations.map { if (it.id == clean.id) clean else it },
                settings = settings
            ),
            "Vestiging opgeslagen"
        )
        if (state.assignments.isNotEmpty()) checkRosterAfterEdit()
    }

    fun upsertSpecialOpeningHours(special: SpecialOpeningHours) {
        val date = special.parsedDate()
        if (date == null) {
            status = "Controleer de uitzonderingsdatum"
            return
        }
        if (special.mode == OpeningMode.OPEN) {
            val validOpen = runCatching { java.time.LocalTime.parse(special.open) }.isSuccess
            val validClose = runCatching { java.time.LocalTime.parse(special.close) }.isSuccess
            if (!validOpen || !validClose || special.open == special.close) {
                status = "Gebruik geldige verschillende tijden als HH:mm"
                return
            }
        }
        val located = special.copy(
            locationId = state.activeLocationId,
            date = date.toString(),
            note = special.note.trim()
        )
        val updated = state.specialOpeningHours.filterNot {
            it.id == located.id ||
                (it.locationId == located.locationId && it.date == located.date)
        } + located
        commit(
            state.copy(specialOpeningHours = updated),
            "Uitzonderlijke openingstijd opgeslagen"
        )
        if (state.assignments.isNotEmpty()) checkRosterAfterEdit()
    }

    fun removeSpecialOpeningHours(id: String) {
        commit(
            state.copy(specialOpeningHours = state.specialOpeningHours.filterNot { it.id == id }),
            "Uitzonderlijke openingstijd verwijderd"
        )
        if (state.assignments.isNotEmpty()) checkRosterAfterEdit()
    }

    fun setEmployeeAtLocation(employeeId: String, locationId: String, enabled: Boolean) {
        val employee = state.employees.firstOrNull { it.id == employeeId } ?: return
        if (!enabled && (state.assignments + state.assignmentHistory).any {
                it.employeeId == employeeId && it.locationId == locationId
            }
        ) {
            status = "${employee.name} heeft roosterhistorie op deze vestiging en kan daarom niet worden losgekoppeld"
            return
        }
        val locationIds = if (enabled) employee.locationIds + locationId else employee.locationIds - locationId
        if (locationIds.isEmpty()) {
            status = "Een manager moet aan minimaal één vestiging gekoppeld blijven"
            return
        }
        updateEmployee(employee.copy(locationIds = locationIds))
        status = if (enabled) "${employee.name} toegevoegd aan vestiging" else "${employee.name} losgekoppeld"
    }

    fun addRecommendedTemplates() {
        val proposed = recommendedShiftTemplates(activeLocation())
        val existingKeys = state.shiftTemplates
            .filter { it.locationId == state.activeLocationId }
            .map { "${it.kind}|${it.start}|${it.end}|${it.enabledWeekdays.sorted()}" }
            .toSet()
        val missing = proposed.filter {
            "${it.kind}|${it.start}|${it.end}|${it.enabledWeekdays.sorted()}" !in existingKeys
        }
        if (missing.isEmpty()) {
            status = "Alle passende diensten bestaan al"
        } else {
            commit(
                state.copy(shiftTemplates = state.shiftTemplates + missing),
                "${missing.size} passende dienst(en) toegevoegd"
            )
        }
    }

    fun upsertStaffingRequirement(requirement: StaffingRequirement) {
        val validStart = runCatching { java.time.LocalTime.parse(requirement.start) }.isSuccess
        val validEnd = runCatching { java.time.LocalTime.parse(requirement.end) }.isSuccess
        if (!validStart || !validEnd || requirement.start == requirement.end) {
            status = "Gebruik geldige verschillende tijden als HH:mm"
            return
        }
        if (requirement.weekdays.isEmpty()) {
            status = "Kies minimaal één weekdag"
            return
        }
        val located = requirement.copy(
            locationId = state.activeLocationId,
            name = requirement.name.trim().ifBlank { "Bezetting" },
            minimumManagers = requirement.minimumManagers.coerceIn(1, 10)
        )
        commit(
            state.copy(
                staffingRequirements = state.staffingRequirements.filterNot { it.id == located.id } + located
            ),
            "Tijdvakbezetting opgeslagen"
        )
        if (state.assignments.isNotEmpty()) checkRosterAfterEdit()
    }

    fun removeStaffingRequirement(id: String) {
        commit(
            state.copy(staffingRequirements = state.staffingRequirements.filterNot { it.id == id }),
            "Tijdvakbezetting verwijderd"
        )
    }

    fun replacementCandidates(requestId: String): List<ReplacementCandidate> {
        val request = state.replacementRequests.firstOrNull {
            it.id == requestId && it.status == ReplacementStatus.OPEN
        } ?: return emptyList()
        if (request.locationId != state.activeLocationId) return emptyList()
        val template = state.shiftTemplates.firstOrNull { it.id == request.shiftTemplateId }
            ?: return emptyList()
        val date = runCatching { LocalDate.parse(request.date) }.getOrNull() ?: return emptyList()
        val ym = YearMonth.from(date)

        return state.employees.asSequence()
            .filter { it.active && it.id != request.originalEmployeeId && it.worksAt(request.locationId) }
            .filter { employee ->
                state.assignments.none { it.employeeId == employee.id && it.date == request.date }
            }
            .filter { employee -> manualBlockReason(employee.id, request.date, template.id) == null }
            .filter { employee ->
                val candidate = Assignment(
                    employeeId = employee.id,
                    date = request.date,
                    shiftTemplateId = template.id,
                    source = "replacement-candidate",
                    locationId = request.locationId
                )
                val baseline = validator.validate(state)
                    .filter { it.severity == AtwValidator.Severity.ERROR && it.employeeId == employee.id }
                    .map { "${it.date}|${it.rule}|${it.message}" }
                    .toSet()
                validator.validate(state.copy(assignments = state.assignments + candidate))
                    .filter { it.severity == AtwValidator.Severity.ERROR && it.employeeId == employee.id }
                    .none { "${it.date}|${it.rule}|${it.message}" !in baseline }
            }
            .map { employee ->
                ReplacementCandidate(
                    employeeId = employee.id,
                    name = employee.name,
                    role = employee.role,
                    plannedShiftsThisMonth = (state.assignments + state.assignmentHistory).count {
                        it.employeeId == employee.id &&
                            runCatching { YearMonth.from(LocalDate.parse(it.date)) == ym }.getOrDefault(false)
                    }
                )
            }
            .sortedWith(
                compareBy<ReplacementCandidate> { if (it.role == EmployeeRole.BORROWED) 1 else 0 }
                    .thenBy { it.plannedShiftsThisMonth }
                    .thenBy { it.name.lowercase() }
            )
            .toList()
    }

    fun assignReplacement(requestId: String, employeeId: String) {
        val request = state.replacementRequests.firstOrNull {
            it.id == requestId && it.status == ReplacementStatus.OPEN
        } ?: run {
            status = "Open dienst niet gevonden"
            return
        }
        if (replacementCandidates(requestId).none { it.employeeId == employeeId }) {
            status = "Deze manager is niet meer beschikbaar voor de vervanging"
            return
        }
        val assignment = Assignment(
            employeeId = employeeId,
            date = request.date,
            shiftTemplateId = request.shiftTemplateId,
            source = "replacement",
            locationId = request.locationId
        )
        val name = state.employees.firstOrNull { it.id == employeeId }?.name ?: "Vervanger"
        commit(
            state.copy(
                assignments = state.assignments + assignment,
                replacementRequests = state.replacementRequests.map {
                    if (it.id == requestId) {
                        it.copy(status = ReplacementStatus.FILLED, replacementEmployeeId = employeeId)
                    } else {
                        it
                    }
                }
            ),
            "$name ingepland als vervanger"
        )
    }

    fun cancelReplacement(requestId: String) {
        commit(
            state.copy(
                replacementRequests = state.replacementRequests.map {
                    if (it.id == requestId) it.copy(status = ReplacementStatus.CANCELLED) else it
                }
            ),
            "Open dienst gesloten zonder vervanger"
        )
    }

    fun swapAssignments(firstAssignmentId: String, secondAssignmentId: String) {
        if (firstAssignmentId == secondAssignmentId) {
            status = "Kies twee verschillende diensten"
            return
        }

        val first = state.assignments.firstOrNull { it.id == firstAssignmentId }
        val second = state.assignments.firstOrNull { it.id == secondAssignmentId }
        if (first == null || second == null) {
            status = "Dienst niet gevonden"
            return
        }
        if (first.employeeId == second.employeeId) {
            status = "Kies diensten van twee verschillende managers"
            return
        }

        val keep = state.assignments.filterNot { it.id == first.id || it.id == second.id }
        val swapBase = state.copy(assignments = keep)
        manualBlockReason(
            second.employeeId,
            first.date,
            first.shiftTemplateId,
            swapBase
        )?.let {
            status = "Ruil niet mogelijk: $it"
            return
        }
        manualBlockReason(
            first.employeeId,
            second.date,
            second.shiftTemplateId,
            swapBase
        )?.let {
            status = "Ruil niet mogelijk: $it"
            return
        }

        val swappedFirst = first.copy(employeeId = second.employeeId, source = "manual-swap")
        val swappedSecond = second.copy(employeeId = first.employeeId, source = "manual-swap")
        val proposed = state.copy(assignments = keep + swappedFirst + swappedSecond)

        val record = ShiftSwapRecord(
            firstAssignmentId = first.id,
            secondAssignmentId = second.id,
            firstEmployeeId = first.employeeId,
            secondEmployeeId = second.employeeId,
            firstDate = first.date,
            secondDate = second.date
        )
        commit(
            proposed.copy(swapHistory = state.swapHistory + record),
            "Diensten geruild"
        )
        checkRosterAfterEdit()
    }

    fun compareScenarios() {
        val normal = engine.generate(state)

        val withoutBorrowedState = state.copy(
            employees = state.employees.map {
                if (it.role == EmployeeRole.BORROWED) it.copy(active = false) else it
            }
        )
        val withoutBorrowed = engine.generate(withoutBorrowedState)

        val freeDaysState = state.copy(
            settings = state.settings.copy(
                minimumTwoDayOffBlocks = maxOf(1, state.settings.minimumTwoDayOffBlocks),
                preferredTwoDayOffBlocks = maxOf(2, state.settings.preferredTwoDayOffBlocks)
            )
        )
        val freeDays = engine.generate(freeDaysState)

        fun line(name: String, result: ScheduleEngine.Result): String {
            val borrowed = result.assignments.count { a ->
                state.employees.firstOrNull { it.id == a.employeeId }?.role == EmployeeRole.BORROWED
            }
            return "$name • open ${result.unfilled.size} • waarschuwingen ${result.warnings.size} • leen $borrowed"
        }

        scenarioSummaries = listOf(
            line("Normaal", normal),
            line("Zonder leenmanager", withoutBorrowed),
            line("Voorkeur 2 dagen vrij", freeDays)
        )
        status = "Scenario's vergeleken"
    }

    fun addTemplate(
        name: String,
        kind: ShiftKind,
        start: String,
        end: String,
        weekdays: Set<Int>
    ): Boolean {
        val template = ShiftTemplate(
            name = name.trim(),
            kind = kind,
            start = start,
            end = end,
            enabledWeekdays = weekdays,
            locationId = state.activeLocationId
        )
        templateProblem(template)?.let {
            status = it
            return false
        }
        commit(
            state.copy(shiftTemplates = state.shiftTemplates + template),
            "Diensttemplate ${template.name} toegevoegd"
        )
        return true
    }

    fun updateTemplate(template: ShiftTemplate) {
        val located = template.copy(
            name = template.name.trim(),
            locationId = state.activeLocationId
        )
        templateProblem(located)?.let {
            status = it
            return
        }
        commit(
            state.copy(
                shiftTemplates = state.shiftTemplates.map {
                    if (it.id == template.id) located else it
                }
            ),
            "Diensttemplate opgeslagen"
        )
        if (state.assignments.any { it.shiftTemplateId == located.id }) checkRosterAfterEdit()
    }

    fun removeTemplate(id: String) {
        val template = state.shiftTemplates.firstOrNull {
            it.id == id && it.locationId == state.activeLocationId
        } ?: return
        val inUse = (state.assignments + state.assignmentHistory).any {
            it.shiftTemplateId == id
        } || state.replacementRequests.any { it.shiftTemplateId == id }
        if (inUse) {
            status = "${template.name} is nog gebruikt in een rooster of vervangingsverzoek"
            return
        }
        commit(
            state.copy(shiftTemplates = state.shiftTemplates.filterNot { it.id == id }),
            "Diensttemplate ${template.name} verwijderd"
        )
    }

    private fun templateProblem(template: ShiftTemplate): String? {
        if (template.name.isBlank()) return "Vul een naam voor de dienst in"
        if (runCatching { java.time.LocalTime.parse(template.start) }.isFailure ||
            runCatching { java.time.LocalTime.parse(template.end) }.isFailure
        ) {
            return "Gebruik tijden als HH:mm, bijvoorbeeld 09:00 en 17:00"
        }
        if (template.start == template.end) return "Start- en eindtijd moeten verschillen"
        if (template.enabledWeekdays.isEmpty()) return "Kies minimaal één weekdag"
        return null
    }

    fun removeEmployee(id: String) {
        val keepAssignments = state.assignments.filterNot { it.employeeId == id }
        val keepHistory = state.assignmentHistory.filterNot { it.employeeId == id }
        commit(state.copy(
            employees = state.employees.filterNot { it.id == id },
            assignments = keepAssignments,
            assignmentHistory = keepHistory,
            availability = state.availability.filterNot { it.employeeId == id },
            weeklyAvailability = state.weeklyAvailability.filterNot { it.employeeId == id },
            absences = state.absences.filterNot { it.employeeId == id },
            responsibilities = state.responsibilities.filterNot { it.employeeId == id },
            personMarkers = state.personMarkers.filterNot { it.employeeId == id },
            replacementRequests = state.replacementRequests.filterNot {
                it.originalEmployeeId == id || it.replacementEmployeeId == id
            },
            manualDaysOff = state.manualDaysOff.filterNot { it.employeeId == id }
        ))
        if (state.assignments.isNotEmpty()) checkRosterAfterEdit()
    }

    fun changeMonth(delta: Long) {
        val current = YearMonth.of(state.year, state.month)
        val target = current.plusMonths(delta)
        val all = (state.assignmentHistory + state.assignments).distinctBy { it.id }
        val currentAssignments = all.filter { assignmentMonth(it) == target }
        val history = all.filterNot { assignmentMonth(it) == target }
        commit(state.copy(
            year = target.year,
            month = target.monthValue,
            assignments = currentAssignments,
            assignmentHistory = history
        ))
        unfilled = emptyList()
        plannerWarnings = emptyList()
    }

    fun generate() {
        val result = engine.generate(state)
        val keepOtherLocations = state.assignments.filter { it.locationId != state.activeLocationId }
        commit(
            state.copy(assignments = keepOtherLocations + result.assignments),
            "Rooster ${activeLocation().name} geoptimaliseerd met Solver v0.10"
        )
        unfilled = result.unfilled
        plannerWarnings = result.warnings
    }

    fun clearCurrentMonth() {
        commit(
            clearRosterMonth(state),
            "Rooster van ${activeLocation().name} voor deze maand gewist"
        )
        unfilled = emptyList()
        plannerWarnings = emptyList()
    }

    fun updateSettings(settings: PlannerSettings) {
        commit(state.copy(settings = settings))
    }

    fun exportJson(): String = storage.exportJson(state)

    fun importJson(raw: String) {
        runCatching { storage.importJson(raw) }
            .onSuccess {
                commit(it, "Import gelukt")
                unfilled = emptyList()
                plannerWarnings = emptyList()
            }
            .onFailure { status = "Import mislukt: ${it.message ?: "ongeldig bestand"}" }
    }

    fun showStatus(message: String) { status = message }

    fun dismissStatus() { status = null }

    private fun assignmentMonth(a: Assignment): YearMonth? = runCatching { YearMonth.from(LocalDate.parse(a.date)) }.getOrNull()
}

private enum class AppTab(val label: String) { OVERZICHT("Overzicht"), TEAM("Team"), ROOSTER("Rooster"), ADMIN("Admin"), REGELS("Regels") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoosterApp(controller: AppController) {
    var tab by remember { mutableStateOf(AppTab.OVERZICHT) }
    val context = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(controller.exportJson()) }
            }.onSuccess { controller.showStatus("Export opgeslagen") }
                .onFailure { controller.showStatus("Export mislukt: ${it.message}") }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Bestand kon niet worden gelezen")
            }.onSuccess { controller.importJson(it) }
                .onFailure { controller.showStatus("Import mislukt: ${it.message}") }
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    RosterPdfExporter.write(controller.state, stream)
                } ?: error("PDF-bestand kon niet worden geopend")
            }.onSuccess {
                controller.showStatus("PDF opgeslagen")
            }.onFailure {
                controller.showStatus("PDF opslaan mislukt: ${it.message}")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("RoosterAndroid", fontWeight = FontWeight.Bold)
                        Text(
                            controller.activeLocation().name,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                AppTab.entries.forEach { item ->
                    val icon = when (item) {
                        AppTab.OVERZICHT -> Icons.Default.Home
                        AppTab.TEAM -> Icons.Default.Group
                        AppTab.ROOSTER -> Icons.Default.CalendarMonth
                        AppTab.ADMIN -> Icons.Default.BarChart
                        AppTab.REGELS -> Icons.Default.Settings
                    }
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        alwaysShowLabel = false
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            controller.status?.let { message ->
                StatusBanner(message, controller::dismissStatus)
            }
            EditHistoryCard(controller)
            when (tab) {
                AppTab.OVERZICHT -> OverviewScreen(controller)
                AppTab.TEAM -> TeamScreen(controller)
                AppTab.ROOSTER -> ScheduleScreen(
                    controller = controller,
                    onPrintPdf = {
                        pdfLauncher.launch(
                            "rooster-${controller.state.year}-${controller.state.month.toString().padStart(2, '0')}.pdf"
                        )
                    }
                )
                AppTab.ADMIN -> AdminScreen(controller)
                AppTab.REGELS -> RulesScreen(
                    controller = controller,
                    onExport = { exportLauncher.launch("roosterandroid-${controller.state.year}-${controller.state.month}.json") },
                    onImport = { importLauncher.launch(arrayOf("application/json", "text/plain")) }
                )
            }
        }
    }
}

@Composable
private fun StatusBanner(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Info, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Melding sluiten")
            }
        }
    }
}

@Composable
private fun EditHistoryCard(controller: AppController) {
    if (controller.pendingEditCount == 0 && !controller.canUndo && !controller.canRedo) return
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (controller.pendingEditCount == 1) {
                    "1 wijziging sinds het herstelpunt"
                } else {
                    "${controller.pendingEditCount} wijzigingen sinds het herstelpunt"
                },
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = controller::undo,
                    enabled = controller.canUndo,
                    modifier = Modifier.weight(1f)
                ) { Text("Ongedaan") }
                OutlinedButton(
                    onClick = controller::redo,
                    enabled = controller.canRedo,
                    modifier = Modifier.weight(1f)
                ) { Text("Opnieuw") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = controller::saveEditCheckpoint,
                    enabled = controller.pendingEditCount > 0,
                    modifier = Modifier.weight(1f)
                ) { Text("Herstelpunt") }
                OutlinedButton(
                    onClick = controller::revertEditSession,
                    enabled = controller.pendingEditCount > 0,
                    modifier = Modifier.weight(1f)
                ) { Text("Alles terug") }
            }
        }
    }
}

@Composable
private fun MonthHeader(controller: AppController) {
    val ym = YearMonth.of(controller.state.year, controller.state.month)
    val locale = Locale("nl", "NL")
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { controller.changeMonth(-1) }) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Vorige maand")
            }
            Text(
                "${ym.month.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.uppercase() }} ${ym.year}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            IconButton(onClick = { controller.changeMonth(1) }) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Volgende maand")
            }
        }
    }
}

@Composable
private fun OverviewScreen(controller: AppController) {
    var showClearMonthDialog by remember { mutableStateOf(false) }
    val errors = controller.violations.count { it.severity == AtwValidator.Severity.ERROR }
    val warnings = controller.violations.count { it.severity == AtwValidator.Severity.WARNING }
    if (showClearMonthDialog) {
        val month = YearMonth.of(controller.state.year, controller.state.month)
        val locale = Locale("nl", "NL")
        AlertDialog(
            onDismissRequest = { showClearMonthDialog = false },
            title = { Text("Roostermaand wissen?") },
            text = {
                Text(
                    "Alle diensten, vaste vrije vakken en open vervangingen van " +
                        "${controller.activeLocation().name} in " +
                        "${month.month.getDisplayName(TextStyle.FULL, locale)} ${month.year} worden gewist. " +
                        "Andere maanden en vestigingen blijven behouden."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    controller.clearCurrentMonth()
                    showClearMonthDialog = false
                }) { Text("Maand wissen") }
            },
            dismissButton = {
                TextButton(onClick = { showClearMonthDialog = false }) { Text("Annuleren") }
            }
        )
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { MonthHeader(controller) }
        if (controller.state.locations.count { it.active } > 1) {
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Actieve vestiging", style = MaterialTheme.typography.labelMedium)
                            Text(controller.activeLocation().name, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(onClick = controller::selectNextLocation) {
                            Text("Volgende")
                        }
                    }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryCard(
                    "Managers",
                    controller.activeEmployees().size.toString(),
                    Modifier.weight(1f),
                    MaterialTheme.colorScheme.secondaryContainer
                )
                SummaryCard(
                    "Diensten",
                    controller.activeAssignments().size.toString(),
                    Modifier.weight(1f),
                    MaterialTheme.colorScheme.primaryContainer
                )
                SummaryCard(
                    "ATW",
                    if (errors == 0) "✓" else "$errors fout",
                    Modifier.weight(1f),
                    if (errors == 0) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                )
            }
        }
        item { ManagementDashboardPanel(controller) }
        item { DayPartCoverageHeatmap(controller.state) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { controller.generate() }, modifier = Modifier.weight(1f)) { Text("Genereer rooster") }
                OutlinedButton(
                    onClick = { showClearMonthDialog = true },
                    modifier = Modifier.weight(1f)
                ) { Text("Wis maand") }
            }
        }
        if (controller.repairProposal != null) {
            item { RepairProposalCard(controller) }
        }
        if (controller.unfilled.isNotEmpty()) {
            item { SectionTitle("Niet ingevuld (${controller.unfilled.size})") }
            items(controller.unfilled.take(12)) { WarningCard(it, true) }
        }
        if (controller.plannerWarnings.isNotEmpty()) {
            item { SectionTitle("Planner-waarschuwingen") }
            items(controller.plannerWarnings) { WarningCard(it, false) }
        }
        item { SectionTitle("ATW-controle: $errors fouten, $warnings waarschuwingen") }
        if (controller.violations.isEmpty()) {
            item { InfoCard("Geen ATW-conflicten gevonden in de beschikbare roosterhistorie.") }
        } else {
            items(controller.violations.filter { it.severity != AtwValidator.Severity.INFO }.take(25)) { v ->
                WarningCard("${v.date ?: ""} • ${v.rule}: ${v.message}", v.severity == AtwValidator.Severity.ERROR)
            }
        }
        item {
            InfoCard("Pauzes worden als informatie gemeld, omdat de app nog niet registreert wanneer iemand tijdens een dienst daadwerkelijk pauze neemt.")
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SummaryCard(
    label: String,
    value: String,
    modifier: Modifier,
    containerColor: Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TeamScreen(controller: AppController) {
    var name by remember { mutableStateOf("") }
    var roleIndex by remember { mutableIntStateOf(0) }
    var availabilityEmployeeIndex by remember { mutableIntStateOf(0) }
    var availabilityDate by remember(controller.state.year, controller.state.month) { mutableStateOf(YearMonth.of(controller.state.year, controller.state.month).atDay(1).toString()) }
    var availabilityEnabled by remember { mutableStateOf(true) }
    var earliestStart by remember { mutableStateOf("") }
    var latestEnd by remember { mutableStateOf("") }
    var fixedKindIndex by remember { mutableIntStateOf(0) }
    val roles = EmployeeRole.entries
    val fixedKinds = listOf<ShiftKind?>(null, ShiftKind.SETUP, ShiftKind.DAY, ShiftKind.MIDDLE, ShiftKind.CLOSE, ShiftKind.NIGHT, ShiftKind.KPI)
    val locationEmployees = controller.activeEmployees()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Manager toevoegen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Naam") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Rol: ${roleLabel(roles[roleIndex])}", modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { roleIndex = (roleIndex + 1) % roles.size }) { Text("Wijzig rol") }
                }
                Button(
                    onClick = {
                        controller.addEmployee(name, roles[roleIndex])
                        name = ""
                    },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Toevoegen") }
            }
        }

        if (locationEmployees.isNotEmpty()) {
            item {
                val idx = availabilityEmployeeIndex.coerceIn(0, locationEmployees.lastIndex)
                val selectedEmployee = locationEmployees[idx]
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Afwijking op specifieke datum", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Gebruik dit voor een afwijkende vrije dag of werktijd. Deze datumregel gaat volledig voor de vaste weekbeschikbaarheid.", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(selectedEmployee.name, modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = { availabilityEmployeeIndex = (idx + 1) % locationEmployees.size }) { Text("Volgende") }
                        }
                        DatePickerField(
                            value = availabilityDate,
                            onValueChange = { availabilityDate = it },
                            label = "Datum",
                            modifier = Modifier.fillMaxWidth()
                        )
                        SettingSwitch("Beschikbaar", availabilityEnabled) { availabilityEnabled = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TimePickerField(
                                value = earliestStart,
                                onValueChange = { earliestStart = it },
                                label = "Vanaf",
                                modifier = Modifier.weight(1f),
                                allowEmpty = true
                            )
                            TimePickerField(
                                value = latestEnd,
                                onValueChange = { latestEnd = it },
                                label = "Tot",
                                modifier = Modifier.weight(1f),
                                allowEmpty = true,
                                fallback = java.time.LocalTime.of(17, 0)
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Vaste dienst: ${fixedKinds[fixedKindIndex]?.let(::shiftKindLabel) ?: "geen"}", modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = { fixedKindIndex = (fixedKindIndex + 1) % fixedKinds.size }) { Text("Wijzig") }
                        }
                        Button(
                            onClick = {
                                val validDate = runCatching { LocalDate.parse(availabilityDate) }.getOrNull()
                                val validEarliest = earliestStart.isBlank() || runCatching { java.time.LocalTime.parse(earliestStart) }.isSuccess
                                val validLatest = latestEnd.isBlank() || runCatching { java.time.LocalTime.parse(latestEnd) }.isSuccess
                                if (validDate == null || !validEarliest || !validLatest) {
                                    controller.showStatus("Controleer datum en tijden (HH:mm)")
                                } else {
                                    controller.upsertAvailability(
                                        Availability(
                                            employeeId = selectedEmployee.id,
                                            date = validDate.toString(),
                                            available = availabilityEnabled,
                                            earliestStart = earliestStart.ifBlank { null },
                                            latestEnd = latestEnd.ifBlank { null },
                                            fixedShiftKind = fixedKinds[fixedKindIndex]
                                        )
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Opslaan") }
                    }
                }
            }

            val ym = YearMonth.of(controller.state.year, controller.state.month)
            val currentAvailability = controller.state.availability.filter { a ->
                a.employeeId in locationEmployees.map { it.id }.toSet() &&
                runCatching { YearMonth.from(LocalDate.parse(a.date)) == ym }.getOrDefault(false)
            }.sortedBy { it.date }
            if (currentAvailability.isNotEmpty()) {
                item { SectionTitle("Beschikbaarheid deze maand") }
                items(currentAvailability, key = { "${it.employeeId}-${it.date}" }) { a ->
                    val employeeName = controller.state.employees.firstOrNull { it.id == a.employeeId }?.name ?: "Onbekend"
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${a.date} • $employeeName", fontWeight = FontWeight.Bold)
                                Text(if (!a.available) "Niet beschikbaar" else listOfNotNull(
                                    a.earliestStart?.let { "vanaf $it" },
                                    a.latestEnd?.let { "tot $it" },
                                    a.fixedShiftKind?.let { "vast ${shiftKindLabel(it)}" }
                                ).ifEmpty { listOf("Beschikbaar") }.joinToString(" • "))
                            }
                            IconButton(onClick = { controller.removeAvailability(a.employeeId, a.date) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Verwijder beschikbaarheid")
                            }
                        }
                    }
                }
            }
        }

        if (locationEmployees.isNotEmpty()) {
            item { RecurringAvailabilityPanel(controller) }
        }

        items(locationEmployees, key = { it.id }) { employee ->
            EmployeeCard(employee, controller::updateEmployee, controller::removeEmployee)
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun RecurringAvailabilityPanel(controller: AppController) {
    var employeeIndex by remember { mutableIntStateOf(0) }
    var weekday by remember { mutableIntStateOf(1) }

    val employees = controller.activeEmployees()
    if (employees.isEmpty()) return

    val idx = employeeIndex.coerceIn(0, employees.lastIndex)
    val employee = employees[idx]
    val dayNames = listOf("Ma", "Di", "Wo", "Do", "Vr", "Za", "Zo")
    val fixedKinds = listOf<ShiftKind?>(
        null,
        ShiftKind.SETUP,
        ShiftKind.DAY,
        ShiftKind.MIDDLE,
        ShiftKind.CLOSE,
        ShiftKind.NIGHT
    )

    val current = controller.state.weeklyAvailability.lastOrNull {
        it.employeeId == employee.id && it.weekday == weekday
    }

    var available by remember(employee.id, weekday, current) {
        mutableStateOf(current?.available ?: true)
    }
    var earliest by remember(employee.id, weekday, current) {
        mutableStateOf(current?.earliestStart ?: "")
    }
    var latest by remember(employee.id, weekday, current) {
        mutableStateOf(current?.latestEnd ?: "")
    }
    var fixedKindIndex by remember(employee.id, weekday, current) {
        mutableIntStateOf(
            fixedKinds.indexOf(current?.fixedShiftKind).let { if (it < 0) 0 else it }
        )
    }

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Beschikbaarheid per manager",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Stel per weekdag in of iemand kan werken en tussen welke tijden. " +
                    "Niet ingesteld betekent de hele dag beschikbaar. Een specifieke datumregel gaat volledig voor deze weekregel."
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(employee.name, fontWeight = FontWeight.Bold)
                    Text("Vaste weekbeschikbaarheid", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = {
                    employeeIndex = (idx + 1) % employees.size
                }) { Text("Volgende manager") }
            }

            Text("Kies dag", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                dayNames.take(4).forEachIndexed { i, label ->
                    val d = i + 1
                    if (weekday == d) {
                        Button(onClick = { weekday = d }, modifier = Modifier.weight(1f)) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { weekday = d }, modifier = Modifier.weight(1f)) { Text(label) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                dayNames.drop(4).forEachIndexed { i, label ->
                    val d = i + 5
                    if (weekday == d) {
                        Button(onClick = { weekday = d }, modifier = Modifier.weight(1f)) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { weekday = d }, modifier = Modifier.weight(1f)) { Text(label) }
                    }
                }
                Spacer(Modifier.weight(1f))
            }

            SettingSwitch("Beschikbaar op ${dayNames[weekday - 1]}", available) {
                available = it
                if (!it) {
                    earliest = ""
                    latest = ""
                    fixedKindIndex = 0
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimePickerField(
                    value = earliest,
                    onValueChange = { earliest = it },
                    label = "Vanaf",
                    modifier = Modifier.weight(1f),
                    allowEmpty = true,
                    enabled = available
                )
                TimePickerField(
                    value = latest,
                    onValueChange = { latest = it },
                    label = "Tot",
                    modifier = Modifier.weight(1f),
                    allowEmpty = true,
                    enabled = available,
                    fallback = java.time.LocalTime.of(22, 0)
                )
            }
            Text(
                "Laat Vanaf/Tot leeg als de manager die dag op elk tijdstip beschikbaar is.",
                style = MaterialTheme.typography.bodySmall
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Vaste dienst: ${fixedKinds[fixedKindIndex]?.let(::shiftKindLabel) ?: "geen"}",
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { fixedKindIndex = (fixedKindIndex + 1) % fixedKinds.size },
                    enabled = available
                ) { Text("Wijzig") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val okStart = !available || earliest.isBlank() ||
                            runCatching { java.time.LocalTime.parse(earliest) }.isSuccess
                        val okEnd = !available || latest.isBlank() ||
                            runCatching { java.time.LocalTime.parse(latest) }.isSuccess

                        if (!okStart || !okEnd) {
                            controller.showStatus("Gebruik tijden als HH:mm, bijvoorbeeld 09:00")
                        } else {
                            controller.upsertWeeklyAvailability(
                                WeeklyAvailability(
                                    employeeId = employee.id,
                                    weekday = weekday,
                                    available = available,
                                    earliestStart = if (available) earliest.ifBlank { null } else null,
                                    latestEnd = if (available) latest.ifBlank { null } else null,
                                    fixedShiftKind = if (available) fixedKinds[fixedKindIndex] else null
                                )
                            )
                            controller.showStatus(
                                "${employee.name}: ${dayNames[weekday - 1]} beschikbaarheid opgeslagen"
                            )
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Opslaan") }

                OutlinedButton(
                    onClick = {
                        controller.removeWeeklyAvailability(employee.id, weekday)
                        controller.showStatus(
                            "${employee.name}: ${dayNames[weekday - 1]} terug naar standaard beschikbaar"
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Reset dag") }
            }

            HorizontalDivider()
            Text("Weekoverzicht ${employee.name}", fontWeight = FontWeight.Bold)

            (1..7).forEach { d ->
                val rule = controller.state.weeklyAvailability.lastOrNull {
                    it.employeeId == employee.id && it.weekday == d
                }
                val description = when {
                    rule == null -> "hele dag beschikbaar (standaard)"
                    !rule.available -> "niet beschikbaar"
                    else -> listOfNotNull(
                        rule.earliestStart?.let { "vanaf $it" },
                        rule.latestEnd?.let { "tot $it" },
                        rule.fixedShiftKind?.let { "vast ${shiftKindLabel(it)}" }
                    ).ifEmpty { listOf("hele dag beschikbaar") }.joinToString(" • ")
                }
                Row(Modifier.fillMaxWidth()) {
                    Text(dayNames[d - 1], modifier = Modifier.width(34.dp), fontWeight = FontWeight.SemiBold)
                    Text(description, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun EmployeeCard(employee: Employee, onUpdate: (Employee) -> Unit, onDelete: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(employee.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${roleLabel(employee.role)} • max ${employee.maxShiftsPerWeek} diensten/week")
                }
                IconButton(onClick = { onDelete(employee.id) }) { Icon(Icons.Default.Delete, contentDescription = "Verwijder") }
            }
            CapabilityRow("Setup", employee.canSetup) { onUpdate(employee.copy(canSetup = it)) }
            CapabilityRow("Dag", employee.canDay) { onUpdate(employee.copy(canDay = it)) }
            CapabilityRow("Tussen", employee.canMiddle) { onUpdate(employee.copy(canMiddle = it)) }
            CapabilityRow("Sluit", employee.canClose) { onUpdate(employee.copy(canClose = it)) }
            CapabilityRow("Nacht", employee.canNight) { onUpdate(employee.copy(canNight = it)) }
            CapabilityRow("KPI", employee.canKpi) { onUpdate(employee.copy(canKpi = it)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Contractdagen/week", modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    val days = (employee.contractedDaysPerWeek - 1).coerceAtLeast(1)
                    onUpdate(employee.copy(contractedDaysPerWeek = days))
                }) { Text("−") }
                Spacer(Modifier.width(8.dp))
                Text(employee.contractedDaysPerWeek.toString(), fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    val days = (employee.contractedDaysPerWeek + 1).coerceAtMost(7)
                    onUpdate(employee.copy(contractedDaysPerWeek = days))
                }) { Text("+") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Contracturen/week", modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    onUpdate(employee.copy(
                        contractedHoursPerWeek =
                            (employee.contractedHoursPerWeek - 4.0).coerceAtLeast(4.0)
                    ))
                }) { Text("−") }
                Spacer(Modifier.width(8.dp))
                Text("${employee.contractedHoursPerWeek.toInt()}u", fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    onUpdate(employee.copy(
                        contractedHoursPerWeek =
                            (employee.contractedHoursPerWeek + 4.0).coerceAtMost(60.0)
                    ))
                }) { Text("+") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Max diensten/week", modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { onUpdate(employee.copy(maxShiftsPerWeek = (employee.maxShiftsPerWeek - 1).coerceAtLeast(1))) }) { Text("−") }
                Spacer(Modifier.width(8.dp))
                Text(employee.maxShiftsPerWeek.toString(), fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { onUpdate(employee.copy(maxShiftsPerWeek = (employee.maxShiftsPerWeek + 1).coerceAtMost(7))) }) { Text("+") }
            }
        }
    }
}

@Composable
private fun CapabilityRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Text(label)
    }
}

@Composable
private fun ScheduleScreen(controller: AppController, onPrintPdf: () -> Unit) {
    val state = controller.state
    val activeLocationId = state.activeLocationId
    val ym = YearMonth.of(state.year, state.month)
    val employees = remember(state.employees, activeLocationId) {
        state.employees.filter { it.active && it.worksAt(activeLocationId) }
    }
    val templates = remember(state.shiftTemplates, activeLocationId) {
        state.shiftTemplates
            .filter { it.locationId == activeLocationId }
            .associateBy { it.id }
    }
    val locationNames = remember(state.locations) {
        state.locations.associate { it.id to it.name }
    }
    val matrixIndex = remember(state, controller.violations, ym) {
        rosterMatrixIndex(state, controller.violations, ym)
    }
    val managerScroll = rememberScrollState()
    val locale = Locale("nl", "NL")

    var noteDate by remember { mutableStateOf<String?>(null) }
    var noteDraft by remember { mutableStateOf("") }
    var editEmployeeId by remember { mutableStateOf<String?>(null) }
    var editDate by remember { mutableStateOf<String?>(null) }
    var editLockMode by remember { mutableStateOf(AssignmentLockMode.FIXED) }
    var showSwapDialog by remember { mutableStateOf(false) }
    var swapFirstIndex by remember { mutableIntStateOf(0) }
    var swapSecondIndex by remember { mutableIntStateOf(1) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedCells by remember { mutableStateOf(emptySet<RosterCellSelection>()) }
    var showBulkDialog by remember { mutableStateOf(false) }
    var bulkTemplateIndex by remember { mutableIntStateOf(0) }
    var bulkLockMode by remember { mutableStateOf(AssignmentLockMode.FIXED) }
    var showWeekCopyDialog by remember { mutableStateOf(false) }
    var sourceWeekIndex by remember { mutableIntStateOf(0) }
    var targetWeekIndex by remember { mutableIntStateOf(1) }
    var copyLockMode by remember { mutableStateOf(AssignmentLockMode.PREFERRED) }
    var showDayCopyDialog by remember { mutableStateOf(false) }
    var dayCopySource by remember(controller.state.year, controller.state.month) {
        mutableStateOf(ym.atDay(1).toString())
    }
    var dayCopyTarget by remember(controller.state.year, controller.state.month) {
        mutableStateOf(ym.atDay(2.coerceAtMost(ym.lengthOfMonth())).toString())
    }
    var dayCopyLockMode by remember { mutableStateOf(AssignmentLockMode.PREFERRED) }
    var showClearSelectionDialog by remember { mutableStateOf(false) }
    var showLockSelectionDialog by remember { mutableStateOf(false) }
    var selectionLockMode by remember { mutableStateOf(AssignmentLockMode.PREFERRED) }
    val locationAssignments = remember(state.assignments, activeLocationId) {
        state.assignments
            .filter { it.locationId == activeLocationId }
            .sortedWith(compareBy<Assignment>({ it.date }, { it.employeeId }))
    }
    val monthMondays = (1..ym.lengthOfMonth())
        .map(ym::atDay)
        .map { it.minusDays((it.dayOfWeek.value - 1).toLong()) }
        .distinct()

    if (showSwapDialog && locationAssignments.size >= 2) {
        val firstIndex = swapFirstIndex.coerceIn(0, locationAssignments.lastIndex)
        val secondIndex = swapSecondIndex.coerceIn(0, locationAssignments.lastIndex)
        val first = locationAssignments[firstIndex]
        val second = locationAssignments[secondIndex]
        fun label(assignment: Assignment): String {
            val employee = controller.state.employees.firstOrNull {
                it.id == assignment.employeeId
            }?.name ?: "?"
            val template = templates[assignment.shiftTemplateId]?.name ?: "?"
            val times = templates[assignment.shiftTemplateId]?.let {
                " ${it.start}-${it.end}"
            }.orEmpty()
            return "${assignment.date} • $employee • $template$times"
        }
        AlertDialog(
            onDismissRequest = { showSwapDialog = false },
            title = { Text("Diensten handmatig ruilen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Dienst 1: ${label(first)}")
                    OutlinedButton(onClick = {
                        swapFirstIndex = (firstIndex + 1) % locationAssignments.size
                    }) { Text("Volgende dienst 1") }
                    Text("Dienst 2: ${label(second)}")
                    OutlinedButton(onClick = {
                        swapSecondIndex = (secondIndex + 1) % locationAssignments.size
                    }) { Text("Volgende dienst 2") }
                    Text(
                        "Na de ruil controleert de app automatisch beschikbaarheid, bezetting en ATW. " +
                            "Bij een probleem verschijnt een toepasbare oplossing."
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    controller.swapAssignments(first.id, second.id)
                    showSwapDialog = false
                }) { Text("Ruil & controleer") }
            },
            dismissButton = {
                TextButton(onClick = { showSwapDialog = false }) { Text("Annuleren") }
            }
        )
    }

    if (showBulkDialog && selectedCells.isNotEmpty()) {
        val bulkOptions = listOf<ShiftTemplate?>(null) + templates.values.sortedBy { it.start }
        val optionIndex = bulkTemplateIndex.coerceIn(0, bulkOptions.lastIndex)
        val selectedTemplate = bulkOptions[optionIndex]
        AlertDialog(
            onDismissRequest = { showBulkDialog = false },
            title = { Text("${selectedCells.size} vakken aanpassen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Nieuwe invulling", fontWeight = FontWeight.Bold)
                            Text(
                                selectedTemplate?.let {
                                    "${it.name} • ${it.start}-${it.end}"
                                } ?: "Vrij vastzetten"
                            )
                        }
                        OutlinedButton(onClick = {
                            bulkTemplateIndex = (optionIndex + 1) % bulkOptions.size
                        }) { Text("Volgende") }
                    }
                    if (selectedTemplate != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Auto-fix: ${assignmentLockLabel(bulkLockMode)}", Modifier.weight(1f))
                            OutlinedButton(onClick = {
                                bulkLockMode = AssignmentLockMode.entries[
                                    (AssignmentLockMode.entries.indexOf(bulkLockMode) + 1) %
                                        AssignmentLockMode.entries.size
                                ]
                            }) { Text("Wijzig") }
                        }
                    }
                    Text(
                        "Ongeldige combinaties, bijvoorbeeld een sluitdienst op een niet-actieve " +
                            "weekdag, worden overgeslagen.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    controller.setManualAssignments(
                        cells = selectedCells,
                        templateId = selectedTemplate?.id,
                        lockMode = bulkLockMode
                    )
                    selectedCells = emptySet()
                    selectionMode = false
                    showBulkDialog = false
                }) { Text("Toepassen & controleren") }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDialog = false }) { Text("Annuleren") }
            }
        )
    }

    if (showClearSelectionDialog && selectedCells.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showClearSelectionDialog = false },
            title = { Text("${selectedCells.size} vakken leegmaken?") },
            text = {
                Text(
                    "Diensten en vaste vrije blokkades verdwijnen uit deze vakken. " +
                        "Andere vestigingen blijven onaangetast en je kunt dit daarna ongedaan maken."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    controller.clearSelectedRosterCells(selectedCells)
                    selectedCells = emptySet()
                    selectionMode = false
                    showClearSelectionDialog = false
                }) { Text("Leegmaken & controleren") }
            },
            dismissButton = {
                TextButton(onClick = { showClearSelectionDialog = false }) { Text("Annuleren") }
            }
        )
    }

    if (showLockSelectionDialog && selectedCells.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showLockSelectionDialog = false },
            title = { Text("Slotniveau wijzigen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Alleen bestaande diensten binnen de ${selectedCells.size} geselecteerde vakken " +
                            "worden aangepast; lege en vrije vakken blijven gelijk."
                    )
                    Text(assignmentLockLabel(selectionLockMode), fontWeight = FontWeight.Bold)
                    Text(
                        assignmentLockDescription(selectionLockMode),
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = {
                            selectionLockMode = AssignmentLockMode.entries[
                                (AssignmentLockMode.entries.indexOf(selectionLockMode) + 1) %
                                    AssignmentLockMode.entries.size
                            ]
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Volgend slotniveau") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    controller.changeSelectedLockMode(selectedCells, selectionLockMode)
                    selectedCells = emptySet()
                    selectionMode = false
                    showLockSelectionDialog = false
                }) { Text("Toepassen & controleren") }
            },
            dismissButton = {
                TextButton(onClick = { showLockSelectionDialog = false }) { Text("Annuleren") }
            }
        )
    }

    if (showWeekCopyDialog && monthMondays.size >= 2) {
        val sourceIndex = sourceWeekIndex.coerceIn(0, monthMondays.lastIndex)
        val targetIndex = targetWeekIndex.coerceIn(0, monthMondays.lastIndex)
        val sourceMonday = monthMondays[sourceIndex]
        val targetMonday = monthMondays[targetIndex]
        fun weekLabel(monday: LocalDate): String {
            val week = monday.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())
            return "Week $week • ${monday.dayOfMonth}-${monday.plusDays(6).dayOfMonth}"
        }
        AlertDialog(
            onDismissRequest = { showWeekCopyDialog = false },
            title = { Text("Hele week kopiëren") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Bron: ${weekLabel(sourceMonday)}", fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = {
                        sourceWeekIndex = (sourceIndex + 1) % monthMondays.size
                    }) { Text("Volgende bronweek") }
                    Text("Doel: ${weekLabel(targetMonday)}", fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = {
                        targetWeekIndex = (targetIndex + 1) % monthMondays.size
                    }) { Text("Volgende doelweek") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Gekopieerde diensten: ${assignmentLockLabel(copyLockMode)}", Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            copyLockMode = AssignmentLockMode.entries[
                                (AssignmentLockMode.entries.indexOf(copyLockMode) + 1) %
                                    AssignmentLockMode.entries.size
                            ]
                        }) { Text("Wijzig") }
                    }
                    Text(
                        "De doelweek wordt vervangen. Beschikbaarheid, vestiging, openingstijden " +
                            "en ATW worden opnieuw gecontroleerd.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.copyWeek(sourceMonday, targetMonday, copyLockMode)
                        showWeekCopyDialog = false
                    },
                    enabled = sourceMonday != targetMonday
                ) { Text("Week kopiëren") }
            },
            dismissButton = {
                TextButton(onClick = { showWeekCopyDialog = false }) { Text("Annuleren") }
            }
        )
    }

    if (showDayCopyDialog) {
        val source = runCatching { LocalDate.parse(dayCopySource) }.getOrNull()
        val target = runCatching { LocalDate.parse(dayCopyTarget) }.getOrNull()
        val datesAreInShownMonth = source != null && target != null &&
            YearMonth.from(source) == ym && YearMonth.from(target) == ym
        AlertDialog(
            onDismissRequest = { showDayCopyDialog = false },
            title = { Text("Hele dag kopiëren") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DatePickerField(
                        value = dayCopySource,
                        onValueChange = { dayCopySource = it },
                        label = "Brondag",
                        modifier = Modifier.fillMaxWidth()
                    )
                    DatePickerField(
                        value = dayCopyTarget,
                        onValueChange = { dayCopyTarget = it },
                        label = "Doeldag",
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Gekopieerde diensten: ${assignmentLockLabel(dayCopyLockMode)}",
                            Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = {
                            dayCopyLockMode = AssignmentLockMode.entries[
                                (AssignmentLockMode.entries.indexOf(dayCopyLockMode) + 1) %
                                    AssignmentLockMode.entries.size
                            ]
                        }) { Text("Wijzig") }
                    }
                    Text(
                        "De volledige doeldag van deze vestiging wordt vervangen. Conflicten met " +
                            "beschikbaarheid, openingstijden of een andere vestiging worden overgeslagen.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (source != null && target != null && !datesAreInShownMonth) {
                        Text(
                            "Kies twee datums binnen ${ym.month.getDisplayName(TextStyle.FULL, locale)} ${ym.year}.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (source != null && target != null) {
                            controller.copyDay(source, target, dayCopyLockMode)
                            showDayCopyDialog = false
                        }
                    },
                    enabled = datesAreInShownMonth && source != target
                ) { Text("Dag kopiëren") }
            },
            dismissButton = {
                TextButton(onClick = { showDayCopyDialog = false }) { Text("Annuleren") }
            }
        )
    }

    noteDate?.let { date ->
        AlertDialog(
            onDismissRequest = { noteDate = null },
            title = { Text("Bijzonderheden • $date") },
            text = {
                OutlinedTextField(
                    value = noteDraft,
                    onValueChange = { noteDraft = it },
                    label = { Text("Bijzonderheden") },
                    placeholder = { Text("Bijv. maandsluiting, meeting, vakantie, HAVI…") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    controller.upsertDayNote(date, noteDraft)
                    noteDate = null
                }) { Text("Opslaan") }
            },
            dismissButton = {
                TextButton(onClick = { noteDate = null }) { Text("Annuleren") }
            }
        )
    }

    if (editEmployeeId != null && editDate != null) {
        val employee = employees.firstOrNull { it.id == editEmployeeId }
        val date = runCatching { LocalDate.parse(editDate) }.getOrNull()
        if (employee != null && date != null) {
            val options = controller.state.shiftTemplates.filter {
                it.locationId == controller.state.activeLocationId &&
                    date.dayOfWeek.value in it.enabledWeekdays &&
                    employee.canWork(it.kind)
            }
            AlertDialog(
                onDismissRequest = {
                    editEmployeeId = null
                    editDate = null
                },
                title = {
                    Text(
                        "${employee.name} • ${date.dayOfMonth} " +
                            date.month.getDisplayName(TextStyle.SHORT, locale)
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Kies handmatig een dienst of Vrij en bepaal hoeveel ruimte Auto-fix " +
                                "krijgt. Het hele rooster wordt daarna automatisch gecontroleerd."
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Gedrag Auto-fix", fontWeight = FontWeight.Bold)
                                Text(
                                    assignmentLockDescription(editLockMode),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            OutlinedButton(onClick = {
                                editLockMode = AssignmentLockMode.entries[
                                    (AssignmentLockMode.entries.indexOf(editLockMode) + 1) %
                                        AssignmentLockMode.entries.size
                                ]
                            }) { Text(assignmentLockLabel(editLockMode)) }
                        }
                        TextButton(onClick = {
                            controller.setManualAssignment(employee.id, date.toString(), null)
                            editEmployeeId = null
                            editDate = null
                        }) { Text("Vrij vastzetten") }

                        options.forEach { template ->
                            TextButton(onClick = {
                                controller.setManualAssignment(
                                    employee.id,
                                    date.toString(),
                                    template.id,
                                    editLockMode
                                )
                                editEmployeeId = null
                                editDate = null
                            }) {
                                Text("${template.name}  ${template.start}-${template.end}")
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = {
                        editEmployeeId = null
                        editDate = null
                    }) { Text("Sluiten") }
                }
            )
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { MonthHeader(controller) }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onPrintPdf) {
                    Text("Print / PDF")
                }
                FilledTonalButton(
                    onClick = { showSwapDialog = true },
                    enabled = locationAssignments.size >= 2
                ) { Text("Ruil diensten") }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        selectionMode = !selectionMode
                        if (!selectionMode) selectedCells = emptySet()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (selectionMode) "Selecteren stoppen" else "Meerdere kiezen")
                }
                OutlinedButton(
                    onClick = { showDayCopyDialog = true },
                    modifier = Modifier.weight(1f)
                ) { Text("Kopieer dag") }
            }
            OutlinedButton(
                onClick = { showWeekCopyDialog = true },
                enabled = monthMondays.size >= 2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            ) { Text("Kopieer hele week") }
            if (selectionMode) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column(
                        Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Text(
                            "${selectedCells.size} vak(ken) geselecteerd",
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Button(
                                onClick = { showBulkDialog = true },
                                enabled = selectedCells.isNotEmpty(),
                                modifier = Modifier.weight(1f)
                            ) { Text("Invulling") }
                            OutlinedButton(
                                onClick = { showLockSelectionDialog = true },
                                enabled = selectedCells.isNotEmpty(),
                                modifier = Modifier.weight(1f)
                            ) { Text("Slotniveau") }
                        }
                        OutlinedButton(
                            onClick = { showClearSelectionDialog = true },
                            enabled = selectedCells.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Selectie leegmaken") }
                    }
                }
            }
            FilledTonalButton(
                onClick = controller::runDeepAutoFix,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                enabled = !controller.isAutoFixRunning
            ) {
                Text(if (controller.isAutoFixRunning) "Auto-fix bezig…" else "Auto-fix • verder puzzelen")
            }
            if (controller.isAutoFixRunning) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(Modifier.width(24.dp).height(24.dp))
                        Column(Modifier.weight(1f)) {
                            Text(controller.autoFixPhase, fontWeight = FontWeight.Bold)
                            Text("${controller.autoFixProgress}%", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = { controller.cancelAutoFix() }) {
                            Text("Annuleren")
                        }
                    }
                }
            }
            Text(
                "Auto-fix bewaart handmatige keuzes waar mogelijk en toont eerst een voorstel. " +
                    "PDF: pagina 1 rooster, pagina 2 loonadministratie.",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (controller.repairProposal != null) {
            item { RepairProposalCard(controller) }
        }

        if (controller.activeAssignments().isEmpty()) {
            item {
                InfoCard("Nog geen rooster. Ga naar Overzicht en tik op ‘Genereer rooster’.")
            }
        }

        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Text(
                    "Maandmatrix",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Datums staan onder elkaar. Veeg horizontaal langs de managers. Tik op ieder " +
                        "dienstvakje om zelf een dienst te kiezen of iemand vrij te zetten. Na iedere " +
                        "wijziging controleert de app het rooster en stelt zo nodig een oplossing voor.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))

                Row {
                    MatrixCell("Datum", 78.dp, MatrixColors.Header, strong = true)

                    Row(Modifier.horizontalScroll(managerScroll)) {
                        employees.forEach { employee ->
                            MatrixCell(
                                text = employee.name,
                                cellWidth = MatrixManagerCellWidth,
                                containerColor = managerHeaderColor(employee.role),
                                strong = true
                            )
                        }
                        MatrixCell(
                            "Bijzonderheden",
                            146.dp,
                            MatrixColors.NoteHeader,
                            strong = true
                        )
                    }
                }
            }
        }

        for (day in 1..ym.lengthOfMonth()) {
            val date = ym.atDay(day)
            val dateString = date.toString()
            val weekend =
                date.dayOfWeek == DayOfWeek.SATURDAY ||
                date.dayOfWeek == DayOfWeek.SUNDAY
            val dateColor =
                if (weekend) MatrixColors.Weekend else MatrixColors.Date

            item(key = "matrix-$dateString") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                    val dow = date.dayOfWeek
                        .getDisplayName(TextStyle.SHORT, locale)
                        .replaceFirstChar { it.uppercase() }
                    val week = date.get(
                        java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear()
                    )

                    MatrixCell(
                        text = "$dow ${date.dayOfMonth}\nW$week",
                        cellWidth = 78.dp,
                        containerColor = dateColor,
                        strong = true
                    )

                    Row(Modifier.horizontalScroll(managerScroll)) {
                        employees.forEach { employee ->
                            val cellSelection = RosterCellSelection(employee.id, dateString)
                            val assignment = matrixIndex.assignmentsByCell[cellSelection]
                            val otherAssignment = matrixIndex.otherAssignmentsByCell[cellSelection]
                            val otherLocationName = otherAssignment?.let { other ->
                                locationNames[other.locationId]
                            }
                            val template = assignment?.let {
                                templates[it.shiftTemplateId]
                            }
                            val explicitAvailability = matrixIndex.availabilityByCell[cellSelection]
                            val weeklyAvailability = matrixIndex.weeklyAvailabilityByEmployeeDay[
                                employee.id to date.dayOfWeek.value
                            ]
                            val absence = matrixIndex.absencesByCell[cellSelection]
                            val manualDayOff = cellSelection in matrixIndex.manualDaysOff
                            val unavailable =
                                explicitAvailability?.available == false ||
                                    (explicitAvailability == null && weeklyAvailability?.available == false)

                            val conflict = cellSelection in matrixIndex.errorCells

                            val taskLabels = matrixIndex.responsibilitiesByEmployee[employee.id]
                                .orEmpty()
                                .filter { responsibilityAppliesUi(it, date, ym) }
                                .map(::responsibilityLabelUi)
                            val markerLabels = matrixIndex.markersByCell[cellSelection]
                                .orEmpty()
                                .map(::personMarkerLabelUi)
                            val extras = (taskLabels + markerLabels).distinct()

                            val shiftTitle = if (absence == null && assignment != null && template != null) {
                                rosterShiftTitle(template, assignment)
                            } else {
                                null
                            }
                            val shiftTime = if (absence == null && assignment != null && template != null) {
                                rosterShiftTime(template)
                            } else {
                                null
                            }
                            val text = when {
                                absence != null -> absenceMatrixLabel(absence)
                                assignment != null && template != null -> extras.joinToString(" / ")
                                otherAssignment != null -> "Elders\n${otherLocationName.orEmpty().take(10)}"
                                manualDayOff -> "Vrij 🔒"
                                unavailable -> "Niet\nbesch."
                                extras.isNotEmpty() -> extras.joinToString("\n")
                                else -> "Vrij"
                            }

                            val isSelected = cellSelection in selectedCells

                            val color = when {
                                isSelected -> MatrixColors.Selected
                                conflict -> MatrixColors.Error
                                absence != null -> absenceColor(absence.type)
                                unavailable -> MatrixColors.Unavailable
                                template != null -> shiftColor(template.kind)
                                otherAssignment != null -> MatrixColors.Present
                                manualDayOff -> if (weekend) MatrixColors.FreeWeekend else MatrixColors.Free
                                extras.isNotEmpty() -> MatrixColors.Present
                                else -> if (weekend) MatrixColors.FreeWeekend else MatrixColors.Free
                            }

                            MatrixCell(
                                text = text,
                                cellWidth = MatrixManagerCellWidth,
                                containerColor = color,
                                strong = assignment != null || otherAssignment != null || manualDayOff || unavailable,
                                shiftTitle = shiftTitle,
                                shiftTime = shiftTime,
                                onClick = {
                                    if (selectionMode) {
                                        selectedCells = if (isSelected) {
                                            selectedCells - cellSelection
                                        } else {
                                            selectedCells + cellSelection
                                        }
                                    } else {
                                        editEmployeeId = employee.id
                                        editDate = dateString
                                        editLockMode = assignment?.effectiveLockMode()
                                            ?: AssignmentLockMode.FIXED
                                    }
                                }
                            )
                        }

                        val details = buildDayDetails(controller, date, ym)
                        MatrixCell(
                            text = details.ifBlank { " " },
                            cellWidth = 146.dp,
                            containerColor =
                                if (details.contains("⚠")) MatrixColors.Error
                                else MatrixColors.Note,
                            onClick = {
                                noteDate = dateString
                                noteDraft = matrixIndex.notesByDate[dateString].orEmpty()
                            }
                        )
                    }
                }
            }
        }

        item {
            MatrixLegend()
            Spacer(Modifier.height(24.dp))
        }
    }
}

private object MatrixColors {
    val Text = Color(0xFF172321)
    val Header = Color(0xFFDDE7F5)
    val NoteHeader = Color(0xFFFFE6A8)
    val Date = Color(0xFFF3F5F7)
    val Weekend = Color(0xFFE7E9EE)
    val Note = Color(0xFFFFF4CF)
    val Setup = Color(0xFFDDF3D8)
    val Day = Color(0xFFD8EBFA)
    val Middle = Color(0xFFFFE4B5)
    val Close = Color(0xFFE6DCF7)
    val Night = Color(0xFFCDD3F6)
    val Kpi = Color(0xFFD7F2EF)
    val Custom = Color(0xFFF5DDEC)
    val Free = Color(0xFFF7F7F7)
    val FreeWeekend = Color(0xFFEEEEF1)
    val Unavailable = Color(0xFFF6D7D7)
    val Vacation = Color(0xFFD9F0FF)
    val Leave = Color(0xFFFFE7B8)
    val Sick = Color(0xFFF9CFCF)
    val Present = Color(0xFFE0F3E8)
    val Error = Color(0xFFFFC9C9)
    val Selected = Color(0xFFFFD54F)
    val RmHeader = Color(0xFFD1E6FF)
    val TraineeHeader = Color(0xFFFFE2C6)
    val BorrowedHeader = Color(0xFFFFF0B8)
}

private val MatrixManagerCellWidth = 144.dp
private val MatrixCellHeight = 84.dp

@Composable
private fun MatrixCell(
    text: String,
    cellWidth: androidx.compose.ui.unit.Dp,
    containerColor: Color,
    strong: Boolean = false,
    shiftTitle: String? = null,
    shiftTime: String? = null,
    onClick: (() -> Unit)? = null
) {
    val density = LocalDensity.current
    val matrixTitleStyle = MaterialTheme.typography.labelMedium.copy(
        fontSize = with(density) { 11.dp.toSp() },
        lineHeight = with(density) { 13.dp.toSp() }
    )
    val matrixTimeStyle = MaterialTheme.typography.labelMedium.copy(
        fontSize = with(density) { 12.dp.toSp() },
        lineHeight = with(density) { 14.dp.toSp() },
        fontFamily = FontFamily.Monospace
    )
    Card(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        modifier = Modifier
            .width(cellWidth)
            .height(MatrixCellHeight)
            .padding(1.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = MatrixColors.Text,
            disabledContainerColor = containerColor,
            disabledContentColor = MatrixColors.Text
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 1.dp,
            disabledElevation = 1.dp
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(4.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (shiftTitle != null && shiftTime != null) {
                Text(
                    text = shiftTitle,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = matrixTitleStyle,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = shiftTime,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = matrixTimeStyle,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false
                )
                if (text.isNotBlank()) {
                    Text(
                        text = text,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
            } else {
                Text(
                    text = text,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 4
                )
            }
        }
    }
}

private fun managerHeaderColor(role: EmployeeRole): Color = when (role) {
    EmployeeRole.RM -> MatrixColors.RmHeader
    EmployeeRole.TRAINEE -> MatrixColors.TraineeHeader
    EmployeeRole.BORROWED -> MatrixColors.BorrowedHeader
    EmployeeRole.MANAGER -> MatrixColors.Header
}

private fun shiftColor(kind: ShiftKind): Color = when (kind) {
    ShiftKind.SETUP -> MatrixColors.Setup
    ShiftKind.DAY -> MatrixColors.Day
    ShiftKind.MIDDLE -> MatrixColors.Middle
    ShiftKind.CLOSE -> MatrixColors.Close
    ShiftKind.NIGHT -> MatrixColors.Night
    ShiftKind.KPI -> MatrixColors.Kpi
    ShiftKind.CUSTOM -> MatrixColors.Custom
}

private fun absenceMatrixLabel(absence: Absence): String = when (absence.type) {
    AbsenceType.VACATION -> "Vakantie"
    AbsenceType.SNIPPER_DAY -> "Snipperdag"
    AbsenceType.LEAVE -> "Verlof"
    AbsenceType.SPECIAL_LEAVE -> "Bijz. verlof"
    AbsenceType.UNPAID_LEAVE -> "Onbet. verlof"
    AbsenceType.COMP_TIME -> "Comp."
    AbsenceType.SICK -> "Ziek"
    AbsenceType.MATERNITY -> "Zw.verlof"
    AbsenceType.ADAPTED_WORK -> "Aangepast"
    AbsenceType.TRAINING -> "Training"
    AbsenceType.OTHER -> "Afwezig"
}

private fun absenceColor(type: AbsenceType): Color = when (type) {
    AbsenceType.VACATION -> MatrixColors.Vacation
    AbsenceType.SICK -> MatrixColors.Sick
    AbsenceType.SNIPPER_DAY,
    AbsenceType.LEAVE,
    AbsenceType.SPECIAL_LEAVE,
    AbsenceType.UNPAID_LEAVE,
    AbsenceType.COMP_TIME,
    AbsenceType.MATERNITY -> MatrixColors.Leave
    AbsenceType.ADAPTED_WORK,
    AbsenceType.TRAINING -> MatrixColors.Present
    AbsenceType.OTHER -> MatrixColors.Unavailable
}

private fun responsibilityAppliesUi(rule: ResponsibilityRule, date: LocalDate, ym: YearMonth): Boolean = when (rule.recurrence) {
    RecurrenceType.WEEKLY -> date.dayOfWeek.value == rule.weekday
    RecurrenceType.MONTHLY_DAY -> rule.monthDay != null && date.dayOfMonth == rule.monthDay
    RecurrenceType.MONTH_END -> date == ym.atEndOfMonth()
    RecurrenceType.SPECIFIC_DATE -> rule.date == date.toString()
}

private fun responsibilityLabelUi(rule: ResponsibilityRule): String = rule.label.ifBlank {
    when (rule.type) {
        ResponsibilityType.WEEK_COUNT -> "Weektelling"
        ResponsibilityType.MONTH_COUNT -> "Maandtelling"
        ResponsibilityType.MAINTENANCE -> "Onderhoud"
        ResponsibilityType.ADMIN -> "Admin"
        ResponsibilityType.KPI -> "KPI"
        ResponsibilityType.HACCP -> "HACCP"
        ResponsibilityType.STOCK -> "Voorraad"
        ResponsibilityType.HAVI -> "HAVI"
        ResponsibilityType.TRAINING -> "Training"
        ResponsibilityType.MEETING -> "Meeting"
        ResponsibilityType.OFFICE -> "Kantoor"
        ResponsibilityType.INTERVIEW -> "Sollicitatie"
        ResponsibilityType.CREW_PLANNING -> "Crewplanning"
        ResponsibilityType.CUSTOM -> "Taak"
    }
}

private fun personMarkerLabelUi(marker: PersonDayMarker): String {
    val base = when (marker.type) {
        PersonMarkerType.PRESENT -> "Aanwezig"
        PersonMarkerType.OFFICE -> "Kantoor"
        PersonMarkerType.TRAINING -> "Training"
        PersonMarkerType.MEETING -> "Meeting"
        PersonMarkerType.MAINTENANCE -> "Onderhoud"
        PersonMarkerType.ADMIN -> "Admin"
        PersonMarkerType.OTHER -> "Marker"
    }
    return if (marker.note.isBlank()) base else "$base: ${marker.note}"
}

private fun buildDayDetails(
    controller: AppController,
    date: LocalDate,
    ym: YearMonth
): String {
    val parts = mutableListOf<String>()
    controller.state.dayNotes.firstOrNull {
        it.locationId == controller.state.activeLocationId && it.date == date.toString()
    }
        ?.text?.takeIf { it.isNotBlank() }?.let { parts += it }

    val rules = controller.state.responsibilities.filter {
        it.active &&
            it.locationId == controller.state.activeLocationId &&
            responsibilityAppliesUi(it, date, ym)
    }
    rules.forEach { rule ->
        val name = controller.state.employees.firstOrNull { it.id == rule.employeeId }?.name ?: "?"
        parts += "${responsibilityLabelUi(rule)}: $name"
    }

    controller.state.personMarkers.filter {
        it.locationId == controller.state.activeLocationId && it.date == date.toString()
    }.forEach { marker ->
        val name = controller.state.employees.firstOrNull { it.id == marker.employeeId }?.name ?: "?"
        parts += "${personMarkerLabelUi(marker)}: $name"
    }

    val settings = controller.state.settings
    val hasMonthCounter = rules.any { it.type == ResponsibilityType.MONTH_COUNT }
    val hasWeekCounter = rules.any { it.type == ResponsibilityType.WEEK_COUNT }
    if (settings.showMonthCountOnLastDay && date == ym.atEndOfMonth() && !hasMonthCounter) {
        parts += "Maandtelling"
    } else if (settings.showWeeklyCount && date.dayOfWeek.value == settings.weekCountWeekday && !hasWeekCounter) {
        parts += "Weektelling"
    }

    if (date.dayOfWeek.value in controller.activeLocation().busyWeekdays) parts += "Drukke dag"
    if (controller.violations.any { it.severity == AtwValidator.Severity.ERROR && it.date == date }) parts += "⚠ ATW"
    return parts.distinct().joinToString(" • ")
}

@Composable
private fun MatrixLegend() {
    Column(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Kleuren", fontWeight = FontWeight.Bold)
        Text("SETUP • DAG • TUSSEN • SLUIT • NACHT • KPI")
        Text("Groen setup • blauw dag • oranje tussen • paars sluit • indigo nacht • turquoise KPI • lichtblauw vakantie • geel verlof/snipper • rood ziek/conflict • mint aanwezig/taak")
        Text(
            "🔒 vast • 📌 voorkeur (alleen verplaatsen indien nodig) • geen symbool = solver vrij",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun RulesScreen(controller: AppController, onExport: () -> Unit, onImport: () -> Unit) {
    val s = controller.state.settings
    val locationId = controller.state.activeLocationId
    val templateKinds = ShiftKind.entries
    var newTemplateName by remember(locationId) { mutableStateOf("") }
    var newTemplateKindIndex by remember(locationId) {
        mutableIntStateOf(templateKinds.indexOf(ShiftKind.CUSTOM))
    }
    var newTemplateStart by remember(locationId) { mutableStateOf("09:00") }
    var newTemplateEnd by remember(locationId) { mutableStateOf("17:00") }
    var newTemplateDays by remember(locationId) { mutableStateOf((1..7).toSet()) }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Roosterregels", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                LocationSettingsPanel(controller)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Algemene regels", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                SettingSwitch("ATW-controle actief", s.atwEnabled) { controller.updateSettings(s.copy(atwEnabled = it)) }
                Text("Dagelijkse rust: ${s.strictDailyRestHours} uur", style = MaterialTheme.typography.bodyMedium)
                SettingSwitch("Sta 1×/7 dagen verkorting tot 8 uur toe", s.allowOneReducedDailyRestPer7Days) {
                    controller.updateSettings(s.copy(allowOneReducedDailyRestPer7Days = it))
                }
                SettingSwitch("Trainee nooit zonder ervaren manager", s.traineeMustHaveExperiencedManager) {
                    controller.updateSettings(s.copy(traineeMustHaveExperiencedManager = it))
                }
                SettingSwitch("Minimaliseer leenmanager", s.minimizeBorrowedManagers) {
                    controller.updateSettings(s.copy(minimizeBorrowedManagers = it))
                }
                SettingSwitch("Weektelling in Bijzonderheden", s.showWeeklyCount) {
                    controller.updateSettings(s.copy(showWeeklyCount = it))
                }
                SettingSwitch("Maandtelling op laatste dag", s.showMonthCountOnLastDay) {
                    controller.updateSettings(s.copy(showMonthCountOnLastDay = it))
                }
                SettingSwitch("Waarschuw bij minder dan 13 vrije zondagen/jaar", s.warnMinimumFreeSundays) {
                    controller.updateSettings(s.copy(warnMinimumFreeSundays = it))
                }
                SettingSwitch("Voorkeur: 2 dagen achter elkaar vrij", s.preferTwoConsecutiveDaysOff) {
                    controller.updateSettings(s.copy(preferTwoConsecutiveDaysOff = it))
                }
                SettingSwitch("Max opeenvolgende dagen is harde regel", s.treatMaxConsecutiveDaysAsHardRule) {
                    controller.updateSettings(s.copy(treatMaxConsecutiveDaysAsHardRule = it))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Max opeenvolgende werkdagen", modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { controller.updateSettings(s.copy(maxConsecutiveWorkDays = (s.maxConsecutiveWorkDays - 1).coerceAtLeast(1))) }) { Text("−") }
                    Spacer(Modifier.width(8.dp))
                    Text(s.maxConsecutiveWorkDays.toString(), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { controller.updateSettings(s.copy(maxConsecutiveWorkDays = (s.maxConsecutiveWorkDays + 1).coerceAtMost(12))) }) { Text("+") }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Diensttemplates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Maak zelf zoveel diensten als nodig. Operationele typen kunnen door de planner worden gebruikt; " +
                        "KPI en Custom zijn alleen voor handmatige planning. Een eindtijd na middernacht, " +
                        "bijvoorbeeld 17:00–01:00, wordt automatisch goed berekend."
                )
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Nieuwe dienst maken", fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = newTemplateName,
                            onValueChange = { newTemplateName = it },
                            label = { Text("Naam, bijvoorbeeld Tussen 11-19") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Type: ${shiftKindLabel(templateKinds[newTemplateKindIndex])}",
                                Modifier.weight(1f)
                            )
                            OutlinedButton(onClick = {
                                newTemplateKindIndex =
                                    (newTemplateKindIndex + 1) % templateKinds.size
                            }) { Text("Wijzig type") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TimePickerField(
                                value = newTemplateStart,
                                onValueChange = { newTemplateStart = it },
                                label = "Start",
                                modifier = Modifier.weight(1f),
                                fallback = java.time.LocalTime.of(9, 0)
                            )
                            TimePickerField(
                                value = newTemplateEnd,
                                onValueChange = { newTemplateEnd = it },
                                label = "Einde",
                                modifier = Modifier.weight(1f),
                                fallback = java.time.LocalTime.of(17, 0)
                            )
                        }
                        Text("Actieve weekdagen", fontWeight = FontWeight.SemiBold)
                        DayToggleRow(newTemplateDays) { newTemplateDays = it }
                        Button(
                            onClick = {
                                val added = controller.addTemplate(
                                    name = newTemplateName,
                                    kind = templateKinds[newTemplateKindIndex],
                                    start = newTemplateStart,
                                    end = newTemplateEnd,
                                    weekdays = newTemplateDays
                                )
                                if (added) newTemplateName = ""
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Diensttemplate toevoegen") }
                    }
                }
                controller.state.shiftTemplates
                    .filter { it.locationId == controller.state.activeLocationId }
                    .forEach { template ->
                    ShiftTemplateEditor(
                        template = template,
                        onSave = controller::updateTemplate,
                        onDelete = controller::removeTemplate,
                        onError = controller::showStatus
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Lokale data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Alles blijft op dit toestel. Import/export gebruikt één JSON-bestand met team, regels en roosterhistorie.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onExport, modifier = Modifier.weight(1f)) { Text("Export") }
                    FilledTonalButton(onClick = onImport, modifier = Modifier.weight(1f)) { Text("Import") }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("ATW-opmerking", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("De app controleert de algemene Nederlandse regels voor werknemers van 18+. CAO-, jeugd-, zwangerschap- en sectorspecifieke afwijkingen kunnen extra regels geven. Voor 4/16-weken- en jaarcontroles is voldoende bewaarde historie nodig.")
            }
        }
    }
}

@Composable
private fun ShiftTemplateEditor(
    template: ShiftTemplate,
    onSave: (ShiftTemplate) -> Unit,
    onDelete: (String) -> Unit,
    onError: (String) -> Unit
) {
    var name by remember(template.id, template.name) { mutableStateOf(template.name) }
    var kind by remember(template.id, template.kind) { mutableStateOf(template.kind) }
    var start by remember(template.id, template.start) { mutableStateOf(template.start) }
    var end by remember(template.id, template.end) { mutableStateOf(template.end) }
    var weekdays by remember(template.id, template.enabledWeekdays) {
        mutableStateOf(template.enabledWeekdays)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Naam dienst") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Type: ${shiftKindLabel(kind)}", Modifier.weight(1f))
                OutlinedButton(onClick = {
                    kind = ShiftKind.entries[(ShiftKind.entries.indexOf(kind) + 1) % ShiftKind.entries.size]
                }) { Text("Wijzig type") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimePickerField(
                    value = start,
                    onValueChange = { start = it },
                    label = "Start",
                    modifier = Modifier.weight(1f)
                )
                TimePickerField(
                    value = end,
                    onValueChange = { end = it },
                    label = "Einde",
                    modifier = Modifier.weight(1f),
                    fallback = java.time.LocalTime.of(17, 0)
                )
            }
            Text("Actieve weekdagen", fontWeight = FontWeight.SemiBold)
            DayToggleRow(weekdays) { weekdays = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val okStart = runCatching { java.time.LocalTime.parse(start) }.isSuccess
                    val okEnd = runCatching { java.time.LocalTime.parse(end) }.isSuccess
                    when {
                        name.isBlank() -> onError("Vul een naam voor de dienst in")
                        !okStart || !okEnd -> onError("Gebruik tijden als HH:mm")
                        weekdays.isEmpty() -> onError("Kies minimaal één weekdag")
                        else -> onSave(
                            template.copy(
                                name = name.trim(),
                                kind = kind,
                                start = start,
                                end = end,
                                enabledWeekdays = weekdays
                            )
                        )
                    }
                }, modifier = Modifier.weight(1f)) { Text("Alles opslaan") }
                OutlinedButton(
                    onClick = { onDelete(template.id) },
                    modifier = Modifier.weight(1f)
                ) { Text("Verwijderen") }
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DayToggleRow(selected: Set<Int>, onChange: (Set<Int>) -> Unit) {
    val labels = listOf("Ma", "Di", "Wo", "Do", "Vr", "Za", "Zo")
    Column {
        labels.chunked(4).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEachIndexed { colIndex, label ->
                    val day = rowIndex * 4 + colIndex + 1
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = day in selected,
                            onCheckedChange = { checked ->
                                onChange(if (checked) selected + day else selected - day)
                            }
                        )
                        Text(label)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun WarningCard(message: String, error: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (error) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            }
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(if (error) "⚠ $message" else "• $message")
        }
    }
}

@Composable
private fun InfoCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Text(message, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun RepairProposalCard(controller: AppController) {
    val proposal = controller.repairProposal ?: return
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Automatische roosteroplossing", fontWeight = FontWeight.Bold)
            Text(
                if (proposal.errorsBefore > 0) {
                    "ATW-fouten: ${proposal.errorsBefore} → ${proposal.errorsAfter}. " +
                        "Controleer het voorstel en pas het met één tik toe."
                } else {
                    "De planner heeft na je handmatige wijziging een passende aanvulling of " +
                        "herverdeling gevonden."
                }
            )
            proposal.changes.take(6).forEach { Text("• $it") }
            if (proposal.unfilled.isNotEmpty()) {
                Text(
                    "Na herstel blijven ${proposal.unfilled.size} open punt(en) over.",
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = controller::applyRepairProposal,
                    modifier = Modifier.weight(1f)
                ) { Text("Oplossing toepassen") }
                OutlinedButton(
                    onClick = controller::dismissRepairProposal,
                    modifier = Modifier.weight(1f)
                ) { Text("Niet toepassen") }
            }
        }
    }
}

private fun shiftKindLabel(kind: ShiftKind): String = when (kind) {
    ShiftKind.SETUP -> "Setup"
    ShiftKind.DAY -> "Dag"
    ShiftKind.MIDDLE -> "Tussen"
    ShiftKind.CLOSE -> "Sluit"
    ShiftKind.NIGHT -> "Nacht"
    ShiftKind.KPI -> "KPI"
    ShiftKind.CUSTOM -> "Custom"
}

private fun roleLabel(role: EmployeeRole): String = when (role) {
    EmployeeRole.MANAGER -> "Manager"
    EmployeeRole.RM -> "Restaurant Manager"
    EmployeeRole.TRAINEE -> "Trainee"
    EmployeeRole.BORROWED -> "Leenmanager"
}
