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


def read(path):
    with open(path, "rb") as f:
        return f.read()


def make_stream(folder, size, seconds=3):
    """A short stream as camera.py makes it (its own flags), remuxed to Annex B the same way."""
    os.makedirs(folder, exist_ok=True)
    args = camera.ffmpeg_args("360p", seconds=seconds)
    args[args.index("testsrc=size=640x360:rate=15")] = f"testsrc=size={size}:rate=15"
    subprocess.run(args, cwd=folder, check=True)
    parts = ["init.mp4"] + sorted(f for f in os.listdir(folder) if f.endswith(".m4s"))
    subprocess.run(camera.ANNEXB_ARGS, cwd=folder, check=True,
                   input=b"".join(read(os.path.join(folder, f)) for f in parts))


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

    def test_an_allocation_relays_on_the_media_address_never_loopback(self):
        """Gecko drops a relayed candidate on loopback (Firefox 152 and the S1's GeckoView,
        2026-11-13): the relayed address is the media address, which never is."""
        r = self.allocate(webrtc.TURN_KEY)
        self.assertEqual(r.message_class, webrtc.stun.Class.RESPONSE)
        self.assertTrue(r.check_message_integrity(webrtc.TURN_KEY))
        self.assertEqual(r.attributes["XOR-RELAYED-ADDRESS"][0], self.svc.addr)
        self.assertFalse(self.svc.addr.startswith(("127.", "169.254.")))
        # This test connects from 127.0.0.1, as adb reverse does: the mapped address mustn't say so,
        # or Gecko drops the relay candidate (the S1's second smoke).
        self.assertEqual(r.attributes["XOR-MAPPED-ADDRESS"][0], self.svc.addr)
        self.assertEqual(r.attributes["LIFETIME"], webrtc.TURN_LIFETIME)

    def test_the_relay_reaches_only_the_peer(self):
        """Not a relay to anywhere: a permission for any address but the peer's is refused (CodeRabbit, #205)."""
        for peer, want in ((("192.0.2.10", 5000), webrtc.stun.Class.ERROR), (("127.0.0.1", 5000), webrtc.stun.Class.ERROR),
                           ((self.svc.addr, 5000), webrtc.stun.Class.RESPONSE)):
            m = webrtc.stun.Message(webrtc.stun.Method.CREATE_PERMISSION, webrtc.stun.Class.REQUEST,
                                    attributes={"XOR-PEER-ADDRESS": peer, "USERNAME": webrtc.TURN_USER,
                                                "REALM": webrtc.TURN_REALM, "NONCE": webrtc.TURN_NONCE})
            m.add_message_integrity(webrtc.TURN_KEY)
            r = self.ask(m)
            self.assertEqual(r.message_class, want, peer)
            if want == webrtc.stun.Class.ERROR:
                self.assertEqual(r.attributes["ERROR-CODE"][0], 403)

    def test_the_browser_gets_the_relay_over_tcp_and_relay_only(self):
        c = webrtc.client_config(8090)["configuration"]
        # A name, not 127.0.0.1: Gecko never connects to a TURN server at a loopback IP.
        self.assertEqual(c["iceServers"][0]["urls"], "turn:localhost:8090?transport=tcp")
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
        since = time.time()                                   # the log is shared with the other tests
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
                    from aiortc.mediastreams import MediaStreamError
                    try:
                        while True:
                            f = await track.recv()
                            frames.append((f.width, f.height))
                            if not first.done():
                                first.set_result(time.monotonic())
                    except MediaStreamError:
                        pass                                  # the track ended: the test closed it
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
            # The peer's only candidate is the media address, whatever other networks this computer is on.
            theirs = [line.split()[4] for line in answer["event"]["answer"].splitlines() if line.startswith("a=candidate:")]
            self.assertTrue(theirs)
            self.assertEqual(set(theirs), {fakeha.Handler.hub.webrtc.addr})
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
        # The relay timed the peer's checks through the browser (round 3's question on the S1): when
        # the browser's TCP connection closes, its record says how many came back, and how fast.
        deadline = time.time() + 10
        while time.time() < deadline:
            with open(self.log) as f:
                closed = [json.loads(line) for line in f if '"turn_closed"' in line]
            used = [e for e in closed if e["to_peer"] and e["t"] >= since]
            if used:
                break
            time.sleep(0.2)
        self.assertTrue(used, "no relay connection carried the session")
        checks = used[0]["checks"]
        self.assertGreaterEqual(checks["answered"], 1)
        self.assertLess(checks["median_ms"], 500)
        # This client closed its connection first: the peer lost it from the browser's side, not by consent.
        mine = [e for e in log if e["t"] >= since]
        self.assertEqual({e.get("reason") for e in mine if e.get("lost")}, {"remote"})
        self.assertFalse([e for e in mine if e.get("aioice")])


    def session_with(self, cam, between, pion=None):
        """A relay-only aiortc browser on `cam`, with the relay's TCP input from it routed through
        `between(tcp_in, conn, data)` (to drop or delay it); the session runs 1.5 s, then `play` runs
        on for `between.seconds`. Returns the fake's log since it started."""
        from unittest import mock
        from aiortc import RTCConfiguration, RTCIceServer, RTCPeerConnection, RTCSessionDescription
        from aiortc.rtcconfiguration import RTCIceTransportPolicy
        since = time.time()
        ws = self.connect()
        cfg, _ = ws.call({"type": "camera/webrtc/get_client_config", "entity_id": cam})
        conf = cfg["result"]["configuration"]
        tcp_in, state = webrtc.TurnConn.data_received, {"on": False}

        def routed(conn, data):
            return between(tcp_in, conn, data) if state["on"] else tcp_in(conn, data)

        async def play():
            s = conf["iceServers"][0]
            pc = RTCPeerConnection(RTCConfiguration(iceServers=[RTCIceServer(s["urls"], s["username"], s["credential"])],
                                                    iceTransportPolicy=RTCIceTransportPolicy(conf["iceTransportPolicy"])))
            pc.addTransceiver("audio", direction="recvonly")
            pc.addTransceiver("video", direction="recvonly")
            await pc.setLocalDescription(await pc.createOffer())
            loop = asyncio.get_running_loop()
            res, _ = await loop.run_in_executor(None, ws.call, {"type": "camera/webrtc/offer", "entity_id": cam,
                                                                "offer": pc.localDescription.sdp})
            await loop.run_in_executor(None, ws.recv)                       # the session's id
            answer = await loop.run_in_executor(None, ws.recv)
            await pc.setRemoteDescription(RTCSessionDescription(answer["event"]["answer"], "answer"))
            await asyncio.sleep(1.5)
            state["on"] = True
            await asyncio.sleep(between.seconds)
            state["on"] = False                              # so the client's own TURN teardown is answered
            self.closing_at = time.time()
            await pc.close()
            return res

        fast = pion or {"keepalive_s": 0.2, "disconnected_s": 0.5, "failed_s": 1.5}
        with mock.patch.dict(webrtc.PION_ICE, fast), mock.patch.object(webrtc.aioice.ice, "CONSENT_INTERVAL", 0.2), \
                mock.patch.object(webrtc.TurnConn, "data_received", routed):
            res = asyncio.run(play())
        ws.call({"type": "unsubscribe_events", "subscription": res["id"]})
        time.sleep(1)
        with open(self.log) as f:
            return [e for e in (json.loads(line) for line in f) if e["t"] >= since]

    def test_a_browser_that_goes_silent_loses_ice_by_liveness_and_says_so(self):
        """Nothing from the browser for disconnected + failed (pion's 5 + 25 s; 0.5 + 1.5 s here):
        the peer closes ICE, logs disconnected then failed, and marks the loss `reason: liveness`."""
        def silent(tcp_in, conn, data):
            return None
        silent.seconds = 4
        log = self.session_with("camera.clarity_cam_1", silent)
        self.assertEqual([e["webrtc_liveness"] for e in log if "webrtc_liveness" in e][:2], ["disconnected", "failed"])
        self.assertTrue([e for e in log if e.get("lost") and e.get("reason") == "liveness"])

    def test_late_answers_keep_ice_as_go2rtc_would(self):
        """The S1's heavy view (smoke 4): every answer came, 1.4 to 2.6 s late (median), and aiortc's
        own check (0.5 s each) dropped every session. pion, in go2rtc, counts any packet as alive.
        Here every byte from the browser arrives 1 s late, for 5 s, with aioice's own check every 0.2 s
        (it would close after about 4 s) and pion's timeouts scaled to 2 + 2 s: the session stays."""
        loop_of = {}

        def late(tcp_in, conn, data):
            loop = loop_of.setdefault("l", asyncio.get_event_loop())
            loop.call_later(1.0, tcp_in, conn, data)
        late.seconds = 5
        log = self.session_with("camera.clarity_cam_2", late, {"keepalive_s": 0.2, "disconnected_s": 2.0, "failed_s": 2.0})
        # Lost only when the client itself closed at the end, never while its answers were late.
        self.assertEqual([e for e in log if e.get("lost") and e["t"] < self.closing_at], [])
        self.assertEqual([e.get("webrtc_liveness") for e in log if "webrtc_liveness" in e], [])
        closed = [e for e in log if e.get("turn_closed") and e["to_peer"]]
        self.assertTrue(closed and closed[0]["checks"]["over_500_ms"] >= 1, closed)       # the answers were late

if __name__ == "__main__":
    unittest.main()
