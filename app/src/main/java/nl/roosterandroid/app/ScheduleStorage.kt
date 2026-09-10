package nl.roosterandroid.app

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ScheduleStorage(
    private val context: Context
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val fileName =
        "rooster_state.json"

    private val backupName =
        "rooster_state.backup.json"

    var lastLoadNotice: String? = null
        private set

    fun load(): AppState {
        lastLoadNotice = null

        val file =
            context.filesDir.resolve(
                fileName
            )

        if (!file.exists()) {
            return AppState()
        }

        decode(file.readText())
            ?.let { return it }

        preserveCorrupt(file)

        val backup =
            context.filesDir.resolve(
                backupName
            )

        val recovered =
            if (backup.exists()) {
                decode(
                    runCatching {
                        backup.readText()
                    }.getOrDefault("")
                )
            } else {
                null
            }

        if (recovered != null) {
            runCatching {
                atomicWrite(
                    file,
                    json.encodeToString(
                        recovered
                    )
                )
            }

            lastLoadNotice =
                "Opslag hersteld vanaf automatische backup"

            return recovered
        }

        lastLoadNotice =
            "Opslagbestand was beschadigd; geen geldige backup gevonden"

        return AppState()
    }

    fun save(state: AppState) {
        val file =
            context.filesDir.resolve(
                fileName
            )

        val backup =
            context.filesDir.resolve(
                backupName
            )

        if (file.exists()) {
            val validCurrent =
                runCatching {
                    decode(
                        file.readText()
                    )
                }.getOrNull()

            if (validCurrent != null) {
                runCatching {
                    file.copyTo(
                        backup,
                        overwrite = true
                    )
                }
            }
        }

        atomicWrite(
            file,
            json.encodeToString(state)
        )
    }

    fun exportJson(
        state: AppState
    ): String =
        json.encodeToString(state)

    fun importJson(
        raw: String
    ): AppState =
        json.decodeFromString(raw)

    private fun decode(
        raw: String
    ): AppState? =
        runCatching {
            json.decodeFromString<AppState>(
                raw
            )
        }.getOrNull()

    private fun preserveCorrupt(
        file: java.io.File
    ) {
        if (!file.exists()) return

        val target =
            context.filesDir.resolve(
                "rooster_state.corrupt-${System.currentTimeMillis()}.json"
            )

        runCatching {
            file.copyTo(
                target,
                overwrite = true
            )
        }
    }

    private fun atomicWrite(
        file: java.io.File,
        content: String
    ) {
        val temp =
            context.filesDir.resolve(
                "${file.name}.tmp"
            )

        temp.writeText(content)

        if (!temp.renameTo(file)) {
            temp.copyTo(
                file,
                overwrite = true
            )
            temp.delete()
        }
    }
}
