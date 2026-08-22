#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else '.').resolve()
ASSET_DIR = Path(__file__).resolve().parent / 'files'

def p(rel): return ROOT / rel

def replace_once(rel, old, new):
    path = p(rel)
    text = path.read_text(encoding='utf-8')
    if new in text:
        print(f'OK already: {rel}')
        return
    if old not in text:
        raise RuntimeError(f'Expected block not found in {rel}: {old[:120]!r}')
    path.write_text(text.replace(old, new, 1), encoding='utf-8')
    print(f'UPDATED: {rel}')

def add_file(rel, source_file):
    path = p(rel)
    content = Path(source_file).read_text(encoding='utf-8')
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and path.read_text(encoding='utf-8') == content:
        print(f'OK already: {rel}')
        return
    path.write_text(content, encoding='utf-8')
    print(f'CREATED: {rel}')

# Complete, syntax-tested shared model replacement.
add_file('app/src/main/java/nl/roosterandroid/app/AppModels.kt', ASSET_DIR / 'AppModels.kt')
add_file('app/src/main/java/nl/roosterandroid/app/SmartShiftTemplates.kt', ASSET_DIR / 'SmartShiftTemplates.kt')
add_file('desktopApp/src/main/kotlin/nl/roosterandroid/desktop/AdministrationHrPanel.kt', ASSET_DIR / 'AdministrationHrPanel.kt')

