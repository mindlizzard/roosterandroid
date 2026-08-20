package nl.roosterandroid.app

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

class ScheduleEngine(private val atw: AtwValidator = AtwValidator()) {
    data class Result(
        val assignments: List<Assignment>,
        val unfilled: List<String>,
        val warnings: List<String>
    )

    private data class Slot(
        val id: Int,
        val date: LocalDate,
        val kind: ShiftKind,
        val label: String,
        val priority: Int
    )

    private data class Choice(
        val employee: Employee,
        val date: LocalDate,
        val template: ShiftTemplate,
        val cost: Double
    )

    private data class Attempt(
        val assignments: List<Assignment>,
        val unfilled: List<String>,
        val warnings: List<String>,
        val score: Double,
        val feasibleCore: Boolean
    )

    companion object {
        private const val ATTEMPTS_WITHOUT_BORROWED = 20
        private const val ATTEMPTS_WITH_BORROWED = 28
    }

    fun generate(state: AppState): Result {
        val ym = YearMonth.of(state.year, state.month)
        val employees = state.employees.filter { it.active }
        if (employees.isEmpty()) {
            return Result(emptyList(), listOf("Voeg eerst minimaal één manager toe."), emptyList())
        }

        val history = (state.assignmentHistory + state.assignments.filterNot { isInMonth(it, ym) })
            .distinctBy { it.id }

        val locked = state.assignments
            .filter { isInMonth(it, ym) && it.source.startsWith("manual") }
            .filterNot { assignment ->
                val date = runCatching { LocalDate.parse(assignment.date) }.getOrNull()
                date != null && approvedAbsence(state, assignment.employeeId, date)
            }
            .distinctBy { "${it.employeeId}|${it.date}" }

        val ownEmployees = employees.filter { it.role != EmployeeRole.BORROWED }
        val hasBorrowed = employees.any { it.role == EmployeeRole.BORROWED }
        val lockedBorrowed = locked.any { assignment ->
            state.employees.firstOrNull { it.id == assignment.employeeId }?.role == EmployeeRole.BORROWED
        }

        var bestOwn: Attempt? = null
        if (ownEmployees.isNotEmpty() && !lockedBorrowed) {
            repeat(ATTEMPTS_WITHOUT_BORROWED) { attemptIndex ->
                val attempt = buildAttempt(
                    state = state,
                    ym = ym,
                    employees = ownEmployees,
                    history = history,
                    locked = locked.filter { assignment ->
                        state.employees.firstOrNull { it.id == assignment.employeeId }?.role != EmployeeRole.BORROWED
                    },
                    attemptIndex = attemptIndex
                )
                if (bestOwn == null || attempt.score < bestOwn!!.score) {
                    bestOwn = attempt
                }
            }
        }

        val winner = if (!lockedBorrowed && (!hasBorrowed || bestOwn?.feasibleCore == true)) {
            bestOwn
        } else {
            var bestWithBorrowed: Attempt? = null
            repeat(ATTEMPTS_WITH_BORROWED) { attemptIndex ->
                val attempt = buildAttempt(
                    state = state,
                    ym = ym,
                    employees = employees,
                    history = history,
                    locked = locked,
                    attemptIndex = 1000 + attemptIndex
                )
                if (bestWithBorrowed == null || attempt.score < bestWithBorrowed!!.score) {
                    bestWithBorrowed = attempt
                }
            }
            bestWithBorrowed ?: bestOwn
        } ?: return Result(locked, emptyList(), emptyList())

        return Result(
            assignments = winner.assignments.sortedWith(
                compareBy<Assignment>({ it.date }, { it.employeeId }, { it.shiftTemplateId })
            ),
            unfilled = winner.unfilled.distinct(),
            warnings = winner.warnings.distinct()
        )
    }

    private fun buildAttempt(
        state: AppState,
        ym: YearMonth,
        employees: List<Employee>,
        history: List<Assignment>,
        locked: List<Assignment>,
        attemptIndex: Int
    ): Attempt {
        val random = Random(state.year * 1009 + state.month * 97 + attemptIndex * 7919)
        val generated = locked.toMutableList()
        val unfilled = mutableListOf<String>()

        val protectedOff = buildProtectedOffDays(
            state = state,
            ym = ym,
            employees = employees,
            locked = locked,
            random = random
        )

        placeSpecificFixedShifts(
            state = state,
            ym = ym,
            employees = employees,
            history = history,
            generated = generated,
            unfilled = unfilled,
            protectedOff = protectedOff,
            random = random
        )

        val requiredSlots = buildRequiredSlots(state, ym, generated)
            .sortedWith(
                compareBy<Slot>(
                    { staticScarcity(it, employees, state) },
                    { it.priority },
                    { it.date },
                    { it.id }
                )
            )

        requiredSlots.forEach { slot ->
            val choices = eligibleChoices(
                slot = slot,
                state = state,
                employees = employees,
                generated = generated,
                history = history,
                protectedOff = protectedOff,
                random = random
            )

            if (choices.isEmpty()) {
                unfilled += "${slot.date} ${slot.label}: geen manager beschikbaar zonder hard conflict."
            } else {
                val chosen = choices.minBy { it.cost }
                generated += Assignment(
                    employeeId = chosen.employee.id,
                    date = slot.date.toString(),
                    shiftTemplateId = chosen.template.id,
                    source = "solver-core"
                )
            }
        }

        fillContractTargets(
            state = state,
            ym = ym,
            employees = employees,
            history = history,
            generated = generated,
            protectedOff = protectedOff,
            unfilled = unfilled,
            random = random
        )

        fillMinimumManagerDemand(
            state = state,
            ym = ym,
            employees = employees,
            history = history,
            generated = generated,
            protectedOff = protectedOff,
            unfilled = unfilled,
            random = random
        )

        ensureTraineeCoverage(
            state = state,
            ym = ym,
            employees = employees,
            history = history,
            generated = generated,
            protectedOff = protectedOff,
            unfilled = unfilled,
            random = random
        )

        val warnings = buildWarnings(state, ym, employees, history, generated)
        val score = evaluate(state, ym, employees, history, generated, unfilled)
        val finalState = state.copy(assignments = generated, assignmentHistory = history)
        val atwErrors = atw.validate(finalState).count {
            it.severity == AtwValidator.Severity.ERROR
        }
        val hardUnfilled = unfilled.count {
            !it.contains("contractdagen", ignoreCase = true)
        }
        val traineeErrors = countTraineeViolations(state, ym, generated)

        return Attempt(
            assignments = generated.toList(),
            unfilled = unfilled,
            warnings = warnings,
            score = score,
            feasibleCore = atwErrors == 0 && hardUnfilled == 0 && traineeErrors == 0
        )
    }

