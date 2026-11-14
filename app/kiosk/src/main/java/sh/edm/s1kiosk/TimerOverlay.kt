package sh.edm.s1kiosk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.hypot
import sh.edm.s1kiosk.timers.Anchor
import sh.edm.s1kiosk.timers.DockMath
import sh.edm.s1kiosk.timers.DockState
import sh.edm.s1kiosk.timers.Edge
import sh.edm.s1kiosk.timers.RingView
import sh.edm.s1kiosk.timers.TimerDoneView
import sh.edm.s1kiosk.ui.Inter
import sh.edm.s1kiosk.voice.OrbBakes

/**
 * Voice timers on screen (0.4.3, direction B; mock-ups in aqara-panel-backups/mockups/0.4.3-timers):
 * small glass orbs with a ring that runs down, grouped at one of eight spots (bottom right at first),
 * and the full-screen "timer done" ([TimerDoneView]).
 *
 * Full screen but only the timers take touches: a drag moves the group (it snaps to the nearest spot,
 * remembered), a tap shows "Hide timers", and hidden timers tuck into the edge as a half orb that
 * brings them back when tapped. The last 10 seconds bring them back by themselves. A timer set while
 * the voice overlay is up flies out of the voice orb as the overlay fades ([voiceShowing]).
 *
 * Under the voice overlay (elevation 12 dp < 16 dp). Main thread only.
 */
