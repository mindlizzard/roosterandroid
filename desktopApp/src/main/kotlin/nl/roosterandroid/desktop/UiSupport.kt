package nl.roosterandroid.desktop

import nl.roosterandroid.app.AbsenceType
import nl.roosterandroid.app.EmployeeRole
import nl.roosterandroid.app.PersonMarkerType
import nl.roosterandroid.app.ResponsibilityRule
import nl.roosterandroid.app.ResponsibilityType
import nl.roosterandroid.app.ShiftKind
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.border.EmptyBorder
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

internal object UiColors {
    val blue = Color(0x1F, 0x6F, 0xE5)
    val blueSoft = Color(0xE8, 0xF1, 0xFF)
    val green = Color(0x25, 0x8B, 0x57)
    val greenSoft = Color(0xE3, 0xF4, 0xE8)
    val orange = Color(0xC4, 0x70, 0x12)
    val orangeSoft = Color(0xFF, 0xED, 0xD5)
    val purpleSoft = Color(0xEB, 0xE3, 0xFA)
    val red = Color(0xB9, 0x2B, 0x33)
    val redSoft = Color(0xFA, 0xDF, 0xE0)
    val yellowSoft = Color(0xFF, 0xF4, 0xCF)
    val cyanSoft = Color(0xDF, 0xF3, 0xF1)
    val graySoft = Color(0xF2, 0xF4, 0xF7)
    val grayWeekend = Color(0xE8, 0xEB, 0xEF)
}

internal fun primaryButton(text: String, action: () -> Unit): JButton = JButton(text).apply {
    putClientProperty("JButton.buttonType", "roundRect")
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    addActionListener { action() }
}

internal fun secondaryButton(text: String, action: () -> Unit): JButton = JButton(text).apply {
    putClientProperty("JButton.buttonType", "roundRect")
    isFocusPainted = false
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    addActionListener { action() }
}

internal fun panelTitle(text: String): JLabel = JLabel(text).apply {
    font = font.deriveFont(Font.BOLD, 20f)
    border = EmptyBorder(4, 2, 8, 2)
}

internal fun sectionPanel(title: String): JPanel = JPanel(GridBagLayout()).apply {
    border = BorderFactory.createCompoundBorder(
        BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor") ?: Color.LIGHT_GRAY),
        EmptyBorder(12, 12, 12, 12)
    )
    putClientProperty("FlatLaf.style", "arc: 14")
    add(
        JLabel(title).apply { font = font.deriveFont(Font.BOLD, 15f) },
        GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            gridwidth = 2
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
            insets = Insets(0, 0, 10, 0)
        }
    )
}

internal fun GridBagConstraints.nextRow(
    row: Int,
    column: Int = 0,
    weight: Double = if (column == 0) 0.0 else 1.0
): GridBagConstraints = GridBagConstraints().also {
    it.gridx = column
    it.gridy = row
    it.weightx = weight
    it.fill = GridBagConstraints.HORIZONTAL
    it.anchor = GridBagConstraints.WEST
    it.insets = Insets(4, if (column == 0) 0 else 8, 4, 0)
}

internal fun configuredTable(model: DefaultTableModel): JTable = JTable(model).apply {
    autoCreateRowSorter = true
    setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
    rowHeight = 30
    fillsViewportHeight = true
    tableHeader.reorderingAllowed = false
    showVerticalLines = false
    intercellSpacing = Dimension(0, 1)
}

internal fun tableScroll(table: JTable): JScrollPane = JScrollPane(table).apply {
    border = BorderFactory.createEmptyBorder()
    viewport.background = table.background
}

internal fun selectedModelRow(table: JTable): Int? {
    val row = table.selectedRow
    return if (row < 0) null else table.convertRowIndexToModel(row)
}

internal open class CenterRenderer : DefaultTableCellRenderer() {
    init {
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
        border = EmptyBorder(3, 5, 3, 5)
    }
}

