package nl.roosterandroid.desktop

import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.border.EmptyBorder
import javax.swing.table.DefaultTableModel

internal class LocationsPanel(private val controller: DesktopController) : JPanel(BorderLayout()), Refreshable {
    private val model = object : DefaultTableModel(arrayOf("Vestiging", "Actief", "Managers", "Diensten deze maand", "Restauranttijden"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val table = configuredTable(model)

    init {
        border = EmptyBorder(14, 14, 14, 14)
        add(panelTitle("Vestigingen"), BorderLayout.NORTH)
        add(JPanel(BorderLayout(0, 10)).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
                add(primaryButton("Nieuwe vestiging") { addLocation() })
                add(secondaryButton("Openen") { selectedLocation()?.let { controller.switchLocation(it.id) } })
                add(secondaryButton("Hernoemen") { renameLocation() })
                add(secondaryButton("Verwijderen") { deleteLocation() })
            }, BorderLayout.NORTH)
            add(tableScroll(table), BorderLayout.CENTER)
            add(JTextArea(
                "Elke vestiging heeft een eigen team, beschikbaarheid, diensttemplates, restauranttijden en roosterhistorie. " +
                    "Bij een nieuwe vestiging kun je de huidige team- en regelinstellingen kopiëren; roosters en afwezigheden worden nooit meegekopieerd."
            ).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                rows = 3
                border = EmptyBorder(10, 8, 4, 8)
                font = font.deriveFont(Font.PLAIN, 13f)
            }, BorderLayout.SOUTH)
        }, BorderLayout.CENTER)
        refresh()
    }

    override fun refresh() {
        model.rowCount = 0
        controller.workspace.locations.forEach { location ->
            val state = location.state
            val timeSummary = state.operatingHours.filterNot { it.closed }
                .groupBy { "${it.open}-${it.close}" }
                .entries.joinToString(" / ") { (time, days) -> "$time (${days.size} d)" }
            model.addRow(arrayOf(
                location.name,
                if (location.id == controller.workspace.activeLocationId) "Ja" else "",
                state.employees.count { it.active },
                state.assignments.size,
                timeSummary
            ))
        }
        val activeIndex = controller.workspace.locations.indexOfFirst { it.id == controller.workspace.activeLocationId }
        if (activeIndex >= 0) table.setRowSelectionInterval(activeIndex, activeIndex)
    }

    private fun addLocation() {
        val name = JTextField("", 22)
        val copy = JCheckBox("Kopieer team, templates en regels van huidige vestiging", true)
        val panel = JPanel(GridBagLayout()).apply {
            add(JLabel("Naam"), GridBagConstraints().apply {
                gridx = 0; gridy = 0; insets = Insets(4, 4, 4, 10); anchor = GridBagConstraints.WEST
            })
            add(name, GridBagConstraints().apply {
                gridx = 1; gridy = 0; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(4, 4, 4, 4)
            })
            add(copy, GridBagConstraints().apply {
                gridx = 0; gridy = 1; gridwidth = 2; anchor = GridBagConstraints.WEST; insets = Insets(8, 0, 2, 0)
            })
        }
        if (JOptionPane.showConfirmDialog(
                this,
                panel,
                "Nieuwe vestiging",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
            ) == JOptionPane.OK_OPTION
        ) {
            if (name.text.isBlank()) controller.showStatus("Vul een vestigingsnaam in")
            else controller.addLocation(name.text, copy.isSelected)
        }
    }

    private fun renameLocation() {
        val location = selectedLocation() ?: return
        val name = JOptionPane.showInputDialog(this, "Nieuwe naam", location.name) ?: return
        controller.renameLocation(location.id, name)
    }

    private fun deleteLocation() {
        val location = selectedLocation() ?: return
        if (JOptionPane.showConfirmDialog(
                this,
                "Vestiging '${location.name}' met alle lokale roosterdata verwijderen?",
                "Vestiging verwijderen",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            ) == JOptionPane.YES_OPTION
        ) controller.deleteLocation(location.id)
    }

    private fun selectedLocation(): LocationWorkspace? {
        val row = selectedModelRow(table) ?: return null
        return controller.workspace.locations.getOrNull(row)
    }
}
