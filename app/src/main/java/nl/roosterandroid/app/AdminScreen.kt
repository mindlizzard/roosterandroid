package nl.roosterandroid.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun AdminScreen(controller: AppController) {
    val state = controller.state
    val ym = YearMonth.of(state.year, state.month)
    val employees = state.employees.filter { it.active }
    val stats = employeeMonthStats(state)
    val quality = rosterQualityScore(
        state,
        controller.unfilled,
        controller.plannerWarnings,
        controller.violations
    )

    var absenceEmployeeIndex by remember { mutableIntStateOf(0) }
    var absenceTypeIndex by remember { mutableIntStateOf(0) }
    var absenceStatusIndex by remember { mutableIntStateOf(1) }
    var absenceStart by remember(state.year, state.month) { mutableStateOf(ym.atDay(1).toString()) }
    var absenceEnd by remember(state.year, state.month) { mutableStateOf(ym.atDay(1).toString()) }
    var absenceNote by remember { mutableStateOf("") }

    var taskEmployeeIndex by remember { mutableIntStateOf(0) }
    var taskTypeIndex by remember { mutableIntStateOf(0) }
    var recurrenceIndex by remember { mutableIntStateOf(0) }
    var taskWeekday by remember { mutableIntStateOf(1) }
    var taskMonthDay by remember { mutableIntStateOf(1) }
    var taskDate by remember(state.year, state.month) { mutableStateOf(ym.atDay(1).toString()) }
    var taskLabel by remember { mutableStateOf("") }
    var preferScheduled by remember { mutableStateOf(true) }

    var markerEmployeeIndex by remember { mutableIntStateOf(0) }
    var markerTypeIndex by remember { mutableIntStateOf(0) }
    var markerDate by remember(state.year, state.month) { mutableStateOf(ym.atDay(1).toString()) }
    var markerNote by remember { mutableStateOf("") }

    var demandDate by remember(state.year, state.month) { mutableStateOf(ym.atDay(1).toString()) }
    var guestCount by remember { mutableStateOf("") }
    var minimumManagers by remember { mutableIntStateOf(0) }

    var swapFirstIndex by remember { mutableIntStateOf(0) }
    var swapSecondIndex by remember { mutableIntStateOf(1) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Administratie", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${ym.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${ym.year}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AdminSummaryCard("Kwaliteit", "$quality/100", Modifier.weight(1f))
                    AdminSummaryCard("Diensten", state.assignments.size.toString(), Modifier.weight(1f))
                    AdminSummaryCard(
                        "ATW",
                        controller.violations.count { it.severity == AtwValidator.Severity.ERROR }.toString(),
                        Modifier.weight(1f)
                    )
                }
            }
        }

        item { AdminSectionTitle("Uren per manager") }
        items(stats) { stat ->
            AdminBar(
                label = stat.name,
                value = stat.hours,
                maxValue = maxOf(1.0, stats.maxOfOrNull { it.hours } ?: 1.0),
                suffix = "${"%.1f".format(stat.hours)}u / ${"%.1f".format(stat.targetHours)}u"
            )
        }

        item { AdminSectionTitle("Dienstverdeling") }
        items(stats) { stat ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(stat.name, fontWeight = FontWeight.Bold)
                    Text("SET ${stat.setup} • DAG ${stat.day} • TUS ${stat.middle} • SLU ${stat.close}")
                    Text("Weekend ${stat.weekend} • diensten ${stat.shifts} • +/- ${"%.1f".format(stat.hours - stat.targetHours)}u")
                    Text("Vak ${stat.vacationDays} • Verlof ${stat.leaveDays} • Ziek ${stat.sickDays}")
                }
            }
        }

        if (employees.isNotEmpty()) {
            item { AdminSectionTitle("Vakantie / verlof / ziek") }
            item {
                val eidx = absenceEmployeeIndex.coerceIn(0, employees.lastIndex)
                val employee = employees[eidx]
                val types = AbsenceType.entries
                val statuses = AbsenceStatus.entries
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AdminCycleRow(employee.name, "Volgende") {
                            absenceEmployeeIndex = (eidx + 1) % employees.size
                        }
                        AdminCycleRow(absenceLabel(types[absenceTypeIndex]), "Type") {
                            absenceTypeIndex = (absenceTypeIndex + 1) % types.size
                        }
                        AdminCycleRow("Status: ${statuses[absenceStatusIndex].name}", "Status") {
                            absenceStatusIndex = (absenceStatusIndex + 1) % statuses.size
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(absenceStart, { absenceStart = it }, label = { Text("Van") }, modifier = Modifier.weight(1f))
                            OutlinedTextField(absenceEnd, { absenceEnd = it }, label = { Text("Tot") }, modifier = Modifier.weight(1f))
                        }
                        OutlinedTextField(absenceNote, { absenceNote = it }, label = { Text("Opmerking") }, modifier = Modifier.fillMaxWidth())
                        Button(
                            onClick = {
                                val start = runCatching { LocalDate.parse(absenceStart) }.getOrNull()
                                val end = runCatching { LocalDate.parse(absenceEnd) }.getOrNull()
                                if (start == null || end == null || end.isBefore(start)) {
                                    controller.showStatus("Controleer van/tot datum")
                                } else {
                                    controller.addAbsence(
                                        Absence(
                                            employeeId = employee.id,
                                            startDate = start.toString(),
                                            endDate = end.toString(),
                                            type = types[absenceTypeIndex],
                                            status = statuses[absenceStatusIndex],
                                            note = absenceNote
                                        )
                                    )
                                    absenceNote = ""
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Afwezigheid opslaan") }
                    }
                }
            }

            val absencesThisMonth = state.absences.filter { absence ->
                val start = runCatching { LocalDate.parse(absence.startDate) }.getOrNull()
                val end = runCatching { LocalDate.parse(absence.endDate) }.getOrNull()
                start != null && end != null &&
                    !end.isBefore(ym.atDay(1)) && !start.isAfter(ym.atEndOfMonth())
            }
            items(absencesThisMonth) { absence ->
                val employee = state.employees.firstOrNull { it.id == absence.employeeId }
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${employee?.name ?: "?"} • ${absenceLabel(absence.type)}", fontWeight = FontWeight.Bold)
                            Text("${absence.startDate} t/m ${absence.endDate} • ${absence.status.name}")
                        }
                        IconButton(onClick = { controller.removeAbsence(absence.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Verwijder")
                        }
                    }
                }
            }

            item { AdminSectionTitle("Vaste taken en verantwoordelijkheden") }
            item {
                val eidx = taskEmployeeIndex.coerceIn(0, employees.lastIndex)
                val employee = employees[eidx]
                val types = ResponsibilityType.entries
                val recurrences = RecurrenceType.entries
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Taak = overlay op een gewone dienst. Er wordt hiervoor géén extra manager ingepland.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        AdminCycleRow(employee.name, "Volgende") {
                            taskEmployeeIndex = (eidx + 1) % employees.size
                        }
                        AdminCycleRow(responsibilityLabel(types[taskTypeIndex]), "Taak") {
                            taskTypeIndex = (taskTypeIndex + 1) % types.size
                        }
                        AdminCycleRow("Herhaling: ${recurrences[recurrenceIndex].name}", "Herhaling") {
                            recurrenceIndex = (recurrenceIndex + 1) % recurrences.size
                        }
                        when (recurrences[recurrenceIndex]) {
                            RecurrenceType.WEEKLY -> {
                                val labels = listOf("Ma", "Di", "Wo", "Do", "Vr", "Za", "Zo")
                                AdminCycleRow("Weekdag: ${labels[taskWeekday - 1]}", "Dag") {
                                    taskWeekday = if (taskWeekday == 7) 1 else taskWeekday + 1
                                }
                            }
                            RecurrenceType.MONTHLY_DAY -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Dag van maand: $taskMonthDay", Modifier.weight(1f))
                                    OutlinedButton(onClick = { taskMonthDay = (taskMonthDay - 1).coerceAtLeast(1) }) { Text("−") }
                                    OutlinedButton(onClick = { taskMonthDay = (taskMonthDay + 1).coerceAtMost(31) }) { Text("+") }
                                }
                            }
                            RecurrenceType.SPECIFIC_DATE -> {
                                OutlinedTextField(taskDate, { taskDate = it }, label = { Text("Datum") }, modifier = Modifier.fillMaxWidth())
                            }
                            RecurrenceType.MONTH_END -> Unit
                        }
                        OutlinedTextField(taskLabel, { taskLabel = it }, label = { Text("Eigen label (optioneel)") }, modifier = Modifier.fillMaxWidth())
                        AdminSwitch("Planner probeert deze persoon op bestaande dienst te zetten", preferScheduled) {
                            preferScheduled = it
                        }
                        Button(
                            onClick = {
                                controller.addResponsibility(
                                    ResponsibilityRule(
                                        employeeId = employee.id,
                                        type = types[taskTypeIndex],
                                        recurrence = recurrences[recurrenceIndex],
                                        weekday = taskWeekday,
                                        monthDay = taskMonthDay,
                                        date = taskDate,
                                        label = taskLabel,
                                        preferScheduled = preferScheduled
                                    )
                                )
                                taskLabel = ""
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Taak opslaan") }
                    }
                }
            }

            items(state.responsibilities.filter { it.active }) { rule ->
                val employee = state.employees.firstOrNull { it.id == rule.employeeId }
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${responsibilityShort(rule)} • ${employee?.name ?: "?"}", fontWeight = FontWeight.Bold)
                            Text("${rule.recurrence.name} • geen extra bezetting")
                        }
                        IconButton(onClick = { controller.removeResponsibility(rule.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Verwijder")
                        }
                    }
                }
            }

            item { AdminSectionTitle("Aanwezig / kantoor / training / meeting") }
            item {
                val eidx = markerEmployeeIndex.coerceIn(0, employees.lastIndex)
                val employee = employees[eidx]
                val types = PersonMarkerType.entries
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AdminCycleRow(employee.name, "Volgende") {
                            markerEmployeeIndex = (eidx + 1) % employees.size
                        }
                        AdminCycleRow(personMarkerLabel(types[markerTypeIndex]), "Type") {
                            markerTypeIndex = (markerTypeIndex + 1) % types.size
                        }
                        OutlinedTextField(markerDate, { markerDate = it }, label = { Text("Datum") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(markerNote, { markerNote = it }, label = { Text("Opmerking") }, modifier = Modifier.fillMaxWidth())
                        Button(
                            onClick = {
                                val date = runCatching { LocalDate.parse(markerDate) }.getOrNull()
                                if (date == null) controller.showStatus("Controleer datum")
                                else controller.addPersonMarker(
                                    PersonDayMarker(
                                        employeeId = employee.id,
                                        date = date.toString(),
                                        type = types[markerTypeIndex],
                                        note = markerNote
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Markering opslaan") }
                    }
                }
            }

            item { AdminSectionTitle("Bezetting / gastenaantal") }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Alleen 'minimum managers' kan extra bezetting toevoegen. Weektelling, admin, onderhoud enz. doen dat nooit.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(demandDate, { demandDate = it }, label = { Text("Datum") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(
                            guestCount,
                            { guestCount = it.filter(Char::isDigit) },
                            label = { Text("Gasten (optioneel)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Minimum managers: $minimumManagers", Modifier.weight(1f))
                            OutlinedButton(onClick = { minimumManagers = (minimumManagers - 1).coerceAtLeast(0) }) { Text("−") }
                            OutlinedButton(onClick = { minimumManagers = (minimumManagers + 1).coerceAtMost(10) }) { Text("+") }
                        }
                        Button(
                            onClick = {
                                val date = runCatching { LocalDate.parse(demandDate) }.getOrNull()
                                if (date == null) controller.showStatus("Controleer datum")
                                else controller.upsertDayDemand(
                                    DayDemand(
                                        date = date.toString(),
                                        guestCount = guestCount.toIntOrNull(),
                                        minimumManagers = minimumManagers
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Bezetting opslaan") }
                    }
                }
            }

            val assignments = state.assignments.sortedBy { it.date }
            if (assignments.size >= 2) {
                item { AdminSectionTitle("Dienst ruilen") }
                item {
                    val firstIdx = swapFirstIndex.coerceIn(0, assignments.lastIndex)
                    val secondIdx = swapSecondIndex.coerceIn(0, assignments.lastIndex)
                    val first = assignments[firstIdx]
                    val second = assignments[secondIdx]
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("1. ${assignmentText(state, first)}")
                            OutlinedButton(onClick = { swapFirstIndex = (firstIdx + 1) % assignments.size }) {
                                Text("Volgende dienst 1")
                            }
                            Text("2. ${assignmentText(state, second)}")
                            OutlinedButton(onClick = { swapSecondIndex = (secondIdx + 1) % assignments.size }) {
                                Text("Volgende dienst 2")
                            }
                            Button(
                                onClick = { controller.swapAssignments(first.id, second.id) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Controleer ATW & ruil") }
                        }
                    }
                }
            }
        }

        item { AdminSectionTitle("Scenario's") }
        item {
            Button(
                onClick = { controller.compareScenarios() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) { Text("Vergelijk normaal / zonder leen / vrije-dagen") }
        }
        items(controller.scenarioSummaries) { summary ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)) {
                Text(summary, Modifier.padding(10.dp))
            }
        }

        item { Spacer(Modifier.height(28.dp)) }
    }
}

private fun assignmentText(state: AppState, a: Assignment): String {
    val e = state.employees.firstOrNull { it.id == a.employeeId }?.name ?: "?"
    val t = state.shiftTemplates.firstOrNull { it.id == a.shiftTemplateId }?.name ?: "?"
    return "${a.date} • $e • $t"
}

fun responsibilityApplies(
    rule: ResponsibilityRule,
    date: LocalDate,
    ym: YearMonth
): Boolean = when (rule.recurrence) {
    RecurrenceType.WEEKLY -> date.dayOfWeek.value == rule.weekday
    RecurrenceType.MONTHLY_DAY -> rule.monthDay == date.dayOfMonth
    RecurrenceType.MONTH_END -> date == ym.atEndOfMonth()
    RecurrenceType.SPECIFIC_DATE -> rule.date == date.toString()
}

fun responsibilityShort(rule: ResponsibilityRule): String =
    rule.label.ifBlank {
        when (rule.type) {
            ResponsibilityType.WEEK_COUNT -> "WT"
            ResponsibilityType.MONTH_COUNT -> "MT"
            ResponsibilityType.MAINTENANCE -> "OND"
            ResponsibilityType.ADMIN -> "ADM"
            ResponsibilityType.KPI -> "KPI"
            ResponsibilityType.HACCP -> "HAC"
            ResponsibilityType.STOCK -> "VRD"
            ResponsibilityType.HAVI -> "HAVI"
            ResponsibilityType.TRAINING -> "TR"
            ResponsibilityType.MEETING -> "MTG"
            ResponsibilityType.OFFICE -> "KTR"
            ResponsibilityType.INTERVIEW -> "SOL"
            ResponsibilityType.CREW_PLANNING -> "PLN"
            ResponsibilityType.CUSTOM -> "TAK"
        }
    }.take(6).uppercase()

fun responsibilityLabel(type: ResponsibilityType): String = when (type) {
    ResponsibilityType.WEEK_COUNT -> "Weektelling"
    ResponsibilityType.MONTH_COUNT -> "Maandtelling"
    ResponsibilityType.MAINTENANCE -> "Onderhoud"
    ResponsibilityType.ADMIN -> "Administratie"
    ResponsibilityType.KPI -> "KPI"
    ResponsibilityType.HACCP -> "HACCP"
    ResponsibilityType.STOCK -> "Voorraad"
    ResponsibilityType.HAVI -> "HAVI"
    ResponsibilityType.TRAINING -> "Training"
    ResponsibilityType.MEETING -> "Meeting"
    ResponsibilityType.OFFICE -> "Kantoor"
    ResponsibilityType.INTERVIEW -> "Sollicitaties"
    ResponsibilityType.CREW_PLANNING -> "Crewplanning"
    ResponsibilityType.CUSTOM -> "Eigen taak"
}

fun absenceLabel(type: AbsenceType): String = when (type) {
    AbsenceType.VACATION -> "Vakantie"
    AbsenceType.LEAVE -> "Verlof"
    AbsenceType.SPECIAL_LEAVE -> "Bijzonder verlof"
    AbsenceType.UNPAID_LEAVE -> "Onbetaald verlof"
    AbsenceType.COMP_TIME -> "Tijd voor tijd"
    AbsenceType.SICK -> "Ziek"
    AbsenceType.MATERNITY -> "Zwangerschapsverlof"
    AbsenceType.ADAPTED_WORK -> "Aangepast werk"
    AbsenceType.TRAINING -> "Training/scholing"
    AbsenceType.OTHER -> "Overig"
}

fun absenceShort(type: AbsenceType): String = when (type) {
    AbsenceType.VACATION -> "VAK"
    AbsenceType.LEAVE -> "VER"
    AbsenceType.SPECIAL_LEAVE -> "BV"
    AbsenceType.UNPAID_LEAVE -> "OV"
    AbsenceType.COMP_TIME -> "TVT"
    AbsenceType.SICK -> "ZIEK"
    AbsenceType.MATERNITY -> "ZWV"
    AbsenceType.ADAPTED_WORK -> "AANG"
    AbsenceType.TRAINING -> "TR"
    AbsenceType.OTHER -> "AFW"
}

fun personMarkerShort(type: PersonMarkerType): String = when (type) {
    PersonMarkerType.PRESENT -> "AANW"
    PersonMarkerType.OFFICE -> "KTR"
    PersonMarkerType.TRAINING -> "TR"
    PersonMarkerType.MEETING -> "MTG"
    PersonMarkerType.MAINTENANCE -> "OND"
    PersonMarkerType.ADMIN -> "ADM"
    PersonMarkerType.OTHER -> "INFO"
}

private fun personMarkerLabel(type: PersonMarkerType): String = when (type) {
    PersonMarkerType.PRESENT -> "Aanwezig"
    PersonMarkerType.OFFICE -> "Kantoor"
    PersonMarkerType.TRAINING -> "Training"
    PersonMarkerType.MEETING -> "Meeting"
    PersonMarkerType.MAINTENANCE -> "Onderhoud"
    PersonMarkerType.ADMIN -> "Administratie"
    PersonMarkerType.OTHER -> "Overig"
}

@Composable
private fun AdminSummaryCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AdminSectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun AdminCycleRow(text: String, button: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f))
        OutlinedButton(onClick = onClick) { Text(button) }
    }
}

@Composable
private fun AdminSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
private fun AdminBar(label: String, value: Double, maxValue: Double, suffix: String) {
    val fraction = if (maxValue <= 0.0) 0f else (value / maxValue).toFloat().coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row {
            Text(label, Modifier.weight(1f), fontWeight = FontWeight.Bold)
            Text(suffix, style = MaterialTheme.typography.bodySmall)
        }
        Box(
            Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier.fillMaxWidth(fraction).height(12.dp).clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
