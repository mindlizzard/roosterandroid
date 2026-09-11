package nl.roosterandroid.desktop

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nl.roosterandroid.app.AppState
import nl.roosterandroid.app.preparedForImport
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

class DesktopStorage(baseDirectory: Path? = null) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val dataDirectory: Path =
        baseDirectory ?: defaultDataDirectory()

    // Bestandsnaam bewust behouden voor backwards compatibility.
    val stateFile: Path =
        dataDirectory.resolve("roosterplanner-v0.10.json")

    private val backupDirectory =
        dataDirectory.resolve("backups")

    var lastLoadNotice: String? = null
        private set

    fun load(): DesktopWorkspace {
        lastLoadNotice = null

        if (!stateFile.exists()) {
            return DesktopWorkspace.default()
        }

        val normalLoad = runCatching {
            decodeWorkspace(stateFile.readText())
        }

        if (normalLoad.isSuccess) {
            return normalLoad.getOrThrow()
        }

        val brokenCopy = preserveBrokenState()
        val recovered = newestValidBackup()

        if (recovered != null) {
            val (backup, workspace) = recovered

            runCatching {
                restoreStateFile(backup)
            }

            lastLoadNotice = buildString {
                append("⚠ Opslagbestand was beschadigd. ")
                append("Automatisch hersteld vanaf backup ")
                append(backup.name)

                brokenCopy?.let {
                    append(". Beschadigd bestand bewaard als ")
                    append(it.name)
                }
            }

            return workspace
        }

        lastLoadNotice = buildString {
            append(
                "⚠ Opslagbestand was beschadigd en er was geen geldige backup. "
            )

            brokenCopy?.let {
                append("Beschadigd bestand is veilig bewaard als ")
                append(it.name)
                append(". ")
            }

            append("Er is een leeg rooster geopend.")
        }

        return DesktopWorkspace.default()
    }

    fun save(workspace: DesktopWorkspace) {
        Files.createDirectories(dataDirectory)
        Files.createDirectories(backupDirectory)

        if (
            stateFile.exists() &&
            stateFile.isRegularFile()
        ) {
            createBackup()
        }

        val prepared = workspace.copy(
            lastSavedAt =
                LocalDateTime.now().toString()
        )

        val temporary =
            dataDirectory.resolve(
                "roosterplanner-v0.10.tmp"
            )

        temporary.writeText(
            json.encodeToString(prepared)
        )

        runCatching {
            Files.move(
                temporary,
                stateFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        }.getOrElse {
            Files.move(
                temporary,
                stateFile,
                StandardCopyOption.REPLACE_EXISTING
            )
        }

        pruneBackups()
    }

    fun readImport(path: Path): DesktopWorkspace =
        decodeWorkspace(
            path.readText()
        ).preparedForImport()

    fun exportWorkspace(
        workspace: DesktopWorkspace,
        path: Path
    ) {
        path.writeText(
            json.encodeToString(workspace)
        )
    }

    fun exportLocation(
        state: AppState,
        path: Path
    ) {
        path.writeText(
            json.encodeToString(state)
        )
    }

    private fun decodeWorkspace(
        raw: String
    ): DesktopWorkspace {
        val decoded =
            runCatching {
                json.decodeFromString<DesktopWorkspace>(
                    raw
                )
            }.getOrElse {
                DesktopWorkspace.fromAppState(
                    json.decodeFromString<AppState>(
                        raw
                    )
                )
            }

        return if (decoded.schemaVersion < 12) {
            decoded.copy(schemaVersion = 12)
        } else {
            decoded
        }
    }

    private fun newestValidBackup():
        Pair<Path, DesktopWorkspace>? {

        if (!backupDirectory.exists()) {
            return null
        }

        val backups =
            Files.list(backupDirectory).use { stream ->
                stream
                    .filter {
                        it.isRegularFile() &&
                            it.name.endsWith(".json")
                    }
                    .sorted(
                        Comparator.reverseOrder()
                    )
                    .toList()
            }

        backups.forEach { backup ->
            val workspace = runCatching {
                decodeWorkspace(
                    backup.readText()
                )
            }.getOrNull()

            if (workspace != null) {
                return backup to workspace
            }
        }

        return null
    }

    private fun preserveBrokenState(): Path? {
        if (!stateFile.exists()) {
            return null
        }

        Files.createDirectories(dataDirectory)

        val stamp =
            LocalDateTime.now().format(
                DateTimeFormatter.ofPattern(
                    "yyyyMMdd-HHmmss-SSS"
                )
            )

        val target =
            dataDirectory.resolve(
                "roosterplanner-corrupt-$stamp.json"
            )

        return runCatching {
            Files.move(
                stateFile,
                target,
                StandardCopyOption.REPLACE_EXISTING
            )
            target
        }.getOrElse {
            runCatching {
                Files.copy(
                    stateFile,
                    target,
                    StandardCopyOption.REPLACE_EXISTING
                )
                Files.deleteIfExists(stateFile)
                target
            }.getOrNull()
        }
    }

    private fun restoreStateFile(
        backup: Path
    ) {
        Files.createDirectories(dataDirectory)

        val temporary =
            dataDirectory.resolve(
                "roosterplanner-restore.tmp"
            )

        Files.copy(
            backup,
            temporary,
            StandardCopyOption.REPLACE_EXISTING
        )

        runCatching {
            Files.move(
                temporary,
                stateFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        }.getOrElse {
            Files.move(
                temporary,
                stateFile,
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun createBackup() {
        val stamp =
            LocalDateTime.now().format(
                DateTimeFormatter.ofPattern(
                    "yyyyMMdd-HHmmss-SSS"
                )
            )

        Files.copy(
            stateFile,
            backupDirectory.resolve(
                "roosterplanner-$stamp.json"
            ),
            StandardCopyOption.REPLACE_EXISTING
        )
    }

    private fun pruneBackups() {
        val backups =
            Files.list(backupDirectory).use { stream ->
                stream
                    .filter {
                        it.isRegularFile() &&
                            it.name.endsWith(".json")
                    }
                    .sorted(
                        Comparator.reverseOrder()
                    )
                    .toList()
            }

        backups.drop(20).forEach {
            runCatching {
                Files.deleteIfExists(it)
            }
        }
    }

    private fun defaultDataDirectory(): Path {
        val appData =
            System.getenv("APPDATA")
                ?.takeIf { it.isNotBlank() }

        return if (appData != null) {
            Path.of(
                appData,
                "RoosterPlanner"
            )
        } else {
            Path.of(
                System.getProperty("user.home"),
                ".roosterplanner"
            )
        }
    }
}
