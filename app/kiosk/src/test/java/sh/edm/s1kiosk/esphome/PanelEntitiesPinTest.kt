package sh.edm.s1kiosk.esphome

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.esphome.ApiEntity
import sh.edm.esphome.DeviceIdentity
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.base.BaseBackend
import sh.edm.s1kiosk.base.WallBase
import java.io.File

/**
 * Pins the entity list [PanelDevice] gives Home Assistant (a test that fails when a migration PR moves
 * an entity: Home Assistant keys entities by object id, so a changed id is a new entity).
 *
 * It builds the real list through [PanelDevice.initEntities], the same code the device runs at start,
 * with no wall base and no dashboards, and compares it with `panel-entities.golden.txt`: one line per
 * entity, sorted by object id (order does not matter to Home Assistant). Fields: object id, key,
 * class, name, icon, device class, category, authOnly, select options.
 *
 * When a change to the list is on purpose, regenerate the file and commit it with the change:
 * `./gradlew :kiosk:testDebugUnitTest --tests '*PanelEntitiesPinTest*' -Ps1RegenGolden=true`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PanelEntitiesPinTest {

    @Test fun entityListMatchesGolden() {
        val actual = describe(realEntityList())
        if (System.getProperty("s1.regenGolden") == "true") {
            goldenFile().writeText(actual)
            return
        }
        val golden = javaClass.getResourceAsStream("/$GOLDEN")?.bufferedReader()?.readText()
        assertNotNull("$GOLDEN missing; regenerate it (see the class KDoc)", golden)
        assertEquals(golden, actual)
    }

    /** Voice off keeps the same entities: Home Assistant would drop the voice ones otherwise. */
    @Test fun voiceOffKeepsTheSameEntities() {
        if (System.getProperty("s1.regenGolden") == "true") return
        KioskPrefs(RuntimeEnvironment.getApplication()).voice.enabled = false
        assertEquals(javaClass.getResourceAsStream("/$GOLDEN")?.bufferedReader()?.readText(), describe(realEntityList()))
    }

    private fun realEntityList(): List<ApiEntity> {
        val device = PanelDevice(RuntimeEnvironment.getApplication(), GlassworksRuntime(), onVoiceEvent = {})
        try {
            val identity = DeviceIdentity(
                name = PanelDevice.NAME, friendlyName = PanelDevice.FRIENDLY_NAME, macAddress = "02:00:00:00:00:01",
                esphomeVersion = PanelDevice.ESPHOME_VERSION, compilationTime = "test", manufacturer = "Aqara",
                model = "Panel Hub S1 Plus (S1 Kiosk)", voiceFeatures = PanelDevice.VOICE_FEATURES,
            )
            device.initEntities(identity, WallBase.Config(BaseBackend.LOCALSERVICE))
            return device.entityList()
        } finally {
            device.stop()
        }
    }

    private fun describe(entities: List<ApiEntity>): String = entities.sortedBy { it.objectId }.joinToString("") { e ->
        val fields = listOf(
            e.objectId, e.key.toString(), e.javaClass.simpleName, e.name, e.icon, field(e, "deviceClass") ?: "",
            e.category.name, "authOnly=${e.authOnly}", "options=" + (field(e, "options") ?: ""),
        )
        fields.joinToString("|") + "\n"
    }

    private fun field(e: ApiEntity, name: String): String? {
        var c: Class<*>? = e.javaClass
        while (c != null) {
            c.declaredFields.firstOrNull { it.name == name }?.let {
                it.isAccessible = true
                return when (val v = it.get(e)) {
                    is List<*> -> v.joinToString(",")
                    else -> v?.toString()
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun goldenFile(): File {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "THIRD-PARTY-NOTICES.md").isFile }
        return File(root, "app/kiosk/src/test/resources/$GOLDEN")
    }

    private companion object {
        const val GOLDEN = "panel-entities.golden.txt"
    }
}
