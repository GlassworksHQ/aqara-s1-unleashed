/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import sh.edm.esphome.ActionArg
import sh.edm.esphome.ActionArgType
import sh.edm.esphome.ActionResponse
import sh.edm.esphome.EntityCategory
import java.util.concurrent.CompletableFuture

class NotificationEntitiesTest {
    private val center = NotificationCenter(
        NotificationStore({ 0L }),
        object : NotificationOutputs {
            override fun render(cards: List<NotificationStore.Entry>, page: NotificationStore.Entry?, list: List<NotificationStore.Entry>) {}
            override fun wake(overNight: Boolean) {}
            override fun play(sound: Sound) {}
        },
        {}, { false }, { null }, { 0L }, { _, _ -> CompletableFuture<Unit>() },
    )

    @Test
    fun theNotifyActionHasCompanionsShape() {
        var dnd = false
        val e = NotificationEntities(center, { dnd }, { dnd = it })
        assertEquals("notify", e.notify.name)
        assertEquals(
            listOf(ActionArg("message", ActionArgType.STRING), ActionArg("title", ActionArgType.STRING), ActionArg("data", ActionArgType.STRING)),
            e.notify.args,
        )
        assertEquals("mistakes come back to the script", ActionResponse.STATUS, e.notify.supportsResponse)
        assertEquals("do_not_disturb", e.doNotDisturb.objectId)
        assertEquals(EntityCategory.CONFIG, e.doNotDisturb.category)
        assertFalse("people automate it, so it's on in HA", e.doNotDisturb.disabledByDefault)
        assertEquals(listOf("notify", "do_not_disturb"), e.all.map { it.objectId })
    }
}
