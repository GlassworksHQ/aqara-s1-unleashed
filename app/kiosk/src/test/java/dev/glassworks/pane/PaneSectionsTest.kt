package dev.glassworks.pane

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.esphome.ButtonEntity
import sh.edm.esphome.EntityCategory
import sh.edm.esphome.EventEntity
import sh.edm.esphome.SensorEntity
import sh.edm.esphome.TextSensorEntity

/** #212: every pane's page shows its state. None says it has no settings; an off pane says what off means. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PaneSectionsTest {
    private val file = RuntimeEnvironment.getApplication().getSharedPreferences("pane-sections", Context.MODE_PRIVATE)
        .also { it.edit().clear().commit() }

    private val pane = object : Pane {
        override val id = "lamp"
        var watts: Float? = 12.4f
        var extra = false
        override fun entities() = listOfNotNull(
            SensorEntity("lamp_power", "Lamp power", unit = "W", accuracy = 1, get = { watts }),
            if (extra) TextSensorEntity("lamp_relay", "Lamp relay", get = { "on" }) else null,
            TextSensorEntity("lamp_firmware", "Lamp firmware", category = EntityCategory.DIAGNOSTIC, disabledByDefault = true, get = { "1.2" }),
            ButtonEntity("lamp_reset", "Reset lamp", press = {}),
            EventEntity("lamp_button", "Lamp button", eventTypes = listOf("single")),
        )
    }

    private val switch = PaneSwitch(file, "lamp_enabled", default = true, title = "Lamp", detail = "A lamp", objectId = "lamp", offNote = "The lamp is off.")

    private fun definition(own: (Pane?) -> List<SettingsSection> = { emptyList() }) = object : PaneDefinition {
        override val id = "lamp"
        override val switch = this@PaneSectionsTest.switch
        override fun settings(running: Pane?) = own(running)
    }

    private fun rows(s: SettingsSection) = s.items.filterIsInstance<SettingsItem.States>().single().rows().map { Triple(it.name, it.value, it.offByDefault) }

    @Test fun aPaneWithoutAPageShowsItsEntitiesAsHomeAssistantDoes() {
        val s = paneSections(listOf(definition())) { pane }.single()
        assertEquals("Lamp", s.master!!.title)
        assertEquals("buttons and events have no state", listOf(
            Triple("Lamp power", "12.4 W", false),
            Triple("Lamp firmware", "1.2", true),
        ), rows(s))
    }

    @Test fun theRowsAreReadAgain() {
        val s = paneSections(listOf(definition())) { pane }.single()
        pane.watts = null
        assertEquals(UNKNOWN, rows(s).first().second)
        pane.watts = 3f
        assertEquals("3.0 W", rows(s).first().second)
    }

    @Test fun entitiesThePaneAddsWhileThePageIsOpenShowUp() {
        // CodeRabbit on #235: a base mounted while the page was open kept the old list.
        val s = paneSections(listOf(definition())) { pane }.single()
        pane.extra = true
        assertEquals(listOf("Lamp power", "Lamp relay", "Lamp firmware"), rows(s).map { it.first })
    }

    @Test fun anOffPaneSaysWhatOffMeansAndItsEntitiesAreUnavailable() {
        switch.on = false
        val s = paneSections(listOf(definition { listOf(SettingsSection("lamp", "Lamp", listOf(SettingsItem.Note { "its own page" }))) })) { pane }.single()
        assertEquals("The lamp is off.", (s.items.first() as SettingsItem.Note).text())
        assertEquals(listOf(UNAVAILABLE, UNAVAILABLE), rows(s).map { it.second })
        assertEquals("its own page waits until the pane is on", 2, s.items.size)
        assertEquals(false, s.master!!.value())
    }

    @Test fun withNoDeviceRunningThePageSaysSo() {
        val s = paneSections(listOf(definition())) { null }.single()
        assertEquals(NOT_RUNNING, (s.items.single() as SettingsItem.Note).text())
    }

    @Test fun aPanesOwnPageGetsTheRunningPaneAndTheMaster() {
        var got: Pane? = null
        val s = paneSections(listOf(definition { p -> got = p; listOf(SettingsSection("lamp", "Lamp", listOfNotNull(inHomeAssistant(p)))) })) { pane }.single()
        assertSame(pane, got)
        assertEquals("Lamp", s.master!!.title)
        assertEquals(2, rows(s).size)
    }

    @Test fun aPaneWithNothingToShowAndNoSwitchHasNoPage() {
        val bare = object : PaneDefinition { override val id = "bare" }
        assertEquals(emptyList<SettingsSection>(), paneSections(listOf(bare)) { null })
        assertNull(inHomeAssistant(object : Pane { override val id = "none" }))
    }
}
