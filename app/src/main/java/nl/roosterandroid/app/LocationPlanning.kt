package nl.roosterandroid.app

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

data class CoverageWindow(
    val businessDate: LocalDate,
    val label: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val minimumManagers: Int
)

data class CoveragePoint(
    val businessDate: LocalDate,
    val instant: LocalDateTime,
    val minimumManagers: Int,
    val labels: Set<String>
)

fun openingBounds(
    location: RestaurantLocation,
    businessDate: LocalDate
): Pair<LocalDateTime, LocalDateTime>? {
    val rule = location.openingHours.lastOrNull {
        it.weekday == businessDate.dayOfWeek.value
    } ?: return null

    return openingBoundsForRule(rule.mode, rule.open, rule.close, businessDate)
}

fun openingBounds(
    state: AppState,
    businessDate: LocalDate
): Pair<LocalDateTime, LocalDateTime>? {
    val location = state.activeLocation()
    val special = state.specialOpeningHours.lastOrNull {
        it.locationId == location.id && it.date == businessDate.toString()
    }
    return if (special == null) {
        openingBounds(location, businessDate)
    } else {
        openingBoundsForRule(special.mode, special.open, special.close, businessDate)
    }
}

fun effectiveOpeningMode(state: AppState, businessDate: LocalDate): OpeningMode? {
    val location = state.activeLocation()
    return state.specialOpeningHours.lastOrNull {
        it.locationId == location.id && it.date == businessDate.toString()
    }?.mode ?: location.openingHours.lastOrNull {
        it.weekday == businessDate.dayOfWeek.value
    }?.mode
}

private fun openingBoundsForRule(
    mode: OpeningMode,
    open: String,
    close: String,
    businessDate: LocalDate
): Pair<LocalDateTime, LocalDateTime>? {
    return when (mode) {
        OpeningMode.CLOSED -> null
        OpeningMode.OPEN_24_HOURS -> {
            val start = businessDate.atTime(6, 0)
            start to start.plusDays(1)
        }
        OpeningMode.OPEN -> {
            val startTime = runCatching { LocalTime.parse(open) }.getOrNull() ?: return null
            val endTime = runCatching { LocalTime.parse(close) }.getOrNull() ?: return null
            val start = businessDate.atTime(startTime)
            var end = businessDate.atTime(endTime)
            if (!end.isAfter(start)) end = end.plusDays(1)
            start to end
        }
    }
}

fun coverageWindows(state: AppState, businessDate: LocalDate): List<CoverageWindow> {
    val location = state.activeLocation()
    val opening = openingBounds(state, businessDate) ?: return emptyList()
    val out = mutableListOf<CoverageWindow>()

    if (location.enforceOpeningCoverage && location.minimumManagersWhileOpen > 0) {
        out += CoverageWindow(
            businessDate = businessDate,
            label = "gehele opening",
            start = opening.first,
            end = opening.second,
            minimumManagers = location.minimumManagersWhileOpen.coerceAtLeast(0)
        )
    }

    state.staffingRequirements
        .filter {
            it.active &&
                it.locationId == location.id &&
                businessDate.dayOfWeek.value in it.weekdays &&
                it.minimumManagers > 0
        }
        .forEach { requirement ->
            val startTime = runCatching { requirement.startTime() }.getOrNull()
                ?: return@forEach
            val endTime = runCatching { requirement.endTime() }.getOrNull()
                ?: return@forEach

            val baseStart = businessDate.atTime(startTime)
            var baseEnd = businessDate.atTime(endTime)
            if (!baseEnd.isAfter(baseStart)) baseEnd = baseEnd.plusDays(1)

            // Try both the calendar-day interval and its next-day counterpart.
            // This clips 08:00-12:00 correctly to a 09:00 opening, while 00:00-06:00
            // still maps to the after-midnight tail of a late or 24-hour business day.
            (0L..1L).forEach { dayOffset ->
                val start = baseStart.plusDays(dayOffset)
                val end = baseEnd.plusDays(dayOffset)
                val clippedStart = maxOf(start, opening.first)
                val clippedEnd = minOf(end, opening.second)
                if (clippedEnd.isAfter(clippedStart)) {
                    out += CoverageWindow(
                        businessDate = businessDate,
                        label = requirement.name.ifBlank { "bezetting" },
                        start = clippedStart,
                        end = clippedEnd,
                        minimumManagers = requirement.minimumManagers
                    )
                }
            }
        }

    return out.distinctBy { listOf(it.label, it.start, it.end, it.minimumManagers) }
}

