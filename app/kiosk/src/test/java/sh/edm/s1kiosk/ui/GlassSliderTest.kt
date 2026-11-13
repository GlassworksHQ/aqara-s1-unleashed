package sh.edm.s1kiosk.ui

import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The presence range's slider (#63): by touch, by keys and through accessibility (CodeRabbit on #163). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class GlassSliderTest {

    private val set = ArrayList<Float>()

    private fun slider(value: Float = 1f): GlassSlider {
        val a = Mockup.activity()
        val s = GlassSlider(a, 0f, 2.5f, 0.1f).apply { this.value = value; onSet = { set += it } }
        a.setContentView(s, ViewGroup.LayoutParams(600, -2))
        Mockup.idle()
        s.layout(0, 0, 600, 54)
        return s
    }

    private fun touch(s: GlassSlider, action: Int, x: Float) {
        val t = SystemClock.uptimeMillis()
        s.onTouchEvent(MotionEvent.obtain(t, t, action, x, 27f, 0))
    }

    @Test
    fun aCancelledDragGoesBackAndSetsNothing() {
        val s = slider(1f)
        touch(s, MotionEvent.ACTION_DOWN, 500f)
        touch(s, MotionEvent.ACTION_MOVE, 550f)
        assertTrue(s.value > 1.5f) // it followed the finger
        touch(s, MotionEvent.ACTION_CANCEL, 550f)
        assertEquals(1f, s.value, 0.001f)
        assertEquals(emptyList<Float>(), set)
    }

    @Test
    fun letGoSetsOnce() {
        val s = slider(1f)
        touch(s, MotionEvent.ACTION_DOWN, 300f)
        touch(s, MotionEvent.ACTION_UP, 300f)
        assertEquals(1, set.size)
    }

    @Test
    fun keysStepIt() {
        val s = slider(1f)
        s.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(1.1f, s.value, 0.001f)
        s.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        s.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(0.9f, s.value, 0.001f)
        assertEquals(3, set.size) // each step is set, like a let-go
    }

    @Test
    fun accessibilitySeesARangeAndCanChangeIt() {
        val s = slider(1f)
        val info = AccessibilityNodeInfo.obtain()
        s.onInitializeAccessibilityNodeInfo(info)
        assertEquals(android.widget.SeekBar::class.java.name, info.className)
        assertEquals(1f, info.rangeInfo.current, 0.001f)
        assertEquals(2.5f, info.rangeInfo.max, 0.001f)
        val ids = info.actionList.map { it.id }
        assertTrue(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in ids)
        assertTrue(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in ids)
        assertTrue(android.R.id.accessibilityActionSetProgress in ids)
        assertTrue(s.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        assertEquals(1.1f, s.value, 0.001f)
        assertTrue(s.performAccessibilityAction(android.R.id.accessibilityActionSetProgress,
            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 2.04f) }))
        assertEquals(2f, s.value, 0.001f) // snapped to the step
        assertEquals(listOf(1.1f, 2f), set.map { (it * 10).toInt() / 10f })
    }
}
