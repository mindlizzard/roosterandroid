package nl.roosterandroid.app

enum class RosterAdviceType {
    FIX_ATW,
    REVIEW_ATW_WARNING,
    ADD_HOURS,
    REDUCE_HOURS,
    REDISTRIBUTE_WEEKENDS,
    REDISTRIBUTE_SETUP,
    REDISTRIBUTE_MIDDLE,
    REDISTRIBUTE_CLOSE
}

data class RosterAdvice(
    val type: RosterAdviceType,
    val title: String,
    val detail: String
)

fun RosterPriorityRow.recommendedActions():
    List<RosterAdvice> {

    val out =
        mutableListOf<RosterAdvice>()

    if (quality.atwErrors > 0) {
        out +=
            RosterAdvice(
                type =
                    RosterAdviceType.FIX_ATW,
                title =
                    "ATW eerst oplossen",
                detail =
                    "${quality.atwErrors} " +
                        "ATW-conflict(en) blokkeren " +
                        "een betrouwbaar rooster."
            )
    }

    if (atwWarnings > 0) {
        out +=
            RosterAdvice(
                type =
                    RosterAdviceType.REVIEW_ATW_WARNING,
                title =
                    "Rusttijden controleren",
                detail =
                    "$atwWarnings " +
                        "ATW-waarschuwing(en) " +
                        "verdienen controle."
            )
    }

    if (
        quality.targetHours > 0.0 &&
        quality.hourDifference < -1.0
    ) {
        out +=
            RosterAdvice(
                type =
                    RosterAdviceType.ADD_HOURS,
                title =
                    "Extra uren plannen",
                detail =
                    "${prettyAdviceHours(
                        -quality.hourDifference
                    )} uur onder maanddoel."
            )
    }

    if (
        quality.targetHours > 0.0 &&
        quality.hourDifference > 1.0
    ) {
        out +=
            RosterAdvice(
                type =
                    RosterAdviceType.REDUCE_HOURS,
                title =
                    "Uren verminderen",
                detail =
                    "${prettyAdviceHours(
                        quality.hourDifference
                    )} uur boven maanddoel."
            )
    }

    if (weekendOverload >= 0.75) {
        out +=
            RosterAdvice(
                type =
                    RosterAdviceType.REDISTRIBUTE_WEEKENDS,
                title =
                    "Weekend herverdelen",
                detail =
                    "${prettyAdviceHours(
                        weekendOverload
                    )} weekend boven " +
                        "teamgemiddelde."
            )
    }

    listOf(
        Triple(
            RosterAdviceType.REDISTRIBUTE_SETUP,
            "SETUP herverdelen",
            setupOverload
        ),
        Triple(
            RosterAdviceType.REDISTRIBUTE_MIDDLE,
            "TUSSEN herverdelen",
            middleOverload
        ),
        Triple(
            RosterAdviceType.REDISTRIBUTE_CLOSE,
            "SLUIT herverdelen",
            closeOverload
        )
    ).filter { (_, _, overload) -> overload >= 1.0 }
        .forEach { (type, title, overload) ->
            out +=
                RosterAdvice(
                    type = type,
                    title = title,
                    detail =
                        "${prettyAdviceHours(overload)} dienst(en) boven " +
                            "teamgemiddelde."
                )
        }

    return out
}

fun RosterPriorityRow.primaryAdvice():
    RosterAdvice? =
    recommendedActions()
        .firstOrNull()

private fun prettyAdviceHours(
    value: Double
): String {
    val rounded =
        kotlin.math.round(
            value * 10.0
        ) / 10.0

    return if (
        rounded % 1.0 == 0.0
    ) {
        rounded.toInt()
            .toString()
    } else {
        rounded.toString()
    }
}
