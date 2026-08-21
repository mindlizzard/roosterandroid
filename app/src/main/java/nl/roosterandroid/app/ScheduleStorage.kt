package nl.roosterandroid.app

import android.content.Context
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.YearMonth
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ScheduleStorage(private val context: Context) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val fileName = "rooster_state.json"

    fun load(): AppState {
        return runCatching {
            val file = context.filesDir.resolve(fileName)
            val temporary = context.filesDir.resolve("$fileName.tmp")
            val source = file.takeIf { it.exists() } ?: temporary.takeIf { it.exists() }
            if (source == null) {
                AppState()
            } else {
                normalizeAppState(json.decodeFromString<AppState>(source.readText()))
            }
        }.getOrElse { AppState() }
    }

    fun save(state: AppState) {
        val target = context.filesDir.resolve(fileName)
        val temporary = context.filesDir.resolve("$fileName.tmp")
        val bytes = json.encodeToString(state).toByteArray(Charsets.UTF_8)
        temporary.outputStream().use { stream ->
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    fun exportJson(state: AppState): String = json.encodeToString(state)

    fun importJson(raw: String): AppState {
        val decoded = json.decodeFromString<AppState>(raw)
        require(runCatching { YearMonth.of(decoded.year, decoded.month) }.isSuccess) {
            "Het importbestand bevat een ongeldige roostermaand"
        }
        val invalidTemplate = decoded.shiftTemplates.firstOrNull { template ->
            runCatching {
                template.startTime()
                template.endTime()
            }.isFailure
        }
        require(invalidTemplate == null) {
            "Diensttemplate '${invalidTemplate?.name.orEmpty()}' bevat een ongeldige tijd"
        }
        return normalizeAppState(decoded)
    }
}

internal fun normalizeAppState(raw: AppState): AppState {
    var locations = raw.locations.ifEmpty { defaultLocations() }
    locations = locations.map { location ->
        val defaults = defaultOpeningHours()
        val hours = (1..7).map { weekday ->
            location.openingHours.lastOrNull { it.weekday == weekday }
                ?: defaults.first { it.weekday == weekday }
        }
        location.copy(openingHours = hours)
    }

    val legacy = locations.size == 1 &&
        locations.first().id == DEFAULT_LOCATION_ID &&
        locations.first().name == "Mijn restaurant"
    if (legacy) {
        locations = listOf(
            locations.first().copy(
                name = raw.settings.locationName,
                requireSetupDaily = raw.settings.requireSetupDaily,
                requireMiddleOnBusyDays = raw.settings.requireMiddleOnBusyDays,
                requireCloseDaily = raw.settings.requireCloseDaily,
                busyWeekdays = raw.settings.busyWeekdays,
                monthEndCloseManagers = raw.settings.monthEndCloseManagers
            )
        )
    }

    val activeId = raw.activeLocationId.takeIf { id -> locations.any { it.id == id } }
        ?: locations.first().id
    val activeName = locations.first { it.id == activeId }.name

    return raw.copy(
        locations = locations,
        activeLocationId = activeId,
        employees = raw.employees.map { employee ->
            if (employee.locationIds.isEmpty()) {
                employee.copy(locationIds = setOf(activeId))
            } else {
                employee
            }
        },
        manualDaysOff = raw.manualDaysOff.distinctBy {
            "${it.employeeId}|${it.date}|${it.locationId}"
        },
        specialOpeningHours = raw.specialOpeningHours
            .filter { special ->
                locations.any { it.id == special.locationId } && special.parsedDate() != null
            }
            .distinctBy { "${it.locationId}|${it.date}" },
        settings = raw.settings.copy(locationName = activeName)
    )
}
