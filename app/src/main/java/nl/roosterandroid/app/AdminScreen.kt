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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun AdminScreen(controller: AppController) {
    val ym = YearMonth.of(controller.state.year, controller.state.month)
    val employees = controller.state.employees.filter { it.active }
    val templates = controller.state.shiftTemplates.associateBy { it.id }
    val locale = Locale("nl", "NL")

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { controller.changeMonth(-1) }) { Text("‹") }
                Text(
                    "Administratie • ${ym.month.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.uppercase() }} ${ym.year}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(onClick = { controller.changeMonth(1) }) { Text("›") }
            }
        }

        item { DashboardSummary(controller, ym, employees, templates) }
        item { HoursChart(controller, ym, employees, templates) }
        item { ShiftMixChart(controller, ym, templates) }
        item { AdminEmployeeTable(controller, ym, employees, templates) }

        if (employees.isNotEmpty()) {
            item { AbsencePanel(controller, ym, employees) }
            item { ResponsibilityPanel(controller, employees) }
            item { PersonMarkerPanel(controller, ym, employees) }
            item { SwapPanel(controller, ym, employees) }
        }

        val absences = controller.state.absences.filter { absenceOverlapsMonth(it, ym) }
        if (absences.isNotEmpty()) {
            item { AdminSectionTitle("Vakantie / verlof / ziek") }
            items(absences, key = { it.id }) { absence ->
                val name = controller.state.employees.firstOrNull { it.id == absence.employeeId }?.name ?: "Onbekend"
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("$name • ${absenceTypeLabel(absence.type)}", fontWeight = FontWeight.Bold)
                            Text("${absence.startDate} t/m ${absence.endDate}" + absence.note.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty())
                        }
                        IconButton(onClick = { controller.removeAbsence(absence.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Verwijder")
                        }
                    }
                }
            }
        }

        if (controller.state.responsibilities.isNotEmpty()) {
            item { AdminSectionTitle("Vaste verantwoordelijkheden") }
            items(controller.state.responsibilities, key = { it.id }) { rule ->
                val name = controller.state.employees.firstOrNull { it.id == rule.employeeId }?.name ?: "Onbekend"
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${responsibilityTypeLabel(rule.type)} • $name", fontWeight = FontWeight.Bold)
                            Text(responsibilityScheduleText(rule) + if (rule.ensureScheduled) " • persoon moet werken" else " • alleen markering")
                        }
                        IconButton(onClick = { controller.removeResponsibility(rule.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Verwijder")
                        }
                    }
                }
            }
        }

        val markers = controller.state.personMarkers.filter {
            val d = runCatching { LocalDate.parse(it.date) }.getOrNull()
            d != null && YearMonth.from(d) == ym
        }
        if (markers.isNotEmpty()) {
            item { AdminSectionTitle("Aanwezig / kantoor / training / meeting") }
            items(markers, key = { it.id }) { marker ->
                val name = controller.state.employees.firstOrNull { it.id == marker.employeeId }?.name ?: "Onbekend"
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${personMarkerLabel(marker.type)} • $name", fontWeight = FontWeight.Bold)
                            Text("${marker.date}" + marker.note.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty())
                        }
                        IconButton(onClick = { controller.removePersonMarker(marker.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Verwijder")
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun DashboardSummary(
    controller: AppController,
    ym: YearMonth,
    employees: List<Employee>,
    templates: Map<String, ShiftTemplate>
) {
    val monthAssignments = controller.state.assignments.filter { assignmentInMonth(it, ym) }
    val totalHours = monthAssignments.sumOf { templates[it.shiftTemplateId]?.let(::templateHours) ?: 0.0 }
    val borrowed = monthAssignments.count { a ->
        controller.state.employees.firstOrNull { it.id == a.employeeId }?.role == EmployeeRole.BORROWED
    }
    val errors = controller.violations.count { it.severity == AtwValidator.Severity.ERROR }
    val absences = controller.state.absences.count { absenceOverlapsMonth(it, ym) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Maandoverzicht", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AdminMetric("Uren", "${totalHours.toInt()}u", Modifier.weight(1f))
            AdminMetric("Diensten", monthAssignments.size.toString(), Modifier.weight(1f))
            AdminMetric("ATW", if (errors == 0) "✓" else errors.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AdminMetric("Managers", employees.size.toString(), Modifier.weight(1f))
            AdminMetric("Leen", borrowed.toString(), Modifier.weight(1f))
            AdminMetric("Afwezig", absences.toString(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun AdminMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HoursChart(
    controller: AppController,
    ym: YearMonth,
    employees: List<Employee>,
    templates: Map<String, ShiftTemplate>
) {
    if (employees.isEmpty()) return
    val values = employees.associateWith { employee ->
        controller.state.assignments.filter { it.employeeId == employee.id && assignmentInMonth(it, ym) }
            .sumOf { templates[it.shiftTemplateId]?.let(::templateHours) ?: 0.0 }
    }
    val max = values.values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Grafiek • uren per manager", fontWeight = FontWeight.Bold)
            values.forEach { (employee, hours) ->
                val target = employee.contractedHoursPerWeek * ym.lengthOfMonth() / 7.0
                Text("${employee.name}: ${hours.toInt()}u / ±${target.toInt()}u")
                AdminBar((hours / max).toFloat(), MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ShiftMixChart(controller: AppController, ym: YearMonth, templates: Map<String, ShiftTemplate>) {
    val counts = ShiftKind.entries.associateWith { kind ->
        controller.state.assignments.count { a ->
            assignmentInMonth(a, ym) && templates[a.shiftTemplateId]?.kind == kind
        }
    }.filterValues { it > 0 }
    if (counts.isEmpty()) return
    val max = counts.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Grafiek • dienstverdeling", fontWeight = FontWeight.Bold)
            counts.forEach { (kind, count) ->
                Text("${shiftKindAdminLabel(kind)}: $count")
                AdminBar(count.toFloat() / max.toFloat(), shiftKindColor(kind))
            }
        }
    }
}

@Composable
private fun AdminBar(fraction: Float, color: Color) {
    Box(
        Modifier.fillMaxWidth().height(12.dp).background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(12.dp).background(color)
        )
    }
}

@Composable
private fun AdminEmployeeTable(
    controller: AppController,
    ym: YearMonth,
    employees: List<Employee>,
    templates: Map<String, ShiftTemplate>
) {
    if (employees.isEmpty()) return
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Administratie per manager", fontWeight = FontWeight.Bold)
            employees.forEach { employee ->
                val assignments = controller.state.assignments.filter { it.employeeId == employee.id && assignmentInMonth(it, ym) }
                val hours = assignments.sumOf { templates[it.shiftTemplateId]?.let(::templateHours) ?: 0.0 }
                val setup = assignments.count { templates[it.shiftTemplateId]?.kind == ShiftKind.SETUP }
                val middle = assignments.count { templates[it.shiftTemplateId]?.kind == ShiftKind.MIDDLE }
                val close = assignments.count { templates[it.shiftTemplateId]?.kind == ShiftKind.CLOSE }
                val weekend = assignments.count {
                    val d = runCatching { LocalDate.parse(it.date) }.getOrNull()
                    d?.dayOfWeek == DayOfWeek.SATURDAY || d?.dayOfWeek == DayOfWeek.SUNDAY
                }
                val vacation = absenceDays(controller.state.absences, employee.id, AbsenceType.VACATION, ym)
                val leave = absenceDays(controller.state.absences, employee.id, AbsenceType.LEAVE, ym)
                val sick = absenceDays(controller.state.absences, employee.id, AbsenceType.SICK, ym)
                Text(employee.name, fontWeight = FontWeight.Bold)
                Text("${assignments.size} d • ${hours.toInt()}u • SET $setup • TUS $middle • SLU $close • weekend $weekend")
                if (vacation + leave + sick > 0) {
                    Text("Vak $vacation • Verlof $leave • Ziek $sick", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun AbsencePanel(controller: AppController, ym: YearMonth, employees: List<Employee>) {
    var employeeIndex by remember { mutableIntStateOf(0) }
    var typeIndex by remember { mutableIntStateOf(0) }
    var start by remember(ym) { mutableStateOf(ym.atDay(1).toString()) }
    var end by remember(ym) { mutableStateOf(ym.atDay(1).toString()) }
    var note by remember { mutableStateOf("") }
    val types = AbsenceType.entries
    val employee = employees[employeeIndex.coerceIn(0, employees.lastIndex)]

    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Vakantie / verlof / ziek", fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(employee.name, Modifier.weight(1f))
                OutlinedButton(onClick = { employeeIndex = (employeeIndex + 1) % employees.size }) { Text("Volgende") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(absenceTypeLabel(types[typeIndex]), Modifier.weight(1f))
                OutlinedButton(onClick = { typeIndex = (typeIndex + 1) % types.size }) { Text("Type") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(start, { start = it }, label = { Text("Vanaf") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(end, { end = it }, label = { Text("T/m") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            OutlinedTextField(note, { note = it }, label = { Text("Opmerking") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = {
                val s = runCatching { LocalDate.parse(start) }.getOrNull()
                val e = runCatching { LocalDate.parse(end) }.getOrNull()
                if (s == null || e == null || e.isBefore(s)) controller.showStatus("Controleer begin- en einddatum")
                else {
                    controller.upsertAbsence(Absence(employeeId = employee.id, startDate = s.toString(), endDate = e.toString(), type = types[typeIndex], note = note))
                    note = ""
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Afwezigheid opslaan") }
        }
    }
}

@Composable
private fun ResponsibilityPanel(controller: AppController, employees: List<Employee>) {
    var employeeIndex by remember { mutableIntStateOf(0) }
    var typeIndex by remember { mutableIntStateOf(0) }
    var recurrenceIndex by remember { mutableIntStateOf(0) }
    var weekday by remember { mutableIntStateOf(1) }
    var label by remember { mutableStateOf("") }
    var ensure by remember { mutableStateOf(true) }
    val types = ResponsibilityType.entries
    val recurrences = RecurrenceType.entries
    val employee = employees[employeeIndex.coerceIn(0, employees.lastIndex)]

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Vaste verantwoordelijkheid", fontWeight = FontWeight.Bold)
            Text("Bijv. Jan elke maandag weektelling, vaste onderhoudsdag, administratie, KPI of HAVI.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(employee.name, Modifier.weight(1f))
                OutlinedButton(onClick = { employeeIndex = (employeeIndex + 1) % employees.size }) { Text("Volgende") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(responsibilityTypeLabel(types[typeIndex]), Modifier.weight(1f))
                OutlinedButton(onClick = { typeIndex = (typeIndex + 1) % types.size }) { Text("Taak") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (recurrences[recurrenceIndex] == RecurrenceType.WEEKLY) "Wekelijks" else "Laatste dag maand", Modifier.weight(1f))
                OutlinedButton(onClick = { recurrenceIndex = (recurrenceIndex + 1) % recurrences.size }) { Text("Herhaling") }
            }
            if (recurrences[recurrenceIndex] == RecurrenceType.WEEKLY) {
                val days = listOf("Ma", "Di", "Wo", "Do", "Vr", "Za", "Zo")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    days.forEachIndexed { index, day ->
                        if (weekday == index + 1) Button(onClick = { weekday = index + 1 }) { Text(day) }
                        else OutlinedButton(onClick = { weekday = index + 1 }) { Text(day) }
                    }
                }
            }
            OutlinedTextField(label, { label = it }, label = { Text("Eigen label (optioneel)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Zorg dat deze persoon die dag werkt", Modifier.weight(1f))
                Switch(checked = ensure, onCheckedChange = { ensure = it })
            }
            Button(onClick = {
                controller.upsertResponsibility(
                    ResponsibilityRule(
                        employeeId = employee.id,
                        type = types[typeIndex],
                        recurrence = recurrences[recurrenceIndex],
                        weekday = weekday,
                        label = label,
                        ensureScheduled = ensure
                    )
                )
                label = ""
            }, modifier = Modifier.fillMaxWidth()) { Text("Verantwoordelijkheid opslaan") }
        }
    }
}

@Composable
private fun PersonMarkerPanel(controller: AppController, ym: YearMonth, employees: List<Employee>) {
    var employeeIndex by remember { mutableIntStateOf(0) }
    var typeIndex by remember { mutableIntStateOf(0) }
    var date by remember(ym) { mutableStateOf(ym.atDay(1).toString()) }
    var note by remember { mutableStateOf("") }
    val types = PersonMarkerType.entries
    val employee = employees[employeeIndex.coerceIn(0, employees.lastIndex)]

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Aanwezig / kantoor / training / meeting", fontWeight = FontWeight.Bold)
            Text("Dit is een marker in de matrix en telt niet als aparte diensttijd.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(employee.name, Modifier.weight(1f))
                OutlinedButton(onClick = { employeeIndex = (employeeIndex + 1) % employees.size }) { Text("Volgende") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(personMarkerLabel(types[typeIndex]), Modifier.weight(1f))
                OutlinedButton(onClick = { typeIndex = (typeIndex + 1) % types.size }) { Text("Type") }
            }
            OutlinedTextField(date, { date = it }, label = { Text("Datum") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(note, { note = it }, label = { Text("Opmerking") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = {
                val parsed = runCatching { LocalDate.parse(date) }.getOrNull()
                if (parsed == null) controller.showStatus("Controleer datum")
                else controller.upsertPersonMarker(PersonDayMarker(employeeId = employee.id, date = parsed.toString(), type = types[typeIndex], note = note))
            }, modifier = Modifier.fillMaxWidth()) { Text("Marker opslaan") }
        }
    }
}

@Composable
private fun SwapPanel(controller: AppController, ym: YearMonth, employees: List<Employee>) {
    var date by remember(ym) { mutableStateOf(ym.atDay(1).toString()) }
    var firstIndex by remember { mutableIntStateOf(0) }
    var secondIndex by remember { mutableIntStateOf(if (employees.size > 1) 1 else 0) }
    val first = employees[firstIndex.coerceIn(0, employees.lastIndex)]
    val second = employees[secondIndex.coerceIn(0, employees.lastIndex)]

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Dienst ruilen", fontWeight = FontWeight.Bold)
            OutlinedTextField(date, { date = it }, label = { Text("Datum") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A: ${first.name}", Modifier.weight(1f))
                OutlinedButton(onClick = { firstIndex = (firstIndex + 1) % employees.size }) { Text("Volgende") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("B: ${second.name}", Modifier.weight(1f))
                OutlinedButton(onClick = { secondIndex = (secondIndex + 1) % employees.size }) { Text("Volgende") }
            }
            Button(
                onClick = { controller.swapAssignments(date, first.id, second.id) },
                enabled = first.id != second.id,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Ruil diensten") }
        }
    }
}

@Composable
private fun AdminSectionTitle(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

private fun absenceTypeLabel(type: AbsenceType): String = when (type) {
    AbsenceType.VACATION -> "Vakantie"
    AbsenceType.LEAVE -> "Verlof"
    AbsenceType.SICK -> "Ziek"
    AbsenceType.OTHER -> "Anders afwezig"
}

private fun responsibilityTypeLabel(type: ResponsibilityType): String = when (type) {
    ResponsibilityType.WEEK_COUNT -> "Weektelling"
    ResponsibilityType.MONTH_COUNT -> "Maandtelling"
    ResponsibilityType.MAINTENANCE -> "Onderhoud"
    ResponsibilityType.ADMIN -> "Administratie"
    ResponsibilityType.KPI -> "KPI"
    ResponsibilityType.HAVI -> "HAVI"
    ResponsibilityType.PRESENT -> "Aanwezig"
    ResponsibilityType.OTHER -> "Andere taak"
}

private fun personMarkerLabel(type: PersonMarkerType): String = when (type) {
    PersonMarkerType.PRESENT -> "Aanwezig"
    PersonMarkerType.OFFICE -> "Kantoor"
    PersonMarkerType.TRAINING -> "Training"
    PersonMarkerType.MEETING -> "Meeting"
    PersonMarkerType.OTHER -> "Anders"
}

private fun responsibilityScheduleText(rule: ResponsibilityRule): String = when (rule.recurrence) {
    RecurrenceType.MONTH_END -> "Elke laatste dag van de maand"
    RecurrenceType.WEEKLY -> "Elke ${listOf("maandag", "dinsdag", "woensdag", "donderdag", "vrijdag", "zaterdag", "zondag")[(rule.weekday - 1).coerceIn(0, 6)]}"
}

private fun assignmentInMonth(a: Assignment, ym: YearMonth): Boolean =
    runCatching { YearMonth.from(LocalDate.parse(a.date)) == ym }.getOrDefault(false)

private fun absenceOverlapsMonth(a: Absence, ym: YearMonth): Boolean {
    val start = runCatching { LocalDate.parse(a.startDate) }.getOrNull() ?: return false
    val end = runCatching { LocalDate.parse(a.endDate) }.getOrNull() ?: return false
    return !end.isBefore(ym.atDay(1)) && !start.isAfter(ym.atEndOfMonth())
}

private fun absenceDays(absences: List<Absence>, employeeId: String, type: AbsenceType, ym: YearMonth): Int {
    var count = 0
    for (day in 1..ym.lengthOfMonth()) {
        val date = ym.atDay(day)
        if (absences.any { it.employeeId == employeeId && it.type == type && it.includes(date) }) count++
    }
    return count
}

private fun templateHours(template: ShiftTemplate): Double {
    var minutes = Duration.between(template.startTime(), template.endTime()).toMinutes()
    if (minutes <= 0) minutes += 24 * 60
    return minutes / 60.0
}

private fun shiftKindAdminLabel(kind: ShiftKind): String = when (kind) {
    ShiftKind.SETUP -> "Setup"
    ShiftKind.DAY -> "Dag"
    ShiftKind.MIDDLE -> "Tussen"
    ShiftKind.CLOSE -> "Sluit"
    ShiftKind.KPI -> "KPI"
    ShiftKind.CUSTOM -> "Custom"
}

private fun shiftKindColor(kind: ShiftKind): Color = when (kind) {
    ShiftKind.SETUP -> Color(0xFF7CBF6A)
    ShiftKind.DAY -> Color(0xFF6BAED6)
    ShiftKind.MIDDLE -> Color(0xFFE5A64A)
    ShiftKind.CLOSE -> Color(0xFF9B7BC3)
    ShiftKind.KPI -> Color(0xFF55B9AE)
    ShiftKind.CUSTOM -> Color(0xFFC77BA8)
}
