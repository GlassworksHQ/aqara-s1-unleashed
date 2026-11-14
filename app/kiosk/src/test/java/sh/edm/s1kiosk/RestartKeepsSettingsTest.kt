package sh.edm.s1kiosk

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.intentExtras
import dev.glassworks.pane.paneSections
import dev.glassworks.pane.applyFrom
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.esphome.ProtoMessage
import sh.edm.esphome.ProtoWriter
import sh.edm.esphome.SwitchEntity
import sh.edm.s1kiosk.frame.AqaraS1Plus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * #185: a setting saved just before the app exits survives the restart. The app exits right after some
 * changes (a restart from the menu, Home Assistant or adb), and `apply()` writes later, on another thread,
 * so the write can die with the process. [Disk] plays that: what was applied but not committed is gone
 * when the process "restarts".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RestartKeepsSettingsTest {
    private val disk = Disk()

    /** A fresh process over the same files. */
    private fun process() = Disk.Process(RuntimeEnvironment.getApplication(), disk)

    private fun switches(ctx: Context): Map<String, Boolean> =
        GlassworksRuntime(AqaraS1Plus()).paneDefinitions(ctx).mapNotNull { d -> d.switch?.let { d.id to it.on } }.toMap()

    private val allOff = mapOf(
        "assist" to false, "sendspin" to false, "notifications" to false, "bluetooth-proxy" to false, "wall-base" to false,
        "time-zone" to false, "diagnostics" to false,
    )

    private fun definitions(ctx: Context): List<PaneDefinition> = GlassworksRuntime(AqaraS1Plus()).paneDefinitions(ctx)

    @Test fun aPaneTurnedOffInTheSettingsStaysOffAfterARestart() {
        val ctx = process()
        for (s in paneSections(definitions(ctx))) {
            val master = s.master ?: continue
            if (s.id == "radio") continue // its on/off is its mode, kept by the radio controller
            val done = CountDownLatch(1)
            master.save(false) { done.countDown() }
            done.await(5, TimeUnit.SECONDS)
        }
        assertEquals(allOff, switches(process()))
    }

    @Test fun aPaneTurnedOffOnSetupsPanesStepStaysOffAfterARestart() {
        val ctx = process()
        // The radio's card is its mode, kept by the radio controller: the cards that are pane switches.
        val cards = definitions(ctx).filter { it.switch != null && it.setupChoice != null }
        assertEquals(listOf("assist", "sendspin", "notifications", "wall-base"), cards.map { it.id })
        cards.forEach { it.setupChoice!!.apply(false) }
        assertEquals(mapOf("assist" to false, "sendspin" to false, "notifications" to false, "wall-base" to false), switches(process()).filterKeys { it in cards.map { c -> c.id } })
        // Notifications starts off: turned on on the Panes step, it stays on too.
        definitions(process()).first { it.id == "notifications" }.setupChoice!!.apply(true)
        assertEquals(true, switches(process())["notifications"])
    }

    @Test fun aPaneTurnedOffFromHomeAssistantStaysOffAfterARestart() {
        val ctx = process()
        for (d in definitions(ctx)) {
            val e = d.switch?.entity() as SwitchEntity? ?: continue
            e.command(ProtoMessage(ProtoWriter().bool(2, false).toByteArray()))
        }
        assertEquals(allOff, switches(process()))
    }

    @Test fun aPaneTurnedOffWithAnExtraThenRestartedStaysOff() {
        val ctx = process()
        val intent = Intent().putExtra("restart", true)
        listOf("voice_enabled", "sendspin_enabled", "notifications_enabled", "bluetooth_proxy_enabled", "wall_base_enabled", "time_zone_enabled", "diagnostics_enabled")
            .forEach { intent.putExtra(it, false) }
        definitions(ctx).intentExtras().applyFrom(intent)
        assertEquals(allOff, switches(process()))
    }

    @Test fun theSettingsTheAppRestartsForSurviveTheRestart() {
        KioskPrefs(process()).apply {
            zoom = 1.25f
            extraSecureHosts = "192.0.2.40"
            remoteDebugging = true
            urlSetting = "http://192.0.2.10:8123/lovelace/0"
        }
        val after = KioskPrefs(process())
        assertEquals(listOf<Any>(1.25f, "192.0.2.40", true, "http://192.0.2.10:8123/lovelace/0"),
            listOf<Any>(after.zoom, after.extraSecureHosts, after.remoteDebugging, after.urlSetting))
    }

    /** The files on "disk": only what was committed. */
    private class Disk {
        val files = HashMap<String, HashMap<String, Any?>>()

        /** One process: its prefs start from the disk, and keep applied writes only in memory. */
        class Process(base: Context, private val disk: Disk) : ContextWrapper(base) {
            private val open = HashMap<String, Prefs>()
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                open.getOrPut(name) { Prefs(disk.files.getOrPut(name) { HashMap() }) }
        }

        class Prefs(private val onDisk: HashMap<String, Any?>) : SharedPreferences {
            private val memory = HashMap(onDisk)
            private val listeners = ArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

            override fun getAll(): Map<String, *> = HashMap(memory)
            override fun getString(key: String, def: String?) = memory[key] as String? ?: def
            override fun getStringSet(key: String, def: Set<String>?) = @Suppress("UNCHECKED_CAST") (memory[key] as Set<String>?) ?: def
            override fun getInt(key: String, def: Int) = memory[key] as Int? ?: def
            override fun getLong(key: String, def: Long) = memory[key] as Long? ?: def
            override fun getFloat(key: String, def: Float) = memory[key] as Float? ?: def
            override fun getBoolean(key: String, def: Boolean) = memory[key] as Boolean? ?: def
            override fun contains(key: String) = key in memory
            override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners += l }
            override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners -= l }

            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
                private val changes = HashMap<String, Any?>()
                private val removed = HashSet<String>()
                private var clear = false
                override fun putString(k: String, v: String?) = apply { changes[k] = v }
                override fun putStringSet(k: String, v: Set<String>?) = apply { changes[k] = v }
                override fun putInt(k: String, v: Int) = apply { changes[k] = v }
                override fun putLong(k: String, v: Long) = apply { changes[k] = v }
                override fun putFloat(k: String, v: Float) = apply { changes[k] = v }
                override fun putBoolean(k: String, v: Boolean) = apply { changes[k] = v }
                override fun remove(k: String) = apply { removed += k }
                override fun clear() = apply { clear = true }

                private fun into(m: HashMap<String, Any?>) {
                    if (clear) m.clear()
                    removed.forEach(m::remove)
                    m.putAll(changes)
                }

                /** In memory now; the disk write would come later, so a restart loses it. */
                override fun apply() {
                    into(memory)
                    changes.keys.forEach { k -> listeners.forEach { it.onSharedPreferenceChanged(this@Prefs, k) } }
                }

                override fun commit(): Boolean {
                    apply()
                    into(onDisk)
                    return true
                }
            }
        }
    }
}
