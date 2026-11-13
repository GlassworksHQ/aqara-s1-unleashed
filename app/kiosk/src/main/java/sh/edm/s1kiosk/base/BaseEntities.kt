package sh.edm.s1kiosk.base

import sh.edm.esphome.ApiEntity
import sh.edm.esphome.EntityCategory
import sh.edm.esphome.EventEntity
import sh.edm.esphome.SensorEntity
import sh.edm.esphome.SwitchEntity
import sh.edm.esphome.TextSensorEntity

/**
 * The ESPHome entities for a wall base layout. Names are fixed ("Base relay 1", ...) on purpose:
 * Home Assistant 2026.11 derives the ESPHome unique id from the entity *name* (aioesphomeapi
 * `build_unique_id` v3), so using the channel names configured in the Aqara app would orphan the
 * HA entities whenever a channel is renamed. Rename them in HA instead. Keys are FNV-1a of the
 * object id, like every other entity.
 */
object BaseEntities {
    const val BASE = "base"
    const val FIRMWARE = "base_firmware"
    const val POWER = "base_power"
    const val ENERGY = "base_energy"
    const val PROTECTION = "base_protection"
    fun relayId(ch: Int) = "base_relay_$ch"
    fun buttonId(ch: Int) = "base_button_$ch"

    /** The entity set for a layout (what decides whether the API clients must re-list). */
    fun objectIds(layout: BaseLayout): List<String> = buildList {
        add(BASE)
        if (layout.attached) add(FIRMWARE)
        if (layout.kind == BaseKind.SWITCH) {
            layout.channels.forEach { add(relayId(it)) }
            add(POWER)
            add(ENERGY)
            add(PROTECTION)
            layout.channels.forEach { add(buttonId(it)) }
        }
    }

    fun build(layout: BaseLayout, t: BaseTracker, setRelay: (Int, Boolean) -> Unit): List<ApiEntity> {
        val list = ArrayList<ApiEntity>()
        list += TextSensorEntity(BASE, "Base", "mdi:wall", EntityCategory.DIAGNOSTIC, disabledByDefault = true, get = { t.current()?.describe() })
        if (layout.attached) {
            list += TextSensorEntity(FIRMWARE, "Base firmware", "mdi:chip", EntityCategory.DIAGNOSTIC, disabledByDefault = true, get = { t.firmware() })
        }
        if (layout.kind == BaseKind.SWITCH) {
            for (ch in layout.channels) {
                list += SwitchEntity(
                    relayId(ch), "Base relay $ch", "mdi:light-switch", deviceClass = "switch",
                    get = { t.relay(ch) == true }, set = { on -> setRelay(ch, on) },
                )
            }
            list += SensorEntity(
                POWER, "Base power", "mdi:flash", unit = "W", accuracy = 1, deviceClass = "power", stateClass = 1,
                get = { t.power() },
            )
            // TODO: unit of 0.13.85 (PW_ACC) is unknown (Wh? kWh?); exposed raw until seen on a real
            // base. No unit/device_class so HA keeps long-term statistics without misreporting.
            list += SensorEntity(
                ENERGY, "Base energy", "mdi:counter", accuracy = 3, stateClass = 2, get = { t.energy() },
            )
            list += TextSensorEntity(
                PROTECTION, "Base protection", "mdi:shield-alert-outline", EntityCategory.DIAGNOSTIC,
                get = { BaseProtocol.protectionText(t.protection()) },
            )
            for (ch in layout.channels) {
                list += EventEntity(
                    buttonId(ch), "Base button $ch", "mdi:gesture-tap-button", deviceClass = "button",
                    eventTypes = BaseProtocol.EVENT_TYPES,
                )
            }
        }
        check(list.map { it.objectId } == objectIds(layout)) { "BaseEntities.objectIds out of sync" }
        return list
    }
}
