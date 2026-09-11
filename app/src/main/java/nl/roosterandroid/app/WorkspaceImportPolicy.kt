package nl.roosterandroid.app

const val SUPPORTED_WORKSPACE_SCHEMA = 12

fun RosterWorkspace.importValidationProblem(): String? {
    if (schemaVersion > SUPPORTED_WORKSPACE_SCHEMA) {
        return (
            "Dit roosterbestand gebruikt schema " +
                "$schemaVersion. Deze app ondersteunt " +
                "maximaal schema $SUPPORTED_WORKSPACE_SCHEMA."
        )
    }

    if (locations.isEmpty()) {
        return "Het roosterbestand bevat geen vestigingen."
    }

    val locationIds =
        locations.map { it.id }

    if (locationIds.any { it.isBlank() }) {
        return "Een vestiging heeft geen geldig ID."
    }

    if (locationIds.distinct().size != locationIds.size) {
        return "Het roosterbestand bevat dubbele vestiging-ID's."
    }

    locations.forEachIndexed { index, location ->
        val employeeIds =
            location.state.employees.map {
                it.id
            }

        if (employeeIds.any { it.isBlank() }) {
            return (
                "Vestiging ${index + 1} bevat " +
                    "een medewerker zonder geldig ID."
            )
        }

        if (
            employeeIds.distinct().size !=
            employeeIds.size
        ) {
            return (
                "Vestiging ${index + 1} bevat " +
                    "dubbele medewerker-ID's."
            )
        }

        val templateIds =
            location.state.shiftTemplates.map {
                it.id
            }

        if (templateIds.any { it.isBlank() }) {
            return (
                "Vestiging ${index + 1} bevat " +
                    "een dienst zonder geldig ID."
            )
        }

        if (
            templateIds.distinct().size !=
            templateIds.size
        ) {
            return (
                "Vestiging ${index + 1} bevat " +
                    "dubbele dienst-ID's."
            )
        }

        if (location.state.month !in 1..12) {
            return (
                "Vestiging ${index + 1} heeft " +
                    "een ongeldige maand."
            )
        }
    }

    return null
}

fun RosterWorkspace.preparedForImport(): RosterWorkspace {
    importValidationProblem()?.let {
        error(it)
    }

    val cleanedLocations =
        locations.mapIndexed { index, location ->
            val cleanName =
                location.name
                    .trim()
                    .ifBlank {
                        "Vestiging ${index + 1}"
                    }

            location.copy(
                name = cleanName,
                state =
                    location.state.copy(
                        settings =
                            location.state.settings.copy(
                                locationName =
                                    cleanName
                            )
                    )
            )
        }

    val cleanActiveId =
        activeLocationId.takeIf { id ->
            cleanedLocations.any {
                it.id == id
            }
        } ?: cleanedLocations.first().id

    return copy(
        schemaVersion =
            SUPPORTED_WORKSPACE_SCHEMA,
        activeLocationId =
            cleanActiveId,
        locations =
            cleanedLocations
    )
}
