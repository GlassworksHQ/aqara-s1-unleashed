package sh.edm.s1kiosk.notifications

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.widget.FrameLayout
import dev.glassworks.notifications.Delivery
import dev.glassworks.notifications.ImageFetch
import dev.glassworks.notifications.Notification
import dev.glassworks.notifications.NotificationAction
import dev.glassworks.notifications.NotificationImages
import dev.glassworks.notifications.NotificationStore.Entry
import dev.glassworks.notifications.NotificationsDefinition
import dev.glassworks.notifications.NotificationsHub
import dev.glassworks.notifications.Priority
import dev.glassworks.pane.PaneChoice
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.radio.FixedReadout
import sh.edm.s1kiosk.radio.RadioMode
import sh.edm.s1kiosk.radio.radioDefinition
import sh.edm.s1kiosk.settings.KioskSettingsOverlay
import sh.edm.s1kiosk.settings.MenuFixture
import sh.edm.s1kiosk.setup.ImprovFacts
import sh.edm.s1kiosk.setup.SetupFlow
import sh.edm.s1kiosk.setup.SetupOverlay
import sh.edm.s1kiosk.setup.SetupPane
import sh.edm.s1kiosk.setup.WifiSetupStatus
import sh.edm.s1kiosk.setup.WifiStep
import sh.edm.s1kiosk.ui.Mockup
import java.io.ByteArrayOutputStream

