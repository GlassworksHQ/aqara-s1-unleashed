/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifyRequestTest {
    private val ha = "http://homeassistant.local:8123"

    private fun post(message: String = "Someone is at the door", title: String = "Doorbell", data: String = "") =
        (NotifyRequest.parse(message, title, data, ha) as NotifyCommand.Post).notification

    private fun refused(data: String, message: String = "m", base: String? = ha): String =
        assertThrows(IllegalArgumentException::class.java) { NotifyRequest.parse(message, "t", data, base) }.message!!

    @Test
    fun aPlainNotification() {
        val n = post(data = "")
        assertEquals("Doorbell", n.title)
        assertEquals("Someone is at the door", n.message)
        assertEquals(Priority.NORMAL, n.priority)
        assertEquals(Sound.CHIME, n.sound)
        assertNull(n.tag); assertNull(n.image); assertNull(n.url); assertNull(n.timeoutS)
        assertTrue(n.actions.isEmpty())
        assertFalse(n.sticky)
        assertEquals(n, post(data = "{}"))
        assertEquals(n, post(data = "  "))
    }

    @Test
    fun theDoorbellRecipe() {
        val n = post(data = """
            {"tag": "doorbell", "priority": "high", "sound": "doorbell", "url": "/dashboard-door/0", "timeout": 90,
             "image": "/api/camera_proxy/camera.porch",
             "actions": [{"action": "open_gate", "title": "Open the gate"}, {"action": "snooze", "title": "Snooze", "uri": "/lovelace/1"}],
             "sticky": true, "channel": "doorbell", "color": "#ff0000", "ttl": 0}
        """)
        assertEquals("doorbell", n.tag)
        assertEquals(Priority.HIGH, n.priority)
        assertEquals(Sound.DOORBELL, n.sound)
        assertEquals("$ha/dashboard-door/0", n.url)
        assertEquals("$ha/api/camera_proxy/camera.porch", n.image)
        assertEquals(90, n.timeoutS)
        assertEquals(
            listOf(NotificationAction("open_gate", "Open the gate"), NotificationAction("snooze", "Snooze", "$ha/lovelace/1")),
            n.actions,
        )
        assertTrue(n.sticky)
    }

    @Test
    fun clearNotificationByTag() {
        assertEquals(NotifyCommand.Clear("doorbell"), NotifyRequest.parse("clear_notification", "", """{"tag":"doorbell"}""", ha))
        assertTrue(refused("{}", message = "clear_notification").contains("tag"))
    }

    @Test
    fun companionPriorities() {
        assertEquals(Priority.HIGH, post(data = """{"priority":"high"}""").priority)
        assertEquals(Priority.NORMAL, post(data = """{"priority":"normal"}""").priority)
        assertEquals(Priority.LOW, post(data = """{"push":{"interruption-level":"passive"}}""").priority)
        assertEquals(Priority.HIGH, post(data = """{"push":{"interruption-level":"time-sensitive"}}""").priority)
        assertEquals(Priority.CRITICAL, post(data = """{"push":{"interruption-level":"critical"}}""").priority)
        // priority wins over the iOS key
        assertEquals(Priority.LOW, post(data = """{"priority":"low","push":{"interruption-level":"critical"}}""").priority)
        assertTrue(refused("""{"priority":"urgent"}""").contains("urgent"))
    }

    @Test
    fun soundFollowsPriorityUnlessNamed() {
        assertEquals(Sound.NONE, post(data = """{"priority":"low"}""").sound)
        assertEquals(Sound.CHIME, post(data = """{"priority":"high"}""").sound)
        assertEquals(Sound.ALERT, post(data = """{"priority":"critical"}""").sound)
        assertEquals(Sound.NONE, post(data = """{"priority":"critical","sound":"none"}""").sound)
        assertTrue(refused("""{"sound":"klaxon"}""").contains("chime"))
    }

    @Test
    fun aCameraEntityBecomesItsProxy() {
        assertEquals("$ha/api/camera_proxy/camera.porch", post(data = """{"entity_id":"camera.porch"}""").image)
        assertEquals("$ha/local/a.jpg", post(data = """{"image":"/local/a.jpg","entity_id":"camera.porch"}""").image)
        assertTrue(refused("""{"entity_id":"light.porch"}""").contains("camera"))
    }

    @Test
    fun linksOffHomeAssistantAreRefused() {
        for (key in listOf("url", "image")) {
            assertTrue(refused("""{"$key":"https://example.com/x"}""").contains("path on this panel's Home Assistant"))
            refused("""{"$key":"//example.com/x"}""")
            refused("""{"$key":"http://homeassistant.local:8123@example.com/"}""")
            refused("""{"$key":"javascript:alert(1)"}""")
        }
        refused("""{"actions":[{"action":"a","title":"A","uri":"https://example.com"}]}""")
        // Without a known Home Assistant, no link is accepted at all.
        assertTrue(refused("""{"url":"/lovelace/0"}""", base = null).contains("address"))
        // A plain notification still works then.
        assertEquals("m", (NotifyRequest.parse("m", "", "", null) as NotifyCommand.Post).notification.message)
    }

    @Test
    fun actionsAreCheckedAndCapped() {
        val four = (1..4).joinToString(",") { """{"action":"a$it","title":"A$it"}""" }
        assertTrue(refused("""{"actions":[$four]}""").contains("at most 3"))
        refused("""{"actions":[{"action":"a"}]}""")
        refused("""{"actions":[{"title":"A"}]}""")
        refused("""{"actions":[{"action":"a","title":"A"},{"action":"a","title":"B"}]}""")
        refused("""{"actions":[{"action":"REPLY","title":"Reply"}]}""")
        refused("""{"actions":"open_gate"}""")
    }

    @Test
    fun timeoutIsSeconds() {
        assertEquals(30, post(data = """{"timeout":30}""").timeoutS)
        assertEquals(30, post(data = """{"timeout":"30"}""").timeoutS)
        assertEquals(2, post(data = """{"timeout":1.5}""").timeoutS)
        refused("""{"timeout":0}""")
        refused("""{"timeout":-5}""")
        refused("""{"timeout":"soon"}""")
        refused("""{"timeout":100000}""")
    }

    @Test
    fun mistakesInDataAreExplained() {
        assertTrue(refused("""tag: doorbell""").contains("JSON object"))
        assertTrue(refused("""["doorbell"]""").contains("JSON object"))
        assertTrue(assertThrows(IllegalArgumentException::class.java) { NotifyRequest.parse("", " ", "", ha) }.message!!.contains("title or a message"))
    }

    @Test
    fun longTextIsClipped() {
        val n = post(message = "x".repeat(5000), title = "y".repeat(500))
        assertEquals(NotifyRequest.MAX_MESSAGE, n.message.length)
        assertEquals(NotifyRequest.MAX_TITLE, n.title.length)
        assertTrue(n.message.endsWith("…"))
    }
}