# ScheduleEngine: role-aware manager slots, stable 24/7 behavior and smart-template preference.
rel = 'app/src/main/java/nl/roosterandroid/app/ScheduleEngine.kt'
replace_once(rel,
'''            if (!state.isOpenOn(date)) continue\n            val required = linkedMapOf<ShiftKind, Int>()\n\n            if (state.settings.requireSetupDaily) {''',
'''            if (!state.isOpenOn(date)) continue\n            val twentyFourHours = state.operatingHoursOn(date)?.isTwentyFourHours() == true\n            val required = linkedMapOf<ShiftKind, Int>()\n\n            if (state.settings.requireSetupDaily && !twentyFourHours) {''')
replace_once(rel,
'''            if (state.settings.requireCloseDaily) {''',
'''            if (state.settings.requireCloseDaily && !twentyFourHours) {''')
replace_once(rel,
'''        val templates = state.shiftTemplates.associateBy { it.id }\n        val slots = mutableListOf<Slot>()''',
'''        val templates = state.shiftTemplates.associateBy { it.id }\n        val employeeById = state.employees.associateBy { it.id }\n        val slots = mutableListOf<Slot>()''')
replace_once(rel,
'''                val already = generated.count { assignment ->\n                    assignment.date == date.toString() &&\n                        templates[assignment.shiftTemplateId]?.kind == kind\n                }''',
'''                val already = generated.count { assignment ->\n                    assignment.date == date.toString() &&\n                        employeeById[assignment.employeeId]?.countsAsManager() == true &&\n                        templates[assignment.shiftTemplateId]?.kind == kind\n                }''')
replace_once(rel,
'''        return employees.count { employee ->\n            templatesForKind(slot.date, slot.kind, state).any { template ->''',
'''        return employees.filter { it.countsAsManager() }.count { employee ->\n            templatesForKind(slot.date, slot.kind, state).any { template ->''')
replace_once(rel,
'''        return employees.flatMap { employee ->\n            templates.mapNotNull { template ->''',
'''        return employees.filter { it.countsAsManager() }.flatMap { employee ->\n            templates.mapNotNull { template ->''')
replace_once(rel,
'''    ) {\n        for (day in 1..ym.lengthOfMonth()) {\n            val date = ym.atDay(day)\n            val minimum = state.dayDemands''',
'''    ) {\n        val employeeById = state.employees.associateBy { it.id }\n        for (day in 1..ym.lengthOfMonth()) {\n            val date = ym.atDay(day)\n            val minimum = state.dayDemands''')
replace_once(rel,
'''            while (generated.count { it.date == date.toString() } < minimum) {\n                val choices = employees.flatMap { employee ->''',
'''            fun managerCount(): Int = generated.count { assignment ->\n                assignment.date == date.toString() &&\n                    employeeById[assignment.employeeId]?.countsAsManager() == true\n            }\n\n            while (managerCount() < minimum) {\n                val choices = employees.filter { it.countsAsManager() }.flatMap { employee ->''')
replace_once(rel,
'''        val templates = state.shiftTemplates.associateBy { it.id }\n        val demands = state.dayPartDemands''',
'''        val templates = state.shiftTemplates.associateBy { it.id }\n        val employeeById = state.employees.associateBy { it.id }\n        val demands = state.dayPartDemands''')
replace_once(rel,
'''            fun coveringCount(): Int = generated.count { assignment ->\n                if (assignment.date != date.toString()) return@count false\n                val template = templates[assignment.shiftTemplateId] ?: return@count false''',
'''            fun coveringCount(): Int = generated.count { assignment ->\n                if (assignment.date != date.toString()) return@count false\n                if (employeeById[assignment.employeeId]?.countsAsManager() != true) return@count false\n                val template = templates[assignment.shiftTemplateId] ?: return@count false''')
replace_once(rel,
'''                val choices = employees.flatMap { employee ->\n                    genericTemplates(employee, date, state)\n                        .filter { templateCoversDayPart(date, it, demand) }''',
'''                val choices = employees.filter { it.countsAsManager() }.flatMap { employee ->\n                    genericTemplates(employee, date, state)\n                        .filter { templateCoversDayPart(date, it, demand) }''')
replace_once(rel,
'''            val choices = employees\n                .filter { it.role != EmployeeRole.TRAINEE }''',
'''            val choices = employees\n                .filter { it.isExperiencedManager() }''')
replace_once(rel,
'''        val regular = employees.filter { it.role != EmployeeRole.BORROWED }\n        if (regular.size < 2) return 0.0''',
'''        val regular = employees.filter {\n            it.countsAsManager() && it.role != EmployeeRole.BORROWED\n        }\n        if (regular.size < 2) return 0.0''')
replace_once(rel,
'''                employeeById[assignment.employeeId]?.role != EmployeeRole.TRAINEE &&''',
'''                employeeById[assignment.employeeId]?.isExperiencedManager() == true &&''')
# second occurrence
replace_once(rel,
'''                employeeById[assignment.employeeId]?.role != EmployeeRole.TRAINEE &&''',
'''                employeeById[assignment.employeeId]?.isExperiencedManager() == true &&''')
replace_once(rel,
'''    private fun templatesForKind(\n        date: LocalDate,\n        kind: ShiftKind,\n        state: AppState\n    ): List<ShiftTemplate> {\n        return state.shiftTemplates''',
'''    private fun templatesForKind(\n        date: LocalDate,\n        kind: ShiftKind,\n        state: AppState\n    ): List<ShiftTemplate> {\n        val isTwentyFourHours = state.operatingHoursOn(date)?.isTwentyFourHours() == true\n        if (isTwentyFourHours && kind in setOf(ShiftKind.SETUP, ShiftKind.CLOSE)) return emptyList()\n        return state.shiftTemplates''')
replace_once(rel,
'''        return orderedKinds.flatMap { kind ->\n            templatesForKind(date, kind, state)\n        }.distinctBy { it.id }''',
'''        val automaticCustom = if (employee.countsAsManager()) {\n            state.shiftTemplates.filter { template ->\n                template.autoGenerated &&\n                    template.kind == ShiftKind.CUSTOM &&\n                    date.dayOfWeek.value in template.enabledWeekdays &&\n                    state.allowsShiftOn(date, template)\n            }\n        } else emptyList()\n\n        return (orderedKinds.flatMap { kind ->\n            templatesForKind(date, kind, state)\n        } + automaticCustom).distinctBy { it.id }''')
replace_once(rel,
'''    private fun templatePreferenceCost(\n        template: ShiftTemplate,\n        date: LocalDate,\n        state: AppState\n    ): Double {\n        if (template.kind != ShiftKind.SETUP) return 0.0''',
'''    private fun templatePreferenceCost(\n        template: ShiftTemplate,\n        date: LocalDate,\n        state: AppState\n    ): Double {\n        var cost = if (template.autoGenerated) 120.0 else 0.0\n        if (template.kind != ShiftKind.SETUP) return cost''')
replace_once(rel,
'''        if (!haviAvailable) return 0.0\n        val isHaviTemplate = template.name.contains("HAVI", ignoreCase = true)\n        return if (isHaviDay) {\n            if (isHaviTemplate) -400.0 else 400.0\n        } else {\n            if (isHaviTemplate) 250.0 else 0.0\n        }''',
'''        if (!haviAvailable) return cost\n        val isHaviTemplate = template.name.contains("HAVI", ignoreCase = true)\n        cost += if (isHaviDay) {\n            if (isHaviTemplate) -400.0 else 400.0\n        } else {\n            if (isHaviTemplate) 250.0 else 0.0\n        }\n        return cost''')