fun coveragePoints(state: AppState, ym: YearMonth): List<CoveragePoint> {
    val windows = (1..ym.lengthOfMonth()).flatMap { day ->
        coverageWindows(state, ym.atDay(day))
    }
    val samples = linkedMapOf<Pair<LocalDate, LocalDateTime>, MutableList<CoverageWindow>>()

    windows.forEach { window ->
        var instant = window.start
        while (instant.isBefore(window.end)) {
            samples.getOrPut(window.businessDate to instant) { mutableListOf() } += window
            instant = instant.plusMinutes(30)
        }
        val lastMinute = window.end.minusMinutes(1)
        if (!lastMinute.isBefore(window.start)) {
            samples.getOrPut(window.businessDate to lastMinute) { mutableListOf() } += window
        }
    }

    return samples.map { (key, active) ->
        CoveragePoint(
            businessDate = key.first,
            instant = key.second,
            minimumManagers = active.maxOf { it.minimumManagers },
            labels = active.filter { it.minimumManagers == active.maxOf { row -> row.minimumManagers } }
                .map { it.label }
                .toSet()
        )
    }.sortedWith(compareBy({ it.businessDate }, { it.instant }))
}

fun recommendedShiftTemplates(location: RestaurantLocation): List<ShiftTemplate> {
    val format = DateTimeFormatter.ofPattern("HH:mm")
    val grouped = location.openingHours
        .filter { it.mode != OpeningMode.CLOSED }
        .groupBy {
            if (it.mode == OpeningMode.OPEN_24_HOURS) {
                Triple(it.mode, "00:00", "00:00")
            } else {
                Triple(it.mode, it.open, it.close)
            }
        }

    return grouped.flatMap { (key, rows) ->
        val weekdays = rows.map { it.weekday }.toSet()
        val suffix = weekdayGroupLabel(weekdays)
        when (key.first) {
            OpeningMode.CLOSED -> emptyList()
            OpeningMode.OPEN_24_HOURS -> listOf(
                ShiftTemplate(
                    name = "Ochtend $suffix",
                    kind = ShiftKind.SETUP,
                    start = "06:00",
                    end = "14:00",
                    enabledWeekdays = weekdays,
                    locationId = location.id
                ),
                ShiftTemplate(
                    name = "Avond $suffix",
                    kind = ShiftKind.MIDDLE,
                    start = "14:00",
                    end = "22:00",
                    enabledWeekdays = weekdays,
                    locationId = location.id
                ),
                ShiftTemplate(
                    name = "Nacht $suffix",
                    kind = ShiftKind.NIGHT,
                    start = "22:00",
                    end = "06:00",
                    enabledWeekdays = weekdays,
                    locationId = location.id
                )
            )
            OpeningMode.OPEN -> {
                val open = runCatching { LocalTime.parse(key.second) }.getOrNull()
                    ?: return@flatMap emptyList()
                val close = runCatching { LocalTime.parse(key.third) }.getOrNull()
                    ?: return@flatMap emptyList()
                var minutes = Duration.between(open, close).toMinutes()
                if (minutes <= 0) minutes += 24 * 60

                if (minutes <= 8 * 60) {
                    listOf(
                        ShiftTemplate(
                            name = "Dag $suffix",
                            kind = ShiftKind.DAY,
                            start = open.format(format),
                            end = close.format(format),
                            enabledWeekdays = weekdays,
                            locationId = location.id
                        )
                    )
                } else {
                    val earlyEnd = open.plusHours(8)
                    val lateStart = close.minusHours(8)
                    buildList {
                        add(
                            ShiftTemplate(
                                name = "Setup $suffix",
                                kind = ShiftKind.SETUP,
                                start = open.format(format),
                                end = earlyEnd.format(format),
                                enabledWeekdays = weekdays,
                                locationId = location.id
                            )
                        )
                        if (minutes > 12 * 60) {
                            val middleStart = open.plusMinutes((minutes - 8 * 60) / 2)
                            add(
                                ShiftTemplate(
                                    name = "Tussen $suffix",
                                    kind = ShiftKind.MIDDLE,
                                    start = middleStart.format(format),
                                    end = middleStart.plusHours(8).format(format),
                                    enabledWeekdays = weekdays,
                                    locationId = location.id
                                )
                            )
                        }
                        add(
                            ShiftTemplate(
                                name = "Sluit $suffix",
                                kind = ShiftKind.CLOSE,
                                start = lateStart.format(format),
                                end = close.format(format),
                                enabledWeekdays = weekdays,
                                locationId = location.id
                            )
                        )
                    }
                }
            }
        }
    }
}

private fun weekdayGroupLabel(days: Set<Int>): String = when (days) {
    (1..7).toSet() -> "dagelijks"
    setOf(1, 2, 3, 4, 5) -> "ma-vr"
    setOf(6, 7) -> "weekend"
    else -> days.sorted().joinToString("/") { day ->
        listOf("ma", "di", "wo", "do", "vr", "za", "zo")[day.coerceIn(1, 7) - 1]
    }
}