/**
 * Screenshots of the real notifications views (#118): [NotificationOverlay] over a stand-in for a light
 * Home Assistant dashboard, the pane's settings page and its card on setup's Panes step, at the panel's
 * 1440x720 px. Illustrative values only: made-up names, a drawn porch with nobody in it. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*NotificationsMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationsMockups {

    @Before fun interFromSources() = Mockup.interFromSources()

    private val now = 10_000_000L
    private var nextId = 1L

    private fun entry(
        title: String, message: String, p: Priority = Priority.NORMAL, dnd: Boolean = false, agoMs: Long = 0,
        image: String? = null, actions: List<String> = emptyList(), timeout: Int? = null, url: String? = null,
        tag: String? = null, held: Boolean? = null,
    ): Entry {
        val n = Notification(
            title, message, tag = tag, priority = p, image = image,
            actions = actions.map { NotificationAction(it.lowercase().replace(' ', '_'), it) }, timeoutS = timeout, url = url,
        )
        val d = Delivery.of(p, dnd)
        return Entry(nextId++, n, now - agoMs, d, held ?: d.held)
    }

    /** A picture of a porch at night, as a JPEG from "Home Assistant". */
    private val porchJpeg: ByteArray by lazy {
        val b = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
        Porch.draw(Canvas(b), RectF(0f, 0f, 640f, 400f), 1f)
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private val images = NotificationImages { ImageFetch.Ok(porchJpeg, "image/jpeg") }
    private val noSignIn = NotificationImages { ImageFetch.Failed("the panel isn't signed in to Home Assistant") }

    private fun shot(name: String, state: NotificationsHub.State, door: Boolean = false, imgs: NotificationImages = images, then: (NotificationOverlay) -> Unit = {}) {
        val root = Mockup.show(FrameLayout(Mockup.activity()))
        root.addView(Dashboard(root.context, door), FrameLayout.LayoutParams(-1, -1))
        val o = NotificationOverlay(root.context, NotificationsHub(), imgs, now = { now })
        root.addView(o, FrameLayout.LayoutParams(-1, -1))
        o.update(state)
        then(o)
        // Pictures load on a worker thread and come back through the main looper.
        repeat(10) { Thread.sleep(50); Mockup.idle() }
        Mockup.layout(root)
        Mockup.save(name, root)
    }

    private val washer = entry("Washing machine", "Done. 1 h 32 min, 0.84 kWh.", actions = listOf("Start the dryer"), timeout = 1800, agoMs = 20_000)
    private val parcel = entry(
        "Parcel at the door", "The porch camera saw a delivery.", Priority.HIGH,
        image = "http://homeassistant.local:8123/api/camera_proxy/camera.porch", actions = listOf("Open the gate", "Snooze 10 min"),
    )

    @Test
    fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)

        shot("notify-01-card-normal", NotificationsHub.State(cards = listOf(washer), list = listOf(washer)))
        shot("notify-02-card-important-picture", NotificationsHub.State(cards = listOf(parcel), list = listOf(parcel)))
        val bin = entry("Bin day", "Put the green bin out tonight.", Priority.LOW, agoMs = 300_000)
        shot("notify-03-card-low", NotificationsHub.State(cards = listOf(bin), list = listOf(bin)))
        val garage = entry("Garage door open", "Open for 20 minutes.", agoMs = 60_000)
        val three = listOf(washer, garage, bin)
        shot("notify-04-stacked", NotificationsHub.State(cards = three, list = three))
        val door = entry("Doorbell", "Someone rang at the front door.", Priority.HIGH, image = parcel.notification.image, url = "http://homeassistant.local:8123/dashboard-door/0", held = true)
        val four = listOf(door, washer, garage, bin)
        shot("notify-05-list", NotificationsHub.State(list = four)) { it.openList() }
        val heldWasher = entry("Washing machine", "Done. 1 h 32 min, 0.84 kWh.", dnd = true, agoMs = 120_000)
        val heldParcel = entry("Parcel at the door", "The porch camera saw a delivery.", Priority.HIGH, dnd = true, agoMs = 540_000, image = parcel.notification.image)
        shot("notify-06-do-not-disturb", NotificationsHub.State(list = listOf(heldWasher, heldParcel), doNotDisturb = true))
        shot("notify-07-do-not-disturb-list", NotificationsHub.State(list = listOf(heldWasher, heldParcel, bin), doNotDisturb = true)) { it.openList() }
        val page = entry("Doorbell", "Someone is at the door", Priority.HIGH, timeout = 90, agoMs = 6_000, url = "http://homeassistant.local:8123/dashboard-door/0", tag = "doorbell")
        shot("notify-08-full-screen-page", NotificationsHub.State(page = page, list = listOf(page)), door = true)
        val leak = entry("Water under the kitchen sink", "The leak sensor turned on at 14:32. The main valve is still open.", Priority.CRITICAL, actions = listOf("Close the valve"), tag = "water_leak")
        shot("notify-09-critical", NotificationsHub.State(cards = listOf(leak), list = listOf(leak)))
        val longLeak = entry(
            "Water under the kitchen sink and behind the dishwasher",
            "The leak sensor under the kitchen sink turned on at 14:32. The main valve is still open, and the dishwasher is running. Water is reaching the floor near the fridge.",
            Priority.CRITICAL, actions = listOf("Close the valve", "Call", "Silence"), tag = "water_leak",
        )
        shot("notify-14-critical-long", NotificationsHub.State(cards = listOf(longLeak), list = listOf(longLeak)))
        shot("notify-10-card-no-picture", NotificationsHub.State(cards = listOf(parcel), list = listOf(parcel)), imgs = noSignIn)

        settings("notify-11-settings", on = true, listOf(washer, heldWasher, bin))
        settings("notify-12-settings-off", on = false, emptyList())
        setupPanes()
    }

    private fun settings(name: String, on: Boolean, list: List<Entry>) {
        val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
        val app = RuntimeEnvironment.getApplication()
        val prefs = KioskPrefs(app).apply { urlSetting = "http://homeassistant.local:8123/lovelace/panel" }
        val hub = NotificationsHub().apply { publish(NotificationsHub.State(list = list)) }
        val notify = NotificationsDefinition(prefs.paneFile("notifications"), hub, "esphome.s1_panel_notify", now = { now })
        notify.switch.on = on
        val radio = radioDefinition(app, FixedReadout(null, running = true))
        val panes = listOf(
            MenuFixture.cardOnly("voice", "Voice", "Assist and timers"), MenuFixture.cardOnly("sendspin", "Music", "A Sendspin speaker"),
            notify, radio,
        ) + MenuFixture.switchOnly(app)
        o.show(MenuFixture.model(app, radio, status = "screen on", panes = panes))
        Mockup.idle()
        o.previewSection("notifications")
        Mockup.idle()
        Mockup.layout(o)
        Mockup.save(name, o)
    }

    private fun setupPanes() {
        val a = Mockup.activity()
        val ha = "http://homeassistant.local:8123"
        val radio = radioDefinition(a, FixedReadout(null, mode = RadioMode.OFF, haBase = ha, panelIp = "192.0.2.10"))
        val notify = NotificationsDefinition(KioskPrefs(a).paneFile("notifications"), NotificationsHub(), "esphome.s1_panel_notify", now = { 0L })
        val panes = listOf(
            SetupPane(PaneChoice("Voice", "Assist and timers", on = { true }, apply = { null }), emptyList()),
            SetupPane(PaneChoice("Music", "A Sendspin speaker", on = { true }, apply = { null }), emptyList()),
            SetupPane(notify.setupChoice!!, emptyList()),
            SetupPane(radio.setupChoice, radio.setupSteps),
            SetupPane(PaneChoice("Wall base", "The S1's wired base", on = { true }, apply = { null }), emptyList()),
        )
        val s = SetupOverlay(a, panes)
        a.setContentView(s, android.view.ViewGroup.LayoutParams(-1, -1))
        val flow = SetupFlow.ids(true, listOf("radio"))
        s.render(SetupOverlay.Model(
            step = SetupFlow.PANES, done = flow.takeWhile { it != SetupFlow.PANES }.toSet(),
            wifi = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi"), haUrl = ha, haSource = "mdns",
            panelIp = "192.0.2.10", signedIn = false, improv = WifiStep.improv(ImprovFacts()), installer = null, preview = false,
        ))
        Mockup.idle()
        Mockup.save("notify-13-setup-panes", s)
    }

    /** A light Home Assistant dashboard (the default theme), so the dark card has to hold its own on it. */
    private class Dashboard(context: Context, private val door: Boolean) : View(context) {
        private val d = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        private val ink = Color.parseColor("#212121")
        private val grey = Color.parseColor("#727272")

        private fun card(c: Canvas, l: Float, tp: Float, r: Float, b: Float) {
            p.shader = null
            p.color = Color.argb(18, 0, 0, 0); c.drawRoundRect(l * d, (tp + 1.5f) * d, r * d, (b + 1.5f) * d, 12 * d, 12 * d, p)
            p.color = Color.WHITE; c.drawRoundRect(l * d, tp * d, r * d, b * d, 12 * d, 12 * d, p)
        }

        private fun text(c: Canvas, s: String, x: Float, y: Float, sp: Float, color: Int) {
            t.textSize = sp * d; t.color = color
            c.drawText(s, x * d, y * d, t)
        }

        override fun onDraw(c: Canvas) {
            c.drawColor(Color.parseColor("#fafafa"))
            p.color = Color.parseColor("#03a9f4"); c.drawRect(0f, 0f, width.toFloat(), 56 * d, p)
            text(c, if (door) "Front door" else "Home", 20f, 35f, 20f, Color.WHITE)
            if (door) {
                card(c, 16f, 72f, 640f, 464f)
                Porch.draw(c, RectF(28 * d, 84 * d, 628 * d, 452 * d), d)
                text(c, "Porch · live", 44f, 108f, 13f, Color.WHITE)
                card(c, 656f, 72f, 944f, 260f); text(c, "Gate", 672f, 100f, 16f, ink); text(c, "Open the gate", 744f, 226f, 15f, ink)
                card(c, 656f, 276f, 944f, 464f); text(c, "Visitor", 672f, 304f, 16f, ink); text(c, "Done", 784f, 430f, 15f, ink)
                return
            }
            card(c, 16f, 72f, 316f, 232f); text(c, "Living room", 32f, 100f, 16f, ink); text(c, "Ceiling · On · 70 %", 32f, 140f, 14f, grey)
            card(c, 330f, 72f, 630f, 232f); text(c, "Climate", 346f, 100f, 16f, ink); text(c, "21.5 °C", 346f, 160f, 38f, ink)
            card(c, 644f, 72f, 944f, 232f); text(c, "Weather", 660f, 100f, 16f, ink); text(c, "14° · Cloudy", 660f, 160f, 30f, ink)
            card(c, 16f, 248f, 472f, 464f); text(c, "Energy today", 32f, 276f, 16f, ink)
            p.color = Color.parseColor("#4db6ac")
            listOf(40, 60, 35, 80, 120, 90, 70, 110, 140, 95, 60, 45).forEachIndexed { i, h -> c.drawRoundRect((40 + i * 34f) * d, (440 - h) * d, (62 + i * 34f) * d, 440 * d, 3 * d, 3 * d, p) }
            card(c, 488f, 248f, 944f, 464f); text(c, "Rooms", 504f, 276f, 16f, ink)
            listOf("Kitchen" to "2 lights on", "Hall" to "Door closed", "Office" to "19.8 °C", "Bedroom" to "Off").forEachIndexed { i, (n, s) ->
                val x = 504f + (i % 2) * 220f; val y = 300f + (i / 2) * 76f
                p.color = Color.argb(12, 0, 0, 0); c.drawRoundRect(x * d, y * d, (x + 208) * d, (y + 64) * d, 10 * d, 10 * d, p)
                text(c, n, x + 14, y + 28, 15f, ink); text(c, s, x + 14, y + 48, 12f, grey)
            }
        }
    }

    /** A drawn porch at night: a door, a warm lamp and a parcel. Nobody in it. */
    private object Porch {
        fun draw(c: Canvas, r: RectF, d: Float) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            c.save(); c.clipRect(r)
            p.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.parseColor("#1b2433"), Color.parseColor("#2b2f38"), Shader.TileMode.CLAMP)
            c.drawRect(r, p); p.shader = null
            val w = r.width(); val h = r.height()
            p.color = Color.parseColor("#3a3f4a"); c.drawRect(r.left, r.top + h * 0.78f, r.right, r.bottom, p)
            p.color = Color.parseColor("#4a3a2c"); c.drawRect(r.left + w * 0.40f, r.top + h * 0.22f, r.left + w * 0.60f, r.top + h * 0.78f, p)
            p.shader = RadialGradient(r.left + w * 0.68f, r.top + h * 0.28f, h * 0.35f, Color.argb(120, 255, 196, 120), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawRect(r, p); p.shader = null
            p.color = Color.parseColor("#ffd8a0"); c.drawCircle(r.left + w * 0.68f, r.top + h * 0.28f, 5f * d, p)
            p.color = Color.parseColor("#8d6e4f"); c.drawRect(r.left + w * 0.64f, r.top + h * 0.68f, r.left + w * 0.76f, r.top + h * 0.80f, p)
            c.restore()
        }
    }
}
