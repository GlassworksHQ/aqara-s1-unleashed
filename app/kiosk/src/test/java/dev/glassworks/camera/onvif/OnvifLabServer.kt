/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera.onvif

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * A lab, not a test: serves [OnvifServer] on 127.0.0.1 for a real ONVIF client to talk to
 * (tools/camera/onvif-lab.sh runs it with Home Assistant's client, docs/research/onvif-rtsp.md).
 * Skipped unless ONVIF_LAB_SECONDS is set. Listens on localhost only and touches no device.
 *
 * ONVIF_LAB_SECONDS: how long to serve. ONVIF_LAB_PORT (18000), ONVIF_LAB_LOG (the server's log, one
 * line per entry). ONVIF_LAB_RESTART_AT: restart after that many seconds, which drops every
 * subscription (an app that went away). ONVIF_LAB_CREDS_CHANGE_AT: change the sign-in then (a client
 * that kept an older one). Motion turns on and off every 20 s. The sign-in is viewer / made-up-pass.
 */
class OnvifLabServer {
    @Test fun serve() {
        val secs = System.getenv("ONVIF_LAB_SECONDS")?.toLongOrNull()
        assumeTrue("set ONVIF_LAB_SECONDS to serve", secs != null)
        val port = System.getenv("ONVIF_LAB_PORT")?.toIntOrNull() ?: 18000
        val log = File(System.getenv("ONVIF_LAB_LOG") ?: "/tmp/onvif-lab-server.log").apply { writeText("") }
        fun note(s: String) = synchronized(log) { log.appendText("${System.currentTimeMillis()} $s\n") }
        val pass = AtomicReference("made-up-pass")
        val restartAt = System.getenv("ONVIF_LAB_RESTART_AT")?.toLongOrNull()
        val credsAt = System.getenv("ONVIF_LAB_CREDS_CHANGE_AT")?.toLongOrNull()
        val s = OnvifServer(
            port = port, host = { "127.0.0.1" },
            info = { OnvifDeviceInfo("Glassworks", "Lab", "0", "LAB1", "lab", "02:00:00:00:00:09", "Lab panel") },
            stream = { OnvifStreamInfo("rtsp://127.0.0.1:8554/live", 1280, 720, 15, 1400) },
            credentials = { "viewer" to pass.get() }, snapshot = { null }, enableDiscovery = false, onLog = ::note,
        )
        s.start()
        val start = System.currentTimeMillis()
        var motion = false
        var nextMotion = start + 20_000
        var restarted = false
        var changed = false
        while (System.currentTimeMillis() - start < secs!! * 1000) {
            Thread.sleep(200)
            val t = (System.currentTimeMillis() - start) / 1000
            if (restartAt != null && !restarted && t >= restartAt) { s.stop(); Thread.sleep(500); s.start(); restarted = true; note("LAB restarted") }
            if (credsAt != null && !changed && t >= credsAt) { pass.set("another-pass"); changed = true; note("LAB sign-in changed") }
            if (System.currentTimeMillis() > nextMotion) { motion = !motion; s.setMotion(motion); note("LAB motion=$motion"); nextMotion += 20_000 }
        }
        s.stop()
    }
}
