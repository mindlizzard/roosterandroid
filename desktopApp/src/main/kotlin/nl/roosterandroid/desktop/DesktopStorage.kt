package nl.roosterandroid.desktop

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nl.roosterandroid.app.AppState
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

    val dataDirectory: Path = baseDirectory ?: defaultDataDirectory()
    val stateFile: Path = dataDirectory.resolve("roosterplanner-v0.10.json")
    private val backupDirectory = dataDirectory.resolve("backups")

    fun load(): DesktopWorkspace {
        if (!stateFile.exists()) return DesktopWorkspace.default()
        return runCatching { decodeWorkspace(stateFile.readText()) }
            .getOrElse { DesktopWorkspace.default() }
    }

    fun save(workspace: DesktopWorkspace) {
        Files.createDirectories(dataDirectory)
        Files.createDirectories(backupDirectory)
        if (stateFile.exists() && stateFile.isRegularFile()) createBackup()

        val prepared = workspace.copy(lastSavedAt = LocalDateTime.now().toString())
        val temporary = dataDirectory.resolve("roosterplanner-v0.10.tmp")
        temporary.writeText(json.encodeToString(prepared))
        runCatching {
            Files.move(
                temporary,
                stateFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        }.getOrElse {
            Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING)
        }
        pruneBackups()
    }

    fun readImport(path: Path): DesktopWorkspace = decodeWorkspace(path.readText())

    fun exportWorkspace(workspace: DesktopWorkspace, path: Path) {
        path.writeText(json.encodeToString(workspace))
    }

    fun exportLocation(state: AppState, path: Path) {
        path.writeText(json.encodeToString(state))
    }

    private fun decodeWorkspace(raw: String): DesktopWorkspace =
        runCatching { json.decodeFromString<DesktopWorkspace>(raw) }
            .getOrElse {
                DesktopWorkspace.fromAppState(json.decodeFromString<AppState>(raw))
            }

    private fun createBackup() {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
        Files.copy(
            stateFile,
            backupDirectory.resolve("roosterplanner-$stamp.json"),
            StandardCopyOption.REPLACE_EXISTING
        )
    }

    private fun pruneBackups() {
        val backups = Files.list(backupDirectory).use { stream ->
            stream.filter { it.isRegularFile() && it.name.endsWith(".json") }
                .sorted(Comparator.reverseOrder())
                .toList()
        }
        backups.drop(20).forEach { runCatching { Files.deleteIfExists(it) } }
    }

    private fun defaultDataDirectory(): Path {
        val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
        return if (appData != null) {
            Path.of(appData, "RoosterPlanner")
        } else {
            Path.of(System.getProperty("user.home"), ".roosterplanner")
        }
    }
}
