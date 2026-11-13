"""Clarity's WebRTC cameras (MIT, #202): the peer the fake Home Assistant answers HA's WebRTC offers
with, and the TURN relay the panel reaches it through.

Real setups mostly play cameras over WebRTC: go2rtc, built into HA since 2024.11, answers the
frontend's offer and the browser's own media stack carries the video. Here aiortc answers instead
and sends camera.py's H.264 stream, the same bitstream the HLS segments carry, never re-encoded:
its access units at 15 fps, looped.

The panel reaches this computer only through `adb reverse`, which forwards TCP, and WebRTC's media
is UDP. So the browser gets one TURN server, over TCP (`turn:127.0.0.1:<port>?transport=tcp`), and
only relayed candidates (`iceTransportPolicy: relay`). Its TCP connection goes through adb reverse
to the relay below; the relay's UDP side and the peer are both on this computer's loopback. Nothing
depends on the network the panel or this computer is on.

Host-side only: aiortc and its dependencies come from tools/perf/webrtc-requirements.txt (locked with
hashes). It listens on 127.0.0.1 and reads nothing from the device.
"""
import asyncio
import fractions
import hashlib
import os
import socket
import struct
import threading
import time

try:
    import aioice.ice
    from aioice import stun
except ImportError:          # not in the venv: the fake still serves HLS, and answers offers with an error
    aioice = stun = None

import camera  # noqa: E402

TURN_USER = TURN_PASSWORD = TURN_REALM = "clarity"
TURN_KEY = hashlib.md5(f"{TURN_USER}:{TURN_REALM}:{TURN_PASSWORD}".encode()).digest()
TURN_NONCE = b"clarity"
TURN_LIFETIME = 600
VIDEO_CLOCK = 90000

# The peer's only candidate is this computer's loopback, the one address the relay reaches without
# the network. aioice leaves 127.0.0.1 out of its own list.
if aioice:
    aioice.ice.get_host_addresses = lambda use_ipv4=True, use_ipv6=True: ["127.0.0.1"]


def client_config(turn_port):
    """camera/webrtc/get_client_config's result. HA sends only iceServers (webrtc-models'
    RTCConfiguration); the relay-only policy is the fake's one addition, so a browser on the same
    network as this computer can't pair its own address and skip the relay."""
    return {"configuration": {"iceServers": [{"urls": f"turn:127.0.0.1:{turn_port}?transport=tcp",
                                              "username": TURN_USER, "credential": TURN_PASSWORD}],
                              "iceTransportPolicy": "relay"}}


def _track_class():
    from aiortc import MediaStreamTrack

    class Camera(MediaStreamTrack):
        """A live camera: the loop's access units at fps, each sent when it is due, from the loop's
        first frame (an IDR) when the browser connects."""
        kind = "video"

        def __init__(self, aus, fps, on_frame=None):
            super().__init__()
            self.aus, self.fps, self.n, self.t0, self.on_frame = aus, fps, 0, None, on_frame

        async def recv(self):
            import av
            if self.t0 is None:
                self.t0 = time.monotonic()
            wait = self.t0 + self.n / self.fps - time.monotonic()
            if wait > 0:
                await asyncio.sleep(wait)
            p = av.Packet(self.aus[self.n % len(self.aus)])
            p.pts = p.dts = self.n * VIDEO_CLOCK // self.fps
            p.time_base = fractions.Fraction(1, VIDEO_CLOCK)
            if self.on_frame:
                self.on_frame(self.n)
            self.n += 1
            return p
    return Camera


# ---- TURN (RFC 5766) over TCP: what a browser needs for one relayed UDP allocation per connection

