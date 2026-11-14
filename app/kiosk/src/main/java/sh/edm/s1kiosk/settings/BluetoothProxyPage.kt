package sh.edm.s1kiosk.settings

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.bluetoothproxy.BluetoothProxyModel
import dev.glassworks.bluetoothproxy.ProxyPhase
import dev.glassworks.bluetoothproxy.ProxyPhase.BLUETOOTH_OFF
import dev.glassworks.bluetoothproxy.ProxyPhase.LISTENING
import dev.glassworks.bluetoothproxy.ProxyPhase.LOCATION_OFF
import dev.glassworks.bluetoothproxy.ProxyPhase.NO_BLE
import dev.glassworks.bluetoothproxy.ProxyPhase.NO_PERMISSION
import dev.glassworks.bluetoothproxy.ProxyPhase.OFF
import dev.glassworks.bluetoothproxy.ProxyPhase.RETRYING
import dev.glassworks.bluetoothproxy.ProxyPhase.WAITING
import dev.glassworks.pane.SettingsItem
import sh.edm.bluetoothproxy.ProxyStatus
import sh.edm.s1kiosk.ui.Glass
import sh.edm.s1kiosk.ui.Glass.Companion.BD
import sh.edm.s1kiosk.ui.Glass.Companion.FA
import sh.edm.s1kiosk.ui.Glass.Companion.MU
import sh.edm.s1kiosk.ui.Glass.Companion.ROSE
import sh.edm.s1kiosk.ui.Glass.Companion.SKY
import sh.edm.s1kiosk.ui.Glass.Companion.TX
import sh.edm.s1kiosk.ui.Glass.Companion.VIOLET

/**
 * The Bluetooth proxy's settings page, as approved on #121 (2026-11-13): a status line with no dot,
 * a card when something stops it (Bluetooth or location off), the advertisements and scanner tiles,
 * then the pane's entities as Home Assistant shows them. The Mode block (listen only, or listen and
 * connect) waits for GATT connections, and so does the connections tile.
 */
class BluetoothProxyPage(private val host: SettingsItem.NativeHost, private val proxy: BluetoothProxyModel) : SettingsItem.NativeView {
    private val c: Context = host.context
    private val g = Glass(c)
    private fun px(v: Float) = g.px(v)

    private val line = g.text(15f, MU).apply { setPadding(0, px(2f), 0, px(8f)) }

