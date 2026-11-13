package sh.edm.s1kiosk.esphome

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The panel's names in Home Assistant (#63). The friendly name is what people see and may change; the
 * node name is in every existing config entry and the mDNS service, and HA up to 2025.11 makes new
 * entity ids from it: it never changes.
 */
class PanelNameTest {
    @Test
    fun theNodeNameStays() = assertEquals("s1-panel", PanelDevice.NAME)

    @Test
    fun homeAssistantShowsGlassworksPanel() = assertEquals("Glassworks Panel", PanelDevice.FRIENDLY_NAME)
}