class TurnConn(asyncio.Protocol):
    """One browser's TCP connection to the relay, and its one allocation."""

    def __init__(self, server):
        self.server, self.buf, self.relay, self.perms, self.chan, self.peer_chan = server, b"", None, set(), {}, {}

    def connection_made(self, transport):
        self.tr = transport
        self.client = transport.get_extra_info("peername")[:2]
        self.server.conns.add(self)

    def connection_lost(self, exc):
        self.server.conns.discard(self)
        if self.relay:
            self.relay.close()

    def data_received(self, data):
        self.buf += data
        while len(self.buf) >= 4:
            if self.buf[0] & 0xC0 == 0x40:                    # ChannelData, padded to 4 over TCP
                num, n = struct.unpack("!HH", self.buf[:4])
                size = 4 + n + (-n % 4)
                if len(self.buf) < size:
                    return
                payload, self.buf = self.buf[4:4 + n], self.buf[size:]
                peer = self.chan.get(num)
                if peer and self.relay:
                    self.relay.sendto(payload, peer)
                continue
            size = 20 + struct.unpack("!H", self.buf[2:4])[0]
            if len(self.buf) < size:
                return
            raw, self.buf = self.buf[:size], self.buf[size:]
            try:
                msg = stun.parse_message(raw)
            except ValueError:
                continue
            self.stun(msg)

    def reply(self, req, cls=None, auth=True, **attrs):
        out = stun.Message(req.message_method, cls or stun.Class.RESPONSE, req.transaction_id,
                           {k.replace("_", "-"): v for k, v in attrs.items()})
        if auth:
            out.add_message_integrity(TURN_KEY)
        self.tr.write(bytes(out))

    def stun(self, m):
        M = stun.Method
        if m.message_method == M.BINDING and m.message_class == stun.Class.REQUEST:
            return self.reply(m, auth=False, XOR_MAPPED_ADDRESS=self.client)
        if m.message_class == stun.Class.INDICATION:
            if m.message_method == M.SEND and self.relay and m.attributes.get("XOR-PEER-ADDRESS", ("",))[0] in self.perms:
                self.relay.sendto(m.attributes.get("DATA", b""), m.attributes["XOR-PEER-ADDRESS"])
            return
        if m.message_class != stun.Class.REQUEST:
            return
        if "MESSAGE-INTEGRITY" not in m.attributes or not m.check_message_integrity(TURN_KEY):
            return self.reply(m, stun.Class.ERROR, auth=False, ERROR_CODE=(401, "Unauthorized"),
                              REALM=TURN_REALM, NONCE=TURN_NONCE)
        if m.message_method == M.ALLOCATE:
            if self.relay:
                return self.reply(m, stun.Class.ERROR, ERROR_CODE=(437, "Allocation Mismatch"))
            asyncio.ensure_future(self.allocate(m))
        elif m.message_method == M.REFRESH:
            life = m.attributes.get("LIFETIME", TURN_LIFETIME)
            if life == 0 and self.relay:
                self.relay.close()
                self.relay = None
            self.reply(m, LIFETIME=min(life, TURN_LIFETIME))
        elif m.message_method == M.CREATE_PERMISSION:
            self.perms.add(m.attributes["XOR-PEER-ADDRESS"][0])
            self.reply(m)
        elif m.message_method == M.CHANNEL_BIND:
            num, peer = m.attributes["CHANNEL-NUMBER"], m.attributes["XOR-PEER-ADDRESS"]
            self.chan[num], self.peer_chan[peer] = peer, num
            self.perms.add(peer[0])
            self.reply(m)

    async def allocate(self, m):
        loop = asyncio.get_running_loop()
        self.relay, _ = await loop.create_datagram_endpoint(lambda: _Relay(self), local_addr=("127.0.0.1", 0))
        self.reply(m, XOR_RELAYED_ADDRESS=self.relay.get_extra_info("sockname")[:2],
                   XOR_MAPPED_ADDRESS=self.client, LIFETIME=TURN_LIFETIME)

    def from_peer(self, data, addr):
        if addr[0] not in self.perms:
            return
        num = self.peer_chan.get(addr)
        if num is not None:
            self.tr.write(struct.pack("!HH", num, len(data)) + data + bytes(-len(data) % 4))
        else:
            ind = stun.Message(stun.Method.DATA, stun.Class.INDICATION,
                               attributes={"XOR-PEER-ADDRESS": addr, "DATA": data})
            self.tr.write(bytes(ind))


class _Relay(asyncio.DatagramProtocol):
    def __init__(self, conn):
        self.conn = conn

    def datagram_received(self, data, addr):
        self.conn.from_peer(data, addr[:2])


# ---- the service: an asyncio loop in its own thread, called from the fake's request threads

