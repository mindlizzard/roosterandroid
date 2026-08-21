package nl.roosterandroid.desktop

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.event.KeyEvent
import java.nio.file.Path
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import java.util.prefs.Preferences
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JTabbedPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.UIManager
import javax.swing.WindowConstants
import javax.swing.border.EmptyBorder
import javax.swing.filechooser.FileNameExtensionFilter

private val preferences = Preferences.userRoot().node("nl/roosterandroid/roosterplanner")

fun main() {
    System.setProperty("flatlaf.useWindowDecorations", "true")
    System.setProperty("awt.useSystemAAFontSettings", "on")
    val dark = preferences.getBoolean("darkMode", false)
    if (dark) FlatDarkLaf.setup() else FlatLightLaf.setup()
    UIManager.put("Component.arc", 12)
    UIManager.put("Button.arc", 12)
    UIManager.put("TextComponent.arc", 10)
    UIManager.put("ScrollBar.width", 13)
    UIManager.put("Table.rowHeight", 30)

    SwingUtilities.invokeLater {
        val storage = DesktopStorage()
        val controller = DesktopController(storage)
        MainWindow(controller, storage, dark).isVisible = true
    }
}

private data class LocationChoice(val id: String, val name: String) {
    override fun toString(): String = name
}

internal class MainWindow(
    private val controller: DesktopController,
    private val storage: DesktopStorage,
    private var darkMode: Boolean
) : JFrame("RoosterPlanner 0.10") {
    private val locale = Locale("nl", "NL")
    private val locationCombo = JComboBox<LocationChoice>()
    private val monthLabel = JLabel()
    private val statusLabel = JLabel("Klaar")
    private val busy = JProgressBar().apply {
        isIndeterminate = true
        isVisible = false
        preferredSize = Dimension(90, 8)
    }
    private val undoButton = secondaryButton("Ongedaan") { controller.undo() }
    private val redoButton = secondaryButton("Opnieuw") { controller.redo() }
    private val themeButton = secondaryButton(if (darkMode) "Lichte modus" else "Donkere modus") { toggleTheme() }
    private val tabs = JTabbedPane()
    private val panels: List<Refreshable>
    private var refreshingHeader = false
    private var taskRunning = false

    init {
        defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        minimumSize = Dimension(1120, 720)
        size = Dimension(1500, 900)
        setLocationRelativeTo(null)
        iconImage = createAppIcon()
        jMenuBar = createMenu()

        val dashboard = DashboardPanel(controller)
        val schedule = SchedulePanel(controller)
        val team = TeamPanel(controller)
        val operations = OperationsPanel(controller)
        val rules = RulesPanel(controller)
        val locations = LocationsPanel(controller)
        panels = listOf(dashboard, schedule, team, operations, rules, locations)

        tabs.putClientProperty("JTabbedPane.tabType", "card")
        tabs.putClientProperty("JTabbedPane.showTabSeparators", true)
        tabs.addTab("Overzicht", dashboard)
        tabs.addTab("Rooster", schedule)
        tabs.addTab("Team", team)
        tabs.addTab("Afwezig & bezetting", operations)
        tabs.addTab("Diensten & regels", rules)
        tabs.addTab("Vestigingen", locations)

        layout = BorderLayout()
        add(createHeader(), BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
        add(createStatusBar(), BorderLayout.SOUTH)

        locationCombo.addActionListener {
            if (!refreshingHeader) (locationCombo.selectedItem as? LocationChoice)?.let {
                controller.switchLocation(it.id)
            }
        }

        controller.addListener {
            SwingUtilities.invokeLater { refreshAll() }
        }
        installShortcuts()
        refreshAll()
    }

    private fun createHeader(): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor") ?: Color.LIGHT_GRAY),
            EmptyBorder(10, 14, 10, 14)
        )
        add(JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
            add(JLabel("RoosterPlanner").apply {
                font = font.deriveFont(Font.BOLD, 22f)
                foreground = UiColors.blue
            })
            add(JLabel("v0.10").apply { font = font.deriveFont(Font.BOLD, 11f) })
            add(locationCombo.apply { preferredSize = Dimension(220, 34) })
            add(secondaryButton("‹") { controller.changeMonth(-1) }.apply { preferredSize = Dimension(44, 34) })
            add(monthLabel.apply {
                horizontalAlignment = JLabel.CENTER
                font = font.deriveFont(Font.BOLD, 15f)
                preferredSize = Dimension(150, 34)
            })
            add(secondaryButton("›") { controller.changeMonth(1) }.apply { preferredSize = Dimension(44, 34) })
        }, BorderLayout.WEST)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            add(undoButton)
            add(redoButton)
            add(primaryButton("Genereer rooster") { runPlannerTask("Rooster genereren") { controller.generate() } })
            add(primaryButton("Auto-fix") { runPlannerTask("Auto-fix puzzelt verder") { controller.autoFix() } })
            add(themeButton)
        }, BorderLayout.EAST)
    }

    private fun createStatusBar(): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor") ?: Color.LIGHT_GRAY),
            EmptyBorder(6, 12, 6, 12)
        )
        add(statusLabel, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            add(busy)
            add(JLabel("Offline • automatisch opgeslagen"))
        }, BorderLayout.EAST)
    }

    private fun createMenu(): JMenuBar = JMenuBar().apply {
        add(JMenu("Bestand").apply {
            add(JMenuItem("Werkbestand importeren…").apply { addActionListener { importWorkspace() } })
            add(JMenuItem("Alle vestigingen exporteren…").apply { addActionListener { exportWorkspace() } })
            add(JMenuItem("Huidige vestiging exporteren…").apply { addActionListener { exportLocation() } })
            addSeparator()
            add(JMenuItem("Lokale gegevensmap openen").apply { addActionListener { openDataDirectory() } })
            addSeparator()
            add(JMenuItem("Afsluiten").apply { addActionListener { dispose() } })
        })
        add(JMenu("Bewerken").apply {
            add(JMenuItem("Ongedaan maken").apply {
                accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_Z, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
                addActionListener { controller.undo() }
            })
            add(JMenuItem("Opnieuw").apply {
                accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_Y, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
                addActionListener { controller.redo() }
            })
        })
        add(JMenu("Help").apply {
            add(JMenuItem("Over RoosterPlanner").apply {
                addActionListener {
                    JOptionPane.showMessageDialog(
                        this@MainWindow,
                        "RoosterPlanner 0.10\nOffline Windows-roosterprogramma\nATW-controle, Auto-fix en meerdere vestigingen",
                        "Over RoosterPlanner",
                        JOptionPane.INFORMATION_MESSAGE
                    )
                }
            })
        })
    }

    private fun refreshAll() {
        refreshingHeader = true
        val choices = controller.workspace.locations.map { LocationChoice(it.id, it.name) }
        locationCombo.model = DefaultComboBoxModel(choices.toTypedArray())
        locationCombo.selectedItem = choices.firstOrNull { it.id == controller.workspace.activeLocationId }
        val ym = YearMonth.of(controller.state.year, controller.state.month)
        monthLabel.text = ym.month.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.uppercase() } + " ${ym.year}"
        title = "RoosterPlanner 0.10 — ${controller.activeLocation.name} — ${monthLabel.text}"
        refreshingHeader = false
        statusLabel.text = controller.status
        undoButton.isEnabled = controller.canUndo && !taskRunning
        redoButton.isEnabled = controller.canRedo && !taskRunning
        panels.forEach { it.refresh() }
    }

    private fun runPlannerTask(label: String, task: () -> Unit) {
        if (taskRunning) return
        taskRunning = true
        busy.isVisible = true
        statusLabel.text = label
        setHeaderEnabled(false)
        object : SwingWorker<Unit, Unit>() {
            override fun doInBackground() = task()
            override fun done() {
                runCatching { get() }.onFailure { controller.showStatus("Bewerking mislukt: ${it.cause?.message ?: it.message}") }
                taskRunning = false
                busy.isVisible = false
                setHeaderEnabled(true)
                refreshAll()
            }
        }.execute()
    }

    private fun setHeaderEnabled(enabled: Boolean) {
        undoButton.isEnabled = enabled && controller.canUndo
        redoButton.isEnabled = enabled && controller.canRedo
        locationCombo.isEnabled = enabled
    }

    private fun importWorkspace() {
        val chooser = JFileChooser().apply {
            dialogTitle = "RoosterPlanner-bestand importeren"
            fileFilter = FileNameExtensionFilter("RoosterPlanner JSON", "json")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        if (JOptionPane.showConfirmDialog(
                this,
                "De huidige lokale werkruimte wordt vervangen. Er wordt automatisch eerst een back-up gemaakt.",
                "Werkbestand importeren",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
            ) != JOptionPane.OK_OPTION
        ) return
        runCatching { controller.importWorkspace(chooser.selectedFile.toPath()) }
            .onFailure { controller.showStatus("Import mislukt: ${it.message}") }
    }

    private fun exportWorkspace() {
        chooseJson("roosterplanner-alle-vestigingen.json")?.let { path ->
            runCatching { controller.exportWorkspace(path) }
                .onSuccess { controller.showStatus("Werkbestand geëxporteerd: ${path.fileName}") }
                .onFailure { controller.showStatus("Export mislukt: ${it.message}") }
        }
    }

    private fun exportLocation() {
        val safeName = controller.activeLocation.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        chooseJson("rooster-$safeName.json")?.let { path ->
            runCatching { controller.exportLocation(path) }
                .onSuccess { controller.showStatus("Vestiging geëxporteerd: ${path.fileName}") }
                .onFailure { controller.showStatus("Export mislukt: ${it.message}") }
        }
    }

    private fun chooseJson(defaultName: String): Path? {
        val chooser = JFileChooser().apply {
            fileFilter = FileNameExtensionFilter("JSON-bestand", "json")
            selectedFile = java.io.File(defaultName)
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return null
        val path = chooser.selectedFile.toPath()
        return if (path.fileName.toString().endsWith(".json", true)) path
        else path.resolveSibling("${path.fileName}.json")
    }

    private fun openDataDirectory() {
        runCatching {
            java.nio.file.Files.createDirectories(storage.dataDirectory)
            Desktop.getDesktop().open(storage.dataDirectory.toFile())
        }.onFailure { controller.showStatus("Gegevensmap kon niet worden geopend: ${it.message}") }
    }

    private fun toggleTheme() {
        darkMode = !darkMode
        preferences.putBoolean("darkMode", darkMode)
        if (darkMode) FlatDarkLaf.setup() else FlatLightLaf.setup()
        themeButton.text = if (darkMode) "Lichte modus" else "Donkere modus"
        SwingUtilities.updateComponentTreeUI(this)
        repaint()
    }

    private fun installShortcuts() {
        val mask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        rootPane.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, mask), "undo")
        rootPane.actionMap.put("undo", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) = controller.undo()
        })
        rootPane.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, mask), "redo")
        rootPane.actionMap.put("redo", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) = controller.redo()
        })
    }

    private fun createAppIcon(): java.awt.Image {
        javaClass.getResource("/roosterplanner.png")?.let { resource ->
            return Toolkit.getDefaultToolkit().getImage(resource)
        }
        val image = java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = UiColors.blue
        graphics.fillRoundRect(2, 2, 60, 60, 16, 16)
        graphics.color = Color.WHITE
        graphics.font = Font("SansSerif", Font.BOLD, 34)
        graphics.drawString("R", 20, 44)
        graphics.dispose()
        return image
    }
}
