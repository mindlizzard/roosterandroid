package nl.roosterandroid.app

import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val rosterClockFormat = DateTimeFormatter.ofPattern("HH:mm")

fun rosterShiftTitle(template: ShiftTemplate, assignment: Assignment): String {
    val label = when (template.kind) {
        ShiftKind.SETUP -> if (template.name.contains("HAVI", ignoreCase = true)) {
            "SETUP HAVI"
        } else {
            "SETUP"
        }
        ShiftKind.DAY -> "DAG"
        ShiftKind.MIDDLE -> "TUSSEN"
        ShiftKind.CLOSE -> "SLUIT"
        ShiftKind.NIGHT -> "NACHT"
        ShiftKind.KPI -> "KPI"
        ShiftKind.CUSTOM -> template.name.take(14).uppercase()
    }
    val lock = when (assignment.effectiveLockMode()) {
        AssignmentLockMode.FIXED -> " 🔒"
        AssignmentLockMode.PREFERRED -> " 📌"
        AssignmentLockMode.AUTO -> ""
    }
    return "$label$lock"
}

fun rosterShiftTime(template: ShiftTemplate): String =
    "${normalRosterClock(template.start)}-${normalRosterClock(template.end)}"

private fun normalRosterClock(value: String): String = runCatching {
    LocalTime.parse(value).format(rosterClockFormat)
}.getOrDefault(value)

fun assignmentLockLabel(mode: AssignmentLockMode): String = when (mode) {
    AssignmentLockMode.FIXED -> "Vast 🔒"
    AssignmentLockMode.PREFERRED -> "Voorkeur 📌"
    AssignmentLockMode.AUTO -> "Vrij voor solver"
}

fun assignmentLockDescription(mode: AssignmentLockMode): String = when (mode) {
    AssignmentLockMode.FIXED -> "Auto-fix mag deze dienst niet verplaatsen."
    AssignmentLockMode.PREFERRED ->
        "Alleen verplaatsen als dat nodig is om het rooster kloppend te krijgen."
    AssignmentLockMode.AUTO -> "De solver mag deze dienst opnieuw verdelen."
}
