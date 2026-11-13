"""python3 -m unittest discover -s tools/perf/test  (under the WebRTC venv: python3 tools/perf/webrtcenv.py)

The fake's WebRTC camera (webrtc.py, #202): its TURN relay as a browser meets it (long-term auth,
one allocation over TCP), and a whole session through the fake's websocket API, with aiortc as the
browser: relay-only, over TCP, as a panel behind adb reverse is. Needs aiortc and ffmpeg with libx264;
skips without them, except with GW_PERF_E2E=1 (CI), where it fails."""
import asyncio
import json
import os
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from test_fakeha import STUB_CAMERA, WS  # noqa: E402  (a stub frontend and camera folder)
sys.path.insert(0, os.path.dirname(HERE))
import camera  # noqa: E402
import fakeha  # noqa: E402
import webrtc  # noqa: E402


def need():
    why = None
    if not webrtc.available():
        why = "no aiortc in this Python (run it under webrtcenv.py's venv)"
    elif not shutil.which("ffmpeg"):
        why = "no ffmpeg"
    if why and os.environ.get("GW_PERF_E2E") == "1":
        raise AssertionError(f"GW_PERF_E2E=1 but {why}")
    if why:
        raise unittest.SkipTest(why)


def make_stream(folder, size, seconds=3):
    """A short stream as camera.py makes it (its own flags), remuxed to Annex B the same way."""
    os.makedirs(folder, exist_ok=True)
    args = camera.ffmpeg_args("360p", seconds=seconds)
    args[args.index("testsrc=size=640x360:rate=15")] = f"testsrc=size={size}:rate=15"
    subprocess.run(args, cwd=folder, check=True)
    parts = ["init.mp4"] + sorted(f for f in os.listdir(folder) if f.endswith(".m4s"))
    subprocess.run(camera.ANNEXB_ARGS, cwd=folder, check=True,
                   input=b"".join(open(os.path.join(folder, f), "rb").read() for f in parts))


class Turn(unittest.TestCase):
    """The relay as a browser's TURN client meets it: RFC 5766 over TCP, long-term credentials."""

    @classmethod
    def setUpClass(cls):
        need()
        cls.svc = webrtc.WebRTC(0, camera.annexb, 15)

    def ask(self, msg):
        s = socket.create_connection(("127.0.0.1", self.svc.turn_port), timeout=5)
        self.addCleanup(s.close)
        s.sendall(bytes(msg))
        head = s.recv(20, socket.MSG_WAITALL)
        body = s.recv(struct.unpack("!H", head[2:4])[0], socket.MSG_WAITALL)
        return webrtc.stun.parse_message(head + body)

    def allocate(self, key=None):
        m = webrtc.stun.Message(webrtc.stun.Method.ALLOCATE, webrtc.stun.Class.REQUEST,
                                attributes={"REQUESTED-TRANSPORT": 17 << 24})
        if key:
            m.attributes.update({"USERNAME": webrtc.TURN_USER, "REALM": webrtc.TURN_REALM, "NONCE": webrtc.TURN_NONCE})
            m.add_message_integrity(key)
        return self.ask(m)

    def test_an_allocation_needs_the_credentials(self):
        r = self.allocate()
        self.assertEqual((r.message_class, r.attributes["ERROR-CODE"][0]), (webrtc.stun.Class.ERROR, 401))
        self.assertEqual((r.attributes["REALM"], r.attributes["NONCE"]), (webrtc.TURN_REALM, webrtc.TURN_NONCE))
        self.assertEqual(self.allocate(b"0" * 16).attributes["ERROR-CODE"][0], 401)

    def test_an_allocation_relays_on_this_computers_loopback(self):
        r = self.allocate(webrtc.TURN_KEY)
        self.assertEqual(r.message_class, webrtc.stun.Class.RESPONSE)
        self.assertTrue(r.check_message_integrity(webrtc.TURN_KEY))
        self.assertEqual(r.attributes["XOR-RELAYED-ADDRESS"][0], "127.0.0.1")
        self.assertEqual(r.attributes["LIFETIME"], webrtc.TURN_LIFETIME)

    def test_the_browser_gets_the_relay_over_tcp_and_relay_only(self):
        c = webrtc.client_config(8090)["configuration"]
        self.assertEqual(c["iceServers"][0]["urls"], "turn:127.0.0.1:8090?transport=tcp")
        self.assertEqual(c["iceTransportPolicy"], "relay")


