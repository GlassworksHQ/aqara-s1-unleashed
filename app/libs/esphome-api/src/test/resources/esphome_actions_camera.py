"""Home Assistant's side of EspHomeActionsCameraTest: aioesphomeapi, the version Home Assistant pins,
lists the device's actions and calls them as HA's ESPHome integration does (manager.py
execute_service), then asks for camera images as its camera platform does (camera.py: one
request_single_image, and request_image_stream again after every frame for the MJPEG stream).
Usage: esphome_actions_camera.py <port>; prints RESULT {json}."""
import asyncio
import hashlib
import json
import sys

from aioesphomeapi import APIClient, CameraInfo, CameraState


async def main(port: int) -> None:
    cli = APIClient("127.0.0.1", port, None, client_info="Home Assistant test")
    await cli.connect(login=True)
    entities, services = await cli.list_entities_services()
    out = {
        "services": {
            s.name: {"args": [[a.name, int(a.type)] for a in s.args], "supports_response": int(s.supports_response)}
            for s in services
        },
        "cameras": [e.object_id for e in entities if isinstance(e, CameraInfo)],
    }
    by_name = {s.name: s for s in services}

    args = {"b": True, "i": -42, "f": 1.5, "s": "héllo", "bs": [True, False, True], "is": [1, -2, 300],
            "fs": [0.5, -2.0], "ss": ["a", "", "c"]}
    r = await cli.execute_service(by_name["echo"], args, return_response=True)
    out["echo"] = {"success": r.success, "data": json.loads(r.response_data)}
    r = await cli.execute_service(by_name["echo"], args, return_response=False)
    out["echo_status_only"] = {"success": r.success, "data": r.response_data.decode()}
    r = await cli.execute_service(by_name["fail"], {}, return_response=False)
    out["fail"] = {"success": r.success, "error": r.error_message}
    await cli.execute_service(by_name["ring"], {"times": 2})

    images: asyncio.Queue[bytes] = asyncio.Queue()
    cli.subscribe_states(lambda s: images.put_nowait(s.data) if isinstance(s, CameraState) else None)
    cli.request_single_image()
    img = await asyncio.wait_for(images.get(), 10)
    out["single"] = {"len": len(img), "sha256": hashlib.sha256(img).hexdigest()}

    stream = []
    for _ in range(5):
        cli.request_image_stream()
        stream.append(hashlib.sha256(await asyncio.wait_for(images.get(), 10)).hexdigest())
    out["stream"] = stream

    await cli.disconnect()
    print("RESULT " + json.dumps(out), flush=True)


asyncio.run(main(int(sys.argv[1])))
