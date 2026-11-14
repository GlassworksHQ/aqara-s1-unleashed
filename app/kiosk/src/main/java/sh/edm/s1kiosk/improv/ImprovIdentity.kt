package sh.edm.s1kiosk.improv

import dev.glassworks.frame.Frame
import dev.glassworks.frame.friendlyName
import dev.glassworks.platform.Platform
import sh.edm.s1kiosk.BuildConfig

/**
 * What the device says about itself over Improv (`RPC_REQUEST_INFO`): firmware, version, chip and name.
 * The name is its friendly name, as Home Assistant shows it.
 */
internal object ImprovIdentity {
    private const val FIRMWARE = "S1 Kiosk"

    /** Over BLE. The chip field is the maker, the model and `Build.HARDWARE`: "Aqara Panel Hub S1 Plus/sun50iw10p1". */
    fun ble(frame: Frame): List<String> =
        listOf(FIRMWARE, BuildConfig.VERSION_NAME, "${frame.hardware.manufacturer} ${frame.hardware.model}/${Platform.hardware}", frame.friendlyName())

    /** Over the adb socket (Improv serial). The chip field is the frame's chip: "Allwinner A133". */
    fun serial(frame: Frame): List<String> = listOf(FIRMWARE, BuildConfig.VERSION_NAME, frame.hardware.chip, frame.friendlyName())
}
