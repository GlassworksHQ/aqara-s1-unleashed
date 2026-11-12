package sh.edm.s1kiosk.gecko

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.MediaSource
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLog
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.MainActivity
import sh.edm.s1kiosk.auth.HaAuth
import sh.edm.s1kiosk.auth.StoredAuth
import sh.edm.s1kiosk.dashboard.Dashboards
import sh.edm.s1kiosk.improv.ImprovController
import java.time.Duration

/**
 * Runs the real MainActivity on GeckoShadows. Tests see only what the app asks of GeckoView (the
 * recorded events) and what the page and the user see (ports, the loading screen, logs). This file
 * is the only one the GeckoView-behind-Engine refactor may touch; the pins stay as they are.
 */
class GeckoPinHarness(
    startIntent: Intent? = null,
    prefs: (KioskPrefs) -> Unit = {},
) : AutoCloseable {
    val app: Application = RuntimeEnvironment.getApplication()
    val controller: ActivityController<MainActivity>

    init {
        GeckoShadows.reset()
        Dashboards::class.java.getDeclaredField("latest").apply { isAccessible = true }.set(null, null)
        haAuthPrefs()?.auth = null
        holdRestarts()
        prefs(KioskPrefs(app))
        ShadowLog.clear()
        controller = (if (startIntent == null) Robolectric.buildActivity(MainActivity::class.java)
            else Robolectric.buildActivity(MainActivity::class.java, startIntent)).setup()
        idle()
    }

    val activity: MainActivity get() = controller.get()
    val events: List<String> get() = GeckoShadows.events.toList()
    val session: GeckoSession get() = GeckoShadows.sessions.last()
    val permission get() = session.permissionDelegate!!
    val content get() = session.contentDelegate!!
    val navigation get() = session.navigationDelegate!!
    val progress get() = session.progressDelegate!!

    /** Events recorded while [block] ran (and the main looper was idled after it). */
    fun eventsDuring(block: () -> Unit): List<String> {
        val before = GeckoShadows.events.size
        block()
        idle()
        return GeckoShadows.events.drop(before)
    }

    fun idle() = shadowOf(Looper.getMainLooper()).idle()
    fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    fun newIntent(i: Intent) { controller.newIntent(i); idle() }

    /** The activity's root views, bottom to top. */
    fun rootChildren(): List<android.view.View> {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0) as ViewGroup
        return (0 until root.childCount).map { root.getChildAt(it) }
    }

    val loading: sh.edm.s1kiosk.setup.LoadingOverlay
        get() = rootChildren().filterIsInstance<sh.edm.s1kiosk.setup.LoadingOverlay>().single()

    /** The loading screen's title and detail, once its cross-fade has run. */
    fun loadingTexts(): List<String> {
        advance(1_000)
        val out = mutableListOf<String>()
        fun walk(v: android.view.View) {
            if (v is android.widget.TextView) out += v.text.toString()
            if (v is ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) }
        }
        walk(loading)
        return out.filter { it.isNotEmpty() }
    }

    /**
     * Android lets an activity ask for one set of permissions at a time and cancels the rest. Clears
     * that flag, as answering the dialog on the panel does.
     */
    fun permissionDialogAnswered() {
        android.app.Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest").apply { isAccessible = true }.setBoolean(activity, false)
    }

    fun logs(tag: String): List<String> = ShadowLog.getLogsForTag(tag).map { it.msg }
    fun errors(tag: String): List<String> = ShadowLog.getLogsForTag(tag).filter { it.type == Log.ERROR }.map { it.msg }

    /** Signs the panel in to [base] with a cached access token, without any network. */
    fun signIn(base: String, accessToken: String = "test-access-token", expiresIn: Long = 1800) {
        haAuthPrefs()!!.auth = StoredAuth(base, "http://192.0.2.99/", "test-refresh-token")
        val cache = HaAuth::class.java.getDeclaredField("cache").apply { isAccessible = true }.get(HaAuth)
        cache.javaClass.getMethod("put", String::class.java, Long::class.javaPrimitiveType).invoke(cache, accessToken, expiresIn)
    }

    fun connect(url: String, topLevel: Boolean = true): GeckoShadows.FakePort {
        val port = GeckoShadows.FakePort(url, topLevel)
        GeckoShadows.messageDelegate!!.onConnect(port)
        idle()
        return port
    }

    /** Sends [message] on [port] as content.js does; waits up to 5 s for [replies] new posts. */
    fun send(port: GeckoShadows.FakePort, message: Any, replies: Int = 1): List<JSONObject> {
        val before = port.posted.size
        port.delegate!!.onPortMessage(message, port)
        val until = System.currentTimeMillis() + 5_000
        while (port.posted.size < before + replies && System.currentTimeMillis() < until) {
            idle()
            Thread.sleep(5)
        }
        idle()
        return port.posted.drop(before)
    }

    fun <T> value(r: GeckoResult<T>?): T? {
        if (r == null) return null
        var out: T? = null
        r.accept { out = it }
        idle()
        return out
    }

    fun loadRequest(uri: String, target: Int = GeckoSession.NavigationDelegate.TARGET_WINDOW_CURRENT): GeckoSession.NavigationDelegate.LoadRequest {
        val r = GeckoShadows.allocate(GeckoSession.NavigationDelegate.LoadRequest::class.java)
        GeckoShadows.setField(r, r.javaClass, "uri", uri)
        GeckoShadows.setField(r, r.javaClass, "target", target)
        return r
    }

    fun contentPermission(uri: String, permission: Int): ContentPermission {
        val p = GeckoShadows.allocate(ContentPermission::class.java)
        GeckoShadows.setField(p, ContentPermission::class.java, "uri", uri)
        GeckoShadows.setField(p, ContentPermission::class.java, "permission", permission)
        return p
    }

    fun mediaSource(source: Int, name: String): MediaSource {
        val m = GeckoShadows.allocate(MediaSource::class.java)
        GeckoShadows.setField(m, MediaSource::class.java, "source", source)
        GeckoShadows.setField(m, MediaSource::class.java, "name", name)
        return m
    }

    override fun close() {
        haAuthPrefs()?.auth = null
        controller.pause().stop().destroy()
    }

    companion object {
        /** HaAuth's own prefs (process-wide, bound to the first context that initialised it). */
        fun haAuthPrefs(): KioskPrefs? {
            val f = HaAuth::class.java.getDeclaredField("prefs").apply { isAccessible = true }
            return f.get(HaAuth) as KioskPrefs?
        }

        /**
         * A restart would end the JVM (exitProcess). Holding Improv "busy" makes MainActivity log
         * `restart deferred` instead, which a test can see.
         */
        private fun holdRestarts() {
            val f = ImprovController::class.java.getDeclaredField("settleUntil").apply { isAccessible = true }
            f.setLong(ImprovController, Long.MAX_VALUE)
            forgetProcessStatics()
        }

        /** Before the refactor the app kept the GeckoRuntime and the helper extension in KioskApp statics. */
        private fun forgetProcessStatics() {
            for (name in listOf("runtime", "helper")) {
                val f = runCatching { sh.edm.s1kiosk.KioskApp::class.java.getDeclaredField(name) }.getOrNull() ?: continue
                f.isAccessible = true
                f.set(null, null)
            }
        }
    }
}
