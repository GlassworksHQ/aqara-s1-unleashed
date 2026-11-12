package sh.edm.s1kiosk.radio

import android.util.Log
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PanelEvent
import sh.edm.esphome.ApiEntity
import sh.edm.esphome.ButtonEntity
import sh.edm.esphome.EntityCategory
import sh.edm.esphome.SelectEntity
import sh.edm.esphome.TextEntity
import sh.edm.esphome.TextSensorEntity
import sh.edm.s1kiosk.BuildConfig
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.auth.HaAuth
import sh.edm.s1kiosk.auth.ThreadFetch
import sh.edm.s1kiosk.auth.ThreadJoin

/**
 * The S1's Zigbee/Thread radio as a pane (docs/radio.md): the [RadioController], its Home Assistant
 * entities, and the Thread join from Home Assistant. While it runs, the setup screen, the setup
 * channel and the settings menu reach the controller through [GlassworksRuntime.radio].
 */
class RadioPane(
    private val ctx: PaneContext,
    private val runtime: GlassworksRuntime,
    /** The panel's LAN address, for the ZHA serial path in the status text. */
    panelIp: () -> String?,
    internal val radio: RadioController = newController(ctx, panelIp),
) : Pane {
    override val id = "radio"

    private val threadJoinListener: (ThreadJoin.State) -> Unit = { ctx.publishStates() }

    private val entities: List<ApiEntity> = listOf(
        SelectEntity(
            "radio_mode", "Radio mode", "mdi:zigbee", options = RadioMode.LABELS,
            get = { radio.mode.label }, set = { radio.request(it) },
        ),
        TextSensorEntity(
            "radio_status", "Radio status", "mdi:radio-tower", EntityCategory.DIAGNOSTIC,
            get = { radio.statusText() },
        ),
        // Who may reach the radio's network services (EZSP :6638, OTBR REST :8081); empty = Home Assistant only.
        TextEntity(
            "radio_allowed_clients", "Radio allowed clients", "mdi:shield-lock-outline", maxLength = RadioAllow.MAX_LEN,
            get = { radio.allowedClients() },
            set = { v ->
                radio.setAllowedClients(v)?.let { Log.w(RadioController.TAG, "Radio allowed clients: $it") }
                ctx.publishStates()
            },
        ),
        // Zigbee stack behind the coordinator mode (helper v4): Aqara's (EZSP 12) or Silicon Labs' (EZSP 18,
        // for Zigbee2MQTT; the panel downloads it from Silicon Labs). docs/radio.md "Zigbee stack".
        SelectEntity(
            "zigbee_stack", "Zigbee stack", "mdi:layers-outline", options = ZigbeeStack.LABELS,
            get = { radio.zigbeeStack.label }, set = { radio.setZigbeeStack(it) },
        ).apply { authOnly = true },
        TextSensorEntity("radio_helper", "Radio helper", "mdi:shield-check-outline", EntityCategory.DIAGNOSTIC, get = { radio.helperStatusText() }),
        // Radio firmware (helper v4): never automatic. Pick an image (armed for 2 minutes), then press Flash.
        TextSensorEntity("radio_firmware", "Radio firmware", "mdi:chip", EntityCategory.DIAGNOSTIC, get = { radio.firmwareText() }),
        SelectEntity(
            "radio_firmware_image", "Radio firmware image", "mdi:chip", options = RadioFirmware.OPTIONS,
            get = { radio.firmwareSelected() }, set = { radio.firmwareSelect(it) },
        ).apply { authOnly = true },
        ButtonEntity("radio_firmware_flash", "Flash radio firmware", "mdi:flash-alert", EntityCategory.CONFIG, press = { radio.firmwareFlash() })
            .apply { authOnly = true },
        // Zigbee router: join/leave the network that has permit-join open (docs/radio.md "Zigbee router").
        ButtonEntity("zigbee_router_join", "Join Zigbee network", "mdi:zigbee", EntityCategory.CONFIG, press = { radio.routerJoin() }),
        ButtonEntity("zigbee_router_leave", "Leave Zigbee network", "mdi:link-variant-off", EntityCategory.CONFIG, press = { radio.routerLeave() }),
        // Forget the Thread network (helper v4, any mode): deletes the dataset (network key) and router settings.
        ButtonEntity("thread_leave", "Leave Thread network", "mdi:link-variant-off", EntityCategory.CONFIG, press = { radio.threadLeave() })
            .apply { authOnly = true },
        // Join HA's preferred Thread network without copying the dataset (auth/ThreadJoin): the panel's
        // own sign-in if it's an administrator, else the panel shows a QR code for a one-off admin sign-in.
        ButtonEntity("thread_join_ha", "Join Home Assistant's Thread network", "mdi:qrcode-scan", EntityCategory.CONFIG,
            press = { HaAuth.threadJoin.start() })
            .apply { authOnly = true },
        TextSensorEntity(
            "thread_join", "Thread join", "mdi:transit-connection-variant", EntityCategory.DIAGNOSTIC,
            get = { ThreadJoin.describe(HaAuth.threadJoin.state) },
        ),
        // Thread router: write-only. The dataset is a secret (it holds the network key): the state is
        // always empty, it goes to the root helper and is never logged or echoed back.
        TextEntity(
            // Pattern: hex plus the invisible characters a copy carries (spaces, line breaks, no-break and
            // zero-width spaces), which setThreadDataset drops. 255 is Home Assistant's cap for any text
            // entity (MAX_LENGTH_STATE_STATE); longer datasets go in through the panel's settings menu.
            "thread_dataset", "Thread dataset", "mdi:key-chain", maxLength = 255, pattern = ThreadDataset.PATTERN, mode = 1,
            get = { "" },
            set = { v ->
                if (v.isNotEmpty()) radio.setThreadDataset(v)?.let { Log.w(RadioController.TAG, it) }
                ctx.publishStates()
            },
        ),
    )

    override fun entities(): List<ApiEntity> = entities

    private val deliverToRadio: (ThreadFetch.Ok) -> String? = { r -> radio.joinThreadNetwork(r.tlv) }

    override fun start() {
        runtime.radio = radio
        HaAuth.threadJoin.deliver = deliverToRadio
        HaAuth.threadJoin.addListener(threadJoinListener)
        radio.start()
    }

    override fun stop() {
        HaAuth.threadJoin.removeListener(threadJoinListener)
        // Both are process-wide: clear them only while they are still this pane's.
        if (HaAuth.threadJoin.deliver === deliverToRadio) HaAuth.threadJoin.deliver = null
        if (runtime.radio === radio) runtime.radio = null
        radio.stop()
    }

    override fun onEvent(event: PanelEvent) {
        // Only Home Assistant may reach the radio's TCP services; only an authenticated one may say where it is.
        if (event is PanelEvent.HomeAssistantSubscribed && event.authenticated) radio.homeAssistantConnected(event.address)
    }

    private companion object {
        fun newController(ctx: PaneContext, panelIp: () -> String?): RadioController {
            val app = ctx.context
            val prefs = ctx.prefs
            return RadioController(
                load = { prefs.radioMode },
                store = { prefs.radioMode = it },
                onChange = ctx.publishStates,
                panelIp = panelIp,
                filesDir = app.filesDir,
                routerHostFactory = { onStatus ->
                    ZigbeeRouterHost(log = { Log.i(RadioController.TAG, it) }, onStatus = { onStatus() })
                },
                allow = prefs.radioAllowStore,
                stackStore = prefs.radioStackStore,
                bundle = RadioBundle(ShellProps, AssetBundleSource(app.assets), app.filesDir, log = { Log.i(RadioController.TAG, it) }),
                zigbeedFetcher = { dest, progress ->
                    SisdkZigbeed.fetch(dest, app.cacheDir, "s1-kiosk/${BuildConfig.VERSION_NAME} (+https://github.com/GlassworksHQ/aqara-s1-unleashed)", progress)
                },
            )
        }
    }
}
