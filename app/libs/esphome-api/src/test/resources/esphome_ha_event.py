"""Home Assistant's side of EspHomeHaEventTest: aioesphomeapi, the version Home Assistant pins,
subscribes to the device's Home Assistant actions and events as HA's ESPHome integration does
(manager.py: subscribe_home_assistant_states_and_services), then calls the device's "tap" action,
which makes the device fire a bus event. Prints RESULT {json} with the event it got.
Usage: esphome_ha_event.py <port>. Touches nothing but the test server."""
import asyncio
import json
import sys

from aioesphomeapi import APIClient


async def main(port: int) -> None:
    cli = APIClient("127.0.0.1", port, None, client_info="Home Assistant test")
    await cli.connect(login=True)
    _, services = await cli.list_entities_services()
    got: asyncio.Queue = asyncio.Queue()
    cli.subscribe_home_assistant_states_and_services(
        on_state=lambda s: None,
        on_service_call=lambda call: got.put_nowait(call),
        on_state_sub=lambda entity_id, attribute: None,
        on_state_request=lambda entity_id, attribute: None,
    )
    by_name = {s.name: s for s in services}
    await cli.execute_service(by_name["tap"], {})
    call = await asyncio.wait_for(got.get(), 10)
    await cli.disconnect()
    print("RESULT " + json.dumps({"service": call.service, "is_event": call.is_event, "data": dict(call.data)}), flush=True)


asyncio.run(main(int(sys.argv[1])))
