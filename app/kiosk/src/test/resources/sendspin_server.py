"""A strict aiosendspin server for SendspinServerTest (plays Music Assistant's part).

    python3 -m venv /tmp/sendspin-venv && /tmp/sendspin-venv/bin/pip install 'aiosendspin[server]==10.2.1'

Usage: sendspin_server.py pair <pairing token>   |   sendspin_server.py guest <client id>

Prints one JSON object per line on stdout (events the JVM test asserts on); aiosendspin's own log
goes to stderr. The server runs with allow_noncompliant_clients=False, so any spec deviation the
library checks for rejects the client.
"""

from __future__ import annotations

import asyncio
import json
import logging
import math
import struct
import sys

from aiohttp import web

from aiosendspin.models.types import PairMethod
from aiosendspin.noise.keys import Identity
from aiosendspin.noise.pairing import PairingAttempt
from aiosendspin.noise.pairing_token import decode_psk_token
from aiosendspin.noise.trust_store import InMemoryServerPairingStore
from aiosendspin.server.audio import AudioFormat
from aiosendspin.server.roles.player.v1 import PlayerV1Role
from aiosendspin.server.server import SendspinServer


def out(**kw: object) -> None:
    print(json.dumps(kw), flush=True)


async def wait_for(pred, timeout: float, what: str):
    async with asyncio.timeout(timeout):
        while True:
            value = pred()
            if value:
                return value
            await asyncio.sleep(0.02)


def player_role(server: SendspinServer, client_id: str) -> PlayerV1Role | None:
    client = server.get_client(client_id)
    if client is None or not client.is_connected:
        return None
    for role in client.active_roles:
        # Ready once the client/state player object arrived (it declares supported_commands).
        if isinstance(role, PlayerV1Role) and role.state_supported_commands:
            return role
    return None


async def stream_tone(server: SendspinServer, client_id: str, seconds: float) -> None:
    client = server.get_client(client_id)
    assert client is not None
    stream = client.group.start_stream()
    fmt = AudioFormat(sample_rate=48_000, bit_depth=16, channels=2)
    slice_frames = 4_800  # 100 ms
    n = 0
    for _ in range(int(seconds * 10)):
        frames = []
        for i in range(slice_frames):
            v = int(8000 * math.sin(2 * math.pi * 440 * (n + i) / 48_000))
            frames.append(struct.pack("<hh", v, v))
        n += slice_frames
        stream.prepare_audio(b"".join(frames), fmt)
        await stream.commit_audio()
    out(event="stream_pushed", seconds=seconds)


def describe(role: PlayerV1Role, server: SendspinServer, client_id: str) -> dict:
    client = server.get_client(client_id)
    security = client.connection_security if client is not None else None
    return {
        "volume": role.volume,
        "muted": role.muted,
        "output_delay_ms": role.output_delay_ms,
        "required_lead_time_ms": role.required_lead_time_ms,
        "min_buffer_ms": role.min_buffer_ms,
        "commands": sorted(c.value for c in role.state_supported_commands),
        "psk": security.psk_category.value if security is not None else None,
    }


async def main() -> None:
    logging.basicConfig(level=logging.INFO, stream=sys.stderr, format="%(levelname)s %(name)s: %(message)s")
    mode, arg = sys.argv[1], sys.argv[2]
    if mode == "pair":
        token = decode_psk_token(arg)
        client_id = token.client_id
    else:
        token = None
        client_id = arg

    server = SendspinServer(
        loop=asyncio.get_running_loop(),
        identity=Identity.generate(),
        server_name="Test Music Assistant",
        pairing_store=InMemoryServerPairingStore(),
        allow_noncompliant_clients=False,
    )
    app = web.Application()
    app.router.add_get(SendspinServer.API_PATH, server.on_client_connect)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "127.0.0.1", 0)
    await site.start()
    port = site._server.sockets[0].getsockname()[1]  # noqa: SLF001
    out(event="listening", port=port, server_id=server.id)

    # 1. The client connects; unpaired and not approved, the server holds it without roles.
    await wait_for(lambda: (c := server.get_client(client_id)) is not None and c.connection is not None, 15, "connect")
    await asyncio.sleep(0.5)
    out(event="connected", has_player=player_role(server, client_id) is not None)

    if token is not None:
        # 2. Pair on the existing connection with the token (what Music Assistant does with the
        #    token Home Assistant reads over the ESPHome API).
        await server.initiate_pairing(
            client_id,
            PairingAttempt(PairMethod.PAIRING_PSK, client_id=token.client_id, pairing_psk=token.pairing_psk),
        )
        out(event="paired")
    else:
        await server.trust_unpaired(client_id)
        out(event="approved")

    role = await wait_for(lambda: player_role(server, client_id), 10, "player role")
    out(event="player_ready", **describe(role, server, client_id))

    # 3. Audio, then commands.
    await stream_tone(server, client_id, 1.5)
    await asyncio.sleep(2.0)
    role.set_player_volume(30)
    await wait_for(lambda: role.volume == 30, 5, "volume")
    role.set_player_mute(True)
    await wait_for(lambda: role.muted, 5, "mute")
    out(event="commands", **describe(role, server, client_id))
    client = server.get_client(client_id)
    assert client is not None
    await client.group.stop()
    out(event="stopped")

    if token is not None:
        # 4. The JVM reconnects; a paired client must come back on its long-term PSK, with roles.
        await wait_for(lambda: player_role(server, client_id) is None, 10, "disconnect")
        role = await wait_for(lambda: player_role(server, client_id), 15, "reconnect")
        out(event="reconnected", **describe(role, server, client_id))

    out(event="done")
    await asyncio.sleep(0.5)
    await server.close()
    await runner.cleanup()


asyncio.run(main())
