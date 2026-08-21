package nl.roosterandroid.app

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

const val DEFAULT_LOCATION_ID = "default-location"

@Serializable
enum class EmployeeRole { MANAGER, RM, TRAINEE, BORROWED }

@Serializable
enum class ShiftKind { SETUP, DAY, MIDDLE, CLOSE, NIGHT, KPI, CUSTOM }

@Serializable
enum class OpeningMode { OPEN, CLOSED, OPEN_24_HOURS }

@Serializable
enum class AssignmentLockMode { AUTO, PREFERRED, FIXED }

@Serializable
enum class AbsenceType {
    VACATION, SNIPPER_DAY, LEAVE, SPECIAL_LEAVE, UNPAID_LEAVE, COMP_TIME,
    SICK, MATERNITY, ADAPTED_WORK, TRAINING, OTHER
}

@Serializable
enum class AbsenceStatus { REQUESTED, APPROVED, REJECTED }

@Serializable
enum class ResponsibilityType {
    WEEK_COUNT, MONTH_COUNT, MAINTENANCE, ADMIN, KPI, HACCP, STOCK, HAVI,
    TRAINING, MEETING, OFFICE, INTERVIEW, CREW_PLANNING, CUSTOM
}

@Serializable
enum class RecurrenceType { WEEKLY, MONTHLY_DAY, MONTH_END, SPECIFIC_DATE }

@Serializable
enum class PersonMarkerType { PRESENT, OFFICE, TRAINING, MEETING, MAINTENANCE, ADMIN, OTHER }

@Serializable
data class OpeningHoursRule(
    val weekday: Int,
    val mode: OpeningMode = OpeningMode.OPEN,
    val open: String = "09:00",
    val close: String = "00:00"
) {
    fun openTime(): LocalTime = LocalTime.parse(open)
    fun closeTime(): LocalTime = LocalTime.parse(close)
}

@Serializable
data class SpecialOpeningHours(
    val id: String = UUID.randomUUID().toString(),
    val locationId: String = DEFAULT_LOCATION_ID,
    val date: String,
    val mode: OpeningMode = OpeningMode.OPEN,
    val open: String = "09:00",
    val close: String = "17:00",
    val note: String = ""
) {
    fun parsedDate(): LocalDate? = runCatching { LocalDate.parse(date) }.getOrNull()
}

@Serializable
data class RestaurantLocation(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Mijn restaurant",
    val openingHours: List<OpeningHoursRule> = defaultOpeningHours(),
    val enforceOpeningCoverage: Boolean = false,
    val minimumManagersWhileOpen: Int = 1,
    val requireSetupDaily: Boolean = true,
    val requireMiddleOnBusyDays: Boolean = true,
    val requireCloseDaily: Boolean = true,
    val busyWeekdays: Set<Int> = setOf(5, 6),
    val monthEndCloseManagers: Int = 2,
    val active: Boolean = true
)

@Serializable
data class StaffingRequirement(
    val id: String = UUID.randomUUID().toString(),
    val locationId: String = DEFAULT_LOCATION_ID,
    val name: String = "Piekbezetting",
    val weekdays: Set<Int> = (1..7).toSet(),
    val start: String = "11:00",
    val end: String = "14:00",
    val minimumManagers: Int = 2,
    val active: Boolean = true
) {
    fun startTime(): LocalTime = LocalTime.parse(start)
    fun endTime(): LocalTime = LocalTime.parse(end)
}

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
    val canNight: Boolean = true,
    val canKpi: Boolean = true,
    val maxShiftsPerWeek: Int = 5,
    val active: Boolean = true,
    val locationIds: Set<String> = emptySet()
)

@Serializable
data class ShiftTemplate(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: ShiftKind,
    val start: String,
    val end: String,
    val enabledWeekdays: Set<Int> = (1..7).toSet(),
    val locationId: String = DEFAULT_LOCATION_ID
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
    val status: AbsenceStatus = AbsenceStatus.APPROVED,
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
    val monthDay: Int? = null,
    val date: String? = null,
    val label: String = "",
    val preferScheduled: Boolean = true,
    val active: Boolean = true,
    val locationId: String = DEFAULT_LOCATION_ID
)

@Serializable
data class PersonDayMarker(
    val id: String = UUID.randomUUID().toString(),
    val employeeId: String,
    val date: String,
    val type: PersonMarkerType = PersonMarkerType.PRESENT,
    val note: String = "",
    val locationId: String = DEFAULT_LOCATION_ID
)

@Serializable
data class DayDemand(
    val date: String,
    val guestCount: Int? = null,
    val minimumManagers: Int = 0,
    val note: String = "",
    val locationId: String = DEFAULT_LOCATION_ID
)

@Serializable
data class ShiftSwapRecord(
    val id: String = UUID.randomUUID().toString(),
    val firstAssignmentId: String,
    val secondAssignmentId: String,
    val firstEmployeeId: String,
    val secondEmployeeId: String,
    val firstDate: String,
    val secondDate: String,
    val createdAt: String = java.time.LocalDateTime.now().toString()
)

@Serializable
data class DayNote(
    val date: String,
    val text: String,
    val locationId: String = DEFAULT_LOCATION_ID
)

@Serializable
data class ManualDayOff(
    val employeeId: String,
    val date: String,
    val locationId: String = DEFAULT_LOCATION_ID
)

@Serializable
data class Assignment(
    val id: String = UUID.randomUUID().toString(),
    val employeeId: String,
    val date: String,
    val shiftTemplateId: String,
    val source: String = "generated",
    val locationId: String = DEFAULT_LOCATION_ID,
    val lockMode: AssignmentLockMode? = null
)

@Serializable
enum class ReplacementStatus { OPEN, FILLED, CANCELLED }