    private fun placeSpecificFixedShifts(
        state: AppState,
        ym: YearMonth,
        employees: List<Employee>,
        history: List<Assignment>,
        generated: MutableList<Assignment>,
        unfilled: MutableList<String>,
        protectedOff: Map<String, Set<LocalDate>>,
        random: Random
    ) {
        val fixedRules = state.availability
            .filter { it.available && it.fixedShiftKind != null }
            .mapNotNull { rule ->
                val date = runCatching { LocalDate.parse(rule.date) }.getOrNull()
                if (date == null || YearMonth.from(date) != ym) null else date to rule
            }
            .sortedBy { it.first }

        fixedRules.forEach { (date, rule) ->
            val employee = employees.firstOrNull { it.id == rule.employeeId } ?: return@forEach
            val kind = rule.fixedShiftKind ?: return@forEach
            val existing = generated.lastOrNull {
                it.employeeId == employee.id && it.date == date.toString()
            }
            if (existing != null) {
                val existingKind = state.shiftTemplates.firstOrNull {
                    it.id == existing.shiftTemplateId
                }?.kind
                if (existingKind != kind) {
                    unfilled += "$date ${employee.name}: handmatige dienst wijkt af van vaste ${kindLabel(kind)}."
                }
                return@forEach
            }

            val templateChoices = templatesForKind(date, kind, state)
            val choices = templateChoices.mapNotNull { template ->
                if (!canAssign(employee, date, template, generated, history, state)) {
                    null
                } else {
                    Choice(
                        employee = employee,
                        date = date,
                        template = template,
                        cost = assignmentCost(
                            employee = employee,
                            date = date,
                            template = template,
                            assignments = generated + history,
                            state = state,
                            protectedOff = protectedOff,
                            random = random
                        )
                    )
                }
            }

            if (choices.isEmpty()) {
                unfilled += "$date ${employee.name}: vaste ${kindLabel(kind)} botst met beschikbaarheid of ATW."
            } else {
                val chosen = choices.minBy { it.cost }
                generated += Assignment(
                    employeeId = employee.id,
                    date = date.toString(),
                    shiftTemplateId = chosen.template.id,
                    source = "fixed"
                )
            }
        }
    }

    private fun buildRequiredSlots(
        state: AppState,
        ym: YearMonth,
        generated: List<Assignment>
    ): List<Slot> {
        val templates = state.shiftTemplates.associateBy { it.id }
        val slots = mutableListOf<Slot>()
        var nextId = 0

        for (day in 1..ym.lengthOfMonth()) {
            val date = ym.atDay(day)
            val required = linkedMapOf<ShiftKind, Int>()

            if (state.settings.requireSetupDaily) {
                required[ShiftKind.SETUP] = 1
            }
            if (
                state.settings.requireMiddleOnBusyDays &&
                date.dayOfWeek.value in state.settings.busyWeekdays
            ) {
                required[ShiftKind.MIDDLE] = 1
            }
            if (state.settings.requireCloseDaily) {
                required[ShiftKind.CLOSE] = 1
                if (date == ym.atEndOfMonth()) {
                    required[ShiftKind.CLOSE] = maxOf(
                        required[ShiftKind.CLOSE] ?: 0,
                        state.settings.monthEndCloseManagers.coerceAtLeast(1)
                    )
                }
            }

            required.forEach { (kind, count) ->
                val already = generated.count { assignment ->
                    assignment.date == date.toString() &&
                        templates[assignment.shiftTemplateId]?.kind == kind
                }
                repeat((count - already).coerceAtLeast(0)) { index ->
                    slots += Slot(
                        id = nextId++,
                        date = date,
                        kind = kind,
                        label = if (kind == ShiftKind.CLOSE && count > 1) {
                            "${kindLabel(kind)} ${index + already + 1}/$count"
                        } else {
                            kindLabel(kind)
                        },
                        priority = slotPriority(kind)
                    )
                }
            }
        }
        return slots
    }

    private fun staticScarcity(
        slot: Slot,
        employees: List<Employee>,
        state: AppState
    ): Int {
        return employees.count { employee ->
            templatesForKind(slot.date, slot.kind, state).any { template ->
                employee.canWork(template.kind) &&
                    isAvailable(employee, slot.date, template, state) &&
                    !approvedAbsence(state, employee.id, slot.date)
            }
        }
    }

    private fun eligibleChoices(
        slot: Slot,
        state: AppState,
        employees: List<Employee>,
        generated: List<Assignment>,
        history: List<Assignment>,
        protectedOff: Map<String, Set<LocalDate>>,
        random: Random
    ): List<Choice> {
        val templates = templatesForKind(slot.date, slot.kind, state)
        return employees.flatMap { employee ->
            templates.mapNotNull { template ->
                if (!canAssign(employee, slot.date, template, generated, history, state)) {
                    null
                } else {
                    Choice(
                        employee = employee,
                        date = slot.date,
                        template = template,
                        cost = assignmentCost(
                            employee = employee,
                            date = slot.date,
                            template = template,
                            assignments = generated + history,
                            state = state,
                            protectedOff = protectedOff,
                            random = random
                        )
                    )
                }
            }
        }
    }

    private fun fillContractTargets(
        state: AppState,
        ym: YearMonth,
        employees: List<Employee>,
        history: List<Assignment>,
        generated: MutableList<Assignment>,
        protectedOff: Map<String, Set<LocalDate>>,
        unfilled: MutableList<String>,
        random: Random
    ) {
        val regularEmployees = employees.filter { it.role != EmployeeRole.BORROWED }

        weeksTouchingMonth(ym).forEach { monday ->
            val ordered = regularEmployees.shuffled(random).sortedByDescending { employee ->
                targetDaysInMonthWeek(employee, monday, ym, state, history) -
                    currentDaysInMonthWeek(employee, monday, ym, generated)
            }

            ordered.forEach { employee ->
                val target = targetDaysInMonthWeek(employee, monday, ym, state, history)
                var safety = 0

                while (
                    currentDaysInMonthWeek(employee, monday, ym, generated) < target &&
                    safety++ < 10
                ) {
                    val weekDates = (0L..6L)
                        .map { monday.plusDays(it) }
                        .filter { YearMonth.from(it) == ym }
                        .filter { date ->
                            generated.none {
                                it.employeeId == employee.id && it.date == date.toString()
                            }
                        }

                    val choices = weekDates.flatMap { date ->
                        contractTemplates(employee, date, state).mapNotNull { template ->
                            if (!canAssign(employee, date, template, generated, history, state)) {
                                null
                            } else {
                                Choice(
                                    employee = employee,
                                    date = date,
                                    template = template,
                                    cost = contractPlacementCost(
                                        employee = employee,
                                        date = date,
                                        template = template,
                                        assignments = generated + history,
                                        state = state,
                                        protectedOff = protectedOff,
                                        random = random
                                    )
                                )
                            }
                        }
                    }

                    if (choices.isEmpty()) {
                        unfilled += "${employee.name}: contractdagen week ${weekNumber(monday)} niet volledig te vullen zonder conflict."
                        break
                    }

                    val chosen = choices.minBy { it.cost }
                    generated += Assignment(
                        employeeId = employee.id,
                        date = chosen.date.toString(),
                        shiftTemplateId = chosen.template.id,
                        source = "solver-contract"
                    )
                }
            }
        }
    }