class Session(unittest.TestCase):
    """A whole session through the fake: get_client_config, the offer, the answer, the browser's
    candidates, and frames over the relay."""

    @classmethod
    def setUpClass(cls):
        need()
        for prof, size in (("720p", "1280x720"), ("360p", "640x360")):
            if not os.path.exists(os.path.join(STUB_CAMERA, prof, "stream.h264")):
                make_stream(os.path.join(STUB_CAMERA, prof, "x"), size)
                os.replace(os.path.join(STUB_CAMERA, prof, "x", "stream.h264"), os.path.join(STUB_CAMERA, prof, "stream.h264"))
        cls.log = os.path.join(STUB_CAMERA, "fakeha.jsonl")
        cls.srv = fakeha.serve(0, cls.log, turn_port=0)
        cls.port = cls.srv.server_address[1]
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.srv.server_close()

    def connect(self):
        ws = WS(self.port)
        self.addCleanup(ws.sock.close)
        ws.recv()
        ws.send({"type": "auth", "access_token": "clarity-access"})
        ws.recv()
        return ws

    def test_a_relay_only_browser_plays_the_camera(self):
        from aiortc import RTCConfiguration, RTCIceServer, RTCPeerConnection, RTCSessionDescription
        from aiortc.rtcconfiguration import RTCIceTransportPolicy
        ws = self.connect()
        cfg, _ = ws.call({"type": "camera/webrtc/get_client_config", "entity_id": "camera.clarity_cam_0"})
        conf = cfg["result"]["configuration"]

        async def play():
            s = conf["iceServers"][0]
            pc = RTCPeerConnection(RTCConfiguration(iceServers=[RTCIceServer(s["urls"], s["username"], s["credential"])],
                                                    iceTransportPolicy=RTCIceTransportPolicy(conf["iceTransportPolicy"])))
            pc.addTransceiver("audio", direction="recvonly")     # as ha-web-rtc-player does
            pc.addTransceiver("video", direction="recvonly")
            frames, first = [], asyncio.get_running_loop().create_future()

            @pc.on("track")
            def on_track(track):
                async def pull():
                    while True:
                        f = await track.recv()
                        frames.append((f.width, f.height))
                        if not first.done():
                            first.set_result(time.monotonic())
                if track.kind == "video":
                    asyncio.ensure_future(pull())
            await pc.setLocalDescription(await pc.createOffer())
            # The candidates go as the frontend sends them: camera/webrtc/candidate after the session's id.
            sdp = pc.localDescription.sdp
            cands = [line[2:] for line in sdp.splitlines() if line.startswith("a=candidate:")]
            offer = "\r\n".join(line for line in sdp.splitlines()
                                  if not line.startswith(("a=candidate:", "a=end-of-candidates"))) + "\r\n"
            t0 = time.monotonic()
            loop = asyncio.get_running_loop()
            res = await loop.run_in_executor(None, ws.call, {"type": "camera/webrtc/offer", "entity_id": "camera.clarity_cam_0",
                                                             "offer": offer})
            session = await loop.run_in_executor(None, ws.recv)
            sid = session["event"]["session_id"]
            for c in cands:
                ok, _ = await loop.run_in_executor(None, ws.call, {"type": "camera/webrtc/candidate", "entity_id": "camera.clarity_cam_0",
                                                                   "session_id": sid, "candidate": {"candidate": c, "sdpMid": "0", "sdpMLineIndex": 0}})
                self.assertTrue(ok["success"])
            answer = await loop.run_in_executor(None, ws.recv)
            self.assertEqual((answer["id"], answer["event"]["type"]), (res[0]["id"], "answer"), answer)
            answered = time.monotonic() - t0
            self.assertIn("H264", answer["event"]["answer"])
            # The peer's only candidate is this computer's loopback, whatever networks it is on.
            theirs = [line.split()[4] for line in answer["event"]["answer"].splitlines() if line.startswith("a=candidate:")]
            self.assertTrue(theirs)
            self.assertEqual(set(theirs), {"127.0.0.1"})
            await pc.setRemoteDescription(RTCSessionDescription(answer["event"]["answer"], "answer"))
            t_first = await asyncio.wait_for(first, 10) - t0
            await asyncio.sleep(2)
            stats = [v for v in (await pc.getStats()).values() if v.type == "transport"]
            await pc.close()
            return res[0], answered, t_first, frames, stats

        res, answered, t_first, frames, stats = asyncio.run(play())
        # The answer comes at once: aiortc's default public STUN server held it 5 s (2026-11-13).
        self.assertLess(answered, 1.0)
        self.assertLess(t_first, 3.0)
        self.assertGreaterEqual(len(frames), 25)                  # 2 s and more at 15 fps
        self.assertEqual(set(frames), {(1280, 720)})
        ws.call({"type": "unsubscribe_events", "subscription": res["id"]})
        deadline = time.time() + 5
        while time.time() < deadline:
            with open(self.log) as f:
                log = [json.loads(line) for line in f]
            if any(e.get("webrtc_closed") for e in log):
                break
            time.sleep(0.1)
        self.assertTrue(any(e.get("webrtc_closed") == "camera.clarity_cam_0" for e in log))
        self.assertTrue(any(e.get("webrtc_ice") == "completed" for e in log))
        self.assertFalse([e for e in log if e.get("webrtc_error")])


if __name__ == "__main__":
    unittest.main()
