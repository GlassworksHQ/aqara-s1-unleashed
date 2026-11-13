package sh.edm.s1kiosk.radio.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import dev.glassworks.pane.SetupCard
import dev.glassworks.pane.SetupHost
import dev.glassworks.pane.SetupScreen
import sh.edm.s1kiosk.radio.RadioDefinition
import sh.edm.s1kiosk.radio.RadioMode
import java.util.concurrent.atomic.AtomicReference

/**
 * The radio's sub-step under Panes in setup (#63 round 2), shown while the radio's card on the Panes
 * step is on: the mode, technology first ([RadioTechPicker], no Off), applied with Continue through the
 * same request as the installer's `radio.set_mode`; then the mode's follow-up card, the one Settings →
 * Radio shows, with what to do in Home Assistant. A Thread router can join Home Assistant's Thread
 * network from that card here: setup signed in before Panes. Main thread.
 */
internal class RadioSetupScreen(
    private val context: Context,
    private val host: SetupHost,
    private val def: RadioDefinition,
) : SetupScreen {

    private enum class Phase { CHOOSE, APPLYING, FOLLOW_UP }

    private val main = Handler(Looper.getMainLooper())
    private var phase = Phase.CHOOSE
    private var problem: String? = null

    private val picker = RadioTechPicker(context).apply { onPick = { host.redraw() } }
    /** The follow-up card. In a preview every request is inert: nothing reaches the radio or Home Assistant. */
    internal val section = RadioSectionView(context).apply {
        onRun = { r, done -> if (host.preview) done(PREVIEW_INERT) else def.run(r, "setup screen", done) { host.redraw() } }
        onDataset = { hex -> if (host.preview) PREVIEW_INERT else def.setDataset(hex) }
        // No onAsk: setup has no dialog layer, so the controls that ask first (the Zigbee stack, Leave)
        // are hidden here; they're in Settings → Radio.
    }
    private val followUp = SetupStyle(context).let { s ->
        // The card's room under the heading, with Continue below: it scrolls when the role says more.
        BoundedScroll(context, s.px(172f)).apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(section.panel, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
    }

    override fun bind(card: SetupCard, entered: Boolean) {
        if (entered) {
            // Back on the step: the mode in effect, or the coordinator offered first on a fresh panel.
            phase = if (def.followUpOpen) Phase.FOLLOW_UP else Phase.CHOOSE
            problem = null
            picker.chosen = def.readout.mode().takeIf { it != RadioMode.OFF } ?: RadioMode.ZIGBEE_TCP
        }
        when (phase) {
            Phase.CHOOSE, Phase.APPLYING -> {
                card.title("What should the radio do?")
                card.body(null)
                card.content(picker)
                picker.isEnabled = phase == Phase.CHOOSE
                when {
                    phase == Phase.APPLYING -> card.status("Starting the radio…", SetupCard.Tone.AMBER)
                    problem != null -> card.status(problem, SetupCard.Tone.ROSE)
                    else -> card.status(null)
                }
                card.primary("Continue", enabled = phase == Phase.CHOOSE) { apply() }
            }
            Phase.FOLLOW_UP -> {
                val mode = def.readout.mode()
                card.title("${RadioStep.OPTIONS.firstOrNull { it.mode == mode }?.title?.substringBefore(" (") ?: "The radio"} is on.")
                card.body(null)
                def.readout.facts()?.let(section::bind)
                card.content(followUp)
                card.tightButtons()
                card.primary("Continue") { finish() }
                card.secondary("Change the mode") {
                    phase = Phase.CHOOSE
                    if (!host.preview) def.answering = true // the step stays until a mode is applied and its card closed
                    def.followUpOpen = false
                    host.redraw()
                }
            }
        }
    }

    override fun tick(current: Boolean) {
        if (current && phase == Phase.FOLLOW_UP) { def.readout.controller()?.refreshSoon(); host.redraw() }
    }

    /** Continue on the mode: off the main thread, within [BUDGET_MS] (the helper can take a while). */
    private fun apply() {
        val mode = picker.chosen ?: return
        if (host.preview) { phase = Phase.FOLLOW_UP; host.redraw(); return }
        def.answering = true // held from here until the follow-up's Continue, also through a failure
        phase = Phase.APPLYING
        problem = null
        host.redraw()
        val result = AtomicReference<String?>()
        val worker = Thread({ result.set(def.setModeFrom(mode.id, "setup screen")) }, "s1-setup-radio").apply { isDaemon = true; start() }
        val started = System.currentTimeMillis()
        fun check() {
            when {
                worker.isAlive && System.currentTimeMillis() - started < BUDGET_MS -> main.postDelayed(::check, 250)
                worker.isAlive -> { problem = DIDNT_ANSWER; phase = Phase.CHOOSE; host.redraw() }
                result.get() != null -> { problem = result.get(); phase = Phase.CHOOSE; host.redraw() }
                else -> { def.followUpOpen = true; phase = Phase.FOLLOW_UP; host.redraw() }
            }
        }
        main.postDelayed(::check, 250)
    }

    /** Continue on the follow-up: the step is answered. */
    private fun finish() {
        if (!host.preview) { def.answering = false; def.followUpOpen = false }
        phase = Phase.CHOOSE
        host.next()
    }

    /** For the mock-ups: a phase without the radio. */
    internal fun preview(followUp: Boolean, problem: String? = null) {
        phase = if (followUp) Phase.FOLLOW_UP else Phase.CHOOSE
        this.problem = problem
    }

    companion object {
        /** RadioDefinition.setMode waits up to 25 s for the controller; past this, setup says so. */
        const val BUDGET_MS = 30_000L
        const val DIDNT_ANSWER = "The radio didn't answer. Try again, or turn it off on Panes."
        const val PREVIEW_INERT = "Nothing changes in a preview."
    }
}
