package sh.edm.s1kiosk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.auth.StoredAuth
import sh.edm.s1kiosk.base.BaseBackend
import sh.edm.s1kiosk.base.BaseLayout
import sh.edm.s1kiosk.base.UartBaseLink
import sh.edm.s1kiosk.base.WallBase
import sh.edm.s1kiosk.sendspin.PrefsSsStore
import sh.edm.s1kiosk.sendspin.SsPairingRecord
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Pins every SharedPreferences file and key the app reads or writes, with its type and the default
 * the code passes when the key is absent. Panels update in place, so a moved key or file loses a
 * setting on every panel that has it.
 *
 * It runs every accessor of the prefs classes against a context that records each call to
 * SharedPreferences: every getter, every simple setter, and the calls listed in [exerciseComposite].
 * Reflection finds them, starting at [roots] and following every getter that returns a `*Prefs` or
 * `*Store` object, so a new property or a new per-pane prefs class is pinned without touching this
 * test. A public method with arguments that is neither a setter nor listed fails
 * [everyAccessorIsExercised]. The record is compared with `prefs.golden.txt`: one line per key,
 * `file|key|type|default`.
 *
 * When a key, file, type or default changes on purpose, regenerate the file and commit it with the
 * change: `./gradlew :kiosk:testDebugUnitTest --tests '*PrefsPinTest*' -Ps1RegenGolden=true`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PrefsPinTest {

    @Test fun keysMatchGolden() {
        val actual = record { ctx -> exerciseAll(ctx) }.describe()
        if (System.getProperty("s1.regenGolden") == "true") {
            goldenFile().writeText(actual)
            return
        }
        val golden = javaClass.getResourceAsStream("/$GOLDEN")?.bufferedReader()?.readText()
        assertNotNull("$GOLDEN missing; regenerate it (see the class KDoc)", golden)
        assertEquals(golden, actual)
    }

    @Test fun everyAccessorIsExercised() {
        val app = RuntimeEnvironment.getApplication()
        val unhandled = prefsObjects(app).flatMap { obj ->
            publicMethods(obj).filter { it.parameterCount > 0 && setterType(it) == null }
                .map { "${obj.javaClass.name}.${it.name}" }
                .filter { name -> COMPOSITE.none { name.endsWith(it) } }
        }
        assertTrue("not pinned by PrefsPinTest; call them in exerciseComposite: $unhandled", unhandled.isEmpty())
    }

    /** The prefs classes the app builds from a context. */
    private fun roots(ctx: Context): List<Any> = listOf(KioskPrefs(ctx), PrefsSsStore(ctx))

    /** [roots] and every prefs or store object reachable from them through getters. */
    private fun prefsObjects(ctx: Context): List<Any> {
        val found = java.util.IdentityHashMap<Any, Unit>()
        fun visit(obj: Any) {
            if (found.put(obj, Unit) != null) return
            for (m in publicMethods(obj)) {
                if (m.parameterCount != 0 || !isPrefsClass(m.returnType)) continue
                m.invoke(obj)?.let(::visit)
            }
        }
        roots(ctx).forEach(::visit)
        return found.keys.toList()
    }

    private fun isPrefsClass(c: Class<*>) = c.simpleName.endsWith("Prefs") || c.simpleName.endsWith("Store")

    private fun exerciseAll(ctx: Context) {
        for (obj in prefsObjects(ctx)) exerciseBeans(obj)
        exerciseComposite(ctx)
        exerciseViews(ctx)
    }

    /** Every method without arguments (getters and the like), then every setter with a sample value, then the first again. */
    private fun exerciseBeans(obj: Any) {
        val methods = publicMethods(obj)
        val getters = methods.filter { it.parameterCount == 0 && !isPrefsClass(it.returnType) }
        getters.forEach { it.invoke(obj) }
        for (m in methods) {
            val type = setterType(m) ?: continue
            m.invoke(obj, SAMPLES.getValue(type))
        }
        getters.forEach { it.invoke(obj) }
    }

    private fun exerciseComposite(ctx: Context) {
        val kiosk = KioskPrefs(ctx)
        kiosk.setHaUrl("http://192.0.2.10:8123", "manual")
        kiosk.auth = StoredAuth("http://192.0.2.10:8123", "http://192.0.2.20/", "pin-token")
        kiosk.auth
        kiosk.auth = null
        kiosk.noiseKeyStore.save(ByteArray(32))
        kiosk.noiseKeyStore.save(null)
        KioskPrefs.PANE_FILES.keys.forEach(kiosk::paneFile)
        kiosk.camera.use = dev.glassworks.camera.CameraUse.PRESENCE_AND_SNAPSHOTS
        kiosk.camera.signIn = "pin-user" to "pin-pass"
        kiosk.camera.signIn = null
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> }
        kiosk.registerListener(listener)
        kiosk.unregisterListener(listener)

        val ss = PrefsSsStore(ctx)
        ss.putRecord(SsPairingRecord("pin-server", ByteArray(32), 1L), emptySet())
        ss.touchRecord("pin-server", 2L)
        ss.removeRecord("pin-server")
    }

    /** Prefs that live in a view or a driver rather than in a prefs class. */
    private fun exerciseViews(ctx: Context) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        TimerOverlay(Recorder.wrap(activity, ctx))

        // The wall base reads its energy counter when the UART link starts: the real port (blocked
        // here, so it is never opened) and the emulator's separate counter.
        for (transport in listOf(UartBaseLink.SERIAL_PATH, "tcp:127.0.0.1:1")) {
            val base = WallBase(ctx, BaseLayout.NONE, NoBase, WallBase.Config(BaseBackend.UART, transport) { "pin test" })
            base.start()
            base.stop()
        }
    }

    private object NoBase : WallBase.Listener {
        override fun onStates() {}
        override fun onLayout(layout: BaseLayout) {}
        override fun onButton(channel: Int, type: String) {}
    }

    private fun publicMethods(obj: Any): List<Method> =
        obj.javaClass.methods.filter { it.declaringClass != Any::class.java && !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }

    private fun setterType(m: Method): Class<*>? =
        m.parameterTypes.singleOrNull()?.takeIf { m.name.startsWith("set") && it in SAMPLES }

    private fun record(block: (Context) -> Unit): Recorder {
        val rec = Recorder(RuntimeEnvironment.getApplication())
        block(rec)
        return rec
    }

    /** A context whose SharedPreferences note every key read or written. */
    private class Recorder(base: Context) : ContextWrapper(base) {
        class Entry(val types: MutableSet<String> = sortedSetOf(), val defaults: MutableSet<String> = sortedSetOf())

        private val log = HashMap<Pair<String, String>, Entry>()

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            proxy(name, super.getSharedPreferences(name, mode))

        private fun note(file: String, key: String?, type: String?, default: Any?, read: Boolean) {
            val e = log.getOrPut(file to (key ?: "<null>")) { Entry() }
            type?.let { e.types += it }
            if (read && type != null) e.defaults += render(file, key, default)
        }

        private fun proxy(file: String, real: SharedPreferences): SharedPreferences =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, m, args ->
                val a = args ?: emptyArray()
                when {
                    m.name == "edit" -> return@newProxyInstance editor(file, real.edit())
                    m.name == "contains" -> note(file, a[0] as String?, null, null, read = true)
                    m.name.startsWith("get") && m.name != "getAll" -> note(file, a[0] as String?, m.name.removePrefix("get"), a[1], read = true)
                }
                m.invoke(real, *a)
            } as SharedPreferences

        private fun editor(file: String, real: SharedPreferences.Editor): SharedPreferences.Editor {
            lateinit var self: SharedPreferences.Editor
            self = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, m, args ->
                val a = args ?: emptyArray()
                when {
                    m.name == "remove" -> note(file, a[0] as String?, null, null, read = false)
                    m.name.startsWith("put") -> note(file, a[0] as String?, m.name.removePrefix("put"), null, read = false)
                }
                val r = m.invoke(real, *a)
                if (r === real) self else r
            } as SharedPreferences.Editor
            return self
        }

        fun describe(): String = buildString {
            appendLine("# file|key|type|default (see PrefsPinTest)")
            for ((k, e) in log.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))) appendLine("${k.first}|${k.second}|${e.types.joinToString(",").ifEmpty { "-" }}|${e.defaults.joinToString(",").ifEmpty { "-" }}")
        }

        companion object {
            /** [view] supplies the theme a view needs; the prefs calls go to [rec]. */
            fun wrap(view: Context, rec: Context): Context = object : ContextWrapper(view) {
                override fun getSharedPreferences(name: String, mode: Int) = rec.getSharedPreferences(name, mode)
            }

            /** The remote-debugging default is the build type, so the debug and release runs share one golden. */
            fun render(file: String, key: String?, default: Any?): String = when {
                file == "kiosk" && key == "remote_debug" -> {
                    check(default == BuildConfig.DEBUG) { "remote_debug default is no longer BuildConfig.DEBUG" }
                    "BuildConfig.DEBUG"
                }
                default is String -> "\"$default\""
                else -> default.toString()
            }
        }
    }

    private fun goldenFile(): File {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "THIRD-PARTY-NOTICES.md").isFile }
        return File(root, "app/kiosk/src/test/resources/$GOLDEN")
    }

    private companion object {
        const val GOLDEN = "prefs.golden.txt"

        val SAMPLES: Map<Class<*>, Any> = mapOf(
            java.lang.Boolean.TYPE to true, Integer.TYPE to 7, java.lang.Float.TYPE to 0.5f, String::class.java to "pin",
        )

        /** Methods with arguments that [exerciseComposite] calls by hand, by class suffix and name. */
        val COMPOSITE = setOf(
            "KioskPrefs.setHaUrl", "KioskPrefs.setAuth", "KioskPrefs.paneFile", "KioskPrefs.registerListener", "KioskPrefs.unregisterListener",
            ".save", "PrefsSsStore.putRecord", "PrefsSsStore.touchRecord", "PrefsSsStore.removeRecord",
            "CameraPrefs.setUse", "CameraPrefs.setSignIn",
        )
    }
}
