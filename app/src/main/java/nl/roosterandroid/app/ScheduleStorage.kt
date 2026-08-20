package nl.roosterandroid.app

import android.content.Context
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
            if (!file.exists()) AppState() else json.decodeFromString<AppState>(file.readText())
        }.getOrElse { AppState() }
    }

    fun save(state: AppState) {
        context.filesDir.resolve(fileName).writeText(json.encodeToString(state))
    }

    fun exportJson(state: AppState): String = json.encodeToString(state)

    fun importJson(raw: String): AppState = json.decodeFromString(raw)
}
