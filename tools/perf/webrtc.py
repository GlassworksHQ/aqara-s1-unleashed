"""Clarity's WebRTC cameras (MIT, #202): the peer the fake Home Assistant answers HA's WebRTC offers
with, and the TURN relay the panel reaches it through.

Real setups mostly play cameras over WebRTC: go2rtc, built into HA since 2024.11, answers the
frontend's offer and the browser's own media stack carries the video. Here aiortc answers instead
and sends camera.py's H.264 stream, the same bitstream the HLS segments carry, never re-encoded:
its access units at 15 fps, looped.

The panel reaches this computer only through `adb reverse`, which forwards TCP, and WebRTC's media
is UDP. So the browser gets one TURN server, over TCP (`turn:localhost:<port>?transport=tcp`), and
only relayed candidates (`iceTransportPolicy: relay`). Its TCP connection goes through adb reverse
to the relay below. The relay's UDP side and the peer share one address of this computer, so the
media between them never leaves it, and the panel never sends to that address itself.

Why not loopback for all of it (Firefox 152 desktop, 2026-11-13; GeckoView on the S1 the same):
Gecko never connects to a TURN server given as 127.0.0.1 (error 701, no TCP connection), and drops a
relayed candidate whose address is loopback. `localhost` gets it to connect; a relayed address that
isn't loopback gets it a candidate. Chrome accepts either. Gecko's `media.peerconnection.ice.loopback`
pref lifts both, but the kiosk's Gecko settings aren't the test's to change.

Host-side only: aiortc and its dependencies come from tools/perf/webrtc-requirements.txt (locked with
hashes). The relay listens on 127.0.0.1 (TCP) and on the media address (UDP); it relays only to the
peer, and reads nothing from the device.
"""
import asyncio
import fractions
import hashlib
import os
import re
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


def media_address():
    """The address the relay and the peer share: GW_PERF_WEBRTC_ADDR, else this computer's IPv4
    address towards its default route, else its first IPv4 address that is neither loopback nor
    link-local (Gecko refuses both for a relay). Packets to it are delivered inside this computer."""
    if os.environ.get("GW_PERF_WEBRTC_ADDR"):
        return os.environ["GW_PERF_WEBRTC_ADDR"]
    usable = lambda a: a and not a.startswith(("127.", "169.254.", "0."))  # noqa: E731
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.0.2.1", 9))           # picks the route only; nothing is sent
            if usable(s.getsockname()[0]):
                return s.getsockname()[0]
    except OSError:
        pass
    import ifaddr
    for adapter in ifaddr.get_adapters():
        for ip in adapter.ips:
            if isinstance(ip.ip, str) and usable(ip.ip):
                return ip.ip
    raise RuntimeError("the WebRTC relay needs an IPv4 address on this computer that isn't loopback "
                       "(set GW_PERF_WEBRTC_ADDR to one)")


# How the peer decides a connection is dead: as go2rtc does in a real setup. HA 2026.11.0 pins go2rtc
# 1.9.15 (homeassistant/components/go2rtc/const.py, and its Dockerfile), which uses pion/webrtc
# v4.2.25 and pion/ice v4.4.8 (go2rtc's go.mod) and sets no ICE timeouts (its pkg/webrtc/api.go). So
# pion/ice's defaults (agent_config.go:21, 24, 31): a Binding request on the selected pair every 2 s,
# "disconnected" after 5 s without receiving anything from the browser, "failed" 25 s after that
# (agent.go:906-946). Anything means any packet, media or STUN, however late an answer is
# (candidate_base.go:337, agent.go:1845). aioice's own check (RFC 7675 as it reads it) gives each
# answer 0.5 s and closes after 6 late ones, which dropped a busy panel's sessions while its answers
# were still arriving (the S1's heavy view, #202 smoke 4: none lost, median 1.4 to 2.6 s).
PION_ICE = {"keepalive_s": 2.0, "disconnected_s": 5.0, "failed_s": 25.0}
_liveness_listeners = []