# DesktopController: smart templates before solving and 24/7-safe closing logic.
rel = 'desktopApp/src/main/kotlin/nl/roosterandroid/desktop/DesktopController.kt'
replace_once(rel, 'import nl.roosterandroid.app.ShiftTemplate\n', 'import nl.roosterandroid.app.ShiftTemplate\nimport nl.roosterandroid.app.SmartShiftTemplates\n')
replace_once(rel,
'''    fun generate() {\n        val result = engine.generate(state)\n        commitActive(\n            state.copy(assignments = result.assignments),''',
'''    fun generate() {\n        val prepared = preparePlannerState(state)\n        val result = engine.generate(prepared)\n        commitActive(\n            prepared.copy(assignments = result.assignments),''')
replace_once(rel, '    fun autoFix(): AutoFixReport {\n        val original = state\n', '    fun autoFix(): AutoFixReport {\n        val original = preparePlannerState(state)\n')
replace_once(rel,
'''    fun alignClosingTemplatesWithOperatingHours() {\n        val activeHours = state.operatingHours.filterNot { it.closed }\n        if (activeHours.isEmpty()) {\n            showStatus("Er zijn geen geopende dagen")''',
'''    fun alignClosingTemplatesWithOperatingHours() {\n        val activeHours = state.operatingHours.filterNot { it.closed || it.isTwentyFourHours() }\n        if (activeHours.isEmpty()) {\n            showStatus("Geen sluitmomenten: 24/7-dagen hebben geen sluitdienst nodig")''')
replace_once(rel,
'''    private fun monthlyShiftCount(employeeId: String, base: AppState): Int =\n        base.assignments.count { it.employeeId == employeeId }\n''',
'''    fun refreshSmartTemplates() {\n        val before = state.shiftTemplates.size\n        val updated = SmartShiftTemplates.augment(state)\n        val added = (updated.shiftTemplates.size - before).coerceAtLeast(0)\n        commitActive(updated, "Slimme diensttemplates bijgewerkt • $added nieuw")\n    }\n\n    private fun preparePlannerState(base: AppState): AppState =\n        if (base.settings.autoGenerateSmartTemplates) SmartShiftTemplates.augment(base) else base\n\n    private fun monthlyShiftCount(employeeId: String, base: AppState): Int =\n        base.assignments.count { it.employeeId == employeeId }\n''')

