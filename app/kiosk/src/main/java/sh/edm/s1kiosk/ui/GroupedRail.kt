package sh.edm.s1kiosk.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * The left rail of setup and of the settings menu (#63 round 2): rows, some under a group (setup's
 * Home Assistant and Panes, settings' Panes). A group shows its rows while one of them is selected and
 * folds to one row otherwise. Tapping a row selects it; tapping a folded group selects its first open
 * row. Main thread.
 */
class GroupedRail(context: Context) : LinearLayout(context) {

    /** The dot before a row's name. */
    enum class Mark { DONE, CURRENT, PENDING, STATUS, NONE }

    /**
     * One row. [group]: the group it sits under, or null. [color]: the dot for [Mark.STATUS]. [dim]: greyed
     * (a pane that can't run), [detail] then says why. [enabled]: it can be tapped.
     */
    class Row(
        val id: String, val title: String, val group: String? = null, val mark: Mark = Mark.NONE,
        val color: Int = Glass.GREEN, val enabled: Boolean = true, val dim: Boolean = false, val detail: String? = null,
    )

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
                add(row(r.title, r.mark, r.color, selected == r.id, child = false, dim = r.dim, detail = r.detail, enabled = r.enabled) { onTap?.invoke(r.id) })
                i++
                continue
            }
            val members = rows.drop(i).takeWhile { it.group == group }
            val mark = when {
                open == group -> Mark.CURRENT
                members.all { it.mark == Mark.DONE } -> Mark.DONE
                members.any { it.mark == Mark.CURRENT } -> Mark.CURRENT
                members.all { it.mark == Mark.NONE || it.mark == Mark.STATUS } -> Mark.NONE
                else -> Mark.PENDING
            }
            val first = members.firstOrNull { it.enabled && it.mark != Mark.DONE } ?: members.firstOrNull { it.enabled }
            add(row(groups[group] ?: group, mark, Glass.GREEN, selected = false, child = false, dim = false, detail = null,
                enabled = first != null, emphasis = open == group) { first?.let { onTap?.invoke(it.id) } })
            if (open == group) members.forEach { m ->
                add(row(m.title, m.mark, m.color, selected == m.id, child = true, dim = m.dim, detail = m.detail, enabled = m.enabled) { onTap?.invoke(m.id) })
            }
            i += members.size
        }
    }

    private fun add(v: View) {
        val child = v.tag == CHILD
        addView(v, LayoutParams(-1, g.px(if (child) childDp else rowDp)).apply { if (childCount > 0) topMargin = g.px(gapDp) })
    }

    private fun row(
        title: String, mark: Mark, color: Int, selected: Boolean, child: Boolean, dim: Boolean, detail: String?,
        enabled: Boolean, emphasis: Boolean = false, onClick: () -> Unit,
    ) = LinearLayout(context).apply {
        tag = if (child) CHILD else null
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(g.px(if (child) 36f else 15f), 0, g.px(12f), 0)
        if (mark != Mark.NONE) addView(Dot(context, mark, color), LayoutParams(g.px(if (child) 7f else 9f), g.px(if (child) 7f else 9f)).apply { marginEnd = g.px(13f) })
        val color2 = when {
            dim -> Glass.FA
            selected || emphasis || mark == Mark.CURRENT -> Glass.TX
            mark == Mark.PENDING -> Glass.FA
            else -> Glass.MU
        }
        addView(g.text(if (child) 15f else 16.5f, color2).apply { text = title; setSingleLine(); ellipsize = TextUtils.TruncateAt.END },
            LayoutParams(0, -2, 1f))
        detail?.let { d -> addView(g.text(12.5f, Glass.FA).apply { text = d; setSingleLine() }, LayoutParams(-2, -2).apply { marginStart = g.px(8f) }) }
        background = if (selected) g.surface(12f) else null
        contentDescription = title
        if (enabled) { isClickable = true; setOnClickListener { onClick() } }
    }

    /** Done: green. Current: sky to violet. Pending: a dashed ring. Status: its colour. */
    private class Dot(context: Context, private val mark: Mark, private val color: Int) : View(context) {
        private val dp = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val r = width / 2f
            p.shader = null; p.pathEffect = null; p.style = Paint.Style.FILL
            when (mark) {
                Mark.DONE -> { p.color = Glass.GREEN; c.drawCircle(r, r, r, p) }
                Mark.STATUS -> { p.color = color; c.drawCircle(r, r, r, p) }
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
    }
}
