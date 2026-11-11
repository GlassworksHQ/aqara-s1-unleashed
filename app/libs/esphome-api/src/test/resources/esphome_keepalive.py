"""Home Assistant's side of the keepalive test (EspHomeKeepaliveTest): aioesphomeapi, the version Home
Assistant pins, connected to our server while the server streams state updates. aioesphomeapi then
never pings by itself (it only does when it hears nothing), so the connection lives or dies by the
server's own keepalive. Usage: esphome_keepalive.py <port> <seconds>; prints RESULT {json}."""
import asyncio
import json
import sys

from aioesphomeapi import APIClient


async def main(port: int, seconds: float) -> None:
    cli = APIClient("127.0.0.1", port, None, client_info="Home Assistant test")
    stopped = []

    async def on_stop(expected_disconnect):
        stopped.append(expected_disconnect)

    await cli.connect(on_stop=on_stop, login=True)
    states = []
    cli.subscribe_states(lambda s: states.append(type(s).__name__))
    await asyncio.sleep(seconds)
    connected = not stopped
    if connected:
        await cli.disconnect()
    print("RESULT " + json.dumps({"states": len(states), "connected_throughout": connected}), flush=True)


asyncio.run(main(int(sys.argv[1]), float(sys.argv[2])))
