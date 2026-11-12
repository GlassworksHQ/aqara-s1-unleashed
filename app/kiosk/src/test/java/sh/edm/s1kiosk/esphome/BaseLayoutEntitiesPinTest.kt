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
import sh.edm.s1kiosk.base.BaseKind
import sh.edm.s1kiosk.base.BaseLayout
import sh.edm.s1kiosk.base.WallBase
import java.io.File

/**
 * Pins the entity list Home Assistant gets with a wall base mounted, for each kind of base: relays,
 * buttons, power and energy for a switch base, firmware for the others. Like [PanelEntitiesPinTest]
 * (same fields, sorted by object id), but the panel starts with a saved base layout, the way it does
 * after a restart with a base on the wall. Golden: `panel-entities-base.golden.txt`, one section per
 * layout.
 *
 * Regenerate on purpose only:
 * `./gradlew :kiosk:testDebugUnitTest --tests '*BaseLayoutEntitiesPinTest*' -Ps1RegenGolden=true`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BaseLayoutEntitiesPinTest {

    private val layouts = listOf(
        BaseLayout(BaseKind.SWITCH, listOf(1)),
        BaseLayout(BaseKind.SWITCH, listOf(1, 2)),
        BaseLayout(BaseKind.SWITCH, listOf(1, 2, 3)),
        BaseLayout(BaseKind.ETHERNET),
        BaseLayout(BaseKind.UNKNOWN, rawClass = 0x22),
    )

    @Test fun entityListsMatchGolden() {
        val actual = layouts.joinToString("") { layout -> "## ${layout.encode()}\n" + describe(entitiesWith(layout)) }
        if (System.getProperty("s1.regenGolden") == "true") {
            goldenFile().writeText(actual)
            return
        }
        val golden = javaClass.getResourceAsStream("/$GOLDEN")?.bufferedReader()?.readText()
        assertNotNull("$GOLDEN missing; regenerate it (see the class KDoc)", golden)
        assertEquals(golden, actual)
    }

    private fun entitiesWith(layout: BaseLayout): List<ApiEntity> {
        val app = RuntimeEnvironment.getApplication()
        KioskPrefs(app).baseLayout = layout.encode()
        val device = PanelDevice(app, GlassworksRuntime(), onVoiceEvent = {})
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
            "eventTypes=" + (field(e, "eventTypes") ?: ""),
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
        const val GOLDEN = "panel-entities-base.golden.txt"
    }
}
