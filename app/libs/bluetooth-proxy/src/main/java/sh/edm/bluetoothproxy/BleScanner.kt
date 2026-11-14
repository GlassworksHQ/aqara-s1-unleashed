package sh.edm.bluetoothproxy

/**
 * How hard the radio listens. Android's scan modes, with Android 10's window and interval
 * (ScanManager.java): [LOW_POWER] 512 of every 5120 ms, [BALANCED] 1024 of 4096 ms, [LOW_LATENCY]
 * all the time. An ESPHome proxy listens 30 of every 320 ms by default.
 */
enum class ScanPower { LOW_POWER, BALANCED, LOW_LATENCY }

/** Why a scan didn't start or stopped. */
enum class ScanFailure {
    /** The device has no Bluetooth LE. */
    NO_BLE,
    BLUETOOTH_OFF,
    /** The app lacks the scan or location permission. */
    NO_PERMISSION,
    /** Android hands scan results only to apps while location is on. */
    LOCATION_OFF,
    /** Android refused a 6th scan start within 30 s. */
    TOO_FREQUENT,
    /** Any other `ScanCallback` error. */
    ANDROID_ERROR,
}

/**
 * One BLE scan at a time: [AndroidBleScanner] on a device, a fake in tests. [start] while a scan
 * runs is not allowed; the engine always [stop]s first.
 */
interface BleScanner {
    fun start(power: ScanPower, listener: Listener)

    fun stop()

    /** Called on any thread. */
    interface Listener {
        /**
         * @param address The 48-bit address, big-endian.
         * @param data The scan record as Android gives it: AD structures, possibly zero-padded.
         */
        fun onAdvertisement(address: Long, rssi: Int, data: ByteArray)

        fun onFailed(failure: ScanFailure)
    }
}
