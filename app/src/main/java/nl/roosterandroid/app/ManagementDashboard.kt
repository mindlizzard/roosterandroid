package nl.roosterandroid.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

enum class ManagementDayPart(val label: String) {
    MORNING("Ochtend"),
    AFTERNOON("Middag"),
    EVENING("Avond"),
    NIGHT("Nacht")
}

data class DayPartCoverage(
    val date: LocalDate,
    val dayPart: ManagementDayPart,
    val required: Int,
    val scheduled: Int,
    val shortage: Int
)

data class ManagementSnapshot(
    val plannedHours: Double,
    val targetHours: Double,
    val openReplacements: Int,
    val activeSicknessCases: Int,
    val hardErrors: Int,
    val understaffedDayParts: Int,
    val borrowedShifts: Int,
    val overContractManagers: Int,
    val forecastGuests: Int,
    val openPlannerPoints: Int
)

private data class AssignmentWindow(
    val start: LocalDateTime,
    val end: LocalDateTime
)

fun dayPartCoverage(state: AppState, ym: YearMonth): List<DayPartCoverage> {
    val points = coveragePoints(state, ym)
    val templates = state.shiftTemplates.associateBy { it.id }
    val assignmentsByDate = state.assignments
        .asSequence()
        .filter { it.locationId == state.activeLocationId }
        .mapNotNull { assignment ->
            val date = runCatching { LocalDate.parse(assignment.date) }.getOrNull()
                ?: return@mapNotNull null
            if (YearMonth.from(date) != ym) return@mapNotNull null
            val template = templates[assignment.shiftTemplateId] ?: return@mapNotNull null
            val window = assignmentWindow(date, template) ?: return@mapNotNull null
            date to window
        }
        .groupBy({ it.first }, { it.second })

    return points.groupBy { point -> point.businessDate to dayPart(point.instant.toLocalTime()) }
        .map { (key, rows) ->
            val samples = rows.map { point ->
                val scheduled = assignmentsByDate[point.businessDate].orEmpty().count { window ->
                    !point.instant.isBefore(window.start) && point.instant.isBefore(window.end)
                }
                Triple(point.minimumManagers, scheduled, (point.minimumManagers - scheduled).coerceAtLeast(0))
            }
            DayPartCoverage(
                date = key.first,
                dayPart = key.second,
                required = samples.maxOfOrNull { it.first } ?: 0,
                scheduled = samples.minOfOrNull { it.second } ?: 0,
                shortage = samples.maxOfOrNull { it.third } ?: 0
            )
        }
        .sortedWith(compareBy({ it.date }, { it.dayPart.ordinal }))
}

fun managementSnapshot(
    state: AppState,
    unfilled: List<String>,
    violations: List<AtwValidator.Violation>
): ManagementSnapshot {
    val ym = YearMonth.of(state.year, state.month)
    val stats = employeeMonthStats(state)
    val locationAssignments = state.assignments.filter {
        it.locationId == state.activeLocationId &&
            runCatching { YearMonth.from(LocalDate.parse(it.date)) == ym }.getOrDefault(false)
    }
    val employeeById = state.employees.associateBy { it.id }
    val coverage = dayPartCoverage(state, ym)
    return ManagementSnapshot(
        plannedHours = stats.sumOf { it.hours },
        targetHours = stats.sumOf { it.targetHours },
        openReplacements = state.replacementRequests.count {
            it.locationId == state.activeLocationId && it.status == ReplacementStatus.OPEN
        },
        activeSicknessCases = state.absences.count { absence ->
            absence.type == AbsenceType.SICK &&
                absence.status == AbsenceStatus.APPROVED &&
                state.employees.any {
                    it.id == absence.employeeId && it.worksAt(state.activeLocationId)
                } &&
                runCatching {
                    val start = LocalDate.parse(absence.startDate)
                    val end = LocalDate.parse(absence.endDate)
                    !end.isBefore(ym.atDay(1)) && !start.isAfter(ym.atEndOfMonth())
                }.getOrDefault(false)
        },
        hardErrors = violations.count { it.severity == AtwValidator.Severity.ERROR },
        understaffedDayParts = coverage.count { it.shortage > 0 },
        borrowedShifts = locationAssignments.count {
            employeeById[it.employeeId]?.role == EmployeeRole.BORROWED
        },
        overContractManagers = stats.count { it.hours > it.targetHours + 0.25 },
        forecastGuests = state.dayDemands.filter {
            it.locationId == state.activeLocationId &&
                runCatching { YearMonth.from(LocalDate.parse(it.date)) == ym }.getOrDefault(false)
        }.sumOf { it.guestCount ?: 0 },
        openPlannerPoints = unfilled.size
    )
}