# Team selection: use selected row directly, and convert model->view when restoring after sorting.
rel = 'desktopApp/src/main/kotlin/nl/roosterandroid/desktop/TeamPanel.kt'
replace_once(rel,
'''                selectedEmployeeId = selectedEmployee()?.id\n                refreshAvailability()''',
'''                val row = selectedModelRow(employeeTable)\n                selectedEmployeeId = row?.let { controller.state.employees.getOrNull(it)?.id }\n                refreshAvailability()''')
replace_once(rel,
'''            employeeTable.setRowSelectionInterval(index, index)\n            selectedEmployeeId = controller.state.employees[index].id''',
'''            val viewIndex = employeeTable.convertRowIndexToView(index)\n            if (viewIndex >= 0) employeeTable.setRowSelectionInterval(viewIndex, viewIndex)\n            selectedEmployeeId = controller.state.employees[index].id''')
replace_once(rel,
'''    private fun selectedEmployee(): Employee? {\n        val id = selectedEmployeeId\n        if (id != null) controller.state.employees.firstOrNull { it.id == id }?.let { return it }\n        val row = selectedModelRow(employeeTable) ?: return null\n        return controller.state.employees.getOrNull(row)\n    }''',
'''    private fun selectedEmployee(): Employee? {\n        val row = selectedModelRow(employeeTable)\n        if (row != null) controller.state.employees.getOrNull(row)?.let { return it }\n        val id = selectedEmployeeId ?: return null\n        return controller.state.employees.firstOrNull { it.id == id }\n    }''')

# Locations: distinct teams by default, and show Team vs Managers.
rel = 'desktopApp/src/main/kotlin/nl/roosterandroid/desktop/LocationsPanel.kt'
replace_once(rel, 'package nl.roosterandroid.desktop\n\n', 'package nl.roosterandroid.desktop\n\nimport nl.roosterandroid.app.countsAsManager\n')
replace_once(rel,
'''arrayOf("Vestiging", "Actief", "Managers", "Diensten deze maand", "Restauranttijden")''',
'''arrayOf("Vestiging", "Actief", "Team", "Managers", "Diensten deze maand", "Restauranttijden")''')
replace_once(rel,
'''                state.employees.count { it.active },\n                state.assignments.size,''',
'''                state.employees.count { it.active },\n                state.employees.count { it.active && it.countsAsManager() },\n                state.assignments.size,''')
replace_once(rel,
'''        if (activeIndex >= 0) table.setRowSelectionInterval(activeIndex, activeIndex)''',
'''        if (activeIndex >= 0) {\n            val viewIndex = table.convertRowIndexToView(activeIndex)\n            if (viewIndex >= 0) table.setRowSelectionInterval(viewIndex, viewIndex)\n        }''')
replace_once(rel,
'''        val copy = JCheckBox("Kopieer team, templates en regels van huidige vestiging", true)''',
'''        val copy = JCheckBox(\n            "Kopieer team, templates en regels van huidige vestiging (losse kopie)",\n            false\n        ).apply {\n            toolTipText = "Standaard uit: iedere vestiging kan zo een volledig eigen managerteam krijgen."\n        }''')

# UI role label.
rel = 'desktopApp/src/main/kotlin/nl/roosterandroid/desktop/UiSupport.kt'
replace_once(rel,
'''    EmployeeRole.BORROWED -> "Leenmanager"\n}''',
'''    EmployeeRole.BORROWED -> "Leenmanager"\n    EmployeeRole.HOST -> "Host(ess)"\n}''')

# DesktopDialogs: HOST defaults and 24/7 toggle.
rel = 'desktopApp/src/main/kotlin/nl/roosterandroid/desktop/DesktopDialogs.kt'
replace_once(rel, 'import nl.roosterandroid.app.canWork\n', 'import nl.roosterandroid.app.canWork\nimport nl.roosterandroid.app.countsAsManager\n')
replace_once(rel,
'''        val active = JCheckBox("Actief", existing?.active ?: true)\n\n        val form = formPanel(''',
'''        val active = JCheckBox("Actief", existing?.active ?: true)\n\n        if (existing == null) {\n            role.addActionListener {\n                val selectedRole = role.selectedItem as EmployeeRole\n                val managerRole = selectedRole.countsAsManager()\n                setup.isSelected = managerRole && selectedRole != EmployeeRole.BORROWED\n                day.isSelected = true\n                middle.isSelected = true\n                close.isSelected = managerRole\n                kpi.isSelected = managerRole\n            }\n        }\n\n        val form = formPanel(''')
replace_once(rel,
'''        val open = JTextField(existing.open, 8)\n        val close = JTextField(existing.close, 8)\n        val closed = JCheckBox("Gesloten", existing.closed)''',
'''        val open = JTextField(existing.open, 8)\n        val close = JTextField(existing.close, 8)\n        val allDay = JCheckBox("24/7 geopend", existing.isTwentyFourHours())\n        val closed = JCheckBox("Gesloten", existing.closed)''')
replace_once(rel,
'''            "Dicht / planning einde" to close,\n            "Status" to closed''',
'''            "Dicht / planning einde" to close,\n            "Doorlopend" to allDay,\n            "Status" to closed''')
replace_once(rel,
'''        if (!confirm(parent, "Restauranttijden", form)) return null\n        if (!validTime(open.text) || !validTime(close.text)) {''',
'''        if (!confirm(parent, "Restauranttijden", form)) return null\n        if (allDay.isSelected && !closed.isSelected) {\n            return existing.copy(open = "00:00", close = "00:00", closed = false)\n        }\n        if (!validTime(open.text) || !validTime(close.text)) {''')

