package sh.edm.s1kiosk.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * Lays its children out left to right and wraps to a new line when one doesn't fit; each line's
 * children are centred on it. [end] sits at the right end of the last line.
 */
class Wrap(context: Context, private val gapX: Int, private val gapY: Int) : ViewGroup(context) {
    var end: View? = null
    private val lines = ArrayList<List<View>>()

    override fun onMeasure(w: Int, h: Int) {
        val max = MeasureSpec.getSize(w)
        lines.clear()
        var line = ArrayList<View>(); var x = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (line.isNotEmpty() && x + c.measuredWidth > max) { lines += line; line = ArrayList(); x = 0 }
            line += c
            x += c.measuredWidth + gapX
        }
        if (line.isNotEmpty()) lines += line
        val height = lines.sumOf { l -> l.maxOf { it.measuredHeight } } + gapY * (lines.size - 1).coerceAtLeast(0)
        setMeasuredDimension(max, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = 0
        for (line in lines) {
            val lh = line.maxOf { it.measuredHeight }
            var x = 0
            for (c in line) {
                val cx = if (c === end) r - l - c.measuredWidth else x
                val cy = y + (lh - c.measuredHeight) / 2
                c.layout(cx, cy, cx + c.measuredWidth, cy + c.measuredHeight)
                x += c.measuredWidth + gapX
            }
            y += lh + gapY
        }
    }
}
