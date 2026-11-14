/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package sh.edm.s1kiosk.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Line glyphs in the brand's stroke style (#117): a person (presence) and a camera (images leave). */
internal class CameraGlyph(context: Context, private val kind: Kind, color: Int, private val stroke: Float = 1.8f) : View(context) {
    enum class Kind { PERSON, CAMERA }

    var color: Int = color
        set(v) { field = v; invalidate() }

    private val dp = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val r = RectF()
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.strokeWidth = stroke * dp
        p.color = color
        when (kind) {
            Kind.PERSON -> {
                c.drawCircle(w * 0.5f, h * 0.3f, w * 0.17f, p)
                r.set(w * 0.18f, h * 0.56f, w * 0.82f, h * 1.12f)
                c.drawArc(r, 180f, 180f, false, p)
            }
            Kind.CAMERA -> {
                r.set(w * 0.08f, h * 0.28f, w * 0.68f, h * 0.76f)
                c.drawRoundRect(r, 4 * dp, 4 * dp, p)
                path.reset()
                path.moveTo(w * 0.68f, h * 0.44f); path.lineTo(w * 0.92f, h * 0.32f); path.lineTo(w * 0.92f, h * 0.72f); path.lineTo(w * 0.68f, h * 0.6f)
                c.drawPath(path, p)
            }
        }
    }
}
