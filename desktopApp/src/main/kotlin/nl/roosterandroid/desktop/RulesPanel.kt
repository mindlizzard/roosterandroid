package nl.roosterandroid.desktop

import nl.roosterandroid.app.OperatingHours
import nl.roosterandroid.app.PlannerSettings
import nl.roosterandroid.app.ShiftTemplate
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.SpinnerNumberModel
import javax.swing.border.EmptyBorder
import javax.swing.table.DefaultTableModel

internal class RulesPanel(private val controller: DesktopController) : JPanel(BorderLayout()), Refreshable {
    private val requireSetup = JCheckBox("Elke geopende dag een setupmanager")
    private val requireClose = JCheckBox("Elke geopende dag een sluitmanager")
    private val requireMiddle = JCheckBox("Tussenmanager op ingestelde drukke dagen")
    private val traineeCoverage = JCheckBox("Trainee nooit zonder ervaren manager")
    private val minimizeBorrowed = JCheckBox("Minimaliseer leenmanagers")
    private val preferTwoDays = JCheckBox("Voorkeur voor twee dagen achter elkaar vrij")
    private val atw = JCheckBox("ATW-controle actief")
    private val reducedRest = JCheckBox("Maximaal één verkorte dagelijkse rust per 7 dagen toestaan")
    private val hardConsecutive = JCheckBox("Maximum opeenvolgende werkdagen is een harde regel")
    private val warnSundays = JCheckBox("Waarschuw bij te weinig vrije zondagen")
    private val autoFix = JCheckBox("Na elke handmatige wijziging automatisch opnieuw puzzelen")
    private val protectManual = JCheckBox("Handmatig vastgezette diensten zoveel mogelijk beschermen")
    private val maxConsecutive = JSpinner(SpinnerNumberModel(6, 1, 12, 1))
    private val minOffBlocks = JSpinner(SpinnerNumberModel(1, 0, 4, 1))
    private val preferredOffBlocks = JSpinner(SpinnerNumberModel(2, 0, 6, 1))
    private val monthEndManagers = JSpinner(SpinnerNumberModel(2, 1, 6, 1))
    private val templateModel = tableModel("Naam", "Type", "Tijd", "Dagen")
    private val templateTable = configuredTable(templateModel)
    private val hoursModel = tableModel("Dag", "Open / start", "Dicht / einde", "Gesloten")
    private val hoursTable = configuredTable(hoursModel)

    init {
        border = EmptyBorder(14, 14, 14, 14)
        add(panelTitle("Diensten en roosterregels"), BorderLayout.NORTH)
        val tabs = JTabbedPane().apply {
            putClientProperty("JTabbedPane.tabType", "card")
            addTab("Roosterregels", settingsTab())
            addTab("Diensttemplates", templatesTab())
            addTab("Restauranttijden", hoursTab())
            addTab("ATW-uitleg", atwTab())
        }
        add(tabs, BorderLayout.CENTER)
        refresh()
    }

    override fun refresh() {
        val settings = controller.state.settings
        requireSetup.isSelected = settings.requireSetupDaily
        requireClose.isSelected = settings.requireCloseDaily
        requireMiddle.isSelected = settings.requireMiddleOnBusyDays
        traineeCoverage.isSelected = settings.traineeMustHaveExperiencedManager
        minimizeBorrowed.isSelected = settings.minimizeBorrowedManagers
        preferTwoDays.isSelected = settings.preferTwoConsecutiveDaysOff
        atw.isSelected = settings.atwEnabled
        reducedRest.isSelected = settings.allowOneReducedDailyRestPer7Days
        hardConsecutive.isSelected = settings.treatMaxConsecutiveDaysAsHardRule
        warnSundays.isSelected = settings.warnMinimumFreeSundays
        autoFix.isSelected = settings.autoFixAfterManualChanges
        protectManual.isSelected = settings.protectManualAssignmentsDuringAutoFix
        maxConsecutive.value = settings.maxConsecutiveWorkDays
        minOffBlocks.value = settings.minimumTwoDayOffBlocks
        preferredOffBlocks.value = settings.preferredTwoDayOffBlocks
        monthEndManagers.value = settings.monthEndCloseManagers

        templateModel.rowCount = 0
        controller.state.shiftTemplates.forEach { template ->
            templateModel.addRow(arrayOf(
                template.name,
                shiftKindLabel(template.kind),
                "${template.start}-${template.end}",
                weekdaySet(template.enabledWeekdays)
            ))
        }
        hoursModel.rowCount = 0
        currentHours().forEach { hours ->
            hoursModel.addRow(arrayOf(
                weekdayName(hours.weekday),
                hours.open,
                hours.close,
                if (hours.closed) "Ja" else "Nee"
            ))
        }
    }

