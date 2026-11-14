/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.app.Application
import android.content.Context
import android.view.View
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SetupCard
import dev.glassworks.pane.SetupHost
import dev.glassworks.pane.paneSections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The camera's setup card and step, and its page's controls, on a panel with a camera (#117). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CameraSetupTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val file get() = app.getSharedPreferences("camera-setup-test", Context.MODE_PRIVATE)

    @After fun tearDown() { file.edit().clear().commit() }

    private class Card : SetupCard {
        var status: Pair<String?, SetupCard.Tone>? = null
        var primaryText = ""; var primary: (() -> Unit)? = null; var secondary: (() -> Unit)? = null
        override fun title(text: String) {}
        override fun body(text: String?) {}
        override fun status(text: String?, tone: SetupCard.Tone) { status = text to tone }
        override fun qr(url: String?) {}
        override fun content(view: View?) {}
        override fun primary(text: String, enabled: Boolean, onClick: () -> Unit) { primaryText = text; primary = onClick }
        override fun secondary(text: String?, onClick: () -> Unit) { secondary = if (text == null) null else onClick }
        override fun tightButtons() {}
    }

    private class Picker(context: Context) : CameraUsePicker {
        override val view = View(context)
        override var onPick: ((CameraUse) -> Unit)? = null
        var shown: CameraUse? = null
        override fun bind(chosen: CameraUse, enabled: Boolean) { shown = chosen }
    }

    private inner class Rig(var allowed: Boolean = false) {
        var picker: Picker? = null
        val def = CameraDefinition(file, hasCamera = true, granted = { allowed }, pickers = { c -> Picker(c).also { picker = it } })
        var next = 0
        fun host(preview: Boolean = false) = object : SetupHost {
            override val preview = preview
            override fun redraw() {}
            override fun next() { this@Rig.next++ }
        }
    }

    @Test fun aPanelWithACameraHasACardAndAStepTheS1HasNeither() {
        val r = Rig()
        assertEquals("Camera", r.def.setupChoice?.title)
        assertEquals(listOf("camera"), r.def.setupSteps.map { it.id })
        val s1 = CameraDefinition(file, hasCamera = false)
        assertNull(s1.setupChoice)
        assertTrue(s1.setupSteps.isEmpty())
    }

    @Test fun theStepIsDoneOncePickedAndAllowedOrWhenTheCameraIsOff() {
        val r = Rig()
        val step = r.def.setupSteps.single()
        assertTrue("the card is off by default: nothing to ask", step.done())
        r.def.switch.on = true
        assertFalse(step.done())
        r.def.prefs.use = CameraUse.PRESENCE
        assertFalse("not allowed yet", step.done())
        r.allowed = true
        assertTrue(step.done())
    }

    @Test fun allowAsksAndKeepsTheUseThenARefusalOffersToTurnItOff() {
        val r = Rig()
        r.def.switch.on = true
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val screen = r.def.setupSteps.single().screen(activity, r.host())
        val card = Card()
        screen.bind(card, entered = true)
        assertEquals(CameraSetupStep.ALLOW, card.primaryText)
        assertNull(card.secondary)
        r.picker!!.onPick!!(CameraUse.PRESENCE_AND_SNAPSHOTS)
        card.primary!!()
        assertEquals("the picked use is kept", CameraUse.PRESENCE_AND_SNAPSHOTS, r.def.prefs.use)
        val asked = shadowOf(activity).lastRequestedPermission
        assertEquals(listOf(android.Manifest.permission.CAMERA), asked.requestedPermissions.toList())
        screen.bind(card, entered = false)
        assertEquals(SetupCard.Tone.ROSE, card.status?.second)
        card.secondary!!()
        assertFalse("Turn the camera off", r.def.switch.on)
        assertEquals(1, r.next)
    }

    @Test fun onceAllowedItContinues() {
        val r = Rig(allowed = true)
        r.def.switch.on = true
        val screen = r.def.setupSteps.single().screen(app, r.host())
        val card = Card()
        screen.bind(card, entered = true)
        assertEquals("Continue", card.primaryText)
        card.primary!!()
        assertTrue(r.def.prefs.useChosen)
        assertEquals(1, r.next)
    }

    @Test fun aPreviewChangesNothing() {
        val r = Rig()
        r.def.switch.on = true
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val screen = r.def.setupSteps.single().screen(activity, r.host(preview = true))
        val card = Card()
        screen.bind(card, entered = true)
        r.picker!!.onPick!!(CameraUse.PRESENCE_AND_SNAPSHOTS)
        card.primary!!()
        assertFalse(r.def.prefs.useChosen)
        assertNull("Android isn't asked", shadowOf(activity).lastRequestedPermission)
    }

    @Test fun thePageRefusesTheStreamInPresenceOnly() {
        val r = Rig(allowed = true)
        r.def.switch.on = true
        val page = paneSections(listOf(r.def)).single()
        assertEquals("the core lists every entity: the page covers none", emptySet<String>(), page.covers)
        val stream = page.items.filterIsInstance<SettingsItem.Group>().single { it.title == "ONVIF stream" }.items
            .filterIsInstance<SettingsItem.Switch>().single()
        var why: String? = "unset"
        stream.save(true) { why = it }
        assertEquals(CameraPage.NEEDS_SNAPSHOTS, why)
        assertFalse(r.def.prefs.onvif)
        r.def.prefs.use = CameraUse.PRESENCE_AND_SNAPSHOTS
        stream.save(true) { why = it }
        assertNull(why)
        assertTrue(r.def.prefs.onvif)
        assertNotNull(page.items.filterIsInstance<SettingsItem.Native>().singleOrNull { it.key == CameraDefinition.USE_KEY })
    }

    @Test fun withoutCardsTheUseIsAList() {
        val def = CameraDefinition(file, hasCamera = true)
        def.switch.on = true
        val use = paneSections(listOf(def)).single().items.filterIsInstance<SettingsItem.Choice>().single()
        use.save(CameraUse.PRESENCE_AND_SNAPSHOTS.name) {}
        assertEquals(CameraUse.PRESENCE_AND_SNAPSHOTS, def.prefs.use)
    }

    @Test fun aPreviewsTurnTheCameraOffChangesNothing() {
        val r = Rig()
        r.def.switch.on = true
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val screen = r.def.setupSteps.single().screen(activity, r.host(preview = true))
        val card = Card()
        screen.bind(card, entered = true)
        card.primary!!() // Allow: a preview walks on to the refusal without asking Android
        screen.bind(card, entered = false)
        assertNull(shadowOf(activity).lastRequestedPermission)
        card.secondary!!()
        assertTrue("the pane stays on", r.def.switch.on)
        assertEquals(1, r.next)
    }

    @Test fun presenceOnlyClearsTheStreamAndSnapshotsDoesntBringItBack() {
        val r = Rig(allowed = true)
        r.def.prefs.use = CameraUse.PRESENCE_AND_SNAPSHOTS
        r.def.prefs.onvif = true
        r.def.prefs.use = CameraUse.PRESENCE
        assertFalse(r.def.prefs.onvif)
        r.def.prefs.use = CameraUse.PRESENCE_AND_SNAPSHOTS
        assertFalse("the ports open only with a tap", r.def.prefs.onvif)
    }

    @Test fun underPrivacyTheStreamIsRefusedForPrivacy() {
        val r = Rig(allowed = true)
        r.def.switch.on = true
        r.def.prefs.use = CameraUse.PRESENCE_AND_SNAPSHOTS
        r.def.prefs.privacy = true
        val stream = paneSections(listOf(r.def)).single().items.filterIsInstance<SettingsItem.Group>().single { it.title == "ONVIF stream" }.items
            .filterIsInstance<SettingsItem.Switch>().single()
        assertEquals(CameraPage.PRIVACY_ON, stream.detail)
        var why: String? = null
        stream.save(true) { why = it }
        assertEquals(CameraPage.PRIVACY_ON, why)
        assertFalse(r.def.prefs.onvif)
    }

    @Test fun anUnsetSignInIsAmberOnThePage() {
        val r = Rig(allowed = true)
        r.def.switch.on = true
        val signIn = paneSections(listOf(r.def)).single().items.filterIsInstance<SettingsItem.Group>().single { it.title == "ONVIF stream" }.items
            .filterIsInstance<SettingsItem.Info>().single { it.title() == "Sign-in" }
        assertTrue(signIn.fixable())
        r.def.prefs.signIn = "viewer" to "made-up-pass"
        assertFalse(signIn.fixable())
        assertEquals("Set", signIn.detail())
    }

    @Test fun theAddressIsReadOncePerPage() {
        var asked = 0
        val readout = object : CameraReadout {
            override fun reading() = CameraReading(CameraUse.PRESENCE, false, false, false, true, null, true, true, 1L, false, null, true, emptyList())
            override fun address(): StreamAddress { asked++; return StreamAddress("192.0.2.41", 8554, 8000) }
        }
        val pane = object : dev.glassworks.pane.Pane, CameraReadout by readout { override val id = "camera" }
        val r = Rig(allowed = true)
        r.def.switch.on = true
        val items = paneSections(listOf(r.def)) { pane }.single().items.filterIsInstance<SettingsItem.Group>().single { it.title == "ONVIF stream" }.items
            .filterIsInstance<SettingsItem.Info>()
        repeat(3) { items.forEach { it.detail() } }
        assertEquals(1, asked)
        assertTrue(items.single { it.title() == "Stream" }.detail().startsWith("rtsp://192.0.2.41:8554/live"))
    }
}
