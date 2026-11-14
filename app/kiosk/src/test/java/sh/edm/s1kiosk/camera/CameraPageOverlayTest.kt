package sh.edm.s1kiosk.camera

import android.app.Application
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import dev.glassworks.camera.CameraDefinition
import dev.glassworks.camera.CameraPage
import dev.glassworks.camera.CameraPane
import dev.glassworks.camera.CameraReading
import dev.glassworks.camera.CameraReadout
import dev.glassworks.camera.CameraUse
import dev.glassworks.camera.CameraUsePickers
import dev.glassworks.pane.Pane
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.radio.FixedReadout
import sh.edm.s1kiosk.radio.radioDefinition
import sh.edm.s1kiosk.settings.KioskSettingsOverlay
import sh.edm.s1kiosk.settings.MenuFixture
import sh.edm.s1kiosk.ui.Glass
import sh.edm.s1kiosk.ui.Mockup

/** The camera's page in the real settings menu (#117): the status dot's colour, and rows that follow a card tapped above them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class CameraPageOverlayTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val file get() = KioskPrefs(app).paneFile("camera")

    @After fun tearDown() { file.edit().clear().commit() }

    private fun pane(problem: String? = null) = object : Pane, CameraReadout {
        override val id = CameraPane.ID
        override fun reading() = CameraReading(CameraUse.PRESENCE, false, false, false, true, problem, problem == null, null, null, false, null, false, emptyList())
    }

    private fun menu(def: CameraDefinition, running: Pane): KioskSettingsOverlay {
        val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
        val radio = radioDefinition(app, FixedReadout(null, running = false))
        fun model() = MenuFixture.model(app, radio, panes = listOf(radio, def), running = { if (it == CameraPane.ID) running else null })
        o.reload = ::model
        o.show(model())
        Mockup.idle()
        o.previewSection(CameraPane.ID)
        Mockup.idle()
        return o
    }

    private fun dotBeside(o: KioskSettingsOverlay, text: String): Int {
        val row = Mockup.label(o, text).parent as ViewGroup
        return (row.getChildAt(0).background as GradientDrawable).color!!.defaultColor
    }

    @Test fun aProblemIsARoseDotAndWorkingIsGreen() {
        val def = CameraDefinition(file, hasCamera = true, granted = { true }).apply { switch.on = true }
        assertEquals(Glass.ROSE, dotBeside(menu(def, pane(CameraPane.NOT_ALLOWED)), "${CameraPage.NOT_ALLOWED_LINE}."))
        assertEquals(Glass.GREEN, dotBeside(menu(def, pane()), "Watching for people · no image leaves the panel"))
    }

    @Test fun pickingPresenceOnlyUpdatesTheStreamRowWhileThePageIsOpen() {
        val def = CameraDefinition(file, hasCamera = true, granted = { true }, pickers = CameraUsePickers { CameraUseCards(it) }).apply { switch.on = true }
        def.prefs.use = CameraUse.PRESENCE_AND_SNAPSHOTS
        def.prefs.onvif = true
        val o = menu(def, pane())
        val forWhat = CameraPage.streamDetail(false, CameraUse.PRESENCE_AND_SNAPSHOTS)
        Mockup.label(o, forWhat) // drawn
        Mockup.label(o, "Presence only").let { (it.parent.parent as android.view.View).performClick() }
        o.refresh()
        Mockup.idle()
        Mockup.label(o, CameraPage.NEEDS_SNAPSHOTS) // the switch's line follows the card
        assertEquals(false, def.prefs.onvif)
    }
}
