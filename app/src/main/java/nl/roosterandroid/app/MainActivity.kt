package nl.roosterandroid.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val storage = remember { ScheduleStorage(applicationContext) }
                val controller = remember { AppController(storage) }
                RoosterApp(controller)
            }
        }
    }
}

class AppController(private val storage: ScheduleStorage) {
    private val validator = AtwValidator()
    private val engine = ScheduleEngine(validator)

    var state by mutableStateOf(storage.load())
        private set
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

    private fun commit(newState: AppState, message: String? = null) {
        state = newState
        storage.save(state)
        violations = validator.validate(state)
        if (message != null) status = message
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
            canSetup = role != EmployeeRole.BORROWED
        )
        commit(state.copy(employees = state.employees + employee), "$clean toegevoegd")
    }

    fun updateEmployee(employee: Employee) {
        commit(state.copy(employees = state.employees.map { if (it.id == employee.id) employee else it }))
    }

    fun upsertAvailability(availability: Availability) {
        val updated = state.availability.filterNot { it.employeeId == availability.employeeId && it.date == availability.date } + availability
        commit(state.copy(availability = updated), "Beschikbaarheid opgeslagen")
    }

    fun removeAvailability(employeeId: String, date: String) {
        commit(state.copy(availability = state.availability.filterNot { it.employeeId == employeeId && it.date == date }))
    }

    fun upsertWeeklyAvailability(rule: WeeklyAvailability) {
        val updated = state.weeklyAvailability.filterNot {
            it.employeeId == rule.employeeId && it.weekday == rule.weekday
        } + rule
        commit(state.copy(weeklyAvailability = updated), "Vaste weekbeschikbaarheid opgeslagen")
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
        val keepAssignments = if (absence.status == AbsenceStatus.APPROVED) {
            state.assignments.filterNot { a ->
                if (a.employeeId != absence.employeeId) return@filterNot false
                val d = runCatching { LocalDate.parse(a.date) }.getOrNull() ?: return@filterNot false
                !d.isBefore(start) && !d.isAfter(end)
            }
        } else {
            state.assignments
        }
        commit(state.copy(absences = updated, assignments = keepAssignments), "${absence.type.name.lowercase()} opgeslagen")
    }

    fun removeAbsence(id: String) {
        commit(state.copy(absences = state.absences.filterNot { it.id == id }), "Afwezigheid verwijderd")
    }

    fun upsertResponsibility(rule: ResponsibilityRule) {
        val updated = state.responsibilities.filterNot { it.id == rule.id } + rule
        commit(state.copy(responsibilities = updated), "Vaste verantwoordelijkheid opgeslagen")
    }

    fun removeResponsibility(id: String) {
        commit(state.copy(responsibilities = state.responsibilities.filterNot { it.id == id }), "Verantwoordelijkheid verwijderd")
    }

    fun upsertPersonMarker(marker: PersonDayMarker) {
        val updated = state.personMarkers.filterNot { it.id == marker.id } + marker
        commit(state.copy(personMarkers = updated), "Marker opgeslagen")
    }

    fun removePersonMarker(id: String) {
        commit(state.copy(personMarkers = state.personMarkers.filterNot { it.id == id }), "Marker verwijderd")
    }

    fun upsertDayNote(date: String, text: String) {
        val clean = text.trim()
        val keep = state.dayNotes.filterNot { it.date == date }
        commit(
            state.copy(dayNotes = if (clean.isEmpty()) keep else keep + DayNote(date, clean)),
            if (clean.isEmpty()) "Bijzonderheid verwijderd" else "Bijzonderheid opgeslagen"
        )
    }

    fun setManualAssignment(employeeId: String, date: String, templateId: String?) {
        val without = state.assignments.filterNot {
            it.employeeId == employeeId && it.date == date
        }
        if (templateId == null) {
            commit(state.copy(assignments = without), "Dienst op vrij gezet")
            return
        }

        manualBlockReason(employeeId, date, templateId)?.let {
            status = "Niet opgeslagen: $it"
            return
        }

        val candidate = Assignment(
            employeeId = employeeId,
            date = date,
            shiftTemplateId = templateId,
            source = "manual"
        )
        val proposed = state.copy(assignments = without + candidate)
        val errors = validator.validate(proposed).filter {
            it.severity == AtwValidator.Severity.ERROR &&
                it.employeeId == employeeId &&
                (it.date?.toString() == date || it.date == null)
        }
        if (errors.isNotEmpty()) {
            status = "Niet opgeslagen: ${errors.first().message}"
            return
        }
        commit(proposed, "Handmatige dienst opgeslagen")
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
        val first = state.assignments.lastOrNull { it.employeeId == firstEmployeeId && it.date == date }
        val second = state.assignments.lastOrNull { it.employeeId == secondEmployeeId && it.date == date }
        if (first == null && second == null) {
            status = "Geen diensten om te ruilen op $date"
            return
        }

        if (first != null) manualBlockReason(secondEmployeeId, date, first.shiftTemplateId)?.let {
            status = "Ruil niet mogelijk: $it"
            return
        }
        if (second != null) manualBlockReason(firstEmployeeId, date, second.shiftTemplateId)?.let {
            status = "Ruil niet mogelijk: $it"
            return
        }

        val keep = state.assignments.filterNot {
            it.date == date && (it.employeeId == firstEmployeeId || it.employeeId == secondEmployeeId)
        }
        val swapped = buildList {
            if (first != null) add(Assignment(employeeId = secondEmployeeId, date = date, shiftTemplateId = first.shiftTemplateId, source = "manual-swap"))
            if (second != null) add(Assignment(employeeId = firstEmployeeId, date = date, shiftTemplateId = second.shiftTemplateId, source = "manual-swap"))
        }
        val proposed = state.copy(assignments = keep + swapped)
        val baselineKeys = validator.validate(state)
            .filter { it.severity == AtwValidator.Severity.ERROR && it.employeeId in setOf(firstEmployeeId, secondEmployeeId) }
            .map { "${it.employeeId}|${it.date}|${it.rule}|${it.message}" }.toSet()
        val newErrors = validator.validate(proposed)
            .filter { it.severity == AtwValidator.Severity.ERROR && it.employeeId in setOf(firstEmployeeId, secondEmployeeId) }
            .filter { "${it.employeeId}|${it.date}|${it.rule}|${it.message}" !in baselineKeys }
        if (newErrors.isNotEmpty()) {
            status = "Ruil niet mogelijk: ${newErrors.first().message}"
            return
        }
        commit(proposed, "Diensten geruild")
    }

    private fun manualBlockReason(employeeId: String, date: String, templateId: String): String? {
        val employee = state.employees.firstOrNull { it.id == employeeId } ?: return "manager niet gevonden"
        val template = state.shiftTemplates.firstOrNull { it.id == templateId } ?: return "dienst niet gevonden"
        val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return "ongeldige datum"
        state.absences.firstOrNull {
            it.employeeId == employeeId &&
                it.status == AbsenceStatus.APPROVED &&
                it.includes(d)
        }?.let {
            return "${employee.name} heeft ${it.type.name.lowercase()}"
        }
        if (!employee.canWork(template.kind)) return "${employee.name} mag ${shiftKindLabel(template.kind)} niet werken"
        val specific = state.availability.lastOrNull { it.employeeId == employeeId && it.date == date }
        val weekly = state.weeklyAvailability.lastOrNull { it.employeeId == employeeId && it.weekday == d.dayOfWeek.value }
        val availabilityRule = specific ?: weekly
        if (!(availabilityRule?.available ?: true)) return "${employee.name} is niet beschikbaar"
        val fixedKind = availabilityRule?.fixedShiftKind
        if (fixedKind != null && fixedKind != template.kind) return "${employee.name} heeft die dag een andere vaste dienst"
        val earliest = availabilityRule?.earliestStart?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() }
        val latest = availabilityRule?.latestEnd?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() }
        if (earliest != null && template.startTime().isBefore(earliest)) return "dienst begint vóór beschikbaarheid van ${employee.name}"
        if (latest != null) {
            val startDt = d.atTime(template.startTime())
            var endDt = d.atTime(template.endTime())
            if (!endDt.isAfter(startDt)) endDt = endDt.plusDays(1)
            var latestDt = d.atTime(latest)
            if (!latestDt.isAfter(startDt)) latestDt = latestDt.plusDays(1)
            if (endDt.isAfter(latestDt)) return "dienst eindigt na beschikbaarheid van ${employee.name}"
        }
        return null
    }

    fun upsertDayDemand(demand: DayDemand) {
        val updated = state.dayDemands.filterNot { it.date == demand.date } + demand
        commit(state.copy(dayDemands = updated), "Bezetting opgeslagen")
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

        manualBlockReason(second.employeeId, first.date, first.shiftTemplateId)?.let {
            status = "Ruil niet mogelijk: $it"
            return
        }
        manualBlockReason(first.employeeId, second.date, second.shiftTemplateId)?.let {
            status = "Ruil niet mogelijk: $it"
            return
        }

        val swappedFirst = first.copy(employeeId = second.employeeId, source = "manual-swap")
        val swappedSecond = second.copy(employeeId = first.employeeId, source = "manual-swap")
        val keep = state.assignments.filterNot { it.id == first.id || it.id == second.id }
        val proposed = state.copy(assignments = keep + swappedFirst + swappedSecond)

        val involved = setOf(first.employeeId, second.employeeId)
        val baseline = validator.validate(state)
            .filter { it.severity == AtwValidator.Severity.ERROR && it.employeeId in involved }
            .map { "${it.employeeId}|${it.date}|${it.rule}|${it.message}" }
            .toSet()

        val newErrors = validator.validate(proposed)
            .filter { it.severity == AtwValidator.Severity.ERROR && it.employeeId in involved }
            .filter { "${it.employeeId}|${it.date}|${it.rule}|${it.message}" !in baseline }

        if (newErrors.isNotEmpty()) {
            status = "Ruil niet mogelijk: ${newErrors.first().message}"
            return
        }

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

    fun updateTemplate(template: ShiftTemplate) {
        commit(state.copy(shiftTemplates = state.shiftTemplates.map { if (it.id == template.id) template else it }))
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
            personMarkers = state.personMarkers.filterNot { it.employeeId == id }
        ))
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
        commit(state.copy(assignments = result.assignments), "Rooster geoptimaliseerd met Solver v0.5.2")
        unfilled = result.unfilled
        plannerWarnings = result.warnings
    }

    fun clearCurrentMonth() {
        commit(state.copy(assignments = emptyList()), "Rooster van deze maand gewist")
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

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("RoosterAndroid", fontWeight = FontWeight.Bold)
                    Text(controller.state.settings.locationName, style = MaterialTheme.typography.labelMedium)
                }
            })
        },
        bottomBar = {
            NavigationBar {
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
                        label = { Text(item.label) }
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
            controller.status?.let {
                Text(
                    text = it,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            when (tab) {
                AppTab.OVERZICHT -> OverviewScreen(controller)
                AppTab.TEAM -> TeamScreen(controller)
                AppTab.ROOSTER -> ScheduleScreen(controller)
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
private fun MonthHeader(controller: AppController) {
    val ym = YearMonth.of(controller.state.year, controller.state.month)
    val locale = Locale("nl", "NL")
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(onClick = { controller.changeMonth(-1) }) { Text("‹") }
        Text(
            "${ym.month.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.uppercase() }} ${ym.year}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        OutlinedButton(onClick = { controller.changeMonth(1) }) { Text("›") }
    }
}

@Composable
private fun OverviewScreen(controller: AppController) {
    val errors = controller.violations.count { it.severity == AtwValidator.Severity.ERROR }
    val warnings = controller.violations.count { it.severity == AtwValidator.Severity.WARNING }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { MonthHeader(controller) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryCard("Managers", controller.state.employees.count { it.active }.toString(), Modifier.weight(1f))
                SummaryCard("Diensten", controller.state.assignments.size.toString(), Modifier.weight(1f))
                SummaryCard("ATW", if (errors == 0) "✓" else "$errors fout", Modifier.weight(1f))
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { controller.generate() }, modifier = Modifier.weight(1f)) { Text("Genereer rooster") }
                OutlinedButton(onClick = { controller.clearCurrentMonth() }, modifier = Modifier.weight(1f)) { Text("Wis maand") }
            }
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
private fun SummaryCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
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
    val fixedKinds = listOf<ShiftKind?>(null, ShiftKind.SETUP, ShiftKind.DAY, ShiftKind.MIDDLE, ShiftKind.CLOSE, ShiftKind.KPI)

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

        if (controller.state.employees.isNotEmpty()) {
            item {
                val idx = availabilityEmployeeIndex.coerceIn(0, controller.state.employees.lastIndex)
                val selectedEmployee = controller.state.employees[idx]
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Afwijking op specifieke datum", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)\n                        Text("Gebruik dit voor een afwijkende vrije dag of werktijd. Deze datumregel gaat volledig voor de vaste weekbeschikbaarheid.", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(selectedEmployee.name, modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = { availabilityEmployeeIndex = (idx + 1) % controller.state.employees.size }) { Text("Volgende") }
                        }
                        OutlinedTextField(
                            value = availabilityDate,
                            onValueChange = { availabilityDate = it },
                            label = { Text("Datum (YYYY-MM-DD)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        SettingSwitch("Beschikbaar", availabilityEnabled) { availabilityEnabled = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = earliestStart, onValueChange = { earliestStart = it },
                                label = { Text("Vanaf HH:mm") }, modifier = Modifier.weight(1f), singleLine = true
                            )
                            OutlinedTextField(
                                value = latestEnd, onValueChange = { latestEnd = it },
                                label = { Text("Tot HH:mm") }, modifier = Modifier.weight(1f), singleLine = true
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

        if (controller.state.employees.isNotEmpty()) {
            item { RecurringAvailabilityPanel(controller) }
        }

        items(controller.state.employees, key = { it.id }) { employee ->
            EmployeeCard(employee, controller::updateEmployee, controller::removeEmployee)
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun RecurringAvailabilityPanel(controller: AppController) {
    var employeeIndex by remember { mutableIntStateOf(0) }
    var weekday by remember { mutableIntStateOf(1) }

    val employees = controller.state.employees
    if (employees.isEmpty()) return

    val idx = employeeIndex.coerceIn(0, employees.lastIndex)
    val employee = employees[idx]
    val dayNames = listOf("Ma", "Di", "Wo", "Do", "Vr", "Za", "Zo")
    val fixedKinds = listOf<ShiftKind?>(
        null,
        ShiftKind.SETUP,
        ShiftKind.DAY,
        ShiftKind.MIDDLE,
        ShiftKind.CLOSE
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
                OutlinedTextField(
                    value = earliest,
                    onValueChange = { earliest = it },
                    label = { Text("Vanaf") },
                    placeholder = { Text("09:00") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = available
                )
                OutlinedTextField(
                    value = latest,
                    onValueChange = { latest = it },
                    label = { Text("Tot") },
                    placeholder = { Text("22:00") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = available
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
private fun ScheduleScreen(controller: AppController) {
    val ym = YearMonth.of(controller.state.year, controller.state.month)
    val employees = controller.state.employees.filter { it.active }
    val templates = controller.state.shiftTemplates.associateBy { it.id }
    val managerScroll = rememberScrollState()
    val locale = Locale("nl", "NL")

    var noteDate by remember { mutableStateOf<String?>(null) }
    var noteDraft by remember { mutableStateOf("") }
    var editEmployeeId by remember { mutableStateOf<String?>(null) }
    var editDate by remember { mutableStateOf<String?>(null) }

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
                date.dayOfWeek.value in it.enabledWeekdays && employee.canWork(it.kind)
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
                        Text("Kies handmatig een dienst. Handmatige vakjes blijven staan bij opnieuw genereren.")
                        TextButton(onClick = {
                            controller.setManualAssignment(employee.id, date.toString(), null)
                            editEmployeeId = null
                            editDate = null
                        }) { Text("Vrij") }

                        options.forEach { template ->
                            TextButton(onClick = {
                                controller.setManualAssignment(
                                    employee.id,
                                    date.toString(),
                                    template.id
                                )
                                editEmployeeId = null
                                editDate = null
                            }) {
                                Text("${template.name}  ${template.start}–${template.end}")
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

        if (controller.state.assignments.isEmpty()) {
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
                    "Datums staan onder elkaar. Veeg horizontaal langs de managers. Tik een bijzonderheid of dienstvakje om te wijzigen.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))

                Row {
                    MatrixCell("Datum", 78.dp, MatrixColors.Header, strong = true)
                    MatrixCell(
                        "Bijzonderheden",
                        146.dp,
                        MatrixColors.NoteHeader,
                        strong = true
                    )

                    Row(Modifier.horizontalScroll(managerScroll)) {
                        employees.forEach { employee ->
                            MatrixCell(
                                text = employee.name,
                                cellWidth = 90.dp,
                                containerColor = managerHeaderColor(employee.role),
                                strong = true
                            )
                        }
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

                    val details = buildDayDetails(controller, date, ym)
                    MatrixCell(
                        text = details.ifBlank { " " },
                        cellWidth = 146.dp,
                        containerColor =
                            if (details.contains("⚠")) MatrixColors.Error
                            else MatrixColors.Note,
                        onClick = {
                            noteDate = dateString
                            noteDraft = controller.state.dayNotes
                                .firstOrNull { it.date == dateString }
                                ?.text
                                .orEmpty()
                        }
                    )

                    Row(Modifier.horizontalScroll(managerScroll)) {
                        employees.forEach { employee ->
                            val assignment = controller.state.assignments.lastOrNull {
                                it.employeeId == employee.id && it.date == dateString
                            }
                            val template = assignment?.let {
                                templates[it.shiftTemplateId]
                            }
                            val explicitAvailability =
                                controller.state.availability.lastOrNull {
                                    it.employeeId == employee.id && it.date == dateString
                                }
                            val weeklyAvailability =
                                controller.state.weeklyAvailability.lastOrNull {
                                    it.employeeId == employee.id && it.weekday == date.dayOfWeek.value
                                }
                            val absence = controller.state.absences.lastOrNull {
                                it.employeeId == employee.id &&
                                    it.status == AbsenceStatus.APPROVED &&
                                    it.includes(date)
                            }
                            val unavailable =
                                explicitAvailability?.available == false ||
                                    (explicitAvailability == null && weeklyAvailability?.available == false)

                            val conflict = controller.violations.any {
                                it.severity == AtwValidator.Severity.ERROR &&
                                    it.employeeId == employee.id && it.date == date
                            }

                            val taskLabels = controller.state.responsibilities
                                .filter { it.active && it.employeeId == employee.id && responsibilityAppliesUi(it, date, ym) }
                                .map(::responsibilityLabelUi)
                            val markerLabels = controller.state.personMarkers
                                .filter { it.employeeId == employee.id && it.date == dateString }
                                .map(::personMarkerLabelUi)
                            val extras = (taskLabels + markerLabels).distinct()

                            val text = when {
                                absence != null -> absenceMatrixLabel(absence)
                                assignment != null && template != null -> {
                                    val base = matrixShiftLabel(template, assignment.source)
                                    if (extras.isEmpty()) base else base + "\n" + extras.joinToString(" / ")
                                }
                                unavailable -> "Niet\nbesch."
                                extras.isNotEmpty() -> extras.joinToString("\n")
                                else -> "Vrij"
                            }

                            val color = when {
                                conflict -> MatrixColors.Error
                                absence != null -> absenceColor(absence.type)
                                unavailable -> MatrixColors.Unavailable
                                template != null -> shiftColor(template.kind)
                                extras.isNotEmpty() -> MatrixColors.Present
                                else -> if (weekend) MatrixColors.FreeWeekend else MatrixColors.Free
                            }

                            MatrixCell(
                                text = text,
                                cellWidth = 90.dp,
                                containerColor = color,
                                strong = assignment != null || unavailable,
                                onClick = {
                                    editEmployeeId = employee.id
                                    editDate = dateString
                                }
                            )
                        }
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
    val Header = Color(0xFFDDE7F5)
    val NoteHeader = Color(0xFFFFE6A8)
    val Date = Color(0xFFF3F5F7)
    val Weekend = Color(0xFFE7E9EE)
    val Note = Color(0xFFFFF4CF)
    val Setup = Color(0xFFDDF3D8)
    val Day = Color(0xFFD8EBFA)
    val Middle = Color(0xFFFFE4B5)
    val Close = Color(0xFFE6DCF7)
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
    val RmHeader = Color(0xFFD1E6FF)
    val TraineeHeader = Color(0xFFFFE2C6)
    val BorrowedHeader = Color(0xFFFFF0B8)
}

@Composable
private fun MatrixCell(
    text: String,
    cellWidth: androidx.compose.ui.unit.Dp,
    containerColor: Color,
    strong: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Card(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        modifier = Modifier
            .width(cellWidth)
            .height(64.dp)
            .padding(1.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            disabledContainerColor = containerColor,
            disabledContentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(4.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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
    ShiftKind.KPI -> MatrixColors.Kpi
    ShiftKind.CUSTOM -> MatrixColors.Custom
}

private fun matrixShiftLabel(
    template: ShiftTemplate,
    source: String
): String {
    val code = when (template.kind) {
        ShiftKind.SETUP -> "SET"
        ShiftKind.DAY -> "DAG"
        ShiftKind.MIDDLE -> "TUS"
        ShiftKind.CLOSE -> "SLU"
        ShiftKind.KPI -> "KPI"
        ShiftKind.CUSTOM -> template.name.take(3).uppercase()
    }
    val lock = if (source.startsWith("manual")) " 🔒" else ""
    val start = template.start.removeSuffix(":00")
    val end = template.end.removeSuffix(":00")
    return "$code$lock\n$start-$end"
}

private fun absenceMatrixLabel(absence: Absence): String = when (absence.type) {
    AbsenceType.VACATION -> "Vakantie"
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
    controller.state.dayNotes.firstOrNull { it.date == date.toString() }
        ?.text?.takeIf { it.isNotBlank() }?.let { parts += it }

    val rules = controller.state.responsibilities.filter {
        it.active && responsibilityAppliesUi(it, date, ym)
    }
    rules.forEach { rule ->
        val name = controller.state.employees.firstOrNull { it.id == rule.employeeId }?.name ?: "?"
        parts += "${responsibilityLabelUi(rule)}: $name"
    }

    controller.state.personMarkers.filter { it.date == date.toString() }.forEach { marker ->
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

    if (date.dayOfWeek.value in settings.busyWeekdays) parts += "Drukke dag"
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
        Text("SET setup • DAG dag • TUS tussen • SLU sluit • KPI vaste KPI-taak")
        Text("Groen setup • blauw dag • oranje tussen • paars sluit • turquoise KPI • lichtblauw vakantie • geel verlof • rood ziek/conflict • mint aanwezig/taak")
        Text(
            "🔒 = handmatig vastgezet en blijft staan bij opnieuw genereren",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun RulesScreen(controller: AppController, onExport: () -> Unit, onImport: () -> Unit) {
    val s = controller.state.settings
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Roosterregels", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = s.locationName,
                    onValueChange = { controller.updateSettings(s.copy(locationName = it)) },
                    label = { Text("Locatienaam") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                SettingSwitch("ATW-controle actief", s.atwEnabled) { controller.updateSettings(s.copy(atwEnabled = it)) }
                Text("Dagelijkse rust: ${s.strictDailyRestHours} uur", style = MaterialTheme.typography.bodyMedium)
                SettingSwitch("Sta 1×/7 dagen verkorting tot 8 uur toe", s.allowOneReducedDailyRestPer7Days) {
                    controller.updateSettings(s.copy(allowOneReducedDailyRestPer7Days = it))
                }
                SettingSwitch("Tussenmanager op drukke dagen", s.requireMiddleOnBusyDays) {
                    controller.updateSettings(s.copy(requireMiddleOnBusyDays = it))
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sluitmanagers bij maandsluiting", modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        controller.updateSettings(
                            s.copy(monthEndCloseManagers = (s.monthEndCloseManagers - 1).coerceAtLeast(1))
                        )
                    }) { Text("−") }
                    Spacer(Modifier.width(8.dp))
                    Text(s.monthEndCloseManagers.toString(), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        controller.updateSettings(
                            s.copy(monthEndCloseManagers = (s.monthEndCloseManagers + 1).coerceAtMost(4))
                        )
                    }) { Text("+") }
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
                Text("Drukke dagen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                DayToggleRow(s.busyWeekdays) { controller.updateSettings(s.copy(busyWeekdays = it)) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Diensttemplates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Pas tijden en actieve weekdagen aan. Een dienst die na middernacht eindigt, bijvoorbeeld 17:00–01:00, wordt automatisch goed over twee kalenderdagen berekend.")
                controller.state.shiftTemplates.forEach { template ->
                    ShiftTemplateEditor(
                        template = template,
                        onSave = controller::updateTemplate,
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
private fun ShiftTemplateEditor(template: ShiftTemplate, onSave: (ShiftTemplate) -> Unit, onError: (String) -> Unit) {
    var start by remember(template.id, template.start) { mutableStateOf(template.start) }
    var end by remember(template.id, template.end) { mutableStateOf(template.end) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${template.name} • ${shiftKindLabel(template.kind)}", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = start, onValueChange = { start = it }, label = { Text("Start") },
                    modifier = Modifier.weight(1f), singleLine = true
                )
                OutlinedTextField(
                    value = end, onValueChange = { end = it }, label = { Text("Einde") },
                    modifier = Modifier.weight(1f), singleLine = true
                )
            }
            DayToggleRow(template.enabledWeekdays) { onSave(template.copy(enabledWeekdays = it)) }
            OutlinedButton(onClick = {
                val okStart = runCatching { java.time.LocalTime.parse(start) }.isSuccess
                val okEnd = runCatching { java.time.LocalTime.parse(end) }.isSuccess
                if (!okStart || !okEnd) onError("Gebruik tijden als HH:mm")
                else onSave(template.copy(start = start, end = end))
            }, modifier = Modifier.fillMaxWidth()) { Text("Tijden opslaan") }
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
    Text(text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun WarningCard(message: String, error: Boolean) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(if (error) "⚠ $message" else "• $message")
        }
    }
}

@Composable
private fun InfoCard(message: String) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(message, modifier = Modifier.padding(12.dp))
    }
}

private fun shiftKindLabel(kind: ShiftKind): String = when (kind) {
    ShiftKind.SETUP -> "Setup"
    ShiftKind.DAY -> "Dag"
    ShiftKind.MIDDLE -> "Tussen"
    ShiftKind.CLOSE -> "Sluit"
    ShiftKind.KPI -> "KPI"
    ShiftKind.CUSTOM -> "Custom"
}

private fun roleLabel(role: EmployeeRole): String = when (role) {
    EmployeeRole.MANAGER -> "Manager"
    EmployeeRole.RM -> "Restaurant Manager"
    EmployeeRole.TRAINEE -> "Trainee"
    EmployeeRole.BORROWED -> "Leenmanager"
}