    private fun fillMinimumManagerDemand(
        state: AppState,
        ym: YearMonth,
        employees: List<Employee>,
        history: List<Assignment>,
        generated: MutableList<Assignment>,
        protectedOff: Map<String, Set<LocalDate>>,
        unfilled: MutableList<String>,
        random: Random
    ) {
        for (day in 1..ym.lengthOfMonth()) {
            val date = ym.atDay(day)
            val minimum = state.dayDemands
                .lastOrNull { it.date == date.toString() }
                ?.minimumManagers
                ?.coerceAtLeast(0)
                ?: 0

            while (generated.count { it.date == date.toString() } < minimum) {
                val choices = employees.flatMap { employee ->
                    genericTemplates(employee, date, state).mapNotNull { template ->
                        if (!canAssign(employee, date, template, generated, history, state)) {
                            null
                        } else {
                            Choice(
                                employee = employee,
                                date = date,
                                template = template,
                                cost = assignmentCost(
                                    employee = employee,
                                    date = date,
                                    template = template,
                                    assignments = generated + history,
                                    state = state,
                                    protectedOff = protectedOff,
                                    random = random
                                )
                            )
                        }
                    }
                }

                if (choices.isEmpty()) {
                    unfilled += "$date: minimumbezetting $minimum managers niet haalbaar zonder conflict."
                    break
                }

                val chosen = choices.minBy { it.cost }
                generated += Assignment(
                    employeeId = chosen.employee.id,
                    date = date.toString(),
                    shiftTemplateId = chosen.template.id,
                    source = "solver-demand"
                )
            }
        }
    }

    private fun ensureTraineeCoverage(
        state: AppState,
        ym: YearMonth,
        employees: List<Employee>,
        history: List<Assignment>,
        generated: MutableList<Assignment>,
        protectedOff: Map<String, Set<LocalDate>>,
        unfilled: MutableList<String>,
        random: Random
    ) {
        if (!state.settings.traineeMustHaveExperiencedManager) return
        val employeeById = state.employees.associateBy { it.id }
        val templates = state.shiftTemplates.associateBy { it.id }

        val trainees = generated
            .filter { assignment ->
                isInMonth(assignment, ym) &&
                    employeeById[assignment.employeeId]?.role == EmployeeRole.TRAINEE
            }
            .sortedBy { it.date }

        trainees.forEach { traineeAssignment ->
            if (experiencedCoversTrainee(traineeAssignment, generated, state)) {
                return@forEach
            }

            val date = runCatching { LocalDate.parse(traineeAssignment.date) }.getOrNull()
                ?: return@forEach
            val traineeTemplate = templates[traineeAssignment.shiftTemplateId]
                ?: return@forEach

            val choices = employees
                .filter { it.role != EmployeeRole.TRAINEE }
                .flatMap { employee ->
                    genericTemplates(employee, date, state).mapNotNull { template ->
                        if (!templateCovers(date, template, traineeTemplate)) {
                            null
                        } else if (!canAssign(employee, date, template, generated, history, state)) {
                            null
                        } else {
                            Choice(
                                employee = employee,
                                date = date,
                                template = template,
                                cost = assignmentCost(
                                    employee = employee,
                                    date = date,
                                    template = template,
                                    assignments = generated + history,
                                    state = state,
                                    protectedOff = protectedOff,
                                    random = random
                                )
                            )
                        }
                    }
                }

            if (choices.isEmpty()) {
                val traineeName = employeeById[traineeAssignment.employeeId]?.name ?: "Trainee"
                unfilled += "$date: $traineeName staat een deel van de dienst zonder ervaren manager."
            } else {
                val chosen = choices.minBy { it.cost }
                generated += Assignment(
                    employeeId = chosen.employee.id,
                    date = date.toString(),
                    shiftTemplateId = chosen.template.id,
                    source = "solver-trainee-support"
                )
            }
        }
    }

    private fun assignmentCost(
        employee: Employee,
        date: LocalDate,
        template: ShiftTemplate,
        assignments: List<Assignment>,
        state: AppState,
        protectedOff: Map<String, Set<LocalDate>>,
        random: Random
    ): Double {
        val ym = YearMonth.from(date)
        val templates = state.shiftTemplates.associateBy { it.id }
        val inMonth = assignments.filter {
            it.employeeId == employee.id && isInMonth(it, ym)
        }

        val hours = inMonth.sumOf { assignment ->
            templates[assignment.shiftTemplateId]?.let(::durationHours) ?: 0.0
        }
        val targetMonthlyHours = employee.contractedHoursPerWeek * ym.lengthOfMonth() / 7.0
        val load = if (targetMonthlyHours <= 0.0) inMonth.size.toDouble() else hours / targetMonthlyHours

        val sameKind = inMonth.count {
            templates[it.shiftTemplateId]?.kind == template.kind
        }
        val weekendCount = inMonth.count {
            val d = runCatching { LocalDate.parse(it.date) }.getOrNull()
            d?.dayOfWeek == DayOfWeek.SATURDAY || d?.dayOfWeek == DayOfWeek.SUNDAY
        }
        val dayLoad = assignments.count { it.date == date.toString() }

        var cost =
            load * 450.0 +
                sameKind * 95.0 +
                dayLoad * 18.0 +
                templatePreferenceCost(template, date, state)

        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) {
            cost += weekendCount * 90.0
        }

        if (employee.role == EmployeeRole.BORROWED && state.settings.minimizeBorrowedManagers) {
            cost += 6000.0
        }

        if (date in protectedOff.orEmpty(employee.id)) {
            cost += 3500.0
        }

        if (employee.role == EmployeeRole.TRAINEE &&
            !experiencedCoversTemplate(date, template, assignments, state)
        ) {
            cost += 1800.0
        }

        val tasks = responsibilitiesFor(employee.id, date, state)
        if (tasks.isNotEmpty()) {
            val preferred = tasks.any { template.kind in preferredKindsForTask(it.type) }
            cost += if (preferred) -3600.0 else 900.0
        }