def _install_pion_liveness():
    """aioice's consent check, replaced by pion's liveness (above). Once per process."""
    if getattr(aioice.ice.Connection, "_gw_pion", False):
        return
    received = aioice.ice.StunProtocol.datagram_received

    def stamped(self, data, addr):
        self.gw_rx = time.monotonic()                  # anything from the browser keeps it alive
        return received(self, data, addr)

    async def keepalive(self):
        start, quiet_since = time.monotonic(), {}
        while True:
            await asyncio.sleep(PION_ICE["keepalive_s"])
            for component, pair in list(self._nominated.items()):
                quiet = time.monotonic() - getattr(pair.protocol, "gw_rx", start)
                if quiet > PION_ICE["disconnected_s"] and component not in quiet_since:
                    quiet_since[component] = True
                    for f in _liveness_listeners:
                        f("disconnected", quiet)
                elif quiet <= PION_ICE["disconnected_s"] and quiet_since.pop(component, None):
                    for f in _liveness_listeners:
                        f("connected", quiet)
                if quiet > PION_ICE["disconnected_s"] + PION_ICE["failed_s"]:
                    for f in _liveness_listeners:
                        f("failed", quiet)
                    self._query_consent_task = None
                    return await self.close()
                request = self.build_request(pair, nominate=False)
                request.add_message_integrity(self.remote_password.encode("utf8"))
                pair.protocol.send_stun(request, pair.remote_addr)

    aioice.ice.StunProtocol.datagram_received = stamped
    aioice.ice.Connection.query_consent = keepalive
    aioice.ice.Connection._gw_pion = True


# How the panel reaches the peer (protocol 7, #202 option B; lanprobe.py picks): `lan-udp`, no relay,
# UDP between the panel and the peer on the LAN as a real setup; `lan-turn`, the relay over TCP to this
# computer's address; `adb`, TURN over TCP through adb reverse, the fallback on any network.
TRANSPORTS = ("adb", "lan-turn", "lan-udp")