    private val cardTitle = g.heading(22f)
    private val cardText = g.text(15f, MU).apply { setLineSpacing(0f, 1.25f) }
    private val cardButton = g.primary("").apply { setPadding(px(24f), px(10f), px(24f), px(10f)) }
    /** The phase whose button the panel refused: its card says so until the phase changes. */
    private var refused: ProxyPhase? = null
    private val card = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = g.surface(24f)
        setPadding(px(26f), px(22f), px(26f), px(22f))
        addView(cardTitle)
        addView(cardText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
        addView(LinearLayout(c).apply { addView(cardButton) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16f) })
    }

    private val ads = Tile("Advertisements", "a minute")
    private val scanner = Tile("Scanner", "")
    private val spark = Spark(c)
    private val tiles = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(ads.view.apply { addView(spark, LinearLayout.LayoutParams(-1, px(22f)).apply { topMargin = px(8f) }) }, LinearLayout.LayoutParams(0, -1, 1f))
        addView(scanner.view.apply { addView(View(c), LinearLayout.LayoutParams(-1, px(22f)).apply { topMargin = px(8f) }) },
            LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = px(12f) })
    }

    private val note = g.text(15f, MU).apply { setLineSpacing(0f, 1.25f) }
    private val adsRow = HaRow("Bluetooth advertisements")
    private val statusRow = HaRow("Bluetooth proxy status")
    private val inHa = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        addView(g.label("In Home Assistant"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(22f); bottomMargin = px(6f) })
        addView(adsRow.view)
        addView(statusRow.view)
        addView(g.text(13.5f, FA).apply { text = FOOTNOTE; setLineSpacing(0f, 1.2f) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12f) })
    }

    override val view: View = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        addView(line)
        addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(12f) })
        addView(tiles)
        addView(note)
        addView(inHa)
    }

    init { refresh() }

    override fun refresh() {
        val phase = proxy.phase()
        val s = proxy.status()
        line.text = line(phase, s)
        line.setTextColor(if (phase == RETRYING) ROSE else MU)
        line.visibility = if (phase == OFF || phase == NO_BLE) View.GONE else View.VISIBLE
        showCard(phase)
        tiles.visibility = if (phase in TILES) View.VISIBLE else View.GONE
        note.visibility = if (phase == OFF || phase == NO_BLE) View.VISIBLE else View.GONE
        note.text = if (phase == NO_BLE) NO_BLE_NOTE else OFF_NOTE
        inHa.visibility = if (phase == NO_BLE) View.GONE else View.VISIBLE

        val perMinute = s?.advertisementsPerMinute ?: 0
        ads.set(perMinute.toString(), if (s != null && s.devicesPerMinute > 0) "From ${s.devicesPerMinute} devices" else "None just now")
        spark.values = s?.lastHour.orEmpty()
        scanner.set(
            if (s?.scanning == true) "On" else "Off",
            when (phase) {
                LISTENING -> "Listening all the time"
                WAITING -> "Not needed yet"
                RETRYING -> retry(s)
                else -> "Off"
            },
        )
        adsRow.value(if (phase == OFF) null else "$perMinute a minute")
        statusRow.value(phase.entityText)
    }

    private fun line(phase: ProxyPhase, s: ProxyStatus?) = when (phase) {
        LISTENING -> "Listening for Home Assistant" + (s?.devicesPerMinute?.takeIf { it > 0 }?.let { " · $it devices nearby" } ?: "")
        WAITING -> "Waiting for Home Assistant. The radio stays quiet until it listens."
        BLUETOOTH_OFF -> "Bluetooth is off"
        LOCATION_OFF -> "Location is off"
        NO_PERMISSION -> "A permission is missing"
        RETRYING -> "Android stopped the scan. ${retry(s)}."
        OFF, NO_BLE -> ""
    }

    private fun retry(s: ProxyStatus?) = s?.retryInMs?.let { "Trying again in ${(it + 999) / 1000} s" } ?: "Trying again shortly"

    private fun showCard(phase: ProxyPhase) {
        val (title, text, action) = when (phase) {
            BLUETOOTH_OFF -> Triple("Turn Bluetooth on to listen", "The proxy uses the panel's own Bluetooth. Turning it on doesn't pair anything.", "Turn on Bluetooth")
            LOCATION_OFF -> Triple(
                "Turn location on to listen",
                "Android hands Bluetooth results to apps only while location is on. The panel doesn't use or share its location.",
                "Turn on location",
            )
            NO_PERMISSION -> Triple("The app can't scan", "It needs the location permission to hear Bluetooth devices. The installer grants it.", null)
            else -> { card.visibility = View.GONE; refused = null; return }
        }
        if (refused != phase) refused = null
        card.visibility = View.VISIBLE
        cardTitle.text = title
        cardText.text = if (refused == phase) REFUSED else text
        cardButton.visibility = if (action != null) View.VISIBLE else View.GONE
        cardButton.text = action ?: ""
        cardButton.setOnClickListener {
            val done = when (phase) {
                BLUETOOTH_OFF -> proxy.turnBluetoothOn() || open(Settings.ACTION_BLUETOOTH_SETTINGS)
                LOCATION_OFF -> proxy.turnLocationOn() || open(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                else -> true
            }
            if (!done) refused = phase
            refresh()
        }
    }

    /** Android's own screen for it, where the device has one. The S1 has none. */
    private fun open(action: String): Boolean {
        val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i.resolveActivity(c.packageManager) == null) return false
        return runCatching { c.startActivity(i) }.isSuccess
    }

    /** A reading: its name, the value large with its unit, a line under it. */
    private inner class Tile(name: String, private val unit: String) {
        private val value = g.heading(27f).apply { setSingleLine() }
        private val unitView = g.text(14f, MU).apply { text = unit; setPadding(px(5f), 0, 0, px(4f)) }
        private val sub = g.text(13.5f, MU).apply { setSingleLine() }
        val view = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = g.surface(20f)
            setPadding(px(16f), px(13f), px(16f), px(12f))
            addView(g.label(name))
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                addView(value)
                if (unit.isNotEmpty()) addView(unitView)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) })
            addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(1f) })
        }

        fun set(v: String, line: String) {
            value.text = v
            sub.text = line
        }
    }

    /** An entity as Home Assistant shows it: its name on the left, its state on the right. */
    private inner class HaRow(name: String) {
        private val valueView: TextView = g.text(15f, MU).apply { setSingleLine() }
        val view = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(View(c).apply { setBackgroundColor(BD) }, LinearLayout.LayoutParams(-1, px(1f).coerceAtLeast(1)))
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = px(46f)
                addView(g.text(15.5f, TX).apply { text = name; setSingleLine() }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(valueView)
            })
        }

        /** Null: the entity is unavailable (the pane is off). */
        fun value(v: String?) {
            valueView.text = v ?: "Unavailable"
            valueView.setTextColor(if (v == null) FA else MU)
        }
    }

    /** The last hour's advertisements a minute: the brand's sky-to-violet, quiet, never a status colour. */
    private class Spark(context: Context) : View(context) {
        var values: List<Int> = emptyList()
            set(v) { if (v != field) { field = v; invalidate() } }
        private val dp = resources.displayMetrics.density
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.6f * dp; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val v = if (values.size >= 2) values else listOf(0, 0)
            val lo = v.min().toFloat()
            val hi = v.max().toFloat().coerceAtLeast(lo + 1f)
            val pad = 2f * dp
            val path = Path()
            v.forEachIndexed { i, x ->
                val px = i * w / (v.size - 1)
                val py = pad + (h - 2 * pad) * (1f - (x - lo) / (hi - lo))
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            fill.shader = LinearGradient(0f, 0f, 0f, h, Color.argb(38, 0x81, 0x8c, 0xf8), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawPath(Path(path).apply { lineTo(w, h); lineTo(0f, h); close() }, fill)
            stroke.shader = LinearGradient(0f, 0f, w, 0f, alpha(SKY), alpha(VIOLET), Shader.TileMode.CLAMP)
            canvas.drawPath(path, stroke)
        }

        private fun alpha(c: Int) = Color.argb(200, Color.red(c), Color.green(c), Color.blue(c))
    }

    private companion object {
        val TILES = setOf(LISTENING, WAITING, RETRYING)
        const val OFF_NOTE = "The Bluetooth proxy is off. The panel doesn't scan, and Home Assistant no longer hears devices through it."
        const val NO_BLE_NOTE = "Home Assistant can still use other Bluetooth proxies, such as an ESP32 near the sensors."
        const val REFUSED = "This panel doesn't let apps do that. Turn it on in the panel's own settings."
        const val FOOTNOTE = "Home Assistant finds the proxy by itself, under Bluetooth. Addresses go only to Home Assistant; the panel doesn't keep or log them."
    }
}