        if (assignments.any { it.employeeId == employee.id && it.date == date.minusDays(1).toString() }) {
            cost -= 60.0
        }
        if (assignments.any { it.employeeId == employee.id && it.date == date.plusDays(1).toString() }) {
            cost -= 45.0
        }

        cost += random.nextDouble(0.0, 55.0)
        return cost
    }

    private fun contractPlacementCost(
        employee: Employee,
        date: LocalDate,
        template: ShiftTemplate,
        assignments: List<Assignment>,
        state: AppState,
        protectedOff: Map<String, Set<LocalDate>>,
        random: Random
    ): Double {
        var cost = simpleDateTemplateCost(employee, date, template, assignments, state)

        if (date in protectedOff.orEmpty(employee.id)) {
            cost += 5000.0
        }

        val tasks = responsibilitiesFor(employee.id, date, state)
        if (tasks.isNotEmpty()) {
            val preferred = tasks.any { template.kind in preferredKindsForTask(it.type) }
            cost += if (preferred) -4200.0 else 1200.0
        }

        if (employee.role == EmployeeRole.TRAINEE &&
            !experiencedCoversTemplate(date, template, assignments, state)
        ) {
            cost += 2400.0
        }

        if (date.dayOfWeek.value in state.settings.busyWeekdays) {
            cost -= 80.0
        }

        cost += random.nextDouble(0.0, 80.0)
        return cost
    }

    private fun simpleDateTemplateCost(
        employee: Employee,
        date: LocalDate,
        template: ShiftTemplate,
        assignments: List<Assignment>,
        state: AppState
    ): Double {
        val templates = state.shiftTemplates.associateBy { it.id }
        val dayLoad = assignments.count { it.date == date.toString() }
        val monthSameKind = assignments.count {
            it.employeeId == employee.id &&
                templates[it.shiftTemplateId]?.kind == template.kind &&
                runCatching { YearMonth.from(LocalDate.parse(it.date)) }.getOrNull() == YearMonth.from(date)
        }
        val weekendCount = assignments.count {
            if (it.employeeId != employee.id) return@count false
            val d = runCatching { LocalDate.parse(it.date) }.getOrNull() ?: return@count false
            d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY
        }

        val sameKindAlreadyOnDay = assignments.any { assignment ->
            assignment.date == date.toString() &&
                templates[assignment.shiftTemplateId]?.kind == template.kind
        }
        val duplicateOperationalPenalty =
            if (sameKindAlreadyOnDay && template.kind in setOf(ShiftKind.SETUP, ShiftKind.MIDDLE, ShiftKind.CLOSE)) {
                3200.0
            } else {
                0.0
            }

        var cost = dayLoad * 70.0 +
            monthSameKind * 35.0 +
            duplicateOperationalPenalty +
            templatePreferenceCost(template, date, state)
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) {
            cost += weekendCount * 65.0
        }

        val yesterday = assignments.any {
            it.employeeId == employee.id && it.date == date.minusDays(1).toString()
        }
        val tomorrow = assignments.any {
            it.employeeId == employee.id && it.date == date.plusDays(1).toString()
        }
        if (yesterday) cost -= 45.0
        if (tomorrow) cost -= 35.0

        return cost
    }

    private fun buildProtectedOffDays(
        state: AppState,
        ym: YearMonth,
        employees: List<Employee>,
        locked: List<Assignment>,
        random: Random
    ): Map<String, Set<LocalDate>> {
        if (!state.settings.preferTwoConsecutiveDaysOff) return emptyMap()

        val desiredBlocks = state.settings.preferredTwoDayOffBlocks.coerceAtLeast(
            state.settings.minimumTwoDayOffBlocks
        )
        if (desiredBlocks <= 0) return emptyMap()

        val lockedByEmployee = locked.groupBy { it.employeeId }
        val out = mutableMapOf<String, Set<LocalDate>>()

        employees.filter { it.role != EmployeeRole.BORROWED }.forEach { employee ->
            val pairs = mutableListOf<Pair<LocalDate, LocalDate>>()
            var date = ym.atDay(1)
            while (date.isBefore(ym.atEndOfMonth())) {
                val next = date.plusDays(1)
                val noLocked = lockedByEmployee[employee.id].orEmpty().none {
                    it.date == date.toString() || it.date == next.toString()
                }
                val noFixed = state.availability.none {
                    it.employeeId == employee.id &&
                        it.available &&
                        it.fixedShiftKind != null &&
                        (it.date == date.toString() || it.date == next.toString())
                }
                val noTask = responsibilitiesFor(employee.id, date, state).isEmpty() &&
                    responsibilitiesFor(employee.id, next, state).isEmpty()
                val noAbsence = !approvedAbsence(state, employee.id, date) &&
                    !approvedAbsence(state, employee.id, next)

                if (noLocked && noFixed && noTask && noAbsence) {
                    pairs += date to next
                }
                date = date.plusDays(1)
            }

            val selected = mutableSetOf<LocalDate>()
            val candidates = pairs.shuffled(random).sortedBy { (a, b) ->
                // Vrije blokken niet massaal op de drukste dagen leggen.
                // Binnen dezelfde drukteklasse blijft de shuffle leidend, zodat
                // niet iedereen automatisch hetzelfde weekend vrij krijgt.
                listOf(a, b).count { it.dayOfWeek.value in state.settings.busyWeekdays }
            }

            for ((a, b) in candidates) {
                if (selected.size / 2 >= desiredBlocks) break
                if (a in selected || b in selected) continue
                if (a.minusDays(1) in selected || b.plusDays(1) in selected) continue
                selected += a
                selected += b
            }
            out[employee.id] = selected
        }

        return out
    }

    private fun buildWarnings(
        state: AppState,
        ym: YearMonth,
        employees: List<Employee>,
        history: List<Assignment>,
        assignments: List<Assignment>
    ): MutableList<String> {
        val warnings = mutableListOf<String>()
        val employeeById = state.employees.associateBy { it.id }

        val borrowed = assignments.filter {
            employeeById[it.employeeId]?.role == EmployeeRole.BORROWED
        }
        if (borrowed.isNotEmpty()) {
            warnings += "Leenmanager: ${borrowed.size} dienst(en) op ${borrowed.map { it.date }.distinct().sorted().joinToString()}."
        } else if (state.employees.any { it.active && it.role == EmployeeRole.BORROWED }) {
            warnings += "Dit rooster lukt zonder leenmanager."
        }

        employees.filter { it.role != EmployeeRole.BORROWED }.forEach { employee ->
            val blocks = countTwoDayOffBlocks(employee, ym, assignments + history)
            if (blocks < state.settings.minimumTwoDayOffBlocks) {
                warnings += "${employee.name}: slechts $blocks blok(ken) van 2 opeenvolgende vrije dagen; minimaal ${state.settings.minimumTwoDayOffBlocks} gewenst."
            } else if (blocks < state.settings.preferredTwoDayOffBlocks) {
                warnings += "${employee.name}: $blocks blok(ken) van 2 vrije dagen; ${state.settings.preferredTwoDayOffBlocks} heeft voorkeur."
            }
        }

        weeksTouchingMonth(ym).forEach { monday ->
            employees.filter { it.role != EmployeeRole.BORROWED }.forEach { employee ->
                val target = targetDaysInMonthWeek(employee, monday, ym, state, history)
                val actual = currentDaysInMonthWeek(employee, monday, ym, assignments)
                if (actual < target) {
                    warnings += "${employee.name}: week ${weekNumber(monday)} heeft $actual/$target geplande contractdagen binnen deze maand."
                }
            }
        }

        for (day in 1..ym.lengthOfMonth()) {
            val date = ym.atDay(day)
            state.responsibilities.filter {
                it.active && it.preferScheduled && responsibilityApplies(it, date, ym)
            }.forEach { rule ->
                val employee = employeeById[rule.employeeId]
                if (employee != null && assignments.none {
                        it.employeeId == employee.id && it.date == date.toString()
                    }
                ) {
                    warnings += "$date ${employee.name}: ${responsibilityLabel(rule)} staat als taak, maar ${employee.name} is die dag niet ingepland."
                }
            }
        }

        if (state.settings.traineeMustHaveExperiencedManager) {
            assignments.filter { assignment ->
                isInMonth(assignment, ym) &&
                    employeeById[assignment.employeeId]?.role == EmployeeRole.TRAINEE
            }.forEach { traineeAssignment ->
                if (!experiencedCoversTrainee(traineeAssignment, assignments, state)) {
                    val name = employeeById[traineeAssignment.employeeId]?.name ?: "Trainee"
                    warnings += "${traineeAssignment.date}: $name staat een deel van de dienst zonder ervaren manager."
                }
            }
        }

        val finalState = state.copy(assignments = assignments, assignmentHistory = history)
        atw.validate(finalState)
            .filter { it.severity == AtwValidator.Severity.ERROR }
            .take(20)
            .forEach { violation ->
                warnings += "ATW ${violation.date ?: ""}: ${violation.message}"
            }

        return warnings
    }

    private fun countTraineeViolations(
        state: AppState,
        ym: YearMonth,
        assignments: List<Assignment>
    ): Int {
        if (!state.settings.traineeMustHaveExperiencedManager) return 0
        val employeeById = state.employees.associateBy { it.id }
        return assignments.count { assignment ->
            isInMonth(assignment, ym) &&
                employeeById[assignment.employeeId]?.role == EmployeeRole.TRAINEE &&
                !experiencedCoversTrainee(assignment, assignments, state)
        }
    }

    private fun evaluate(
        state: AppState,
        ym: YearMonth,
        employees: List<Employee>,
        history: List<Assignment>,
        assignments: List<Assignment>,
        unfilled: List<String>
    ): Double {
        val employeeById = state.employees.associateBy { it.id }
        val templates = state.shiftTemplates.associateBy { it.id }
        val finalState = state.copy(assignments = assignments, assignmentHistory = history)
        val atwErrors = atw.validate(finalState).count {
            it.severity == AtwValidator.Severity.ERROR
        }

        var contractDeficit = 0
        var contractOver = 0
        var hourDeviation = 0.0

        weeksTouchingMonth(ym).forEach { monday ->
            employees.filter { it.role != EmployeeRole.BORROWED }.forEach { employee ->
                val targetDays = targetDaysInMonthWeek(employee, monday, ym, state, history)
                val actualDays = currentDaysInMonthWeek(employee, monday, ym, assignments)
                contractDeficit += (targetDays - actualDays).coerceAtLeast(0)
                contractOver += (actualDays - targetDays).coerceAtLeast(0)

                val expectedHours = if (employee.contractedDaysPerWeek <= 0) {
                    0.0
                } else {
                    employee.contractedHoursPerWeek *
                        (targetDays.toDouble() / employee.contractedDaysPerWeek.toDouble())
                }
                val actualHours = assignments.filter { assignment ->
                    assignment.employeeId == employee.id &&
                        runCatching {
                            val d = LocalDate.parse(assignment.date)
                            !d.isBefore(monday) &&
                                !d.isAfter(monday.plusDays(6)) &&
                                YearMonth.from(d) == ym
                        }.getOrDefault(false)
                }.sumOf { assignment ->
                    templates[assignment.shiftTemplateId]?.let(::durationHours) ?: 0.0
                }
                hourDeviation += abs(expectedHours - actualHours)
            }
        }

        val taskMisses = (1..ym.lengthOfMonth()).sumOf { day ->
            val date = ym.atDay(day)
            state.responsibilities.count { rule ->
                rule.active &&
                    rule.preferScheduled &&
                    responsibilityApplies(rule, date, ym) &&
                    assignments.none {
                        it.employeeId == rule.employeeId && it.date == date.toString()
                    }
            }
        }

        val borrowedCount = assignments.count {
            employeeById[it.employeeId]?.role == EmployeeRole.BORROWED
        }

        val offBlockDeficit = employees
            .filter { it.role != EmployeeRole.BORROWED }
            .sumOf { employee ->
                val blocks = countTwoDayOffBlocks(employee, ym, assignments + history)
                (state.settings.minimumTwoDayOffBlocks - blocks).coerceAtLeast(0)
            }

        val traineeViolations = countTraineeViolations(state, ym, assignments)

        val fairness = fairnessPenalty(state, ym, employees, assignments)
        val isolated = isolatedWorkdayCount(ym, employees, assignments)

        return atwErrors * 1_000_000_000.0 +
            traineeViolations * 500_000_000.0 +
            unfilled.size * 100_000_000.0 +
            contractDeficit * 5_000_000.0 +
            taskMisses * 1_500_000.0 +
            borrowedCount * 350_000.0 +
            offBlockDeficit * 180_000.0 +
            contractOver * 60_000.0 +
            hourDeviation * 2500.0 +
            fairness * 1200.0 +
            isolated * 750.0
    }

    private fun fairnessPenalty(
        state: AppState,
        ym: YearMonth,
        employees: List<Employee>,
        assignments: List<Assignment>
    ): Double {
        val regular = employees.filter { it.role != EmployeeRole.BORROWED }
        if (regular.size < 2) return 0.0
        val templates = state.shiftTemplates.associateBy { it.id }

        fun counts(kind: ShiftKind): List<Double> =
            regular.filter { it.canWork(kind) }.map { employee ->
                assignments.count {
                    it.employeeId == employee.id &&
                        isInMonth(it, ym) &&
                        templates[it.shiftTemplateId]?.kind == kind
                }.toDouble()
            }

        val weekends = regular.map { employee ->
            assignments.count { assignment ->
                if (assignment.employeeId != employee.id || !isInMonth(assignment, ym)) {
                    return@count false
                }
                val date = LocalDate.parse(assignment.date)
                date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
            }.toDouble()
        }

        return variance(counts(ShiftKind.CLOSE)) * 3.0 +
            variance(counts(ShiftKind.SETUP)) * 1.5 +
            variance(counts(ShiftKind.MIDDLE)) * 2.0 +
            variance(weekends) * 2.5
    }

    private fun variance(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return values.sumOf { value ->
            val diff = value - mean
            diff * diff
        } / values.size
    }

    private fun isolatedWorkdayCount(
        ym: YearMonth,
        employees: List<Employee>,
        assignments: List<Assignment>
    ): Int {
        val workedByEmployee = assignments
            .filter { isInMonth(it, ym) }
            .groupBy { it.employeeId }
            .mapValues { (_, rows) ->
                rows.map { LocalDate.parse(it.date) }.toSet()
            }

        return employees.sumOf { employee ->
            val worked = workedByEmployee[employee.id].orEmpty()
            worked.count { date ->
                date != ym.atDay(1) &&
                    date != ym.atEndOfMonth() &&
                    date.minusDays(1) !in worked &&
                    date.plusDays(1) !in worked
            }
        }
    }

    private fun templatePreferenceCost(
        template: ShiftTemplate,
        date: LocalDate,
        state: AppState
    ): Double {
        if (template.kind != ShiftKind.SETUP) return 0.0
        val isHaviDay =
            date.dayOfWeek == DayOfWeek.WEDNESDAY ||
                date.dayOfWeek == DayOfWeek.FRIDAY
        val haviAvailable = state.shiftTemplates.any {
            it.kind == ShiftKind.SETUP &&
                it.name.contains("HAVI", ignoreCase = true) &&
                date.dayOfWeek.value in it.enabledWeekdays
        }
        if (!haviAvailable) return 0.0
        val isHaviTemplate = template.name.contains("HAVI", ignoreCase = true)
        return if (isHaviDay) {
            if (isHaviTemplate) -400.0 else 400.0
        } else {
            if (isHaviTemplate) 250.0 else 0.0
        }
    }

    private fun templatesForKind(
        date: LocalDate,
        kind: ShiftKind,
        state: AppState
    ): List<ShiftTemplate> {
        return state.shiftTemplates
            .filter {
                it.kind == kind && date.dayOfWeek.value in it.enabledWeekdays
            }
            .sortedWith(
                compareBy<ShiftTemplate> {
                    if (
                        kind == ShiftKind.SETUP &&
                        (date.dayOfWeek == DayOfWeek.WEDNESDAY ||
                            date.dayOfWeek == DayOfWeek.FRIDAY)
                    ) {
                        if (it.name.contains("HAVI", ignoreCase = true)) 0 else 1
                    } else {
                        if (it.name.contains("HAVI", ignoreCase = true)) 1 else 0
                    }
                }.thenBy { it.start }
            )
    }

    private fun genericTemplates(
        employee: Employee,
        date: LocalDate,
        state: AppState
    ): List<ShiftTemplate> {
        val specific = state.availability.lastOrNull {
            it.employeeId == employee.id && it.date == date.toString()
        }
        val weekly = state.weeklyAvailability.lastOrNull {
            it.employeeId == employee.id && it.weekday == date.dayOfWeek.value
        }
        val fixedKind = specific?.fixedShiftKind ?: weekly?.fixedShiftKind
        if (fixedKind != null) {
            return templatesForKind(date, fixedKind, state)
        }

        val operationalKinds = setOf(
            ShiftKind.DAY,
            ShiftKind.MIDDLE,
            ShiftKind.SETUP,
            ShiftKind.CLOSE
        )
        val taskKinds = responsibilitiesFor(employee.id, date, state)
            .flatMap { preferredKindsForTask(it.type) }
            .filter { it in operationalKinds }
            .distinct()

        // KPI en CUSTOM zijn geen automatische bezettingsdiensten.
        // Ze mogen alleen voorkomen wanneer de gebruiker ze expliciet vastzet
        // of handmatig kiest. Taken zoals KPI/weektelling blijven overlays.
        val orderedKinds = (
            taskKinds +
                listOf(
                    ShiftKind.DAY,
                    ShiftKind.MIDDLE,
                    ShiftKind.SETUP,
                    ShiftKind.CLOSE
                )
            ).distinct()

        return orderedKinds.flatMap { kind ->
            templatesForKind(date, kind, state)
        }.distinctBy { it.id }
    }

    private fun contractTemplates(
        employee: Employee,
        date: LocalDate,
        state: AppState
    ): List<ShiftTemplate> = genericTemplates(employee, date, state)

    private fun canAssign(
        employee: Employee,
        date: LocalDate,
        template: ShiftTemplate,
        generated: List<Assignment>,
        history: List<Assignment>,
        state: AppState
    ): Boolean {
        if (!employee.active) return false
        if (approvedAbsence(state, employee.id, date)) return false
        if (!employee.canWork(template.kind)) return false
        if (generated.any {
                it.employeeId == employee.id && it.date == date.toString()
            }
        ) return false
        if (!isAvailable(employee, date, template, state)) return false
        if (!withinWeeklyShiftLimit(employee, date, generated + history)) return false

        val templates = state.shiftTemplates.associateBy { it.id }
        val existing = (generated + history).mapNotNull { assignment ->
            if (assignment.employeeId != employee.id) return@mapNotNull null
            val existingEmployee = state.employees.firstOrNull {
                it.id == assignment.employeeId
            } ?: return@mapNotNull null
            val existingTemplate = templates[assignment.shiftTemplateId]
                ?: return@mapNotNull null
            runCatching {
                atw.toScheduledShift(assignment, existingEmployee, existingTemplate)
            }.getOrNull()
        }

        val quickOk = atw.canPlace(
            employee = employee,
            date = date,
            template = template,
            existing = existing,
            settings = state.settings
        )
        if (quickOk) return true

        if (!state.settings.allowOneReducedDailyRestPer7Days) return false

        val candidate = Assignment(
            employeeId = employee.id,
            date = date.toString(),
            shiftTemplateId = template.id,
            source = "solver-candidate"
        )
        val baseline = state.copy(
            assignments = generated,
            assignmentHistory = history
        )
        val proposed = state.copy(
            assignments = generated + candidate,
            assignmentHistory = history
        )

        fun errorKey(v: AtwValidator.Violation): String =
            "${v.employeeId}|${v.date}|${v.rule}|${v.message}"

        val baselineErrors = atw.validate(baseline)
            .filter {
                it.severity == AtwValidator.Severity.ERROR &&
                    it.employeeId == employee.id
            }
            .map(::errorKey)
            .toSet()

        return atw.validate(proposed)
            .filter {
                it.severity == AtwValidator.Severity.ERROR &&
                    it.employeeId == employee.id
            }
            .none { errorKey(it) !in baselineErrors }
    }

    private fun isAvailable(
        employee: Employee,
        date: LocalDate,
        template: ShiftTemplate,
        state: AppState
    ): Boolean {
        if (approvedAbsence(state, employee.id, date)) return false

        val specific = state.availability.lastOrNull {
            it.employeeId == employee.id && it.date == date.toString()
        }
        val weekly = state.weeklyAvailability.lastOrNull {
            it.employeeId == employee.id && it.weekday == date.dayOfWeek.value
        }

        val rule = specific ?: weekly
        val available = rule?.available ?: true
        if (!available) return false

        val fixedKind = rule?.fixedShiftKind
        if (fixedKind != null && fixedKind != template.kind) return false

        val earliest = rule?.earliestStart?.let {
            runCatching { java.time.LocalTime.parse(it) }.getOrNull()
        }
        val latest = rule?.latestEnd?.let {
            runCatching { java.time.LocalTime.parse(it) }.getOrNull()
        }

        if (earliest != null && template.startTime().isBefore(earliest)) {
            return false
        }

        if (latest != null) {
            val startDt = date.atTime(template.startTime())
            var endDt = date.atTime(template.endTime())
            if (!endDt.isAfter(startDt)) endDt = endDt.plusDays(1)

            var latestDt = date.atTime(latest)
            if (!latestDt.isAfter(startDt)) latestDt = latestDt.plusDays(1)

            if (endDt.isAfter(latestDt)) return false
        }

        return true
    }

    private fun approvedAbsence(
        state: AppState,
        employeeId: String,
        date: LocalDate
    ): Boolean = state.absences.any {
        it.employeeId == employeeId &&
            it.status == AbsenceStatus.APPROVED &&
            it.includes(date)
    }

    private fun withinWeeklyShiftLimit(
        employee: Employee,
        date: LocalDate,
        assignments: List<Assignment>
    ): Boolean {
        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val sunday = monday.plusDays(6)
        val count = assignments.count { assignment ->
            if (assignment.employeeId != employee.id) return@count false
            val assignmentDate = runCatching {
                LocalDate.parse(assignment.date)
            }.getOrNull() ?: return@count false
            !assignmentDate.isBefore(monday) && !assignmentDate.isAfter(sunday)
        }
        return count < employee.maxShiftsPerWeek
    }

    private fun targetDaysInMonthWeek(
        employee: Employee,
        monday: LocalDate,
        ym: YearMonth,
        state: AppState,
        history: List<Assignment>
    ): Int {
        val base = minOf(
            employee.contractedDaysPerWeek.coerceAtLeast(0),
            employee.maxShiftsPerWeek.coerceAtLeast(0)
        )
        if (base == 0) return 0

        val weekDates = (0L..6L).map { monday.plusDays(it) }
        val inMonthDates = weekDates.filter { YearMonth.from(it) == ym }
        if (inMonthDates.isEmpty()) return 0

        val monthStart = ym.atDay(1)
        val monthEnd = ym.atEndOfMonth()

        var target = when {
            monday.isBefore(monthStart) -> {
                val priorWorked = history.count { assignment ->
                    if (assignment.employeeId != employee.id) return@count false
                    val date = runCatching {
                        LocalDate.parse(assignment.date)
                    }.getOrNull() ?: return@count false
                    date in weekDates && date.isBefore(monthStart)
                }
                (base - priorWorked).coerceIn(0, inMonthDates.size)
            }

            monday.plusDays(6).isAfter(monthEnd) -> {
                (base * inMonthDates.size.toDouble() / 7.0)
                    .roundToInt()
                    .coerceIn(0, inMonthDates.size)
            }

            else -> base.coerceAtMost(inMonthDates.size)
        }

        val absentDays = inMonthDates.count {
            approvedAbsence(state, employee.id, it)
        }
        target = (target - minOf(absentDays, target)).coerceAtLeast(0)
        return target
    }

    private fun currentDaysInMonthWeek(
        employee: Employee,
        monday: LocalDate,
        ym: YearMonth,
        assignments: List<Assignment>
    ): Int {
        val sunday = monday.plusDays(6)
        return assignments.count { assignment ->
            if (assignment.employeeId != employee.id) return@count false
            val date = runCatching {
                LocalDate.parse(assignment.date)
            }.getOrNull() ?: return@count false
            YearMonth.from(date) == ym &&
                !date.isBefore(monday) &&
                !date.isAfter(sunday)
        }
    }

    private fun responsibilitiesFor(
        employeeId: String,
        date: LocalDate,
        state: AppState
    ): List<ResponsibilityRule> {
        val ym = YearMonth.from(date)
        return state.responsibilities.filter {
            it.active &&
                it.preferScheduled &&
                it.employeeId == employeeId &&
                responsibilityApplies(it, date, ym)
        }
    }

    private fun preferredKindsForTask(type: ResponsibilityType): List<ShiftKind> =
        when (type) {
            ResponsibilityType.WEEK_COUNT -> listOf(ShiftKind.DAY, ShiftKind.SETUP)
            ResponsibilityType.MONTH_COUNT -> listOf(ShiftKind.CLOSE, ShiftKind.DAY)
            ResponsibilityType.MAINTENANCE -> listOf(ShiftKind.DAY, ShiftKind.SETUP)
            ResponsibilityType.ADMIN -> listOf(ShiftKind.DAY, ShiftKind.SETUP)
            ResponsibilityType.KPI -> listOf(ShiftKind.DAY, ShiftKind.SETUP)
            ResponsibilityType.HACCP -> listOf(ShiftKind.DAY, ShiftKind.SETUP)
            ResponsibilityType.STOCK -> listOf(ShiftKind.DAY, ShiftKind.SETUP)
            ResponsibilityType.HAVI -> listOf(ShiftKind.SETUP, ShiftKind.DAY)
            ResponsibilityType.TRAINING -> listOf(ShiftKind.DAY)
            ResponsibilityType.MEETING -> listOf(ShiftKind.DAY)
            ResponsibilityType.OFFICE -> listOf(ShiftKind.DAY)
            ResponsibilityType.INTERVIEW -> listOf(ShiftKind.DAY)
            ResponsibilityType.CREW_PLANNING -> listOf(ShiftKind.DAY)
            ResponsibilityType.CUSTOM -> listOf(ShiftKind.DAY)
        }

    private fun responsibilityApplies(
        rule: ResponsibilityRule,
        date: LocalDate,
        ym: YearMonth
    ): Boolean =
        when (rule.recurrence) {
            RecurrenceType.WEEKLY -> date.dayOfWeek.value == rule.weekday
            RecurrenceType.MONTHLY_DAY ->
                rule.monthDay != null && date.dayOfMonth == rule.monthDay
            RecurrenceType.MONTH_END -> date == ym.atEndOfMonth()
            RecurrenceType.SPECIFIC_DATE -> rule.date == date.toString()
        }

    private fun responsibilityLabel(rule: ResponsibilityRule): String =
        rule.label.ifBlank {
            when (rule.type) {
                ResponsibilityType.WEEK_COUNT -> "weektelling"
                ResponsibilityType.MONTH_COUNT -> "maandtelling"
                ResponsibilityType.MAINTENANCE -> "onderhoud"
                ResponsibilityType.ADMIN -> "administratie"
                ResponsibilityType.KPI -> "KPI"
                ResponsibilityType.HACCP -> "HACCP"
                ResponsibilityType.STOCK -> "voorraad"
                ResponsibilityType.HAVI -> "HAVI"
                ResponsibilityType.TRAINING -> "training"
                ResponsibilityType.MEETING -> "meeting"
                ResponsibilityType.OFFICE -> "kantoor"
                ResponsibilityType.INTERVIEW -> "sollicitatie"
                ResponsibilityType.CREW_PLANNING -> "crewplanning"
                ResponsibilityType.CUSTOM -> "vaste taak"
            }
        }

    private fun experiencedCoversTemplate(
        date: LocalDate,
        targetTemplate: ShiftTemplate,
        assignments: List<Assignment>,
        state: AppState
    ): Boolean {
        val employeeById = state.employees.associateBy { it.id }
        val templates = state.shiftTemplates.associateBy { it.id }
        return assignments.any { assignment ->
            assignment.date == date.toString() &&
                employeeById[assignment.employeeId]?.role != EmployeeRole.TRAINEE &&
                templates[assignment.shiftTemplateId]?.let {
                    templateCovers(date, it, targetTemplate)
                } == true
        }
    }

    private fun experiencedCoversTrainee(
        traineeAssignment: Assignment,
        assignments: List<Assignment>,
        state: AppState
    ): Boolean {
        val date = runCatching { LocalDate.parse(traineeAssignment.date) }.getOrNull()
            ?: return false
        val employeeById = state.employees.associateBy { it.id }
        val templates = state.shiftTemplates.associateBy { it.id }
        val targetTemplate = templates[traineeAssignment.shiftTemplateId] ?: return false

        return assignments.any { assignment ->
            assignment.id != traineeAssignment.id &&
                assignment.date == traineeAssignment.date &&
                employeeById[assignment.employeeId]?.role != EmployeeRole.TRAINEE &&
                templates[assignment.shiftTemplateId]?.let {
                    templateCovers(date, it, targetTemplate)
                } == true
        }
    }

    private fun templateCovers(
        date: LocalDate,
        covering: ShiftTemplate,
        target: ShiftTemplate
    ): Boolean {
        val (coverStart, coverEnd) = shiftBounds(date, covering)
        val (targetStart, targetEnd) = shiftBounds(date, target)
        return !coverStart.isAfter(targetStart) && !coverEnd.isBefore(targetEnd)
    }

    private fun shiftBounds(
        date: LocalDate,
        template: ShiftTemplate
    ): Pair<java.time.LocalDateTime, java.time.LocalDateTime> {
        val start = date.atTime(template.startTime())
        var end = date.atTime(template.endTime())
        if (!end.isAfter(start)) end = end.plusDays(1)
        return start to end
    }

    private fun countTwoDayOffBlocks(
        employee: Employee,
        ym: YearMonth,
        assignments: List<Assignment>
    ): Int {
        val worked = assignments
            .filter { it.employeeId == employee.id }
            .mapNotNull {
                runCatching { LocalDate.parse(it.date) }.getOrNull()
            }
            .toSet()

        var blocks = 0
        var date = ym.atDay(1)
        val end = ym.atEndOfMonth()

        while (date.isBefore(end)) {
            if (date !in worked && date.plusDays(1) !in worked) {
                blocks++
                date = date.plusDays(2)
            } else {
                date = date.plusDays(1)
            }
        }

        return blocks
    }

    private fun weeksTouchingMonth(ym: YearMonth): List<LocalDate> {
        val first = ym.atDay(1)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val last = ym.atEndOfMonth()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        val out = mutableListOf<LocalDate>()
        var date = first
        while (!date.isAfter(last)) {
            out += date
            date = date.plusDays(7)
        }
        return out
    }

    private fun durationHours(template: ShiftTemplate): Double {
        var minutes = Duration.between(
            template.startTime(),
            template.endTime()
        ).toMinutes()
        if (minutes <= 0) minutes += 24 * 60
        return minutes / 60.0
    }

    private fun slotPriority(kind: ShiftKind): Int =
        when (kind) {
            ShiftKind.CLOSE -> 0
            ShiftKind.MIDDLE -> 1
            ShiftKind.SETUP -> 2
            ShiftKind.KPI -> 3
            ShiftKind.DAY -> 4
            ShiftKind.CUSTOM -> 5
        }

    private fun kindLabel(kind: ShiftKind): String =
        when (kind) {
            ShiftKind.SETUP -> "Setup"
            ShiftKind.DAY -> "Dag"
            ShiftKind.MIDDLE -> "Tussen"
            ShiftKind.CLOSE -> "Sluit"
            ShiftKind.KPI -> "KPI"
            ShiftKind.CUSTOM -> "Dienst"
        }

    private fun weekNumber(date: LocalDate): Int =
        date.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())

    private fun isInMonth(
        assignment: Assignment,
        ym: YearMonth
    ): Boolean {
        val date = runCatching {
            LocalDate.parse(assignment.date)
        }.getOrNull() ?: return false
        return YearMonth.from(date) == ym
    }

    private fun <K, V> Map<K, Set<V>>.orEmpty(key: K): Set<V> =
        this[key] ?: emptySet()
}
