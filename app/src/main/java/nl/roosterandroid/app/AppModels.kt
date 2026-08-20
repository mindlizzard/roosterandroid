package nl.roosterandroid.app

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Serializable
enum class EmployeeRole { MANAGER, RM, TRAINEE, BORROWED }

@Serializable
enum class ShiftKind { SETUP, DAY, MIDDLE, CLOSE, KPI, CUSTOM }

@Serializable
enum class AbsenceType { VACATION, LEAVE, SICK, OTHER }

@Serializable
enum class ResponsibilityType {
    WEEK_COUNT,
    MONTH_COUNT,
    MAINTENANCE,
    ADMIN,
    KPI,
    HAVI,
    PRESENT,
    OTHER
}

@Serializable
enum class RecurrenceType { WEEKLY, MONTH_END }

@Serializable
enum class PersonMarkerType { PRESENT, OFFICE, TRAINING, MEETING, OTHER }

@Serializable
data class Employee(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val role: EmployeeRole = EmployeeRole.MANAGER,
    val contractedDaysPerWeek: Int = 5,
    val contractedHoursPerWeek: Double = 40.0,
    val canSetup: Boolean = true,
    val canDay: Boolean = true,
    val canMiddle: Boolean = true,
    val canClose: Boolean = true,
    val canKpi: Boolean = true,
    val maxShiftsPerWeek: Int = 5,
    val active: Boolean = true
)

@Serializable
data class ShiftTemplate(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: ShiftKind,
    val start: String,
    val end: String,
    val enabledWeekdays: Set<Int> = (1..7).toSet()
) {
    fun startTime(): LocalTime = LocalTime.parse(start)
    fun endTime(): LocalTime = LocalTime.parse(end)
}

@Serializable
data class Availability(
    val employeeId: String,
    val date: String,
    val available: Boolean = true,
    val earliestStart: String? = null,
    val latestEnd: String? = null,
    val fixedShiftKind: ShiftKind? = null
)

@Serializable
data class WeeklyAvailability(
    val employeeId: String,
    val weekday: Int,
    val available: Boolean = true,
    val earliestStart: String? = null,
    val latestEnd: String? = null,
    val fixedShiftKind: ShiftKind? = null
)

@Serializable
data class Absence(
    val id: String = UUID.randomUUID().toString(),
    val employeeId: String,
    val startDate: String,
    val endDate: String,
    val type: AbsenceType,
    val note: String = ""
) {
    fun includes(date: LocalDate): Boolean {
        val start = runCatching { LocalDate.parse(startDate) }.getOrNull() ?: return false
        val end = runCatching { LocalDate.parse(endDate) }.getOrNull() ?: return false
        return !date.isBefore(start) && !date.isAfter(end)
    }
}

@Serializable
data class ResponsibilityRule(
    val id: String = UUID.randomUUID().toString(),
    val employeeId: String,
    val type: ResponsibilityType,
    val recurrence: RecurrenceType = RecurrenceType.WEEKLY,
    val weekday: Int = 1,
    val label: String = "",
    val ensureScheduled: Boolean = true,
    val active: Boolean = true
)

@Serializable
data class PersonDayMarker(
    val id: String = UUID.randomUUID().toString(),
    val employeeId: String,
    val date: String,
    val type: PersonMarkerType = PersonMarkerType.PRESENT,
    val note: String = ""
)

@Serializable
data class DayNote(
    val date: String,
    val text: String
)

@Serializable
data class Assignment(
    val id: String = UUID.randomUUID().toString(),
    val employeeId: String,
    val date: String,
    val shiftTemplateId: String,
    val source: String = "generated"
)

@Serializable
data class PlannerSettings(
    val locationName: String = "Mijn restaurant",
    val maxConsecutiveWorkDays: Int = 6,
    val preferTwoConsecutiveDaysOff: Boolean = true,
    val busyWeekdays: Set<Int> = setOf(5, 6),
    val requireMiddleOnBusyDays: Boolean = true,
    val requireSetupDaily: Boolean = true,
    val requireCloseDaily: Boolean = true,
    val atwEnabled: Boolean = true,
    val strictDailyRestHours: Int = 11,
    val allowOneReducedDailyRestPer7Days: Boolean = false,
    val allowIncidentalTwelveHourNightShift: Boolean = false,
    val treatMaxConsecutiveDaysAsHardRule: Boolean = true,
    val traineeMustHaveExperiencedManager: Boolean = true,
    val minimizeBorrowedManagers: Boolean = true,
    val minimumTwoDayOffBlocks: Int = 1,
    val preferredTwoDayOffBlocks: Int = 2,
    val showWeeklyCount: Boolean = true,
    val weekCountWeekday: Int = 1,
    val showMonthCountOnLastDay: Boolean = true,
    val monthEndCloseManagers: Int = 2,
    val warnMinimumFreeSundays: Boolean = true
)

@Serializable
data class AppState(
    val year: Int = LocalDate.now().year,
    val month: Int = LocalDate.now().monthValue,
    val employees: List<Employee> = emptyList(),
    val shiftTemplates: List<ShiftTemplate> = defaultShiftTemplates(),
    val availability: List<Availability> = emptyList(),
    val weeklyAvailability: List<WeeklyAvailability> = emptyList(),
    val absences: List<Absence> = emptyList(),
    val responsibilities: List<ResponsibilityRule> = emptyList(),
    val personMarkers: List<PersonDayMarker> = emptyList(),
    val dayNotes: List<DayNote> = emptyList(),
    val assignments: List<Assignment> = emptyList(),
    val assignmentHistory: List<Assignment> = emptyList(),
    val settings: PlannerSettings = PlannerSettings()
)

fun defaultShiftTemplates(): List<ShiftTemplate> = listOf(
    ShiftTemplate(name = "Setup", kind = ShiftKind.SETUP, start = "09:00", end = "17:00"),
    ShiftTemplate(name = "Setup HAVI", kind = ShiftKind.SETUP, start = "08:45", end = "16:45", enabledWeekdays = setOf(3, 5)),
    ShiftTemplate(name = "Dag", kind = ShiftKind.DAY, start = "09:00", end = "17:00"),
    ShiftTemplate(name = "Tussen", kind = ShiftKind.MIDDLE, start = "14:00", end = "22:00", enabledWeekdays = setOf(5, 6)),
    ShiftTemplate(name = "Sluit zo-do", kind = ShiftKind.CLOSE, start = "16:00", end = "00:00", enabledWeekdays = setOf(1, 2, 3, 4, 7)),
    ShiftTemplate(name = "Sluit vr-za", kind = ShiftKind.CLOSE, start = "17:00", end = "01:00", enabledWeekdays = setOf(5, 6)),
    ShiftTemplate(name = "KPI", kind = ShiftKind.KPI, start = "09:00", end = "17:00")
)

fun Employee.canWork(kind: ShiftKind): Boolean = when (kind) {
    ShiftKind.SETUP -> canSetup
    ShiftKind.DAY -> canDay
    ShiftKind.MIDDLE -> canMiddle
    ShiftKind.CLOSE -> canClose
    ShiftKind.KPI -> canKpi
    ShiftKind.CUSTOM -> true
}

fun Int.asDayOfWeek(): DayOfWeek = DayOfWeek.of(coerceIn(1, 7))
