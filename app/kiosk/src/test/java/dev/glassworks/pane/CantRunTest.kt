/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.pane

import android.content.pm.PackageManager
import dev.glassworks.frame.Capability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** #23: a pane needs what its definition `requires`; on a frame without it, it can't run and nothing can start it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CantRunTest {
    private val file = RuntimeEnvironment.getApplication().getSharedPreferences("cant-run-test", 0)

    private val radio = object : PaneDefinition {
        override val id = "radio"
        override val requires = setOf(Capability.SILABS_RADIO)
        override val switch = PaneSwitch(file, "radio_enabled", true, "Radio", "Zigbee and Thread", "radio_enabled", inSetup = true)
        override val extras = listOf(IntentExtra.string("radio_mode") {})
        override val setupSteps = listOf(object : SetupStep {
            override val id = "radio"
            override val title = "Radio"
            override val reason = "radio mode not chosen"
            override fun done() = false
            override fun screen(context: android.content.Context, host: SetupHost) = throw UnsupportedOperationException()
        })
        override fun settings() = listOf(SettingsSection("radio", "Radio", emptyList(), heading = "Zigbee / Thread radio"))
    }

    @Test fun aFrameWithTheHardwareRunsThePaneAsItIs() =
        assertSame(radio, CantRun.unless(radio, setOf(Capability.SILABS_RADIO, Capability.SPEAKER)))

    @Test fun aFrameWithoutItGetsAPageThatSaysWhyAndNothingThatStartsIt() {
        val d = CantRun.unless(radio, setOf(Capability.SPEAKER, Capability.MICROPHONE))
        assertTrue(d is CantRun)
        assertEquals("radio", d.id)
        assertNull("no on/off", d.switch)
        assertNull("no card on setup's Panes step", d.setupChoice)
        assertEquals("setup waits for none of its steps", emptyList<SetupStep>(), d.setupSteps)
        assertEquals(emptyList<IntentExtra>(), d.extras)
        val page = d.settings(null).single()
        assertEquals("Radio", page.title)
        assertEquals("Zigbee / Thread radio", page.heading)
        assertEquals("it has no Silicon Labs radio", page.unavailable())
        assertEquals("Needs a Silicon Labs radio.", (page.items.single() as SettingsItem.Note).text())
        assertEquals(PaneState(PaneState.Kind.CANT_RUN, "it has no Silicon Labs radio"), paneState(paneSections(listOf(d)).single()))
    }

    @Test fun aDeviceHasWhatItsFeaturesSay() {
        val pixel = setOf(PackageManager.FEATURE_MICROPHONE, PackageManager.FEATURE_AUDIO_OUTPUT, PackageManager.FEATURE_BLUETOOTH_LE, PackageManager.FEATURE_CAMERA_ANY)
        assertEquals(setOf(Capability.MICROPHONE, Capability.SPEAKER, Capability.BLUETOOTH_LE, Capability.CAMERA), Capability.ofDevice { it in pixel })
        assertEquals("a TV box with no mic", setOf(Capability.SPEAKER), Capability.ofDevice { it == PackageManager.FEATURE_AUDIO_OUTPUT })
        assertEquals("no feature names a Silicon Labs radio or a wall base", 4, Capability.ofDevice { true }.size)
    }
}
