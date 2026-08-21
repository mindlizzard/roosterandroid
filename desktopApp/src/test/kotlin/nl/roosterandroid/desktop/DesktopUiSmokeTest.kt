package nl.roosterandroid.desktop

import com.formdev.flatlaf.FlatLightLaf
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import javax.swing.SwingUtilities

class DesktopUiSmokeTest {
    @Test
    fun allMainPanelsConstructAndRefreshHeadless() {
        System.setProperty("java.awt.headless", "true")
        FlatLightLaf.setup()
        val controller = DesktopController(
            DesktopStorage(Files.createTempDirectory("roosterplanner-ui-"))
        )
        var panelCount = 0

        SwingUtilities.invokeAndWait {
            val panels = listOf(
                DashboardPanel(controller),
                SchedulePanel(controller),
                TeamPanel(controller),
                OperationsPanel(controller),
                RulesPanel(controller),
                LocationsPanel(controller)
            )
            panels.forEach(Refreshable::refresh)
            panelCount = panels.size
        }

        assertEquals(6, panelCount)
    }
}
