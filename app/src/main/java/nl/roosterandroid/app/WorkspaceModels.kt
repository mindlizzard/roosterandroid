package nl.roosterandroid.app

import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.util.UUID

@Serializable
data class LocationWorkspace(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val state: AppState = AppState(
        settings = PlannerSettings(locationName = name)
    )
)

@Serializable
data class RosterWorkspace(
    val schemaVersion: Int = 12,
    val activeLocationId: String,
    val locations: List<LocationWorkspace>,
    val lastSavedAt: String = LocalDateTime.now().toString()
) {
    fun activeLocation(): LocationWorkspace =
        locations.firstOrNull { it.id == activeLocationId }
            ?: locations.firstOrNull()
            ?: defaultLocation()

    fun withActiveState(newState: AppState): RosterWorkspace {
        val active = activeLocation()

        return copy(
            activeLocationId = active.id,
            locations = locations.map {
                if (it.id == active.id) {
                    it.copy(
                        name = newState.settings.locationName.ifBlank { it.name },
                        state = newState
                    )
                } else {
                    it
                }
            }
        )
    }

    fun normalized(): RosterWorkspace {
        if (locations.isEmpty()) return default()

        if (locations.any { it.id == activeLocationId }) {
            return this
        }

        return copy(activeLocationId = locations.first().id)
    }

    companion object {
        fun default(): RosterWorkspace {
            val location = defaultLocation()

            return RosterWorkspace(
                activeLocationId = location.id,
                locations = listOf(location)
            )
        }

        fun fromAppState(state: AppState): RosterWorkspace {
            val name = state.settings.locationName.ifBlank {
                "Mijn restaurant"
            }

            val location = LocationWorkspace(
                name = name,
                state = state.copy(
                    settings = state.settings.copy(
                        locationName = name
                    )
                )
            )

            return RosterWorkspace(
                activeLocationId = location.id,
                locations = listOf(location)
            )
        }

        private fun defaultLocation() =
            LocationWorkspace(name = "Mijn restaurant")
    }
}

fun AppState.copyForNewLocation(
    locationName: String
): AppState =
    copy(
        assignments = emptyList(),
        assignmentHistory = emptyList(),
        availability = emptyList(),
        absences = emptyList(),
        responsibilities =
            responsibilities.filterNot {
                it.recurrence ==
                    RecurrenceType.SPECIFIC_DATE
            },
        personMarkers = emptyList(),
        dayNotes = emptyList(),
        dayDemands = emptyList(),
        dayPartDemands = emptyList(),
        swapHistory = emptyList(),
        settings = settings.copy(
            locationName = locationName
        )
    )


fun LocationWorkspace.borrowableManagers(): List<Employee> =
    state.employees.filter {
        it.active &&
            it.isExperiencedManager() &&
            it.role != EmployeeRole.BORROWED
    }

fun Employee.asBorrowedManagerFrom(
    sourceLocation: LocationWorkspace
): Employee =
    copy(
        id = UUID.randomUUID().toString(),
        role = EmployeeRole.BORROWED,
        loanSourceLocationId = sourceLocation.id,
        loanSourceEmployeeId = id,
        loanSourceLocationName = sourceLocation.name,
        contractedDaysPerWeek = 0,
        contractedHoursPerWeek = 0.0,
        maxShiftsPerWeek = 3,
        active = true
    )
