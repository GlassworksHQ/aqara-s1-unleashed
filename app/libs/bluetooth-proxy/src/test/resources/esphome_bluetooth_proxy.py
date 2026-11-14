"""Home Assistant's side of BluetoothProxyHomeAssistantTest: aioesphomeapi, the version Home Assistant
pins, reads the device info and decides on a Bluetooth scanner as HA's esphome integration does
(manager.py: any flag connects one; bleak-esphome connect.py connect_scanner: raw advertisements,
connectable), subscribes to raw advertisements and collects <count> of them, then disconnects.
Usage: esphome_bluetooth_proxy.py <port> <count>; prints RESULT {json}."""
import asyncio
import json
import sys

from aioesphomeapi import APIClient
from aioesphomeapi.model import BluetoothProxyFeature


async def main(port: int, count: int) -> None:
    cli = APIClient("127.0.0.1", port, None, client_info="Home Assistant test")
    await cli.connect(login=True)
    info = await cli.device_info()
    flags = info.bluetooth_proxy_feature_flags_compat(cli.api_version)
    out = {
        "source": info.bluetooth_mac_address or info.mac_address,
        "scanner": bool(flags),
        "passive_scan": bool(flags & BluetoothProxyFeature.PASSIVE_SCAN),
        "raw": bool(flags & BluetoothProxyFeature.RAW_ADVERTISEMENTS),
        "connectable": bool(flags & BluetoothProxyFeature.ACTIVE_CONNECTIONS),
        "state_and_mode": bool(flags & BluetoothProxyFeature.FEATURE_STATE_AND_MODE),
    }

    got: list = []
    done = asyncio.get_running_loop().create_future()

    def on_raw(resp) -> None:
        advs = resp.advertisements
        for i in range(len(advs)):
            a = advs[i]
            got.append({
                "address": ":".join(f"{b:02X}" for b in a.address.to_bytes(6, "big")),
                "rssi": a.rssi,
                "address_type": a.address_type,
                "data": a.data.hex(),
            })
        out.setdefault("batches", []).append(len(advs))
        if len(got) >= count and not done.done():
            done.set_result(None)

    unsub = cli.subscribe_bluetooth_le_raw_advertisements(on_raw)
    await asyncio.wait_for(done, 20)
    unsub()
    out["advertisements"] = got
    await cli.disconnect()
    print("RESULT " + json.dumps(out), flush=True)


asyncio.run(main(int(sys.argv[1]), int(sys.argv[2])))
