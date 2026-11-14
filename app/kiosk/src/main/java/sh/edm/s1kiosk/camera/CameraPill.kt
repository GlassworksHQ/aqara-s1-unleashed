/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package sh.edm.s1kiosk.camera

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.annotation.SuppressLint
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.glassworks.platform.Platform
import sh.edm.s1kiosk.ui.Glass
import sh.edm.s1kiosk.ui.Inter

/**
 * The camera's privacy indicator on the panel (#117, the approved "● Camera" pill): shown while images
 * leave the panel (a still to Home Assistant, the stream to a viewer). Top right, above everything,
 * below Android's own camera dot where Android draws one. It takes no touches. Main thread.
 */
class CameraPill(context: Context) : LinearLayout(context) {

    private val g = Glass(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = 999f
            setColor(Color.argb(225, 14, 15, 19))
            setStroke(g.px(1f).coerceAtLeast(1), Color.argb(90, 0x34, 0xd3, 0x99))
        }
        setPadding(g.px(10f), g.px(6f), g.px(13f), g.px(6f))
        addView(g.dot(Glass.GREEN), LayoutParams(g.px(8f), g.px(8f)).apply { marginEnd = g.px(7f) })
        addView(CameraGlyph(context, CameraGlyph.Kind.CAMERA, Glass.TX, 1.6f), LayoutParams(g.px(17f), g.px(17f)).apply { marginEnd = g.px(5f) })
        addView(g.text(13f, Glass.TX, Inter.MEDIUM).apply { text = WORD })
        contentDescription = "The camera is sending images"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = false
        isFocusable = false
        elevation = g.px(30f).toFloat() // above the settings menu (24 dp) and the Thread join screen (26 dp)
        visibility = GONE
    }

    /** Shows or hides it. */
    fun show(leaving: Boolean) {
        visibility = if (leaving) VISIBLE else GONE
    }

    /** Where it sits in the activity's root: top right, under Android's camera dot (and the status bar's height) when there is one. */
    fun layoutParams(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = g.px(TOP_DP); marginEnd = g.px(12f) }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val top = topFor(insets)
        (layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            if (lp.topMargin != top) { lp.topMargin = top; layoutParams = lp }
        }
        return insets
    }

    /** Under the status bar's height even while it is hidden, and under the privacy dot where Android says where it is. */
    @SuppressLint("NewApi") // each call behind its Platform query
    private fun topFor(insets: WindowInsets): Int {
        val gap = g.px(6f)
        var top = g.px(TOP_DP)
        if (Platform.hasInsetsIgnoringVisibility) {
            top = maxOf(top, insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top + gap)
        }
        if (Platform.hasPrivacyIndicatorBounds) {
            insets.privacyIndicatorBounds?.let { top = maxOf(top, it.bottom + gap) }
        }
        return top
    }

    companion object {
        const val WORD = "Camera"
        /** Without insets: clear of the corner where Android draws its camera dot. */
        const val TOP_DP = 30f
    }
}