def client_config(turn_port, transport="adb", addr=None):
    """camera/webrtc/get_client_config's result. HA sends only iceServers (webrtc-models'
    RTCConfiguration); the relay-only policy is the fake's one addition, so a browser on the same
    network as this computer can't pair its own address and skip the relay. `lan-udp` gives no
    servers, as HA would on a LAN (it adds public STUN servers, which a LAN doesn't need)."""
    if transport == "lan-udp":
        return {"configuration": {"iceServers": []}}
    host = addr if transport == "lan-turn" else "localhost"
    return {"configuration": {"iceServers": [{"urls": f"turn:{host}:{turn_port}?transport=tcp",
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
        self.to_peer = self.from_peer_n = 0        # datagrams relayed each way (logged when it closes)
        # Bytes over the TCP connection each way, and once a second (WebRTC._seconds) those totals
        # with the bytes still queued toward the browser (#202, smoke 5): where the video waits, on
        # this computer (adb not taking it) or on the panel (its /proc/net/tcp rx_queue).
        self.down_bytes = self.up_bytes = 0
        self.born, self.seconds = time.time(), []
        # The peer's ICE checks (its keepalives once connected, PION_ICE) on their way to the
        # browser, by transaction id, and how long each answer took through the browser.
        self.checks, self.check_rtt, self.late = {}, [], []

    def mapped(self):
        """XOR-MAPPED-ADDRESS: the browser as this relay sees it. Through adb reverse that is always
        127.0.0.1 (adb's own connection), and Gecko drops the relay candidate of an allocation whose
        mapped address is loopback (Firefox 152, 2026-11-13; the S1's second smoke). The browser's
        real address is unknown here, so a loopback one is reported as the media address, which the
        browser never sends to: with relay-only ICE it only labels the relay candidate."""
        host, port = self.client
        return (self.server.addr, port) if host.startswith("127.") or host == "::1" else self.client

    def connection_made(self, transport):
        self.tr = transport
        self.client = transport.get_extra_info("peername")[:2]
        self.server.conns.add(self)
        self.server.record({"t": time.time(), "turn_connected": list(self.client)})

    def connection_lost(self, exc):
        self.server.conns.discard(self)
        if self.relay:
            self.relay.close()
        rtt = sorted(self.check_rtt)
        self.server.record({"t": time.time(), "turn_closed": list(self.client), "to_peer": self.to_peer,
                            "from_peer": self.from_peer_n, "permissions": sorted(self.perms),
                            "channels": len(self.chan),
                            # Each second: [s since opened, kB down, kB up, datagrams down, up, kB queued
                            # here toward the browser] (totals so far, except queued).
                            "seconds": self.seconds if self.to_peer or self.from_peer_n else [],
                            # The peer's checks through the browser: answered (round trip, ms), over
                            # 500 ms (late for aiortc: the first 20, with when), and never answered.
                            "checks": {"answered": len(rtt), "unanswered": len(self.checks),
                                       "median_ms": round(rtt[len(rtt) // 2]) if rtt else None,
                                       "max_ms": round(rtt[-1]) if rtt else None,
                                       "over_500_ms": sum(1 for x in rtt if x > 500), "late": self.late}})

    def data_received(self, data):
        self.up_bytes += len(data)
        self.buf += data
        while len(self.buf) >= 4:
            if self.buf[0] & 0xC0 == 0x40:                    # ChannelData, padded to 4 over TCP
                num, n = struct.unpack("!HH", self.buf[:4])
                size = 4 + n + (-n % 4)
                if len(self.buf) < size:
                    return
                payload, self.buf = self.buf[4:4 + n], self.buf[size:]
                peer = self.chan.get(num)
                # Checked again on every packet, as Send and from_peer are: a channel bound while a
                # peer was live mustn't reach its port after that session closed (#205's verifier).
                if peer and self.relay and self.server.is_peer(peer):
                    self.relay.sendto(payload, peer)
                    self.to_peer += 1
                    self.answer_seen(payload)
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
        self.write(bytes(out))

    def write(self, data):
        self.down_bytes += len(data)
        self.tr.write(data)

    def second(self):
        """One sample of this connection: seconds since it opened, kB down and up so far, datagrams
        each way so far, and kB queued here toward the browser now."""
        queued = self.tr.get_write_buffer_size() if not self.tr.is_closing() else 0
        self.seconds.append([round(time.time() - self.born, 1), round(self.down_bytes / 1024, 1),
                             round(self.up_bytes / 1024, 1), self.from_peer_n, self.to_peer, round(queued / 1024, 1)])

    def stun(self, m):
        M = stun.Method
        if m.message_method == M.BINDING and m.message_class == stun.Class.REQUEST:
            return self.reply(m, auth=False, XOR_MAPPED_ADDRESS=self.mapped())
        if m.message_class == stun.Class.INDICATION:
            if m.message_method == M.SEND and self.relay and m.attributes.get("XOR-PEER-ADDRESS", ("",))[0] in self.perms \
                    and self.server.is_peer(m.attributes["XOR-PEER-ADDRESS"]):
                self.relay.sendto(m.attributes.get("DATA", b""), m.attributes["XOR-PEER-ADDRESS"])
                self.to_peer += 1
                self.answer_seen(m.attributes.get("DATA", b""))
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
        elif m.attributes.get("XOR-PEER-ADDRESS", ("",))[0] != self.server.addr:
            # The peer is at the media address; the relay reaches nothing else.
            self.server.record({"t": time.time(), "turn_refused": m.attributes.get("XOR-PEER-ADDRESS")})
            self.reply(m, stun.Class.ERROR, ERROR_CODE=(403, "Forbidden"))
        elif m.message_method == M.CREATE_PERMISSION:
            if m.attributes["XOR-PEER-ADDRESS"][0] not in self.perms:
                self.server.record({"t": time.time(), "turn_permission": list(m.attributes["XOR-PEER-ADDRESS"]),
                                    "client": list(self.client)})
            self.perms.add(m.attributes["XOR-PEER-ADDRESS"][0])
            self.reply(m)
        elif m.message_method == M.CHANNEL_BIND:
            num, peer = m.attributes["CHANNEL-NUMBER"], m.attributes["XOR-PEER-ADDRESS"]
            if not self.server.is_peer(peer):
                # The media address, but not one of the fake's peers' ports: another program's (#205, CodeRabbit).
                self.server.record({"t": time.time(), "turn_refused": list(peer)})
                return self.reply(m, stun.Class.ERROR, ERROR_CODE=(403, "Forbidden"))
            if num not in self.chan:
                self.server.record({"t": time.time(), "turn_channel": list(peer), "number": num, "client": list(self.client)})
            self.chan[num], self.peer_chan[peer] = peer, num
            self.perms.add(peer[0])
            self.reply(m)

    async def allocate(self, m):
        loop = asyncio.get_running_loop()
        self.relay, _ = await loop.create_datagram_endpoint(lambda: _Relay(self), local_addr=(self.server.addr, 0))
        if self.tr.is_closing():                  # the browser left while it was made
            self.relay.close()
            return
        relayed = self.relay.get_extra_info("sockname")[:2]
        self.server.record({"t": time.time(), "turn_allocated": list(relayed), "client": list(self.client),
                            "mapped": list(self.mapped())})
        self.reply(m, XOR_RELAYED_ADDRESS=relayed, XOR_MAPPED_ADDRESS=self.mapped(), LIFETIME=TURN_LIFETIME)

    def answer_seen(self, data):
        """A STUN Binding success from the browser to the peer: the answer to one of the peer's checks."""
        if len(data) >= 20 and data[:2] == b"\x01\x01":
            sent = self.checks.pop(data[8:20], None)
            if sent is not None:
                rtt = (time.monotonic() - sent) * 1000
                self.check_rtt.append(rtt)
                if rtt > 500 and len(self.late) < 20:
                    self.late.append([round(time.time(), 1), round(rtt)])

    def from_peer(self, data, addr):
        if addr[0] not in self.perms or not self.server.is_peer(addr):
            return
        self.from_peer_n += 1
        if len(data) >= 20 and data[:2] == b"\x00\x01":       # a STUN Binding request: the peer's check
            self.checks[data[8:20]] = time.monotonic()
        num = self.peer_chan.get(addr)
        if num is not None:
            self.write(struct.pack("!HH", num, len(data)) + data + bytes(-len(data) % 4))
        else:
            ind = stun.Message(stun.Method.DATA, stun.Class.INDICATION,
                               attributes={"XOR-PEER-ADDRESS": addr, "DATA": data})
            self.write(bytes(ind))


class _Relay(asyncio.DatagramProtocol):
    def __init__(self, conn):
        self.conn = conn

    def datagram_received(self, data, addr):
        self.conn.from_peer(data, addr[:2])


# ---- the service: an asyncio loop in its own thread, called from the fake's request threads

class WebRTC:
    def __init__(self, turn_port, streams, fps, record=lambda e: None, transport="adb"):
        """streams(profile): the path of its Annex B stream (camera.annexb); record: the fake's log."""
        self.turn_port, self.streams, self.fps, self.record = turn_port, streams, fps, record
        if transport not in TRANSPORTS:
            raise ValueError(f"transport {transport!r} isn't one of {', '.join(TRANSPORTS)}")
        self.transport = transport
        self.sessions, self.aus = {}, {}
        # The peer's only candidate is the media address: aioice's own list would add every
        # interface (and leaves loopback out).
        self.addr = media_address()
        aioice.ice.get_host_addresses = lambda use_ipv4=True, use_ipv6=True: [self.addr]
        # The peer's liveness as go2rtc's (PION_ICE); its transitions are logged, and the ICE close
        # after "failed" is marked with that reason.
        _install_pion_liveness()
        self.liveness_failed_at = 0.0

        def liveness(state, quiet):
            if state == "failed":
                self.liveness_failed_at = time.monotonic()
            self.record({"t": time.time(), "webrtc_liveness": state, "quiet_s": round(quiet, 1)})
        _liveness_listeners.append(liveness)
        self.loop = asyncio.new_event_loop()
        self.conns = set()
        threading.Thread(target=self.loop.run_forever, daemon=True).start()
        self.call(self._listen())

    def call(self, coro, timeout=30):
        return asyncio.run_coroutine_threadsafe(coro, self.loop).result(timeout)

    async def _listen(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        # adb reverse reaches loopback; lan-turn comes over the network to the media address.
        sock.bind((self.addr if self.transport == "lan-turn" else "127.0.0.1", self.turn_port))
        self.turn_port = sock.getsockname()[1]
        self.turn = await self.loop.create_server(lambda: TurnConn(self), sock=sock)
        asyncio.ensure_future(self._seconds())

    async def _seconds(self):
        while True:
            await asyncio.sleep(1)
            for conn in list(self.conns):
                conn.second()

    def offer(self, session_id, entity_id, profile, sdp, send):
        """Answers an offer as go2rtc does through HA: send({type: answer}) or send({type: error})."""
        self.loop.call_soon_threadsafe(lambda: asyncio.ensure_future(
            self._offer(session_id, entity_id, profile, sdp, send)))

    def candidate(self, session_id, cand):
        self.loop.call_soon_threadsafe(lambda: asyncio.ensure_future(self._candidate(session_id, cand)))

    def close(self, session_id):
        self.loop.call_soon_threadsafe(lambda: asyncio.ensure_future(self._close(session_id)))

    def is_peer(self, addr):
        """One of this service's WebRTC peers: the media address at a port a live session's peer
        listens on. The relay reaches nothing else, not even another program on the media address:
        its credentials are a fixed test value (#205, CodeRabbit)."""
        try:
            return addr[0] == self.addr and int(addr[1]) in {p for s in list(self.sessions.values()) for p in s.get("ports", ())}
        except (TypeError, ValueError, IndexError):
            return False

    async def _offer(self, sid, entity_id, profile, sdp, send):
        from aiortc import RTCConfiguration, RTCPeerConnection, RTCRtpSender, RTCSessionDescription
        s = self.sessions[sid] = {"ready": asyncio.Event(), "pc": None, "entity_id": entity_id}
        try:
            if profile not in self.aus:
                self.aus[profile] = await self.loop.run_in_executor(None, lambda: camera.access_units(self.streams(profile)))
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
                # `lost`: closed while the browser was still subscribed (not by its unsubscribe):
                # `reason` liveness when nothing came from the browser for 30 s (PION_ICE), else
                # the browser's side closed (DTLS or the connection ended).
                lost = pc.iceConnectionState == "closed" and sid in self.sessions
                extra = {}
                if lost:
                    extra = {"lost": True, "reason": "liveness" if time.monotonic() - self.liveness_failed_at < 2 else "remote"}
                self.record(dict({"t": time.time(), "webrtc_ice": pc.iceConnectionState, "session": sid}, **extra))

            await pc.setLocalDescription(await pc.createAnswer())
            answer = pc.localDescription.sdp
            # The ports this peer listens on (its host candidates in the answer, all gathered by
            # now): the relay sends to and takes from these only (is_peer).
            s["ports"] = {int(p) for host, p in re.findall(r"a=candidate:\S+ \d+ \S+ \d+ (\S+) (\d+) typ host", answer)
                          if host == self.addr}
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
            self.record({"t": time.time(), "webrtc_candidate": s["entity_id"], "session": sid, "type": c.type,
                         "protocol": c.protocol})
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
