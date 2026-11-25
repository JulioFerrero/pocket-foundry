package app.pocketfoundry.core.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test fun `Fast CPU is on by default only on a MediaTek chip, however old the Android`() {
        assertTrue(Settings.mediaTek(34, "Mediatek", "mt6895"))     // Xiaomi 12T
        assertFalse(Settings.mediaTek(34, "QTI", "qcom"))           // POCO F3 (#92)
        assertFalse(Settings.mediaTek(36, "Google", "tensor"))
        assertTrue(Settings.mediaTek(30, null, "mt6768"))
        assertFalse(Settings.mediaTek(30, null, "qcom"))
        assertFalse(Settings.mediaTek(29, null, null))
    }
}
