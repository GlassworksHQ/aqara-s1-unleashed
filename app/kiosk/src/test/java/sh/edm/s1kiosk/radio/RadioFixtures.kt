package sh.edm.s1kiosk.radio

import android.content.Context
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.radio.ui.RadioFollowUp
import sh.edm.s1kiosk.setup.SetupState

/** A radio as the mock-ups and view tests show it: fixed facts, no controller behind it. */
class FixedReadout(
    var facts: RadioFollowUp.Facts?,
    private val running: Boolean = facts != null,
    private val allowedNow: String? = null,
    private val mode: RadioMode = facts?.mode ?: RadioMode.OFF,
    private val haBase: String? = facts?.haBase,
    private val panelIp: String? = facts?.panelIp,
) : RadioReadout {
    override fun controller(): RadioController? = null
    override fun running() = running
    override fun mode() = facts?.mode ?: mode
    override fun facts() = facts
    override fun haBase() = haBase
    override fun panelIp() = panelIp
    override fun allowedClients() = ""
    override fun allowedNow() = allowedNow
}

/** The radio's definition over [readout], with the app's prefs (`kiosk.xml`). */
fun radioDefinition(context: Context, readout: RadioReadout): RadioDefinition {
    val prefs = KioskPrefs(context)
    return RadioDefinition(prefs.radio, { SetupState.setUpBefore(prefs.haBase != null, prefs.haSeen, prefs.apiEncryption, prefs.haSetupSkipped) }, readout)
}
