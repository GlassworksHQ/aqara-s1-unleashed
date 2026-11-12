/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine

import android.app.Activity
import android.view.View
import org.json.JSONObject

/**
 * The web engine that shows the dashboard. One per process: it reads its [EngineSettings] once, at
 * the first [open], so the process must restart to change them.
 *
 * It is a kiosk engine. It allows every permission a page asks for without a prompt, and grants
 * the microphone and the camera to `getUserMedia`. Only Android's own runtime permissions go to the
 * app ([PageHost.onAndroidPermissions]), because only an activity can ask for them.
 */
interface Engine {
    /** What the engine started with, or null before the first [open]. */
    val started: EngineSettings?

    /**
     * Opens an empty page in [activity]. The caller adds [Page.view] to its layout and loads a URL.
     * The view shows [background] until the page first paints. [host] hears from the page.
     */
    fun open(activity: Activity, host: PageHost, background: Int): Page
}

/** What an engine starts with. */
data class EngineSettings(
    /** Hosts treated as secure contexts, so a plain-http page there can use the microphone. */
    val secureHosts: List<String>,
    /** CSS pixels per screen pixel. 0 keeps the engine's own. */
    val zoom: Float,
    /** Lets a desktop browser debug the page. */
    val remoteDebugging: Boolean,
)

/** A page in one activity. Call it on the main thread. */
interface Page {
    val view: View

    fun load(url: String)
    fun reload()

    /** Goes back one page, if the page has history. */
    fun goBack()

    /** Replaces the page with a new, empty one in the same view, after [PageHost.onContentGone]. */
    fun restart()

    /** Sends [message] to every connected [PagePort]. A port that fails is skipped. */
    fun post(message: JSONObject)
}

/** A page script's connection to the app (the web extension's native port). */
interface PagePort {
    /** The URL of the page that opened the port. */
    val url: String?

    /** True for the top-level document, false for a frame. */
    val topLevel: Boolean

    fun post(message: JSONObject)
}

/** What a [Page] reports. Every call comes on the main thread. */
interface PageHost {
    fun onPageStart(url: String)
    fun onPageStop(success: Boolean)
    fun onFirstPaint()
    fun onLocationChange(url: String?)

    /** A top-level load in the page's own window. Return a URL to load instead, or null to let it load. */
    fun redirect(url: String): String?

    /** The content process crashed, or the system [killed] it. The page shows nothing until [Page.restart]. */
    fun onContentGone(killed: Boolean)

    /** The page needs these Android runtime permissions. Call [answer] once: true if all are granted. */
    fun onAndroidPermissions(permissions: List<String>, answer: (Boolean) -> Unit)

    fun onConnect(port: PagePort)

    /** A message from a port. Only JSON objects reach the host. */
    fun onPortMessage(port: PagePort, message: JSONObject)
}
