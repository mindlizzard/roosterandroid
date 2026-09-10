package nl.roosterandroid.app

data class EmployeeRemovalResult(
    val state: AppState,
    val employee: Employee,
    val deactivated: Boolean
)

fun AppState.removeEmployeeSafely(
    employeeId: String
): EmployeeRemovalResult? {
    val employee =
        employees.firstOrNull {
            it.id == employeeId
        } ?: return null

    val hasRosterReference =
        assignments.any {
            it.employeeId == employeeId
        } ||
        assignmentHistory.any {
            it.employeeId == employeeId
        } ||
        swapHistory.any {
            it.firstEmployeeId == employeeId ||
                it.secondEmployeeId == employeeId
        }

    if (hasRosterReference) {
        return EmployeeRemovalResult(
            state = copy(
                employees = employees.map {
                    if (it.id == employeeId) {
                        it.copy(active = false)
                    } else {
                        it
                    }
                }
            ),
            employee = employee,
            deactivated = true
        )
    }

    return EmployeeRemovalResult(
        state = copy(
            employees =
                employees.filterNot {
                    it.id == employeeId
                },
            availability =
                availability.filterNot {
                    it.employeeId == employeeId
                },
            weeklyAvailability =
                weeklyAvailability.filterNot {
                    it.employeeId == employeeId
                },
            absences =
                absences.filterNot {
                    it.employeeId == employeeId
                },
            responsibilities =
                responsibilities.filterNot {
                    it.employeeId == employeeId
                },
            personMarkers =
                personMarkers.filterNot {
                    it.employeeId == employeeId
                }
        ),
        employee = employee,
        deactivated = false
    )
}