class WebRTC:
    def __init__(self, turn_port, streams, fps, record=lambda e: None):
        """streams(profile): the path of its Annex B stream (camera.annexb); record: the fake's log."""
        self.turn_port, self.streams, self.fps, self.record = turn_port, streams, fps, record
        self.sessions, self.aus = {}, {}
        self.loop = asyncio.new_event_loop()
        self.conns = set()
        threading.Thread(target=self.loop.run_forever, daemon=True).start()
        self.call(self._listen())

    def call(self, coro, timeout=30):
        return asyncio.run_coroutine_threadsafe(coro, self.loop).result(timeout)

    async def _listen(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("127.0.0.1", self.turn_port))
        self.turn_port = sock.getsockname()[1]
        self.turn = await self.loop.create_server(lambda: TurnConn(self), sock=sock)

    def offer(self, session_id, entity_id, profile, sdp, send):
        """Answers an offer as go2rtc does through HA: send({type: answer}) or send({type: error})."""
        self.loop.call_soon_threadsafe(lambda: asyncio.ensure_future(
            self._offer(session_id, entity_id, profile, sdp, send)))

    def candidate(self, session_id, cand):
        self.loop.call_soon_threadsafe(lambda: asyncio.ensure_future(self._candidate(session_id, cand)))

    def close(self, session_id):
        self.loop.call_soon_threadsafe(lambda: asyncio.ensure_future(self._close(session_id)))

    async def _offer(self, sid, entity_id, profile, sdp, send):
        from aiortc import RTCConfiguration, RTCPeerConnection, RTCRtpSender, RTCSessionDescription
        s = self.sessions[sid] = {"ready": asyncio.Event(), "pc": None, "entity_id": entity_id}
        try:
            if profile not in self.aus:
                self.aus[profile] = await self.loop.run_in_executor(None, camera.access_units, self.streams(profile))
            if not any("H264" in line for line in sdp.splitlines() if line.startswith("a=rtpmap")):
                raise RuntimeError("the offer has no H.264 video")
            first = {}

            def on_frame(n):
                if not first:
                    first["t"] = time.time()
                    self.record({"t": first["t"], "webrtc_sending": entity_id, "session": sid})
            # No ICE servers: aiortc's default is a public STUN server, which it waits up to 5 s for
            # before it answers (2026-11-13), and which the relay makes useless anyway.
            pc = s["pc"] = RTCPeerConnection(RTCConfiguration(iceServers=[]))
            # Before the offer is read, so the video m-line takes this transceiver and its codecs:
            # aiortc settles the common codecs when it reads the offer.
            pc.addTransceiver(_track_class()(self.aus[profile], self.fps, on_frame), direction="sendonly")
            pc.getTransceivers()[0].setCodecPreferences(
                [c for c in RTCRtpSender.getCapabilities("video").codecs if c.mimeType == "video/H264"])
            await pc.setRemoteDescription(RTCSessionDescription(sdp=sdp, type="offer"))

            @pc.on("iceconnectionstatechange")
            def _ice():
                self.record({"t": time.time(), "webrtc_ice": pc.iceConnectionState, "session": sid})

            await pc.setLocalDescription(await pc.createAnswer())
            answer = pc.localDescription.sdp
            fmtp = next((line for line in answer.splitlines() if line.startswith("a=fmtp") and "profile-level-id" in line), None)
            self.record({"t": time.time(), "webrtc_answer": entity_id, "session": sid, "fmtp": fmtp})
            send({"type": "answer", "answer": answer})
        except Exception as e:      # an answer HA would give: the offer failed, and why
            self.record({"t": time.time(), "webrtc_error": entity_id, "session": sid, "error": repr(e)})
            send({"type": "error", "code": "webrtc_offer_failed", "message": str(e)})
        finally:
            s["ready"].set()

    async def _candidate(self, sid, cand):
        from aiortc.sdp import candidate_from_sdp
        s = self.sessions.get(sid)
        if not s:
            return
        await s["ready"].wait()
        text = (cand or {}).get("candidate") or ""
        if not s["pc"] or not text:
            return
        try:
            c = candidate_from_sdp(text.split(":", 1)[1] if text.startswith("candidate:") else text)
            c.sdpMid, c.sdpMLineIndex = cand.get("sdpMid"), cand.get("sdpMLineIndex")
            if c.sdpMid is None and c.sdpMLineIndex is None:
                c.sdpMid = "0"
            await s["pc"].addIceCandidate(c)
        except Exception as e:      # HA logs and ignores a candidate it can't use; so does the fake
            self.record({"t": time.time(), "webrtc_candidate_error": s["entity_id"], "session": sid, "error": repr(e)})

    async def _close(self, sid):
        s = self.sessions.pop(sid, None)
        if s:
            await s["ready"].wait()
            if s["pc"]:
                await s["pc"].close()
            self.record({"t": time.time(), "webrtc_closed": s["entity_id"], "session": sid})

    def close_all(self):
        for sid in list(self.sessions):
            self.close(sid)


def available():
    """Whether aiortc can be imported here (the fake runs from the venv run.sh makes)."""
    try:
        import aiortc  # noqa: F401
        return True
    except ImportError:
        return False


if __name__ == "__main__":
    print(os.path.abspath(__file__))
