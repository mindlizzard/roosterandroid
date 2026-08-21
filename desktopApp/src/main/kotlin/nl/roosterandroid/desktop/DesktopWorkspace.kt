package nl.roosterandroid.desktop

import kotlinx.serialization.Serializable
import nl.roosterandroid.app.AppState
import nl.roosterandroid.app.PlannerSettings
import java.time.LocalDateTime
import java.util.UUID

@Serializable
data class LocationWorkspace(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val state: AppState = AppState(settings = PlannerSettings(locationName = name))
)

@Serializable
data class DesktopWorkspace(
    val schemaVersion: Int = 10,
    val activeLocationId: String,
    val locations: List<LocationWorkspace>,
    val lastSavedAt: String = LocalDateTime.now().toString()
) {
    fun activeLocation(): LocationWorkspace =
        locations.firstOrNull { it.id == activeLocationId }
            ?: locations.firstOrNull()
            ?: defaultLocation()

    companion object {
        fun default(): DesktopWorkspace {
            val location = defaultLocation()
            return DesktopWorkspace(
                activeLocationId = location.id,
                locations = listOf(location)
            )
        }

        fun fromAppState(state: AppState): DesktopWorkspace {
            val name = state.settings.locationName.ifBlank { "Mijn restaurant" }
            val location = LocationWorkspace(
                name = name,
                state = state.copy(settings = state.settings.copy(locationName = name))
            )
            return DesktopWorkspace(
                activeLocationId = location.id,
                locations = listOf(location)
            )
        }

        private fun defaultLocation(): LocationWorkspace =
            LocationWorkspace(name = "Mijn restaurant")
    }
}