    private fun settingsTab(): JPanel {
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = EmptyBorder(14, 14, 14, 14)
            listOf(
                atw,
                requireSetup,
                requireClose,
                requireMiddle,
                traineeCoverage,
                minimizeBorrowed,
                preferTwoDays,
                hardConsecutive,
                reducedRest,
                warnSundays,
                autoFix,
                protectManual
            ).forEach { checkbox ->
                checkbox.alignmentX = LEFT_ALIGNMENT
                add(checkbox)
                add(Box.createVerticalStrut(5))
            }
            add(Box.createVerticalStrut(8))
            add(spinnerRow("Maximum opeenvolgende werkdagen", maxConsecutive))
            add(spinnerRow("Minimaal aantal blokken van 2 vrije dagen", minOffBlocks))
            add(spinnerRow("Gewenst aantal blokken van 2 vrije dagen", preferredOffBlocks))
            add(spinnerRow("Sluitmanagers bij maandsluiting", monthEndManagers))
            add(Box.createVerticalStrut(14))
            add(primaryButton("Roosterregels opslaan") { saveSettings() }.apply { alignmentX = LEFT_ALIGNMENT })
            add(Box.createVerticalGlue())
        }
        return JPanel(BorderLayout()).apply {
            add(JScrollPane(content).apply { border = BorderFactory.createEmptyBorder() }, BorderLayout.CENTER)
        }
    }

    private fun templatesTab(): JPanel = JPanel(BorderLayout(0, 8)).apply {
        border = EmptyBorder(12, 4, 4, 4)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            add(primaryButton("Nieuw template") {
                DesktopDialogs.template(this@RulesPanel)?.let(controller::addTemplate)
            })
            add(secondaryButton("Wijzigen") {
                selectedTemplate()?.let { template ->
                    DesktopDialogs.template(this@RulesPanel, template)?.let(controller::updateTemplate)
                }
            })
            add(secondaryButton("Dupliceren") { selectedTemplate()?.let(controller::duplicateTemplate) })
            add(secondaryButton("Verwijderen") {
                selectedTemplate()?.let { template ->
                    if (JOptionPane.showConfirmDialog(
                            this@RulesPanel,
                            "Template '${template.name}' verwijderen?",
                            "Diensttemplate verwijderen",
                            JOptionPane.YES_NO_OPTION
                        ) == JOptionPane.YES_OPTION
                    ) controller.removeTemplate(template.id)
                }
            })
        }, BorderLayout.NORTH)
        add(tableScroll(templateTable), BorderLayout.CENTER)
        add(JLabel("Je kunt onbeperkt eigen diensten maken. Een eindtijd na middernacht, zoals 01:00, wordt correct berekend."), BorderLayout.SOUTH)
    }

    private fun hoursTab(): JPanel = JPanel(BorderLayout(0, 8)).apply {
        border = EmptyBorder(12, 4, 4, 4)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            add(primaryButton("Tijden wijzigen") {
                selectedHours()?.let { DesktopDialogs.operatingHours(this@RulesPanel, it) }
                    ?.let(controller::updateOperatingHours)
            })
            add(secondaryButton("Sluitdiensten automatisch bijwerken") {
                if (JOptionPane.showConfirmDialog(
                        this@RulesPanel,
                        "De sluittemplates worden per sluitingstijd opnieuw opgebouwd. Bestaande sluitdiensten worden gekoppeld aan de nieuwe tijden.",
                        "Sluitdiensten bijwerken",
                        JOptionPane.OK_CANCEL_OPTION
                    ) == JOptionPane.OK_OPTION
                ) controller.alignClosingTemplatesWithOperatingHours()
            })
        }, BorderLayout.NORTH)
        add(tableScroll(hoursTable), BorderLayout.CENTER)
        add(JLabel("Vestigingen mogen andere openingstijden hebben; vrijdag en zaterdag kunnen dus langer open zijn."), BorderLayout.SOUTH)
    }

    private fun atwTab(): JPanel = JPanel(BorderLayout()).apply {
        border = EmptyBorder(18, 18, 18, 18)
        add(JTextArea(
            """
            De planner controleert de algemene Nederlandse Arbeidstijdenwet voor werknemers van 18 jaar en ouder.

            In versie 0.10 worden onder andere gecontroleerd:
            • maximaal 12 uur per dienst;
            • maximaal 60 uur per week;
            • gemiddeld maximaal 55 uur over 4 weken en 48 uur over 16 weken;
            • dagelijkse rust, wekelijkse rust en rust na nachtdiensten;
            • aantallen nachtdiensten, vrije zondagen en opeenvolgende werkdagen;
            • overlap, beschikbaarheid en diensten over middernacht;
            • historie uit de vorige en volgende maand bij grenscontroles.

            De bedrijfsregel kan strenger staan dan de wettelijke standaard. Voor jouw rooster blijft minimaal 12 uur rust als veilige bedrijfsinstelling bruikbaar via de bestaande templates en beschikbaarheidsregels. CAO-, jeugd-, zwangerschap- en sectorspecifieke uitzonderingen kunnen aanvullende eisen geven.

            Auto-fix probeert handmatige wijzigingen te behouden. Alleen wanneer het rooster anders niet herstelbaar is, worden conflicterende handmatige diensten één voor één losgelaten en opnieuw ingepland.
            """.trimIndent()
        ).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = font.deriveFont(Font.PLAIN, 14f)
            border = EmptyBorder(12, 12, 12, 12)
        }, BorderLayout.CENTER)
    }

    private fun saveSettings() {
        val old = controller.state.settings
        controller.updateSettings(old.copy(
            requireSetupDaily = requireSetup.isSelected,
            requireCloseDaily = requireClose.isSelected,
            requireMiddleOnBusyDays = requireMiddle.isSelected,
            traineeMustHaveExperiencedManager = traineeCoverage.isSelected,
            minimizeBorrowedManagers = minimizeBorrowed.isSelected,
            preferTwoConsecutiveDaysOff = preferTwoDays.isSelected,
            atwEnabled = atw.isSelected,
            allowOneReducedDailyRestPer7Days = reducedRest.isSelected,
            treatMaxConsecutiveDaysAsHardRule = hardConsecutive.isSelected,
            warnMinimumFreeSundays = warnSundays.isSelected,
            autoFixAfterManualChanges = autoFix.isSelected,
            protectManualAssignmentsDuringAutoFix = protectManual.isSelected,
            maxConsecutiveWorkDays = maxConsecutive.value as Int,
            minimumTwoDayOffBlocks = minOffBlocks.value as Int,
            preferredTwoDayOffBlocks = preferredOffBlocks.value as Int,
            monthEndCloseManagers = monthEndManagers.value as Int
        ))
    }

    private fun selectedTemplate(): ShiftTemplate? {
        val row = selectedModelRow(templateTable) ?: return null
        return controller.state.shiftTemplates.getOrNull(row)
    }

    private fun selectedHours(): OperatingHours? {
        val row = selectedModelRow(hoursTable) ?: return currentHours().firstOrNull()
        return currentHours().getOrNull(row)
    }

    private fun currentHours(): List<OperatingHours> = controller.state.operatingHours.sortedBy { it.weekday }

    private fun spinnerRow(label: String, spinner: JSpinner): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, 36)
        add(JLabel(label).apply { preferredSize = Dimension(330, 28) })
        spinner.preferredSize = Dimension(75, 30)
        add(spinner)
    }

    private fun weekdaySet(days: Set<Int>): String = days.sorted().joinToString(" ") { weekdayName(it).take(2) }
    private fun weekdayName(day: Int): String = listOf("Maandag", "Dinsdag", "Woensdag", "Donderdag", "Vrijdag", "Zaterdag", "Zondag")[day.coerceIn(1, 7) - 1]
}

private fun tableModel(vararg columns: String): DefaultTableModel = object : DefaultTableModel(columns, 0) {
    override fun isCellEditable(row: Int, column: Int): Boolean = false
}