@Serializable
data class ReplacementRequest(
    val id: String = UUID.randomUUID().toString(),
    val locationId: String = DEFAULT_LOCATION_ID,
    val date: String,
    val shiftTemplateId: String,
    val originalEmployeeId: String,
    val absenceId: String,
    val absenceType: AbsenceType = AbsenceType.SICK,
    val status: ReplacementStatus = ReplacementStatus.OPEN,
    val replacementEmployeeId: String? = null,
    val createdAt: String = java.time.LocalDateTime.now().toString()
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
    val locations: List<RestaurantLocation> = defaultLocations(),
    val activeLocationId: String = DEFAULT_LOCATION_ID,
    val employees: List<Employee> = emptyList(),
    val shiftTemplates: List<ShiftTemplate> = defaultShiftTemplates(),
    val availability: List<Availability> = emptyList(),
    val weeklyAvailability: List<WeeklyAvailability> = emptyList(),
    val absences: List<Absence> = emptyList(),
    val responsibilities: List<ResponsibilityRule> = emptyList(),
    val personMarkers: List<PersonDayMarker> = emptyList(),
    val dayDemands: List<DayDemand> = emptyList(),
    val staffingRequirements: List<StaffingRequirement> = emptyList(),
    val specialOpeningHours: List<SpecialOpeningHours> = emptyList(),
    val replacementRequests: List<ReplacementRequest> = emptyList(),
    val swapHistory: List<ShiftSwapRecord> = emptyList(),
    val dayNotes: List<DayNote> = emptyList(),
    val manualDaysOff: List<ManualDayOff> = emptyList(),
    val assignments: List<Assignment> = emptyList(),
    val assignmentHistory: List<Assignment> = emptyList(),
    val settings: PlannerSettings = PlannerSettings()
)

fun defaultOpeningHours(open24Hours: Boolean = false): List<OpeningHoursRule> = (1..7).map { weekday ->
    if (open24Hours) {
        OpeningHoursRule(weekday = weekday, mode = OpeningMode.OPEN_24_HOURS, open = "00:00", close = "00:00")
    } else {
        OpeningHoursRule(
            weekday = weekday,
            mode = OpeningMode.OPEN,
            open = "09:00",
            close = if (weekday in setOf(5, 6)) "01:00" else "00:00"
        )
    }
}

fun defaultLocations(): List<RestaurantLocation> = listOf(
    RestaurantLocation(id = DEFAULT_LOCATION_ID)
)

fun defaultShiftTemplates(locationId: String = DEFAULT_LOCATION_ID): List<ShiftTemplate> = listOf(
    ShiftTemplate(name = "Setup", kind = ShiftKind.SETUP, start = "09:00", end = "17:00", locationId = locationId),
    ShiftTemplate(name = "Setup HAVI", kind = ShiftKind.SETUP, start = "08:45", end = "16:45", enabledWeekdays = setOf(3, 5), locationId = locationId),
    ShiftTemplate(name = "Dag", kind = ShiftKind.DAY, start = "09:00", end = "17:00", locationId = locationId),
    ShiftTemplate(name = "Tussen", kind = ShiftKind.MIDDLE, start = "14:00", end = "22:00", enabledWeekdays = setOf(5, 6), locationId = locationId),
    ShiftTemplate(name = "Sluit zo-do", kind = ShiftKind.CLOSE, start = "16:00", end = "00:00", enabledWeekdays = setOf(1, 2, 3, 4, 7), locationId = locationId),
    ShiftTemplate(name = "Sluit vr-za", kind = ShiftKind.CLOSE, start = "17:00", end = "01:00", enabledWeekdays = setOf(5, 6), locationId = locationId),
    ShiftTemplate(name = "KPI", kind = ShiftKind.KPI, start = "09:00", end = "17:00", locationId = locationId)
)

fun Employee.canWork(kind: ShiftKind): Boolean = when (kind) {
    ShiftKind.SETUP -> canSetup
    ShiftKind.DAY -> canDay
    ShiftKind.MIDDLE -> canMiddle
    ShiftKind.CLOSE -> canClose
    ShiftKind.NIGHT -> canNight
    ShiftKind.KPI -> canKpi
    ShiftKind.CUSTOM -> true
}

fun Employee.worksAt(locationId: String): Boolean =
    locationId in locationIds || (locationIds.isEmpty() && locationId == DEFAULT_LOCATION_ID)

fun Assignment.effectiveLockMode(): AssignmentLockMode = lockMode ?: when {
    source.startsWith("manual") || source == "replacement" -> AssignmentLockMode.FIXED
    else -> AssignmentLockMode.AUTO
}

fun AppState.activeLocation(): RestaurantLocation {
    val location = locations.firstOrNull { it.id == activeLocationId }
        ?: locations.firstOrNull()
        ?: RestaurantLocation(id = DEFAULT_LOCATION_ID, name = settings.locationName)

    // A single default location is also the migration path for v0.6 data and for
    // callers that still only configure PlannerSettings. Keep those operational
    // flags in sync until the user explicitly starts managing multiple locations.
    return if (locations.size == 1 && location.id == DEFAULT_LOCATION_ID) {
        location.copy(
            name = settings.locationName,
            requireSetupDaily = settings.requireSetupDaily,
            requireMiddleOnBusyDays = settings.requireMiddleOnBusyDays,
            requireCloseDaily = settings.requireCloseDaily,
            busyWeekdays = settings.busyWeekdays,
            monthEndCloseManagers = settings.monthEndCloseManagers
        )
    } else {
        location
    }
}

fun Int.asDayOfWeek(): DayOfWeek = DayOfWeek.of(coerceIn(1, 7))
