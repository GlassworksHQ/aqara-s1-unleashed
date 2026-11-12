package sh.edm.s1kiosk.settings

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.edm.s1kiosk.radio.HelperFacts
import sh.edm.s1kiosk.radio.RadioMode
import sh.edm.s1kiosk.radio.ZigbeeStack
import sh.edm.s1kiosk.setup.RadioFollowUp
import sh.edm.s1kiosk.ui.Inter
import java.io.File
import java.time.Duration

/** Cards in the settings column scroll all the way to the screen's top and bottom edges (#51). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsColumnEdgesTest {

    @Before fun interFromSources() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "THIRD-PARTY-NOTICES.md").isFile }
        Inter.load = { File(root, "app/kiosk/src/main/res/font/inter.ttf").readBytes() }
    }

    private lateinit var overlay: KioskSettingsOverlay
    private lateinit var scroll: ScrollView

    private fun open() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        overlay = KioskSettingsOverlay(a)
        a.setContentView(overlay, ViewGroup.LayoutParams(-1, -1))
        val radio = RadioFollowUp.Facts(
            RadioMode.OFF, "ok Off: Aqara radio services stopped, radio idle", HelperFacts(true, 4), ZigbeeStack.AQARA,
            null, null, "http://homeassistant.local:8123", "192.0.2.10",
        )
        overlay.show(
            KioskSettingsOverlay.Model(
                values = KioskSettingsOverlay.Values(
                    url = "", zoom = "", secureHosts = "", hideHeader = true, radioAllow = "", presence = true, idleOff = "120",
                    nightIdleOff = "20", nightWindow = "22:00-07:00", presenceRange = "", autoBrightness = true,
                ),
                facts = SettingsMenu.Facts(deviceSettings = true, radio = true), radioRunning = true,
                allowedNow = SettingsMenu.allowedNow(true, null), version = "0.4.3", status = "screen on", radio = radio,
            ),
        )
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        layout()
        scroll = generateSequence(listOf<View>(overlay)) { level -> level.flatMap { v -> (v as? ViewGroup)?.let { g -> List(g.childCount) { g.getChildAt(it) } }.orEmpty() }.ifEmpty { null } }
            .flatten().filterIsInstance<ScrollView>().first()
    }

    private fun layout() {
        overlay.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        overlay.layout(0, 0, W, H)
    }

    /** The column's centre line, rows [from, to), with the column scrolled to [y]. */
    private fun rowsAt(y: Int, from: Int, to: Int): List<Int> {
        scroll.scrollTo(0, y)
        layout()
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        overlay.draw(Canvas(b))
        val x = scroll.left + scroll.width / 2
        return (from until to).map { b.getPixel(x, it) }
    }

    private fun maxScroll(): Int {
        scroll.scrollTo(0, Int.MAX_VALUE / 2)
        return scroll.scrollY
    }

    @Test
    fun cardsScrollUnderTheTopEdge() {
        open()
        val max = maxScroll()
        assertTrue("the column scrolls (max $max px)", max > EDGE * 4)
        val backdrop = rowsAt(0, 0, EDGE)
        val scrolled = rowsAt(max / 2, 0, EDGE)
        assertNotEquals("a card shows in the top 30 dp once the column is scrolled", backdrop, scrolled)
    }

    @Test
    fun cardsScrollUnderTheBottomEdge() {
        open()
        val max = maxScroll()
        assertTrue("the column scrolls (max $max px)", max > EDGE * 4)
        val backdrop = rowsAt(max, H - EDGE, H)
        val scrolled = rowsAt(max / 2, H - EDGE, H)
        assertNotEquals("a card shows in the bottom 30 dp before the column reaches its end", backdrop, scrolled)
    }

    private companion object {
        const val W = 1440
        const val H = 720
        const val EDGE = 45 // 30 dp at hdpi
    }
}