class TimerOverlay(
    context: Context,
    /** The orb layers the timer-done stage shares with the critical alert (#118). */
    orbs: OrbBakes = OrbBakes(),
) : FrameLayout(context) {

    private val dp = resources.displayMetrics.density
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var anchor = Anchor.parse(prefs.getString(KEY_ANCHOR, null))
    private val state = DockState()
    private val ease = PathInterpolator(0.65f, 0f, 0.35f, 1f)

    private val guide = Guide(context)
    private val dock = Dock(context)
    private val chip = HideChip(context)
    private val peek = Peek(context)
    private val done = TimerDoneView(context, orbs)

    private val rings = LinkedHashMap<String, RingView>()
    /** Timers set while the voice overlay is up: they appear when it goes. */
    private val waiting = HashSet<String>()
    private var voice = false
    private var mode = DockState.Mode.NONE
    /** uptimeMillis until which the group is animating (the ticks then leave its position alone). */
    private var settleAt = 0L

    private val tick = Runnable { refresh() }
    private val chipEnd = Runnable { showChip(false) }

    init {
        clipChildren = false
        clipToPadding = false
        outlineProvider = null // no shadow for a full-screen layer
        elevation = 12 * dp
        addView(guide, LayoutParams(-1, -1))
        addView(dock, LayoutParams(-2, -2))
        addView(chip, LayoutParams(-2, -2))
        addView(peek, LayoutParams(px(PEEK_VIEW_DP), px(PEEK_VIEW_DP)))
        addView(done, LayoutParams(-1, -1))
        guide.visibility = View.GONE
        dock.visibility = View.GONE
        chip.visibility = View.GONE
        peek.visibility = View.GONE
        done.onDismissed = { refresh() }
    }

    fun layoutParams(): LayoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

    /**
     * The voice overlay appeared or went. Timers set while it was up fly out of its orb once it goes.
     */
    fun voiceShowing(showing: Boolean) {
        if (showing == voice) return
        voice = showing
        if (!showing && waiting.isNotEmpty()) {
            val ids = waiting.toSet()
            waiting.clear()
            refresh()
            dock.post { for (id in ids) rings[id]?.let { arrive(it, fromOrb = true) } }
        }
    }

    /** Call when timers changed; keeps itself ticking while anything is shown. */
    fun refresh() {
        removeCallbacks(tick)
        val now = SystemClock.elapsedRealtime()
        val timers = TimerBoard.timers
        val fresh = state.onTimers(timers)
        if (voice) waiting += fresh
        waiting.retainAll(timers.map { it.id }.toSet())
        syncRings(timers, fresh)
        done.refresh(now)
        applyMode(state.mode(timers, now))
        peek.invalidate()
        if (timers.isNotEmpty() || done.showing) postDelayed(tick, nextTick(timers, now))
    }

    // ---------------------------------------------------------------- rings

    private fun syncRings(timers: List<TimerBoard.Shown>, fresh: Set<String>) {
        val ids = timers.map { it.id }.toSet()
        rings.keys.filter { it !in ids }.forEach { id -> dock.removeView(rings.remove(id)) }
        for (t in timers) {
            val r = rings.getOrPut(t.id) { RingView(context).also { dock.addView(it) } }
            r.timer = t
            r.alpha = if (t.id in waiting) 0f else r.alpha
        }
        val order = DockMath.order(timers, anchor).map { it.id }
        if (dock.order != order) { dock.order = order; dock.requestLayout() }
        if (!voice) dock.post { for (id in fresh) rings[id]?.let { arrive(it, fromOrb = false) } }
        for (r in rings.values) r.invalidate()
    }

    /** A new ring: out of the voice orb (scale 1.4 at the orb's centre) or just fading in where it sits. */
    private fun arrive(r: RingView, fromOrb: Boolean) {
        r.animate().cancel()
        if (fromOrb) {
            val ox = width / 2f; val oy = ORB_Y_DP * dp
            r.translationX = ox - (dock.x + r.left + r.width / 2f)
            r.translationY = oy - (dock.y + r.top + (RingView.PAD_DP + RingView.RING_DP / 2f) * dp)
            r.scaleX = 1.4f; r.scaleY = 1.4f; r.alpha = 0f
            r.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(ARRIVE_MS).setInterpolator(ease).start()
        } else {
            r.translationX = 0f; r.translationY = 0f
            r.scaleX = 0.8f; r.scaleY = 0.8f; r.alpha = 0f
            r.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(350).setInterpolator(ease).start()
        }
    }

    // ---------------------------------------------------------------- out / tucked away

    private fun applyMode(m: DockState.Mode) {
        val prev = mode
        mode = m
        when (m) {
            DockState.Mode.NONE -> {
                dock.animate().cancel(); dock.visibility = View.GONE
                peek.visibility = View.GONE
                showChip(false)
            }
            DockState.Mode.EXPANDED -> {
                peek.visibility = View.GONE
                if (dock.visibility != View.VISIBLE) {
                    dock.visibility = View.VISIBLE
                    dock.alpha = if (prev == DockState.Mode.COLLAPSED) 0f else 1f
                    dock.post { slideIn(prev == DockState.Mode.COLLAPSED) }
                } else if (prev == DockState.Mode.COLLAPSED) {
                    dock.post { slideIn(true) }
                } else if (!dock.dragging && SystemClock.uptimeMillis() >= settleAt) dock.post { place(animated = false) }
            }
            DockState.Mode.COLLAPSED -> {
                showChip(false)
                if (prev != DockState.Mode.COLLAPSED) dock.post { slideOut() }
                peek.visibility = View.VISIBLE
                dock.post { placePeek() }
            }
        }
    }

    /** Top-left of the rings (not the room around them) at their slot, in this view's px. */
    private fun slot(a: Anchor = anchor): DockMath.Pt =
        DockMath.slot(a, width.toFloat(), height.toFloat(), dock.bodyW, dock.bodyH, DockMath.MARGIN_DP * dp)

    /** Moves the group so its rings' top-left is at [p]. */
    private fun dockAt(p: DockMath.Pt) { dock.x = p.x - dock.pad; dock.y = p.y - dock.pad }

    private fun place(animated: Boolean) {
        if (width == 0) return
        val s = slot()
        if (animated) {
            settleAt = SystemClock.uptimeMillis() + 300
            dock.animate().x(s.x - dock.pad).y(s.y - dock.pad).scaleX(1f).scaleY(1f).alpha(1f).setDuration(260).setInterpolator(ease).start()
        } else dockAt(s)
    }

    /** Where the rings go when the group tucks into the edge: just past it. */
    private fun offscreen(): DockMath.Pt {
        val s = slot()
        return when (anchor.edge) {
            Edge.LEFT -> DockMath.Pt(-dock.bodyW - dock.pad, s.y)
            Edge.RIGHT -> DockMath.Pt(width + dock.pad, s.y)
            Edge.TOP -> DockMath.Pt(s.x, -dock.bodyH - dock.pad)
            Edge.BOTTOM -> DockMath.Pt(s.x, height + dock.pad)
        }
    }

    private fun slideOut() {
        val o = offscreen()
        dock.animate().cancel()
        settleAt = SystemClock.uptimeMillis() + 360
        dock.animate().x(o.x - dock.pad).y(o.y - dock.pad).alpha(0f).setDuration(320).setInterpolator(ease)
            .withEndAction { if (mode == DockState.Mode.COLLAPSED) dock.visibility = View.INVISIBLE }.start()
    }

    private fun slideIn(fromEdge: Boolean) {
        dock.visibility = View.VISIBLE
        if (fromEdge) {
            val o = offscreen()
            dock.animate().cancel()
            if (dock.alpha == 0f) dockAt(o)
            place(animated = true)
        } else place(animated = false)
    }

    private fun placePeek() {
        if (width == 0) return
        val s = slot()
        val half = PEEK_VIEW_DP / 2f * dp
        val ringCy = s.y + RingView.RING_DP * dp / 2f
        val cx = s.x + dock.bodyW / 2f
        val m = DockMath.MARGIN_DP * dp + half
        when (anchor.edge) {
            Edge.LEFT -> { peek.x = -half; peek.y = ringCy.coerceIn(m, height - m) - half }
            Edge.RIGHT -> { peek.x = width - half; peek.y = ringCy.coerceIn(m, height - m) - half }
            Edge.TOP -> { peek.x = cx.coerceIn(m, width - m) - half; peek.y = -half }
            Edge.BOTTOM -> { peek.x = cx.coerceIn(m, width - m) - half; peek.y = height - half }
        }
        peek.edge = anchor.edge
    }

    private fun showChip(show: Boolean) {
        removeCallbacks(chipEnd)
        for (r in rings.values) r.highlighted = false
        if (!show || mode != DockState.Mode.EXPANDED) { chip.visibility = View.GONE; return }
        chip.edge = anchor.edge
        chip.below = anchor.v < 0
        chip.visibility = View.VISIBLE
        chip.alpha = 0f
        chip.post {
            val gap = 10 * dp
            val bx = dock.x + dock.pad; val by = dock.y + dock.pad
            chip.x = when (anchor.h) {
                1 -> bx + dock.bodyW - chip.width
                -1 -> bx
                else -> bx + (dock.bodyW - chip.width) / 2f
            }
            chip.y = if (chip.below) by + dock.bodyH + gap else by - chip.height - gap
            chip.animate().alpha(1f).setDuration(180).start()
        }
        postDelayed(chipEnd, CHIP_MS)
    }

    // ---------------------------------------------------------------- ticking

    /** Just after the next whole-second change of the soonest running timer (digits never lag). */
    private fun nextTick(timers: List<TimerBoard.Shown>, now: Long): Long {
        val t = timers.firstOrNull { it.active } ?: return 1000
        val ms = (t.endsAt - now).mod(1000L)
        return (if (ms < 20) ms + 1000 else ms) + 15
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The slots depend on the screen size (first layout, or the window changing).
        post {
            if (mode == DockState.Mode.EXPANDED && !dock.dragging) place(animated = false)
            if (mode == DockState.Mode.COLLAPSED) placePeek()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        removeCallbacks(chipEnd)
        super.onDetachedFromWindow()
    }

    private fun px(v: Float) = (v * dp + 0.5f).toInt()

    // ---------------------------------------------------------------- the group

    /**
     * The rings in a row, with [pad] of room around them for their halos (so nothing depends on a
     * parent not clipping). Takes the touches that land on the rings and their tags, and no others: a
     * drag moves the group, a tap shows "Hide timers".
     */
    private inner class Dock(context: Context) : FrameLayout(context) {
        var order: List<String> = emptyList()
        var dragging = false
        val pad = RingView.PAD_DP * dp
        /** Size of the rings and tags without the room around them. */
        var bodyW = 0f; private set
        val bodyH = RingView.BODY_H_DP * dp
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f; private var downY = 0f
        private var startX = 0f; private var startY = 0f
        private var tapped: RingView? = null

        init { clipChildren = false; clipToPadding = false; outlineProvider = null }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val spec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            for (i in 0 until childCount) getChildAt(i).measure(spec, spec)
            val n = order.size.coerceAtLeast(1)
            bodyW = n * RingView.RING_DP * dp + (n - 1) * GAP_DP * dp
            val h = if (childCount > 0) getChildAt(0).measuredHeight else (pad + bodyH).toInt()
            setMeasuredDimension((bodyW + 2 * pad).toInt(), h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val step = (RingView.RING_DP + GAP_DP) * dp
            order.forEachIndexed { i, id ->
                val v = rings[id] ?: return@forEachIndexed
                val x = (i * step).toInt() // the ring's own room overlaps its neighbours'
                v.layout(x, 0, x + v.measuredWidth, v.measuredHeight)
            }
            if (!dragging && mode == DockState.Mode.EXPANDED && changed) place(animated = false)
            if (mode == DockState.Mode.COLLAPSED && changed) placePeek()
        }

        override fun onInterceptTouchEvent(ev: MotionEvent) = true

        /** A touch that starts outside the rings and tags isn't ours: the page below gets it. */
        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN && !onBody(e.x, e.y)) return false
            return super.dispatchTouchEvent(e)
        }

        private fun onBody(x: Float, y: Float) = x >= pad && x < pad + bodyW && y >= pad && y < pad + bodyH

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = x; startY = y
                    tapped = ringAt(e.x)
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) startDrag()
                    if (dragging) {
                        val p = DockMath.clamp(startX + pad + dx, startY + pad + dy,
                            this@TimerOverlay.width.toFloat(), this@TimerOverlay.height.toFloat(), bodyW, bodyH)
                        x = p.x - pad; y = p.y - pad
                        guide.target = nearest()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) endDrag()
                    else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        val show = chip.visibility != View.VISIBLE
                        showChip(show)
                        if (show) tapped?.highlighted = true
                    }
                }
            }
            return true
        }

        private fun ringAt(x: Float): RingView? {
            val i = ((x - pad) / ((RingView.RING_DP + GAP_DP) * dp)).toInt()
            return order.getOrNull(i)?.let { rings[it] }
        }

        private fun nearest() = DockMath.nearest(x + pad, y + pad, this@TimerOverlay.width.toFloat(), this@TimerOverlay.height.toFloat(),
            bodyW, bodyH, DockMath.MARGIN_DP * dp)

        private fun startDrag() {
            dragging = true
            showChip(false)
            parent.requestDisallowInterceptTouchEvent(true)
            animate().cancel() // e.g. still sliding back in: it is in the hand now, fully shown
            alpha = 1f
            animate().scaleX(1.06f).scaleY(1.06f).setDuration(150).start()
            guide.groupW = bodyW; guide.groupH = bodyH
            guide.target = nearest()
            guide.show(true)
        }

        private fun endDrag() {
            dragging = false
            anchor = nearest()
            prefs.edit().putString(KEY_ANCHOR, anchor.name).apply()
            guide.show(false)
            order = DockMath.order(TimerBoard.timers, anchor).map { it.id }
            requestLayout()
            place(animated = true)
        }
    }

    // ---------------------------------------------------------------- pieces

    /** While dragging: a light dim, the eight spots, and a dashed outline where the group will land. */
    private inner class Guide(context: Context) : View(context) {
        var groupW = 0f; var groupH = 0f
        var target: Anchor = anchor
            set(v) { if (v != field) { field = v; invalidate() } }
        private val dim = Paint().apply { color = Color.argb(107, 10, 11, 15) }
        private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; pathEffect = DashPathEffect(floatArrayOf(4 * dp, 3 * dp), 0f)
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(15, 251, 191, 36) }
        private val box = RectF()

        override fun hasOverlappingRendering() = false

        fun show(on: Boolean) {
            animate().cancel()
            if (on) { visibility = View.VISIBLE; alpha = 0f; animate().alpha(1f).setDuration(150).start() }
            else animate().alpha(0f).setDuration(200).withEndAction { visibility = View.GONE }.start()
        }

        override fun onDraw(c: Canvas) {
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
            val m = DockMath.MARGIN_DP * dp
            for (a in Anchor.entries) {
                val s = DockMath.slot(a, width.toFloat(), height.toFloat(), groupW, groupH, m)
                if (a == target) {
                    box.set(s.x - 6 * dp, s.y - 6 * dp, s.x + groupW + 6 * dp, s.y + groupH + 6 * dp)
                    c.drawRoundRect(box, 22 * dp, 22 * dp, fill)
                    dash.color = Color.argb(191, 251, 191, 36)
                    c.drawRoundRect(box, 22 * dp, 22 * dp, dash)
                } else {
                    dash.color = Color.argb(115, 244, 244, 245)
                    c.drawCircle(s.x + groupW / 2, s.y + groupH / 2, 7 * dp, dash)
                }
            }
        }
    }

    /** "Hide timers" (with an arrow to the edge it tucks into) and a "Drag to move" tag. */
    private inner class HideChip(context: Context) : View(context) {
        var edge = Edge.RIGHT
        var below = false
        private val chipH = 38 * dp; private val tagH = 18 * dp; private val gap = 8 * dp
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14 * dp; typeface = Inter.typeface(context, Inter.MEDIUM, 14f); color = Color.parseColor("#f4f4f5")
        }
        private val tag = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10 * dp; typeface = Inter.typeface(context, Inter.MEDIUM, 10f); letterSpacing = 0.14f
            color = Color.parseColor("#a1a1aa"); textAlign = Paint.Align.CENTER
        }
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2 * dp; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val arrow = Path()
        private val r = RectF()
        private val label = "Hide timers"
        private val tagText = "DRAG TO MOVE"

        init {
            isClickable = true
            setOnClickListener { state.hide(); refresh() }
            outlineProvider = null
        }

        override fun onMeasure(w: Int, h: Int) {
            val cw = 12 * dp + 16 * dp + 8 * dp + text.measureText(label) + 16 * dp
            val tw = tag.measureText(tagText) + 16 * dp
            setMeasuredDimension(maxOf(cw, tw).toInt() + 1, (chipH + gap + tagH).toInt() + 1)
        }

        override fun onDraw(c: Canvas) {
            val cw = 12 * dp + 16 * dp + 8 * dp + text.measureText(label) + 16 * dp
            val chipTop = if (below) 0f else tagH + gap
            val tagTop = if (below) chipH + gap else 0f
            val cx = when (edge) { Edge.LEFT -> 0f; Edge.RIGHT -> width - cw; else -> (width - cw) / 2 }
            // chip: opaque glass (no blur on API 29), top highlight, 1 dp rim
            r.set(cx, chipTop, cx + cw, chipTop + chipH)
            bg.shader = null; bg.color = Color.rgb(15, 16, 21)
            c.drawRoundRect(r, chipH / 2, chipH / 2, bg)
            bg.color = Color.argb(15, 255, 255, 255)
            c.drawRoundRect(r.left, r.top, r.right, r.top + chipH * 0.6f, chipH / 2, chipH / 2, bg)
            line.strokeWidth = 1 * dp; line.color = Color.argb(31, 255, 255, 255)
            r.inset(0.5f * dp, 0.5f * dp); c.drawRoundRect(r, chipH / 2, chipH / 2, line)
            // arrow to the edge: "→|" turned to face it
            val ix = cx + 12 * dp; val iy = chipTop + (chipH - 16 * dp) / 2
            c.save()
            c.translate(ix + 8 * dp, iy + 8 * dp)
            c.rotate(when (edge) { Edge.RIGHT -> 0f; Edge.BOTTOM -> 90f; Edge.LEFT -> 180f; Edge.TOP -> 270f })
            val u = 16 * dp / 24f
            arrow.reset()
            arrow.moveTo(-7 * u, 0f); arrow.lineTo(5 * u, 0f)
            arrow.moveTo(1 * u, -5 * u); arrow.lineTo(6 * u, 0f); arrow.lineTo(1 * u, 5 * u)
            arrow.moveTo(8 * u, -8 * u); arrow.lineTo(8 * u, 8 * u)
            line.strokeWidth = 2 * dp; line.color = Color.rgb(251, 191, 36)
            c.drawPath(arrow, line)
            c.restore()
            val fm = text.fontMetrics
            c.drawText(label, ix + 16 * dp + 8 * dp, chipTop + chipH / 2 - (fm.ascent + fm.descent) / 2, text)
            // tag
            val tw = tag.measureText(tagText) + 16 * dp
            val tx = when (edge) { Edge.LEFT -> 0f; Edge.RIGHT -> width - tw; else -> (width - tw) / 2 }
            r.set(tx, tagTop, tx + tw, tagTop + tagH)
            bg.color = Color.argb(209, 10, 11, 15)
            c.drawRoundRect(r, tagH / 2, tagH / 2, bg)
            val tfm = tag.fontMetrics
            c.drawText(tagText, r.centerX(), tagTop + tagH / 2 - (tfm.ascent + tfm.descent) / 2, tag)
        }
    }

    /**
     * Hidden timers: half an orb peeking from the edge, its rim an amber arc for the soonest timer's
     * time left. Tapping it brings the timers back.
     */
    private inner class Peek(context: Context) : View(context) {
        var edge = Edge.RIGHT
            set(v) { if (v != field) { field = v; invalidate() } }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val box = RectF()

        init {
            isClickable = true
            setOnClickListener { state.show(); refresh() }
            outlineProvider = null
        }

        override fun hasOverlappingRendering() = false

        override fun onDraw(c: Canvas) {
            val now = SystemClock.elapsedRealtime()
            val t = TimerBoard.timers.firstOrNull { it.active } ?: TimerBoard.timers.firstOrNull() ?: return
            val cx = width / 2f; val cy = height / 2f
            val r = PEEK_R_DP * dp
            paint.shader = null; paint.color = Color.argb(140, 0, 0, 0)
            c.drawCircle(cx, cy, r + 3 * dp, paint) // soft edge over the page
            paint.color = Color.rgb(15, 16, 21); c.drawCircle(cx, cy, r, paint)
            stroke.strokeWidth = 1 * dp; stroke.color = Color.argb(36, 255, 255, 255); c.drawCircle(cx, cy, r, stroke)
            val ar = r - 1.5f * dp
            box.set(cx - ar, cy - ar, cx + ar, cy + ar)
            stroke.strokeWidth = 2.5f * dp; stroke.color = Color.argb(26, 255, 255, 255)
            c.drawCircle(cx, cy, ar, stroke)
            // The visible half, from one end along to the other, scaled by the time left.
            val (start, dir) = when (edge) {
                Edge.RIGHT -> 90f to 1f
                Edge.LEFT -> -90f to 1f
                Edge.TOP -> 180f to -1f
                Edge.BOTTOM -> 180f to 1f
            }
            val left = t.remaining(now)
            stroke.color = if (t.active) Color.argb(230, 251, 191, 36) else Color.argb(97, 244, 244, 245)
            if (left > 0f) c.drawArc(box, start, dir * 180f * left, false, stroke)
            // the amber core, inside the visible half
            val inward = 11 * dp
            val (dx, dy) = when (edge) { Edge.RIGHT -> -inward to 0f; Edge.LEFT -> inward to 0f; Edge.TOP -> 0f to inward; Edge.BOTTOM -> 0f to -inward }
            if (t.active) {
                paint.shader = RadialGradient(cx + dx, cy + dy, 9 * dp,
                    intArrayOf(Color.argb(242, 251, 191, 36), Color.argb(77, 251, 191, 36), Color.argb(0, 251, 191, 36)),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(cx + dx, cy + dy, 9 * dp, paint)
                paint.shader = null
            }
        }
    }

    private companion object {
        const val PREFS = "timers"
        const val KEY_ANCHOR = "anchor"
        const val GAP_DP = 16f
        const val PEEK_R_DP = 26f
        const val PEEK_VIEW_DP = 80f
        const val ORB_Y_DP = 162f
        const val ARRIVE_MS = 700L
        const val CHIP_MS = 4_000L
    }
}
