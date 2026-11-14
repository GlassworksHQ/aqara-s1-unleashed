package sh.edm.s1kiosk.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * The left rail of setup and of the settings menu (#63 round 2): rows, some under a group (setup's
 * Home Assistant and Panes, settings' Panes). A group shows its rows while one of them is selected and
 * folds to one row otherwise. Tapping a row selects it; tapping a folded group selects its first open
 * row. Rows that are [Row.off] (a pane turned off, #212) go last in their group, folded under "Off · N";
 * the fold opens while one of them is selected, and tapping it selects the first. Main thread.
 */
class GroupedRail(context: Context) : LinearLayout(context) {

    /** The dot before a row's name. */
    enum class Mark { DONE, CURRENT, PENDING, NONE }

    /**
     * One row. [group]: the group it sits under, or null. [dim]: greyed
     * (a pane that can't run), [detail] then says why. [enabled]: it can be tapped. [off]: folded at the
     * end of its group. [chip]: the pane's status after its name, at the row's end ("Radio … ● Running"), so
     * every name starts at the same x (#243); null for none.
     */
    class Row(
        val id: String, val title: String, val group: String? = null, val mark: Mark = Mark.NONE,
        val enabled: Boolean = true, val dim: Boolean = false, val detail: String? = null,
        val off: Boolean = false, val chip: Chip? = null,
    )

    /** A status chip: its word and its dot's colour (a status colour, or [Glass.FA] for off). */
    data class Chip(val word: String, val dot: Int)

    var onTap: ((String) -> Unit)? = null

    /** Row heights and the gap between rows, in dp. Settings uses a tighter rail, so its Panes group fits open. */
    var rowDp = 39f
    var childDp = 34f
    var gapDp = 4f

    private val g = Glass(context)

    init { orientation = VERTICAL }

    /** Draws [rows] with [selected] picked; [groups] names each group (id to title). */
    fun bind(rows: List<Row>, groups: Map<String, String>, selected: String?) {
        removeAllViews()
        val open = rows.firstOrNull { it.id == selected }?.group
        var i = 0
        while (i < rows.size) {
            val r = rows[i]
            val group = r.group
            if (group == null) {
                add(row(r.title, r.mark, selected == r.id, child = false, dim = r.dim, detail = r.detail, enabled = r.enabled, chip = r.chip) { onTap?.invoke(r.id) })
                i++
                continue
            }
            val members = rows.drop(i).takeWhile { it.group == group }
            val mark = when {
                open == group -> Mark.CURRENT
                members.all { it.mark == Mark.DONE } -> Mark.DONE
                members.any { it.mark == Mark.CURRENT } -> Mark.CURRENT
                members.all { it.mark == Mark.NONE } -> Mark.NONE
                else -> Mark.PENDING
            }
            val first = members.firstOrNull { it.enabled && it.mark != Mark.DONE } ?: members.firstOrNull { it.enabled }
            add(row(groups[group] ?: group, mark, selected = false, child = false, dim = false, detail = null,
                enabled = first != null, emphasis = open == group) { first?.let { onTap?.invoke(it.id) } })
            if (open == group) {
                val (offs, ons) = members.partition { it.off }
                ons.forEach { m ->
                    add(row(m.title, m.mark, selected == m.id, child = true, dim = m.dim, detail = m.detail, enabled = m.enabled, chip = m.chip) { onTap?.invoke(m.id) })
                }
                if (offs.isNotEmpty()) {
                    val unfolded = offs.any { it.id == selected }
                    add(fold(offs.size, unfolded) { onTap?.invoke(offs.first().id) })
                    if (unfolded) offs.forEach { m ->
                        add(row(m.title, Mark.NONE, selected == m.id, child = true, dim = m.dim, detail = m.detail, enabled = m.enabled, off = true, chip = m.chip) { onTap?.invoke(m.id) })
                    }
                }
            }
            i += members.size
        }
    }

    private fun add(v: View) {
        val child = v.tag == CHILD
        addView(v, LayoutParams(-1, g.px(if (child) childDp else rowDp)).apply { if (childCount > 0) topMargin = g.px(gapDp) })
    }

    private fun row(
        title: String, mark: Mark, selected: Boolean, child: Boolean, dim: Boolean, detail: String?,
        enabled: Boolean, emphasis: Boolean = false, off: Boolean = false, chip: Chip? = null, onClick: () -> Unit,
    ) = LinearLayout(context).apply {
        tag = if (child) CHILD else null
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(g.px(if (child) 36f else 15f), 0, g.px(12f), 0)
        if (mark != Mark.NONE) addView(Dot(context, mark), LayoutParams(g.px(if (child) 7f else 9f), g.px(if (child) 7f else 9f)).apply { marginEnd = g.px(13f) })
        val color2 = when {
            dim -> Glass.FA
            off && !selected -> OFF
            selected || emphasis || mark == Mark.CURRENT -> Glass.TX
            mark == Mark.PENDING -> Glass.FA
            else -> Glass.MU
        }
        val name = g.text(if (child) 15f else 16.5f, color2).apply { text = title; setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        // The name keeps its x and takes what the chip leaves, shortened with an ellipsis if it must: the chip
        // keeps its whole word at the row's end, as on the Manage page (#235 verifier: "Can't run" was cut).
        addView(name, LayoutParams(0, -2, 1f))
        chip?.let { addView(chip(it), LayoutParams(-2, -2).apply { marginStart = g.px(8f) }) }
        detail?.let { d -> addView(g.text(12.5f, Glass.FA).apply { text = d; setSingleLine() }, LayoutParams(-2, -2).apply { marginStart = g.px(8f) }) }
        background = if (selected) g.surface(12f) else null
        contentDescription = title
        if (enabled) { isClickable = true; setOnClickListener { onClick() } }
    }

    /**
     * The status chip, in the Manage page's style (#243): a pill with a hairline edge on glass, a dot in
     * the status colour, the word in the muted colour.
     */
    private fun chip(c: Chip) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = g.surface(999f, Glass.S1)
        setPadding(g.px(8f), g.px(2f), g.px(9f), g.px(2f))
        addView(g.dot(c.dot), LayoutParams(g.px(6f), g.px(6f)).apply { marginEnd = g.px(6f) })
        // A backstop: the row gives the chip its whole width, but a word longer than any of today's
        // shortens rather than overflow the rail.
        addView(g.text(12f, Glass.MU).apply { text = c.word; setSingleLine(); ellipsize = TextUtils.TruncateAt.END; maxWidth = g.px(110f) })
        contentDescription = "status ${c.word}"
    }

    /** "Off · N" and a chevron, quieter than a row: right while folded, down while open. */
    private fun fold(n: Int, open: Boolean, onClick: () -> Unit) = LinearLayout(context).apply {
        tag = CHILD
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(g.px(36f), 0, g.px(14f), 0)
        addView(g.text(13f, Glass.FA, Inter.MEDIUM).apply { text = "Off · $n"; isAllCaps = true; letterSpacing = 0.12f }, LayoutParams(0, -2, 1f))
        addView(Chevron(context, open), LayoutParams(g.px(12f), g.px(12f)))
        contentDescription = "Off: $n panes"
        isClickable = true
        setOnClickListener { onClick() }
    }

    private class Chevron(context: Context, private val down: Boolean) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.6f * resources.displayMetrics.density; color = Glass.FA
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            path.reset()
            if (down) { path.moveTo(w * 0.15f, h * 0.35f); path.lineTo(w * 0.5f, h * 0.7f); path.lineTo(w * 0.85f, h * 0.35f) }
            else { path.moveTo(w * 0.35f, h * 0.15f); path.lineTo(w * 0.7f, h * 0.5f); path.lineTo(w * 0.35f, h * 0.85f) }
            c.drawPath(path, p)
        }
    }

    /** Done: green. Current: sky to violet. Pending: a dashed ring. */
    private class Dot(context: Context, private val mark: Mark) : View(context) {
        private val dp = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val r = width / 2f
            p.shader = null; p.pathEffect = null; p.style = Paint.Style.FILL
            when (mark) {
                Mark.DONE -> { p.color = Glass.GREEN; c.drawCircle(r, r, r, p) }
                Mark.CURRENT -> {
                    p.color = Color.WHITE
                    p.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), Glass.SKY, Glass.VIOLET, Shader.TileMode.CLAMP)
                    c.drawCircle(r, r, r, p)
                }
                Mark.PENDING -> {
                    p.style = Paint.Style.STROKE; p.color = Glass.FA; p.strokeWidth = 1.5f * dp
                    p.pathEffect = DashPathEffect(floatArrayOf(2f * dp, 1.6f * dp), 0f)
                    c.drawCircle(r, r, r - dp, p)
                }
                Mark.NONE -> {}
            }
        }
    }

    private companion object {
        const val CHILD = "child"
        /** An off pane's name: [Glass.MU] at about 47 %, a little less white (#212). */
        val OFF = Color.argb(120, 0xa1, 0xa1, 0xaa)
    }
}