internal fun htmlEscape(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

internal fun roleLabel(role: EmployeeRole): String = when (role) {
    EmployeeRole.MANAGER -> "Manager"
    EmployeeRole.RM -> "Restaurant Manager"
    EmployeeRole.TRAINEE -> "Trainee"
    EmployeeRole.BORROWED -> "Leenmanager"
}

internal fun shiftKindLabel(kind: ShiftKind): String = when (kind) {
    ShiftKind.SETUP -> "SETUP"
    ShiftKind.DAY -> "DAG"
    ShiftKind.MIDDLE -> "TUSSEN"
    ShiftKind.CLOSE -> "SLUIT"
    ShiftKind.KPI -> "KPI"
    ShiftKind.CUSTOM -> "EIGEN"
}

internal fun absenceLabel(type: AbsenceType): String = when (type) {
    AbsenceType.VACATION -> "Vakantie"
    AbsenceType.SNIPPER_DAY -> "Snipperdag"
    AbsenceType.LEAVE -> "Verlof"
    AbsenceType.SPECIAL_LEAVE -> "Bijzonder verlof"
    AbsenceType.UNPAID_LEAVE -> "Onbetaald verlof"
    AbsenceType.COMP_TIME -> "Tijd voor tijd"
    AbsenceType.SICK -> "Ziek"
    AbsenceType.MATERNITY -> "Zwangerschapsverlof"
    AbsenceType.ADAPTED_WORK -> "Aangepast werk"
    AbsenceType.TRAINING -> "Training"
    AbsenceType.OTHER -> "Overig"
}

internal fun responsibilityLabel(type: ResponsibilityType): String = when (type) {
    ResponsibilityType.WEEK_COUNT -> "Weektelling"
    ResponsibilityType.MONTH_COUNT -> "Maandtelling"
    ResponsibilityType.MAINTENANCE -> "Onderhoud"
    ResponsibilityType.ADMIN -> "Administratie"
    ResponsibilityType.KPI -> "KPI"
    ResponsibilityType.HACCP -> "HACCP"
    ResponsibilityType.STOCK -> "Voorraad"
    ResponsibilityType.HAVI -> "HAVI"
    ResponsibilityType.TRAINING -> "Training"
    ResponsibilityType.MEETING -> "Meeting"
    ResponsibilityType.OFFICE -> "Kantoor"
    ResponsibilityType.INTERVIEW -> "Sollicitatie"
    ResponsibilityType.CREW_PLANNING -> "Crewplanning"
    ResponsibilityType.CUSTOM -> "Eigen taak"
}

internal fun responsibilityShort(rule: ResponsibilityRule): String =
    rule.label.ifBlank {
        when (rule.type) {
            ResponsibilityType.WEEK_COUNT -> "WT"
            ResponsibilityType.MONTH_COUNT -> "MT"
            ResponsibilityType.MAINTENANCE -> "OND"
            ResponsibilityType.ADMIN -> "ADM"
            ResponsibilityType.KPI -> "KPI"
            ResponsibilityType.HACCP -> "HAC"
            ResponsibilityType.STOCK -> "VRD"
            ResponsibilityType.HAVI -> "HAVI"
            ResponsibilityType.TRAINING -> "TR"
            ResponsibilityType.MEETING -> "MTG"
            ResponsibilityType.OFFICE -> "KTR"
            ResponsibilityType.INTERVIEW -> "SOL"
            ResponsibilityType.CREW_PLANNING -> "PLN"
            ResponsibilityType.CUSTOM -> "TAK"
        }
    }.take(8).uppercase()

internal fun markerLabel(type: PersonMarkerType): String = when (type) {
    PersonMarkerType.PRESENT -> "Aanwezig"
    PersonMarkerType.OFFICE -> "Kantoor"
    PersonMarkerType.TRAINING -> "Training"
    PersonMarkerType.MEETING -> "Meeting"
    PersonMarkerType.MAINTENANCE -> "Onderhoud"
    PersonMarkerType.ADMIN -> "Administratie"
    PersonMarkerType.OTHER -> "Overig"
}

internal fun setComponentEnabled(component: Component, enabled: Boolean) {
    component.isEnabled = enabled
    if (component is JPanel) component.components.forEach { setComponentEnabled(it, enabled) }
}
