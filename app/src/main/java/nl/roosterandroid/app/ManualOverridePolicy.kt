package nl.roosterandroid.app

import java.time.LocalDate
import java.time.LocalTime

fun AppState.manualOperationalWarnings(
    employeeId: String,
    date: String,
    templateId: String
): List<String> {
    val employee =
        employees.firstOrNull {
            it.id == employeeId
        } ?: return emptyList()

    val template =
        shiftTemplates.firstOrNull {
            it.id == templateId
        } ?: return emptyList()

    val parsedDate =
        runCatching {
            LocalDate.parse(date)
        }.getOrNull()
            ?: return emptyList()

    val warnings =
        mutableListOf<String>()

    if (
        parsedDate.dayOfWeek.value !in
        template.enabledWeekdays
    ) {
        warnings +=
            "diensttemplate is normaal niet actief op deze weekdag"
    }

    val specific =
        availability.lastOrNull {
            it.employeeId == employeeId &&
                it.date == date
        }

    val weekly =
        weeklyAvailability.lastOrNull {
            it.employeeId == employeeId &&
                it.weekday ==
                    parsedDate.dayOfWeek.value
        }

    val available =
        if (specific != null) {
            specific.available
        } else {
            weekly?.available ?: true
        }

    if (!available) {
        warnings +=
            "${employee.name} staat als niet beschikbaar"
    }

    val fixedKind =
        if (specific != null) {
            specific.fixedShiftKind
        } else {
            weekly?.fixedShiftKind
        }

    if (
        fixedKind != null &&
        fixedKind != template.kind
    ) {
        warnings +=
            "wijkt af van vaste dienst ${fixedKind.name.lowercase()}"
    }

    val earliestText =
        if (specific != null) {
            specific.earliestStart
        } else {
            weekly?.earliestStart
        }

    val latestText =
        if (specific != null) {
            specific.latestEnd
        } else {
            weekly?.latestEnd
        }

    val earliest =
        earliestText?.let {
            runCatching {
                LocalTime.parse(it)
            }.getOrNull()
        }

    val latest =
        latestText?.let {
            runCatching {
                LocalTime.parse(it)
            }.getOrNull()
        }

    if (
        earliest != null &&
        template.startTime().isBefore(earliest)
    ) {
        warnings +=
            "dienst begint vóór beschikbaarheid $earliestText"
    }

    if (latest != null) {
        val start =
            parsedDate.atTime(
                template.startTime()
            )

        var end =
            parsedDate.atTime(
                template.endTime()
            )

        if (!end.isAfter(start)) {
            end = end.plusDays(1)
        }

        var allowedEnd =
            parsedDate.atTime(latest)

        if (!allowedEnd.isAfter(start)) {
            allowedEnd =
                allowedEnd.plusDays(1)
        }

        if (end.isAfter(allowedEnd)) {
            warnings +=
                "dienst eindigt na beschikbaarheid $latestText"
        }
    }

    return warnings.distinct()
}
