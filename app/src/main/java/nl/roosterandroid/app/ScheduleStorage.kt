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

    fun loadWorkspace(): RosterWorkspace {
        lastLoadNotice = null

        val file =
            context.filesDir.resolve(fileName)

        if (!file.exists()) {
            return RosterWorkspace.default()
        }

        decodeWorkspace(file.readText())
            ?.let { return it }

        preserveCorrupt(file)

        val backup =
            context.filesDir.resolve(backupName)

        val recovered =
            if (backup.exists()) {
                decodeWorkspace(
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
                    json.encodeToString(recovered)
                )
            }

            lastLoadNotice =
                "Opslag hersteld vanaf automatische backup"

            return recovered
        }

        lastLoadNotice =
            "Opslagbestand was beschadigd; geen geldige backup gevonden"

        return RosterWorkspace.default()
    }

    fun saveWorkspace(
        workspace: RosterWorkspace
    ) {
        val file =
            context.filesDir.resolve(fileName)

        val backup =
            context.filesDir.resolve(backupName)

        if (file.exists()) {
            val validCurrent =
                decodeWorkspace(
                    runCatching {
                        file.readText()
                    }.getOrDefault("")
                )

            if (validCurrent != null) {
                runCatching {
                    file.copyTo(
                        backup,
                        overwrite = true
                    )
                }
            }
        }

        val prepared =
            workspace.normalized().copy(
                lastSavedAt =
                    java.time.LocalDateTime
                        .now()
                        .toString()
            )

        atomicWrite(
            file,
            json.encodeToString(prepared)
        )
    }

    /*
     * Legacy API bewust behouden.
     */
    fun load(): AppState =
        loadWorkspace()
            .activeLocation()
            .state

    fun save(state: AppState) {
        val workspace =
            loadWorkspace()
                .withActiveState(state)

        saveWorkspace(workspace)
    }

    fun exportJson(
        state: AppState
    ): String =
        json.encodeToString(state)

    fun importJson(
        raw: String
    ): AppState =
        json.decodeFromString(raw)

    fun exportWorkspaceJson(
        workspace: RosterWorkspace
    ): String =
        json.encodeToString(
            workspace.normalized()
        )

    fun importWorkspaceJson(
        raw: String
    ): RosterWorkspace =
        decodeWorkspace(raw)
            ?: error(
                "Geen geldig roosterbestand"
            )

    private fun decodeWorkspace(
        raw: String
    ): RosterWorkspace? {
        if (raw.isBlank()) {
            return null
        }

        runCatching {
            json.decodeFromString<
                RosterWorkspace
            >(raw)
        }.getOrNull()?.let {
            return it.normalized()
        }

        /*
         * Oud Android AppState-bestand
         * automatisch naar workspace.
         */
        val oldState =
            runCatching {
                json.decodeFromString<
                    AppState
                >(raw)
            }.getOrNull()
                ?: return null

        return RosterWorkspace
            .fromAppState(oldState)
    }

    private fun preserveCorrupt(
        file: java.io.File
    ) {
        if (!file.exists()) return

        val target =
            context.filesDir.resolve(
                "rooster_state.corrupt-" +
                    "${System.currentTimeMillis()}.json"
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