# RulesPanel: smart-template setting and button.
rel = 'desktopApp/src/main/kotlin/nl/roosterandroid/desktop/RulesPanel.kt'
replace_once(rel,
'''    private val warnSundays = JCheckBox("Waarschuw bij te weinig vrije zondagen")\n    private val autoFix = JCheckBox''',
'''    private val warnSundays = JCheckBox("Waarschuw bij te weinig vrije zondagen")\n    private val smartTemplates = JCheckBox("Voor genereren automatisch passende diensttemplates maken")\n    private val autoFix = JCheckBox''')
replace_once(rel,
'''    private val templateModel = tableModel("Naam", "Type", "Tijd", "Dagen")''',
'''    private val templateModel = tableModel("Naam", "Type", "Tijd", "Dagen", "Bron")''')
replace_once(rel,
'''        warnSundays.isSelected = settings.warnMinimumFreeSundays\n        autoFix.isSelected''',
'''        warnSundays.isSelected = settings.warnMinimumFreeSundays\n        smartTemplates.isSelected = settings.autoGenerateSmartTemplates\n        autoFix.isSelected''')
replace_once(rel,
'''                "${template.start}-${template.end}",\n                weekdaySet(template.enabledWeekdays)''',
'''                "${template.start}-${template.end}",\n                weekdaySet(template.enabledWeekdays),\n                if (template.autoGenerated) "Slim" else "Handmatig"''')
replace_once(rel,
'''                hours.open,\n                hours.close,\n                if (hours.closed) "Ja" else "Nee"''',
'''                if (hours.isTwentyFourHours()) "24/7" else hours.open,\n                if (hours.isTwentyFourHours()) "24/7" else hours.close,\n                if (hours.closed) "Ja" else "Nee"''')
replace_once(rel,
'''                reducedRest,\n                warnSundays,\n                autoFix,''',
'''                reducedRest,\n                warnSundays,\n                smartTemplates,\n                autoFix,''')
replace_once(rel,
'''            add(primaryButton("Nieuw template") {\n                DesktopDialogs.template(this@RulesPanel)?.let(controller::addTemplate)\n            })''',
'''            add(primaryButton("Nieuw template") {\n                DesktopDialogs.template(this@RulesPanel)?.let(controller::addTemplate)\n            })\n            add(primaryButton("Slimme templates voorstellen") {\n                controller.refreshSmartTemplates()\n            })''')
replace_once(rel,
'''            warnMinimumFreeSundays = warnSundays.isSelected,\n            autoFixAfterManualChanges''',
'''            warnMinimumFreeSundays = warnSundays.isSelected,\n            autoGenerateSmartTemplates = smartTemplates.isSelected,\n            autoFixAfterManualChanges''')

