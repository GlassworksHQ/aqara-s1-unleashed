package sh.edm.s1kiosk.timers

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.junit.After
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowLog
import sh.edm.s1kiosk.ui.Inter
import sh.edm.s1kiosk.TimerBoard
import sh.edm.s1kiosk.TimerBoard.Entry
import sh.edm.s1kiosk.TimerOverlay
import sh.edm.s1kiosk.VoiceOverlay
import sh.edm.s1kiosk.voice.VoiceUi
import java.io.File
import java.time.Duration

/**
 * Screenshots of the real timer views (TimerOverlay, TimerDoneView) and the voice overlay's text in
 * Inter, rendered by Robolectric's native graphics at the panel's 960x480 dp, density 1.5 (1440x720
 * px), over a plain stand-in for a dashboard. Illustrative timers only. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*TimerMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimerMockups {

    private val dir = System.getProperty("s1.mockupDir")

    // Real frame pacing: the animation loops redraw the whole screen in software on every frame.
    // Paused: a frame runs only when the test idles past it, so an endless animation loop can't run
    // the clock away (unpaused, each requested frame runs at once and moves the clock on).
    @Before fun setUp() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        // Screenshots of end states: view animations (slide, fade) finish at once.
        android.animation.ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, 0f)
        ShadowLog.stream = System.out
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "THIRD-PARTY-NOTICES.md").isFile }
        Inter.load = { File(root, "app/kiosk/src/main/res/font/inter.ttf").readBytes() }
    }

    @After fun tearDown() {
        TimerBoard.reset()
        ShadowChoreographer.setPaused(false)
    }

    private fun idle(ms: Long = 800) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Lets a Bakery bake (a real background thread) post its bitmaps back. */
    private fun bakeWait() = repeat(20) { Thread.sleep(150); idle(16) }

    private fun layout(v: View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, W, H)
    }

    /** Draw-and-idle a few times: Robolectric starts view animations only once a frame has been drawn. */
    private fun settle(v: View) {
        val scratch = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        repeat(3) { layout(v); v.draw(Canvas(scratch)); idle(300) }
    }

    private fun save(name: String, v: View) {
        settle(v)
        layout(v)
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        val out = File(dir!!).apply { mkdirs() }
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private class Scene(val root: FrameLayout, val timers: TimerOverlay, val voice: VoiceOverlay)

    private fun scene(): Scene {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        a.getSharedPreferences("timers", Context.MODE_PRIVATE).edit().clear().commit()
        val root = FrameLayout(a)
        root.addView(FakeDashboard(a), FrameLayout.LayoutParams(-1, -1))
        val timers = TimerOverlay(a)
        val voice = VoiceOverlay(a).apply { onShowingChanged = { timers.voiceShowing(it) } }
        root.addView(timers, timers.layoutParams())
        root.addView(voice, FrameLayout.LayoutParams(-1, -1))
        a.setContentView(root, ViewGroup.LayoutParams(-1, -1))
        layout(root)
        idle()
        return Scene(root, timers, voice)
    }

    private fun set(vararg e: Entry) = TimerBoard.update(e.toList())

    private fun touch(v: View, action: Int, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, action, x, y, 0))
    }

    @Test
    fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", dir != null)
        val d = 1.5f

        // 1. Two timers, bottom right.
        var s = scene()
        set(Entry("p", "Pasta", 600, 492, true), Entry("e", "Eggs", 420, 220, true))
        s.timers.refresh(); idle()
        save("t1-running", s.root)

        // 2. The last 10 seconds, next to a paused timer.
        set(Entry("p", "Pasta", 600, 391, false), Entry("e", "Eggs", 420, 7, true))
        s.timers.refresh(); idle(400)
        save("t2-last10-paused", s.root)

        // 3. Tap a timer: "Hide timers".
        set(Entry("p", "Pasta", 600, 492, true), Entry("e", "Eggs", 420, 220, true))
        s.timers.refresh(); idle()
        val dock = s.timers.getChildAt(1)
        touch(dock, MotionEvent.ACTION_DOWN, 96 * d, 96 * d)
        touch(dock, MotionEvent.ACTION_UP, 96 * d, 96 * d)
        idle(500)
        save("t3-hide-control", s.root)

        // 4. Hidden: the half orb at the edge.
        s.timers.getChildAt(2).performClick()
        idle()
        save("t4-collapsed", s.root)

        // 5. Dragging (bring them back first).
        s.timers.getChildAt(3).performClick()
        settle(s.root)
        touch(dock, MotionEvent.ACTION_DOWN, 96 * d, 96 * d)
        touch(dock, MotionEvent.ACTION_MOVE, -380 * d, -150 * d)
        touch(dock, MotionEvent.ACTION_MOVE, -500 * d, -230 * d)
        idle(400)
        save("t5-drag", s.root)
        touch(dock, MotionEvent.ACTION_UP, -500 * d, -230 * d)
        idle()
        save("t6-dropped-top-left", s.root)

        // 7. Timer done: Eggs finished, Pasta still running.
        s = scene()
        set(Entry("p", "Pasta", 600, 273, true), Entry("e", "Eggs", 420, 0, true))
        TimerBoard.finished("Eggs")
        set(Entry("p", "Pasta", 600, 273, true))
        s.timers.refresh(); bakeWait(); idle(1200)
        save("t7-done", s.root)

        // 8. Two finished together.
        TimerBoard.finished("Pasta")
        TimerBoard.update(emptyList())
        s.timers.refresh(); idle(300)
        save("t8-done-two", s.root)
        TimerBoard.dismissFinished(); s.timers.refresh(); idle()

        // 9. The voice overlay in Inter, replying to a timer request.
        s = scene()
        s.voice.update(VoiceUi("speaking", heard = "Set a pasta timer for ten minutes", reply = "Pasta timer set for 10 minutes", seq = 1), enabled = true)
        bakeWait(); idle(1200)
        save("t9-voice-speaking", s.root)
    }

    /** A stand-in for a busy dashboard: cards, amber tiles and a white button, so the halos show. */
    private class FakeDashboard(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            c.drawColor(Color.rgb(16, 18, 22))
            fun card(l: Float, t: Float, r: Float, b: Float, color: Int) { p.color = color; c.drawRoundRect(l * d, t * d, r * d, b * d, 14 * d, 14 * d, p) }
            val card = Color.rgb(27, 29, 34)
            card(12f, 12f, 298f, 230f, card); card(12f, 242f, 298f, 468f, card)
            card(310f, 12f, 610f, 200f, card); card(310f, 212f, 610f, 468f, card)
            card(622f, 12f, 948f, 72f, card)
            val amber = Color.rgb(74, 61, 26)
            card(622f, 82f, 780f, 156f, amber); card(790f, 82f, 948f, 156f, amber); card(622f, 166f, 780f, 240f, card); card(790f, 166f, 948f, 240f, amber)
            card(622f, 250f, 948f, 300f, card); card(622f, 310f, 948f, 468f, card)
            p.color = Color.rgb(241, 241, 241); c.drawRoundRect(850 * d, 22 * d, 938 * d, 60 * d, 19 * d, 19 * d, p)
            c.drawRoundRect(860 * d, 400 * d, 938 * d, 436 * d, 18 * d, 18 * d, p)
            p.color = Color.rgb(200, 204, 210); p.textSize = 15 * d
            for ((i, y) in listOf(60f, 110f, 160f, 290f, 340f, 390f).withIndex()) c.drawText("Dashboard text ${i + 1}", 30 * d, y * d, p)
        }
    }

    private companion object {
        const val W = 1440
        const val H = 720
    }
}
