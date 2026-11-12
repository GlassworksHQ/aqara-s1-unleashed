/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.pane

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import sh.edm.esphome.ApiEntity
import sh.edm.esphome.ButtonEntity

class EntityRegistryTest {

    private fun button(id: String) = ButtonEntity(id, id, press = {})

    private fun pane(vararg entities: ApiEntity) = object : Pane {
        override val id = "test"
        override fun entities() = entities.toList()
    }

    @Test fun paneEntitiesComeAfterTheCoreOnes() {
        val list = EntityRegistry(listOf(pane(button("a"), button("b")), pane(button("c"))))
            .assemble(listOf(button("core")))
        assertEquals(listOf("core", "a", "b", "c"), list.map { it.objectId })
    }

    @Test fun noPanesLeavesTheCoreListAlone() {
        val core = listOf(button("x"), button("y"))
        assertEquals(core, EntityRegistry(emptyList()).assemble(core))
    }

    @Test fun aPaneEntityClashingWithTheCoreThrowsNamingTheObjectId() {
        val e = assertClash { EntityRegistry(listOf(pane(button("dup")))).assemble(listOf(button("dup"))) }
        assertTrue(e.message, e.message!!.contains("'dup'"))
    }

    @Test fun twoPanesClashingThrowsNamingTheObjectId() {
        val e = assertClash { EntityRegistry(listOf(pane(button("same")), pane(button("same")))).assemble(emptyList()) }
        assertTrue(e.message, e.message!!.contains("'same'"))
    }

    private fun assertClash(block: () -> Unit): IllegalStateException {
        try {
            block()
        } catch (e: IllegalStateException) {
            return e
        }
        fail("expected IllegalStateException")
        throw AssertionError()
    }
}
