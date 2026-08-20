package nl.roosterandroid.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
            availability = state.availability.filterNot { it.employeeId == id }
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
        val result = engine.generate(state.copy(assignments = emptyList()))
        commit(state.copy(assignments = result.assignments), "Rooster opnieuw gegenereerd")
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

    fun setStatus(message: String) { status = message }

    private fun assignmentMonth(a: Assignment): YearMonth? = runCatching { YearMonth.from(LocalDate.parse(a.date)) }.getOrNull()
}

private enum class AppTab(val label: String) { OVERZICHT("Overzicht"), TEAM("Team"), ROOSTER("Rooster"), REGELS("Regels") }

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
            }.onSuccess { controller.setStatus("Export opgeslagen") }
                .onFailure { controller.setStatus("Export mislukt: ${it.message}") }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Bestand kon niet worden gelezen")
            }.onSuccess { controller.importJson(it) }
                .onFailure { controller.setStatus("Import mislukt: ${it.message}") }
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
                        Text("Beschikbaarheid / vaste dienst", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                                    controller.setStatus("Controleer datum en tijden (HH:mm)")
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

        items(controller.state.employees, key = { it.id }) { employee ->
            EmployeeCard(employee, controller::updateEmployee, controller::removeEmployee)
        }
        item { Spacer(Modifier.height(24.dp)) }
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
    val employees = controller.state.employees.associateBy { it.id }
    val templates = controller.state.shiftTemplates.associateBy { it.id }
    val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale("nl", "NL"))

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { MonthHeader(controller) }
        if (controller.state.assignments.isEmpty()) {
            item { InfoCard("Nog geen rooster. Ga naar Overzicht en tik op ‘Genereer rooster’.") }
        }
        for (d in 1..ym.lengthOfMonth()) {
            val date = ym.atDay(d)
            val dayAssignments = controller.state.assignments.filter { it.date == date.toString() }
            item(key = date.toString()) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(date.format(dateFmt).replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Bold)
                        if (dayAssignments.isEmpty()) {
                            Text("Geen managerdiensten", style = MaterialTheme.typography.bodySmall)
                        } else {
                            dayAssignments.forEach { a ->
                                val e = employees[a.employeeId]
                                val t = templates[a.shiftTemplateId]
                                Text("${t?.name ?: "Dienst"} ${t?.start ?: ""}–${t?.end ?: ""}  •  ${e?.name ?: "Onbekend"}")
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
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
                        onError = controller::setStatus
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