# Main window: HR tab and v0.11 labels.
rel = 'desktopApp/src/main/kotlin/nl/roosterandroid/desktop/DesktopMain.kt'
replace_once(rel, 'JFrame("RoosterPlanner 0.10")', 'JFrame("RoosterPlanner 0.11")')
replace_once(rel,
'''        val operations = OperationsPanel(controller)\n        val rules = RulesPanel(controller)\n        val locations = LocationsPanel(controller)\n        panels = listOf(dashboard, schedule, team, operations, rules, locations)''',
'''        val operations = OperationsPanel(controller)\n        val administrationHr = AdministrationHrPanel(controller)\n        val rules = RulesPanel(controller)\n        val locations = LocationsPanel(controller)\n        panels = listOf(dashboard, schedule, team, operations, administrationHr, rules, locations)''')
replace_once(rel,
'''        tabs.addTab("Afwezig & bezetting", operations)\n        tabs.addTab("Diensten & regels", rules)''',
'''        tabs.addTab("Afwezig & bezetting", operations)\n        tabs.addTab("Administratie / HR", administrationHr)\n        tabs.addTab("Diensten & regels", rules)''')
replace_once(rel, 'add(JLabel("v0.10")', 'add(JLabel("v0.11")')
replace_once(rel, 'title = "RoosterPlanner 0.10 —', 'title = "RoosterPlanner 0.11 —')
replace_once(rel, '"RoosterPlanner 0.10\\nOffline Windows-roosterprogramma', '"RoosterPlanner 0.11\\nOffline Windows-roosterprogramma')

# Desktop build version.
replace_once('desktopApp/build.gradle.kts', 'version = "0.10.0"', 'version = "0.11.0"')

# Android UI exhaustive role whens so adding HOST remains build-compatible.
rel = 'app/src/main/java/nl/roosterandroid/app/MainActivity.kt'
replace_once(rel,
'''        val employee = Employee(
            name = clean,
            role = role,
            contractedDaysPerWeek = if (role == EmployeeRole.BORROWED) 3 else 5,
            contractedHoursPerWeek = if (role == EmployeeRole.BORROWED) 24.0 else 40.0,
            maxShiftsPerWeek = if (role == EmployeeRole.BORROWED) 3 else 5,
            canSetup = role != EmployeeRole.BORROWED
        )''',
'''        val borrowed = role == EmployeeRole.BORROWED
        val managerRole = role.countsAsManager()
        val employee = Employee(
            name = clean,
            role = role,
            contractedDaysPerWeek = if (borrowed) 3 else 5,
            contractedHoursPerWeek = if (borrowed) 24.0 else 40.0,
            maxShiftsPerWeek = if (borrowed) 3 else 5,
            canSetup = managerRole && !borrowed,
            canDay = true,
            canMiddle = true,
            canClose = managerRole,
            canKpi = managerRole
        )''')
replace_once(rel,
'''    EmployeeRole.BORROWED -> MatrixColors.BorrowedHeader\n    EmployeeRole.MANAGER -> MatrixColors.Header\n}''',
'''    EmployeeRole.BORROWED -> MatrixColors.BorrowedHeader\n    EmployeeRole.MANAGER -> MatrixColors.Header\n    EmployeeRole.HOST -> MatrixColors.Header\n}''')
replace_once(rel,
'''    EmployeeRole.BORROWED -> "Leenmanager"\n}''',
'''    EmployeeRole.BORROWED -> "Leenmanager"\n    EmployeeRole.HOST -> "Host(ess)"\n}''')

rel = 'app/src/main/java/nl/roosterandroid/app/RosterPdfExporter.kt'
replace_once(rel,
'''        EmployeeRole.BORROWED -> rgb("fff0b8")\n        EmployeeRole.MANAGER -> rgb("dde7f5")\n    }''',
'''        EmployeeRole.BORROWED -> rgb("fff0b8")\n        EmployeeRole.MANAGER -> rgb("dde7f5")\n        EmployeeRole.HOST -> rgb("e0f3e8")\n    }''')
replace_once(rel,
'''        EmployeeRole.BORROWED -> "Leen"\n    }''',
'''        EmployeeRole.BORROWED -> "Leen"\n        EmployeeRole.HOST -> "Host"\n    }''')

# Optional wording only.
rel = 'app/src/main/java/nl/roosterandroid/app/AdminScreen.kt'
replace_once(rel, 'AdminSectionTitle("Uren per manager")', 'AdminSectionTitle("Uren per medewerker")')

print('\nV0.11 source transformation completed.')