@Composable
fun ManagementDashboardPanel(controller: AppController) {
    val snapshot = remember(controller.state, controller.unfilled, controller.violations) {
        managementSnapshot(
            controller.state,
            controller.unfilled,
            controller.violations
        )
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Managementdashboard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ManagementMetric("Open diensten", snapshot.openReplacements.toString(), Modifier.weight(1f))
            ManagementMetric("Ziek", snapshot.activeSicknessCases.toString(), Modifier.weight(1f))
            ManagementMetric("Onderbezet", snapshot.understaffedDayParts.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ManagementMetric("ATW-fouten", snapshot.hardErrors.toString(), Modifier.weight(1f))
            ManagementMetric("Leendiensten", snapshot.borrowedShifts.toString(), Modifier.weight(1f))
            ManagementMetric("Open punten", snapshot.openPlannerPoints.toString(), Modifier.weight(1f))
        }
        Text(
            "Geplande/contracturen: ${snapshot.plannedHours.toInt()}/${snapshot.targetHours.toInt()}",
            fontWeight = FontWeight.SemiBold
        )
        if (snapshot.forecastGuests > 0 || snapshot.overContractManagers > 0) {
            Text(
                listOfNotNull(
                    snapshot.forecastGuests.takeIf { it > 0 }?.let { "Gastprognose: $it" },
                    snapshot.overContractManagers.takeIf { it > 0 }?.let { "$it manager(s) boven contract" }
                ).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun DayPartCoverageHeatmap(state: AppState) {
    val ym = YearMonth.of(state.year, state.month)
    val coverage = remember(state, ym) { dayPartCoverage(state, ym) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text("Bezetting per dagdeel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Ingepland/minimaal: groen klopt, oranje is gedeeltelijk en rood heeft een tekort.",
            style = MaterialTheme.typography.bodySmall
        )
        if (coverage.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "Stel bij Admin een minimumbezetting per dagdeel in om de heatmap te vullen.",
                    Modifier.padding(12.dp)
                )
            }
            return@Column
        }

        val scroll = rememberScrollState()
        val byDate = coverage.groupBy { it.date }
        Row(Modifier.horizontalScroll(scroll)) {
            HeatmapCell("Datum", HeatmapColors.Header, 74)
            ManagementDayPart.entries.forEach { part ->
                HeatmapCell(part.label, HeatmapColors.Header, 74)
            }
        }
        byDate.toSortedMap().forEach { (date, rows) ->
            Row(Modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
                HeatmapCell("${date.dayOfMonth}-${date.monthValue}", HeatmapColors.Date, 74)
                ManagementDayPart.entries.forEach { part ->
                    val row = rows.firstOrNull { it.dayPart == part }
                    val text = row?.let { "${it.scheduled}/${it.required}" } ?: "—"
                    val color = when {
                        row == null -> HeatmapColors.Empty
                        row.shortage == 0 -> HeatmapColors.Good
                        row.scheduled > 0 -> HeatmapColors.Partial
                        else -> HeatmapColors.Bad
                    }
                    HeatmapCell(text, color, 74)
                }
            }
        }
    }
}

@Composable
private fun ManagementMetric(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(9.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HeatmapCell(text: String, color: Color, width: Int) {
    Card(
        Modifier.width(width.dp).padding(1.dp),
        colors = CardDefaults.cardColors(containerColor = color)
    ) {
        Text(
            text,
            Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 3.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF172321)
        )
    }
}

private object HeatmapColors {
    val Header = Color(0xFFDDE7F5)
    val Date = Color(0xFFF0F2F5)
    val Good = Color(0xFFCDECCF)
    val Partial = Color(0xFFFFE4A8)
    val Bad = Color(0xFFFFC7C7)
    val Empty = Color(0xFFF4F4F4)
}

private fun dayPart(time: LocalTime): ManagementDayPart = when {
    !time.isBefore(LocalTime.of(6, 0)) && time.isBefore(LocalTime.of(11, 0)) ->
        ManagementDayPart.MORNING
    !time.isBefore(LocalTime.of(11, 0)) && time.isBefore(LocalTime.of(16, 0)) ->
        ManagementDayPart.AFTERNOON
    !time.isBefore(LocalTime.of(16, 0)) && time.isBefore(LocalTime.of(22, 0)) ->
        ManagementDayPart.EVENING
    else -> ManagementDayPart.NIGHT
}

private fun assignmentWindow(
    date: LocalDate,
    template: ShiftTemplate
): AssignmentWindow? = runCatching {
    val start = date.atTime(template.startTime())
    var end = date.atTime(template.endTime())
    if (!end.isAfter(start)) end = end.plusDays(1)
    AssignmentWindow(start, end)
}.getOrNull()

fun shiftDurationHours(template: ShiftTemplate): Double = runCatching {
    var minutes = Duration.between(template.startTime(), template.endTime()).toMinutes()
    if (minutes <= 0) minutes += 24 * 60
    minutes / 60.0
}.getOrDefault(0.0)
