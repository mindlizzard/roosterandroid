package nl.roosterandroid.app

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

enum class RosterPriorityLevel {
    CRITICAL,
    HIGH,
    MEDIUM,
    OK
}

data class RosterPriorityRow(
    val quality: RosterQualityRow,
    val atwWarnings: Int,
    val weekendsWorked: Int,
    val teamAverageWeekends: Double,
    val weekendOverload: Double,
    val priorityScore: Int,
    val level: RosterPriorityLevel,
    val reasons: List<String>
) {
    val employeeId: String
        get() = quality.employeeId

    val employeeName: String
        get() = quality.employeeName

    val requiresAttention: Boolean
        get() = level != RosterPriorityLevel.OK
}

fun AppState.rosterPriorityRows(
    violations: List<AtwValidator.Violation> =
        AtwValidator().validate(this)
): List<RosterPriorityRow> {
    val qualityRows =
        rosterQualityRows(violations)

    if (qualityRows.isEmpty()) {
        return emptyList()
    }

    val ym =
        YearMonth.of(
            year,
            month
        )

    fun weekendsWorked(
        employeeId: String
    ): Int =
        assignments
            .asSequence()
            .filter {
                it.employeeId == employeeId
            }
            .mapNotNull {
                runCatching {
                    LocalDate.parse(it.date)
                }.getOrNull()
            }
            .filter {
                YearMonth.from(it) == ym
            }
            .filter {
                it.dayOfWeek ==
                    DayOfWeek.SATURDAY ||
                    it.dayOfWeek ==
                    DayOfWeek.SUNDAY
            }
            .map {
                it.with(
                    TemporalAdjusters
                        .previousOrSame(
                            DayOfWeek.SATURDAY
                        )
                )
            }
            .distinct()
            .count()

    val weekendCounts =
        qualityRows.associate {
            it.employeeId to
                weekendsWorked(
                    it.employeeId
                )
        }

    val teamAverage =
        if (weekendCounts.isEmpty()) {
            0.0
        } else {
            weekendCounts
                .values
                .average()
        }

    return qualityRows
        .map { quality ->
            val atwWarnings =
                violations.count {
                    it.employeeId ==
                        quality.employeeId &&
                        it.severity ==
                        AtwValidator.Severity.WARNING &&
                        (
                            it.date == null ||
                                YearMonth.from(
                                    it.date
                                ) == ym
                        )
                }

            val weekends =
                weekendCounts[
                    quality.employeeId
                ] ?: 0

            val weekendOverload =
                (
                    weekends -
                        teamAverage
                ).coerceAtLeast(0.0)

            val hourGap =
                if (
                    quality.targetHours >
                    0.0
                ) {
                    (
                        abs(
                            quality.hourDifference
                        ) -
                            1.0
                    ).coerceAtLeast(0.0)
                } else {
                    0.0
                }

            val hourPenalty =
                (
                    hourGap *
                        2.0
                )
                    .roundToInt()
                    .coerceAtMost(80)

            val weekendPenalty =
                (
                    weekendOverload *
                        20.0
                ).roundToInt()

            val priorityScore =
                quality.atwErrors * 100 +
                    atwWarnings * 25 +
                    hourPenalty +
                    weekendPenalty

            val level =
                when {
                    quality.atwErrors > 0 ||
                        priorityScore >= 100 ->
                        RosterPriorityLevel.CRITICAL

                    priorityScore >= 50 ->
                        RosterPriorityLevel.HIGH

                    priorityScore >= 15 ->
                        RosterPriorityLevel.MEDIUM

                    else ->
                        RosterPriorityLevel.OK
                }

            val reasons =
                buildList {
                    if (
                        quality.atwErrors > 0
                    ) {
                        add(
                            "${quality.atwErrors} ATW-fout(en)"
                        )
                    }

                    if (atwWarnings > 0) {
                        add(
                            "$atwWarnings ATW-waarschuwing(en)"
                        )
                    }

                    quality.issues
                        .filterNot {
                            it.contains(
                                "ATW-conflict"
                            )
                        }
                        .forEach(::add)

                    if (
                        weekendOverload >=
                        0.75
                    ) {
                        add(
                            "${prettyPriorityNumber(weekendOverload)} " +
                                "weekend boven teamgemiddelde"
                        )
                    }
                }

            RosterPriorityRow(
                quality = quality,
                atwWarnings =
                    atwWarnings,
                weekendsWorked =
                    weekends,
                teamAverageWeekends =
                    roundPriority(
                        teamAverage
                    ),
                weekendOverload =
                    roundPriority(
                        weekendOverload
                    ),
                priorityScore =
                    priorityScore,
                level =
                    level,
                reasons =
                    reasons
            )
        }
        .sortedWith(
            compareByDescending<RosterPriorityRow> {
                it.priorityScore
            }
                .thenByDescending {
                    it.quality.atwErrors
                }
                .thenBy {
                    it.employeeName
                        .lowercase()
                }
        )
}

private fun roundPriority(
    value: Double
): Double =
    round(value * 10.0) /
        10.0

private fun prettyPriorityNumber(
    value: Double
): String {
    val rounded =
        roundPriority(value)

    return if (
        rounded % 1.0 == 0.0
    ) {
        rounded.toInt()
            .toString()
    } else {
        rounded.toString()
    }
}
