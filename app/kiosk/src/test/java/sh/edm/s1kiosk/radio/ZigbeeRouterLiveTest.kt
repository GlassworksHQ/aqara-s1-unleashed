package sh.edm.s1kiosk.radio

import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The router host against the real zigbeed, from a computer: skipped unless S1_EZSP_ROUTER=host:port.
 * With the panel in `zigbee_router` mode:
 *
 *   adb forward tcp:6639 tcp:6639
 *   S1_EZSP_ROUTER=127.0.0.1:6639 ./gradlew :kiosk:testDebugUnitTest --tests '*ZigbeeRouterLiveTest*' -i
 *
 * Always a dry run: it scans and reports what it would join, never calls joinNetwork. With
 * S1_EZSP_ROUTER_LEAVE=1 it first makes zigbeed leave (erase) the network in its NVM: the helper
 * keeps a copy of the NVM from before the mode started in /data/s1kiosk/radio/zigbee-nvm/.
 */
class ZigbeeRouterLiveTest {
    @Test
    fun readyToJoinAndScan() {
        val target = System.getenv("S1_EZSP_ROUTER")
        assumeTrue("set S1_EZSP_ROUTER=host:port to run against a panel", !target.isNullOrEmpty())
        val (host, port) = target!!.split(":").let { it[0] to it[1].toInt() }
        val log = { s: String -> println("[${System.currentTimeMillis() % 100000}] $s") }
        val h = ZigbeeRouterHost(connect = { ZigbeeRouterHost.tcp(host, port) }, log = log, dryRunJoin = true)
        h.start()
        try {
            val settled = setOf(ZigbeeRouterHost.State.NOT_JOINED, ZigbeeRouterHost.State.JOINED, ZigbeeRouterHost.State.HOLDS_COORDINATOR)
            fun waitSettled(ms: Long = 60_000): ZigbeeRouterHost.Snapshot {
                val until = System.currentTimeMillis() + ms
                while (System.currentTimeMillis() < until) {
                    val s = h.snapshot
                    if (s.state in settled || s.state == ZigbeeRouterHost.State.ERROR) return s
                    Thread.sleep(100)
                }
                throw AssertionError("no settled state: ${h.status}")
            }
            println("STATUS: ${waitSettled().let { h.status }}")
            if (System.getenv("S1_EZSP_ROUTER_LEAVE") == "1") {
                val before = h.snapshot
                h.requestLeave()
                val until = System.currentTimeMillis() + 30_000
                while (h.snapshot == before && System.currentTimeMillis() < until) Thread.sleep(100)
                println("STATUS after leave: ${waitSettled().let { h.status }}")
            }
            h.requestJoin() // dry run: scan only
            Thread.sleep(500)
            val until = System.currentTimeMillis() + 90_000
            while (h.snapshot.state == ZigbeeRouterHost.State.JOINING && System.currentTimeMillis() < until) Thread.sleep(200)
            println("STATUS after scan: ${h.status}")
        } finally {
            h.stop()
        }
    }
}
