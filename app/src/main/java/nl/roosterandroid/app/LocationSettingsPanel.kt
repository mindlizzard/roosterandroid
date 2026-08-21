package nl.roosterandroid.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
fun LocationSettingsPanel(controller: AppController) {
    val location = controller.activeLocation()
    var draft by remember(location.id, location) { mutableStateOf(location) }
    var newName by remember { mutableStateOf("") }
    var newOpen24 by remember { mutableStateOf(false) }
    var specialDate by remember(location.id) { mutableStateOf(LocalDate.now().toString()) }
    var specialMode by remember(location.id) { mutableStateOf(OpeningMode.OPEN) }
    var specialOpen by remember(location.id) { mutableStateOf("09:00") }
    var specialClose by remember(location.id) { mutableStateOf("17:00") }
    var specialNote by remember(location.id) { mutableStateOf("") }
    val dayNames = listOf("Ma", "Di", "Wo", "Do", "Vr", "Za", "Zo")

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Vestigingen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Iedere vestiging heeft een eigen team, rooster, diensten, openingstijden en bezettingseisen. " +
                "ATW blijft over alle vestigingen heen controleren."
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Actieve vestiging", style = MaterialTheme.typography.labelMedium)
                        Text(location.name, fontWeight = FontWeight.Bold)
                    }
                    if (controller.state.locations.count { it.active } > 1) {
                        OutlinedButton(onClick = controller::selectNextLocation) { Text("Volgende") }
                    }
                }
                controller.state.locations.filter { it.active }.forEach { candidate ->
                    if (candidate.id == location.id) {
                        FilledTonalButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("✓ ${candidate.name}") }
                    } else {
                        OutlinedButton(
                            onClick = { controller.switchLocation(candidate.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Open ${candidate.name}") }
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Nieuwe vestiging") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                LocationSwitch("Nieuwe vestiging is 24 uur open", newOpen24) { newOpen24 = it }
                Button(
                    onClick = {
                        controller.addLocation(newName, newOpen24)
                        newName = ""
                        newOpen24 = false
                    },
                    enabled = newName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Vestiging toevoegen") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Instellingen ${location.name}", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    label = { Text("Vestigingsnaam") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                LocationSwitch("Controleer management tijdens alle openingsuren", draft.enforceOpeningCoverage) {
                    draft = draft.copy(enforceOpeningCoverage = it)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Minimum managers tijdens opening", Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        draft = draft.copy(
                            minimumManagersWhileOpen = (draft.minimumManagersWhileOpen - 1).coerceAtLeast(0)
                        )
                    }) { Text("−") }
                    Spacer(Modifier.width(8.dp))
                    Text(draft.minimumManagersWhileOpen.toString(), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        draft = draft.copy(
                            minimumManagersWhileOpen = (draft.minimumManagersWhileOpen + 1).coerceAtMost(10)
                        )
                    }) { Text("+") }
                }
                LocationSwitch("Dagelijks een setupmanager", draft.requireSetupDaily) {
                    draft = draft.copy(requireSetupDaily = it)
                }
                LocationSwitch("Tussenmanager op drukke dagen", draft.requireMiddleOnBusyDays) {
                    draft = draft.copy(requireMiddleOnBusyDays = it)
                }
                LocationSwitch("Sluit- of nachtmanager verplicht", draft.requireCloseDaily) {
                    draft = draft.copy(requireCloseDaily = it)
                }

                Text("Drukke dagen", fontWeight = FontWeight.SemiBold)
                dayNames.chunked(4).forEachIndexed { rowIndex, labels ->
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        labels.forEachIndexed { columnIndex, label ->
                            val weekday = rowIndex * 4 + columnIndex + 1
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = weekday in draft.busyWeekdays,
                                    onCheckedChange = { checked ->
                                        draft = draft.copy(
                                            busyWeekdays = if (checked) {
                                                draft.busyWeekdays + weekday
                                            } else {
                                                draft.busyWeekdays - weekday
                                            }
                                        )
                                    }
                                )
                                Text(label)
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            draft = draft.copy(
                                openingHours = defaultOpeningHours(open24Hours = true),
                                enforceOpeningCoverage = true
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Alles 24 uur") }
                    OutlinedButton(
                        onClick = { draft = draft.copy(openingHours = defaultOpeningHours()) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Standaardtijden") }
                }

                Text("Openingstijden", fontWeight = FontWeight.SemiBold)
                (1..7).forEach { weekday ->
                    val rule = draft.openingHours.lastOrNull { it.weekday == weekday }
                        ?: defaultOpeningHours().first { it.weekday == weekday }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(dayNames[weekday - 1], Modifier.width(34.dp), fontWeight = FontWeight.Bold)
                            Text(openingModeLabel(rule.mode), Modifier.weight(1f))
                            OutlinedButton(onClick = {
                                val next = OpeningMode.entries[
                                    (OpeningMode.entries.indexOf(rule.mode) + 1) % OpeningMode.entries.size
                                ]
                                draft = draft.copy(
                                    openingHours = draft.openingHours.filterNot { it.weekday == weekday } +
                                        rule.copy(mode = next)
                                )
                            }) { Text("Wijzig") }
                        }
                        if (rule.mode == OpeningMode.OPEN) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TimePickerField(
                                    value = rule.open,
                                    onValueChange = { value ->
                                        draft = draft.copy(
                                            openingHours = draft.openingHours.filterNot { it.weekday == weekday } +
                                                rule.copy(open = value)
                                        )
                                    },
                                    label = "Open",
                                    modifier = Modifier.weight(1f),
                                    fallback = java.time.LocalTime.of(9, 0)
                                )
                                TimePickerField(
                                    value = rule.close,
                                    onValueChange = { value ->
                                        draft = draft.copy(
                                            openingHours = draft.openingHours.filterNot { it.weekday == weekday } +
                                                rule.copy(close = value)
                                        )
                                    },
                                    label = "Dicht",
                                    modifier = Modifier.weight(1f),
                                    fallback = java.time.LocalTime.MIDNIGHT
                                )
                            }
                        }
                    }
                }

                Text("Uitzondering per datum", fontWeight = FontWeight.SemiBold)
                Text(
                    "Voor feestdagen, evenementen, koopavonden of een tijdelijke 24-uursopening.",
                    style = MaterialTheme.typography.bodySmall
                )
                DatePickerField(
                    value = specialDate,
                    onValueChange = { specialDate = it },
                    label = "Datum",
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(openingModeLabel(specialMode), Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        specialMode = OpeningMode.entries[
                            (OpeningMode.entries.indexOf(specialMode) + 1) % OpeningMode.entries.size
                        ]
                    }) { Text("Wijzig type") }
                }
                if (specialMode == OpeningMode.OPEN) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TimePickerField(
                            value = specialOpen,
                            onValueChange = { specialOpen = it },
                            label = "Open",
                            modifier = Modifier.weight(1f)
                        )
                        TimePickerField(
                            value = specialClose,
                            onValueChange = { specialClose = it },
                            label = "Dicht",
                            modifier = Modifier.weight(1f),
                            fallback = java.time.LocalTime.of(17, 0)
                        )
                    }
                }
                OutlinedTextField(
                    value = specialNote,
                    onValueChange = { specialNote = it },
                    label = { Text("Reden (optioneel)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                FilledTonalButton(
                    onClick = {
                        controller.upsertSpecialOpeningHours(
                            SpecialOpeningHours(
                                locationId = location.id,
                                date = specialDate,
                                mode = specialMode,
                                open = specialOpen,
                                close = specialClose,
                                note = specialNote
                            )
                        )
                        specialNote = ""
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Uitzondering opslaan") }

                controller.state.specialOpeningHours
                    .filter { it.locationId == location.id }
                    .sortedBy { it.date }
                    .forEach { special ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(special.date, fontWeight = FontWeight.Bold)
                                Text(
                                    when (special.mode) {
                                        OpeningMode.CLOSED -> "Gesloten"
                                        OpeningMode.OPEN_24_HOURS -> "24 uur open"
                                        OpeningMode.OPEN -> "${special.open}-${special.close}"
                                    } + special.note.takeIf { it.isNotBlank() }
                                        ?.let { " • $it" }.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            IconButton(onClick = {
                                controller.removeSpecialOpeningHours(special.id)
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Verwijder uitzondering")
                            }
                        }
                    }

                Button(
                    onClick = { controller.updateLocation(draft) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Vestiging en tijden opslaan") }
                OutlinedButton(
                    onClick = controller::addRecommendedTemplates,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Voeg passende diensten toe") }
                Text(
                    "Voor een 24-uursvestiging maakt de app ochtend-, avond- en nachtdiensten. " +
                        "Bestaande diensten worden niet verwijderd.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (controller.state.employees.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Teamkoppeling", fontWeight = FontWeight.Bold)
                    Text(
                        "Vink managers aan die op ${location.name} mogen werken. Gedeelde managers worden door ATW over alle vestigingen gecontroleerd.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    controller.state.employees.forEach { employee ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = employee.worksAt(location.id),
                                onCheckedChange = {
                                    controller.setEmployeeAtLocation(employee.id, location.id, it)
                                }
                            )
                            Column {
                                Text(employee.name)
                                Text(employee.role.name, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun openingModeLabel(mode: OpeningMode): String = when (mode) {
    OpeningMode.OPEN -> "Open met tijden"
    OpeningMode.CLOSED -> "Gesloten"
    OpeningMode.OPEN_24_HOURS -> "24 uur open"
}
