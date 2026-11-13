package sh.edm.s1kiosk.setup

/**
 * What the loading screen says when the dashboard didn't load because the panel has no network.
 *
 * After a reboot the panel joins its saved Wi-Fi a few seconds after the app starts. Saying "No network
 * yet" plus how to set up Wi-Fi in that window is wrong and alarming, so while Wi-Fi is on and a join is
 * underway or expected, it says "Connecting to Wi-Fi…" instead. The setup hint (setup screen, Improv)
 * comes only when there is nothing to wait for: Wi-Fi off, no saved network, or no connection within
 * [graceMs] (the same 30 s after which Improv starts by itself, ImprovController.BOOT_GRACE_MS).
 * Never shows an SSID. Pure, for tests; MainActivity gathers the [Facts].
 */
object OfflineText {

    data class Facts(
        val wifiEnabled: Boolean,
        /** Android lists a saved network (true), lists none (false), or won't tell this app (null). */
        val savedNetwork: Boolean?,
        /** Wi-Fi is associating, authenticating or getting an address right now. */
        val joining: Boolean,
        /** The panel was online over Wi-Fi before (or reached Home Assistant): it has a network to rejoin. */
        val wasOnlineBefore: Boolean,
        /** How long the panel has been without a network (from app start at boot). */
        val offlineForMs: Long,
    )

    data class Text(val title: String, val detail: String, val connecting: Boolean)

    const val CONNECTING_TITLE = "Connecting to Wi-Fi…"
    const val CONNECTING_DETAIL = "Home Assistant loads as soon as it's connected"
    const val NO_NETWORK_TITLE = "No network yet"
    const val WIFI_OFF_TITLE = "Wi-Fi is off"
    const val SETUP_HINT = "Set it up from the setup screen, or from Home Assistant."
    /** The button under [SETUP_HINT]: Android's Wi-Fi settings, where Wi-Fi can be turned on and a network chosen. */
    const val SETUP_ACTION = "Set up Wi-Fi"

    /**
     * Wi-Fi is joining a network right now, from WifiManager's SupplicantState and ConnectivityManager's
     * Wi-Fi NetworkInfo.DetailedState (enum names; both readable without location on Android 10).
     * COMPLETED / CONNECTED while the panel is still offline = associated, waiting for an address.
     */
    fun joining(supplicantState: String?, detailedState: String?): Boolean =
        supplicantState in JOINING_SUPPLICANT || detailedState in JOINING_DETAILED

    private val JOINING_SUPPLICANT = setOf(
        "AUTHENTICATING", "ASSOCIATING", "ASSOCIATED", "FOUR_WAY_HANDSHAKE", "GROUP_HANDSHAKE", "COMPLETED",
    )
    private val JOINING_DETAILED = setOf("CONNECTING", "AUTHENTICATING", "OBTAINING_IPADDR", "CONNECTED")

    /** A join is underway or expected: Wi-Fi on and either joining now or with a network to join. */
    fun expectsJoin(f: Facts): Boolean =
        f.wifiEnabled && (f.joining || f.savedNetwork == true || (f.savedNetwork == null && f.wasOnlineBefore))

    fun decide(f: Facts, graceMs: Long): Text = when {
        expectsJoin(f) && f.offlineForMs < graceMs -> Text(CONNECTING_TITLE, CONNECTING_DETAIL, connecting = true)
        !f.wifiEnabled -> Text(WIFI_OFF_TITLE, SETUP_HINT, connecting = false)
        else -> Text(NO_NETWORK_TITLE, SETUP_HINT, connecting = false)
    }
}
