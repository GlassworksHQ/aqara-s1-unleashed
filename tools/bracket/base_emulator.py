#!/usr/bin/env python3
"""
Fake Aqara S1 Plus wall base ("bracket") speaking the UART protocol of /dev/ttyS3, over TCP.

The protocol is documented in docs/base-protocol.md (reverse-engineered from Aqara's
/vendor/bin/bracket_cli and /vendor/lib/libha_bracket.so). This emulator plays the *base* side:

  * answers reads (type 1 -> type 4 read response, same sequence number),
  * applies writes (type 2 -> type 3 write ack, status 0) and then reports the new value (type 5),
  * sends unsolicited reports (type 5): power (0.12.85) / current (0.14.85) every --report-interval s,
    and a relay report whenever a "physical" toggle is simulated (stdin: "t1", "t2", ...),
  * checks every received frame (header checksum, 0x80 0x02 marker, CRC-16/MODBUS) and logs it.

Default identity: a 2-channel switch base (200.1.159 = 0xF1100002 -> model 0xF110, 2 channels,
dock class 14.160.85 = 0x0100F110 as derived by libha_bracket).

Expose it to the panel without hardware:
    python3 tools/bracket/base_emulator.py --port 9999
    adb reverse tcp:9999 tcp:9999
    adb shell 'cd /data/local/tmp && /vendor/bin/socat pty,link=s3a,raw,echo=0 tcp:127.0.0.1:9999 &'
    adb shell /vendor/bin/bracket_cli /data/local/tmp/s3a 115200 200.1.159
The S1 Kiosk app can connect directly: --es base_transport tcp:127.0.0.1:9999 (after adb reverse).

Only one client at a time (like a UART). Stdin commands: t<N> toggle relay N (simulated
physical press, sends a report), p<W> set power, r report power now, s show state, q quit.
"""
import argparse
import random
import select
import socket
import struct
import sys
import threading
import time

SYNC = 0x55
MARK = (0x80, 0x02)
T_READ, T_WRITE, T_WRITE_ACK, T_READ_RSP, T_REPORT = 1, 2, 3, 4, 5
TYPE_NAMES = {1: "read", 2: "write", 3: "write-ack", 4: "read-rsp", 5: "report"}


def crc16_modbus(data: bytes) -> int:
    """CRC-16/MODBUS: poly 0x8005 reflected (0xA001), init 0xFFFF, no xorout. Sent low byte first."""
    crc = 0xFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc


def header_checksum(h7: bytes) -> int:
    return (-sum(h7)) & 0xFF


# (name, cls, idx, dev, default) - libha_bracket s_tResMaps (0x1a018) order. cls selects the value type.
RES = [
    ("200.1.159", 0x00, 0x00, 0x80, 0xF1100002),  # identity: model<<16 | channels (handshake)
    ("8.0.2022", 0x00, 0x01, 0x80, 0x00000017),   # firmware version (u32)
    ("200.4.1", 0x00, 0x0F, 0x80, 3),             # config, written 3 by ha_master
    ("4.1.85", 0x01, 0x00, 0x55, 0),              # relay 1 (u8)
    ("4.2.85", 0x01, 0x01, 0x55, 0),
    ("4.3.85", 0x01, 0x02, 0x55, 0),
    ("200.4.5", 0x05, 0x00, 0x55, 0),             # over-temperature state (bracket_cli: "8.0.2014")
    ("200.4.6", 0x05, 0x01, 0x55, 0),             # over-load state (bracket_cli: "8.0.2014")
    ("4.103.85", 0x05, 0x05, 0x55, 1),            # config, written 1 by ha_master (power-on state?)
    ("0.11.85", 0x06, 0x00, 0x55, 231.4),         # float
    ("0.14.85", 0x06, 0x01, 0x55, 0.0),           # current (float)
    ("0.12.85", 0x06, 0x02, 0x55, 0.0),           # power W (float)
    ("0.13.85", 0x06, 0x04, 0x55, 0.0),           # energy (host-side in ha_master)
    ("0.32.85", 0x06, 0x08, 0x55, 41.5),          # float (chip temperature?)
    ("200.4.2", 0x06, 0x06, 0x55, 85.0),          # config, written 85.0 (over-temp threshold?)
    ("200.4.3", 0x06, 0x07, 0x55, 2200.0),        # config, written 2200.0 (over-load W?)
    ("200.4.4", 0x06, 0x09, 0x55, 10000.0),       # config, written 10000.0
]


def value_len(cls: int) -> int:
    return 1 if cls in (1, 4) else 4


def is_float(cls: int) -> bool:
    return cls in (3, 6)


def enc_value(cls: int, v) -> bytes:
    if cls in (1, 4):
        return bytes([int(v) & 0xFF])
    if is_float(cls):
        return struct.pack(">f", float(v))
    return struct.pack(">I", int(v) & 0xFFFFFFFF)


def dec_value(cls: int, b: bytes):
    if cls in (1, 4):
        return b[0]
    if is_float(cls):
        return struct.unpack(">f", b[:4])[0]
    return struct.unpack(">I", b[:4])[0]


def frame(ftype: int, seq: int, payload: bytes) -> bytes:
    h = bytes([SYNC, MARK[0], MARK[1], seq & 0xFF, ftype, (len(payload) >> 8) & 0xFF, len(payload) & 0xFF])
    body = h + bytes([header_checksum(h)]) + payload
    crc = crc16_modbus(body)
    return body + bytes([crc & 0xFF, crc >> 8])


def hexs(b: bytes) -> str:
    return " ".join("%02x" % x for x in b)


class Parser:
    """Byte-stream framer: resyncs on 55 80 02 with a valid header checksum."""

    def __init__(self):
        self.buf = bytearray()

    def feed(self, data: bytes):
        self.buf += data
        out = []
        while True:
            i = self.buf.find(bytes([SYNC, MARK[0], MARK[1]]))
            if i < 0:
                if len(self.buf) > 2:
                    junk = bytes(self.buf[:-2])
                    del self.buf[:-2]
                    out.append(("junk", junk))
                return out
            if i > 0:
                out.append(("junk", bytes(self.buf[:i])))
                del self.buf[:i]
            if len(self.buf) < 8:
                return out
            h = bytes(self.buf[:8])
            if header_checksum(h[:7]) != h[7]:
                out.append(("junk", bytes(self.buf[:1])))
                del self.buf[:1]
                continue
            total = 8 + ((h[5] << 8) | h[6]) + 2
            if total > 512:
                del self.buf[:1]
                continue
            if len(self.buf) < total:
                return out
            f = bytes(self.buf[:total])
            del self.buf[:total]
            crc = crc16_modbus(f[:-2])
            ok = f[-2] == (crc & 0xFF) and f[-1] == (crc >> 8)
            out.append(("frame" if ok else "badcrc", f))


class Base:
    def __init__(self, args):
        self.args = args
        self.lock = threading.Lock()
        self.by_id = {}
        self.vals = {}
        for name, cls, idx, dev, default in RES:
            self.by_id[(cls, idx, dev)] = name
            self.vals[name] = default
        self.vals["200.1.159"] = (args.model << 16) | (args.channels & 0xFF)
        self.vals["8.0.2022"] = args.firmware
        self.meta = {name: (cls, idx, dev) for name, cls, idx, dev, _ in RES}
        self.seq = random.randrange(256)
        self.conn = None
        self.log_file = open(args.transcript, "a") if args.transcript else None
        self.load_w = args.load

    def log(self, msg: str):
        line = "%s %s" % (time.strftime("%H:%M:%S"), msg)
        print(line, flush=True)
        if self.log_file:
            self.log_file.write(line + "\n")
            self.log_file.flush()

    def describe(self, f: bytes) -> str:
        t, seq, plen = f[4], f[3], (f[5] << 8) | f[6]
        p = f[8:8 + plen]
        s = "type=%d(%s) seq=0x%02x len=%d" % (t, TYPE_NAMES.get(t, "?"), seq, plen)
        if t in (T_READ, T_WRITE, T_READ_RSP, T_REPORT) and len(p) >= 3:
            name = self.by_id.get((p[0], p[1], p[2]), "?")
            s += " rid=%02x.%02x.%02x(%s)" % (p[0], p[1], p[2], name)
            if len(p) > 3:
                s += " value=%r" % (dec_value(p[0], p[3:]),)
        elif t == T_WRITE_ACK and p:
            s += " status=%d" % p[0]
        crc = crc16_modbus(f[:-2])
        s += " crc=%02x%02x(%s)" % (f[-2], f[-1], "ok" if (f[-2], f[-1]) == (crc & 0xFF, crc >> 8) else "BAD")
        return s

    def send(self, f: bytes, what: str):
        fault = self.args.fault
        if fault and f[4] in (T_WRITE_ACK, T_READ_RSP):
            f = bytearray(f)
            if fault == "crc":
                f[-1] ^= 0x01
            elif fault == "seq":
                f[3] = (f[3] + 1) & 0xFF
                f[7] = header_checksum(bytes(f[:7]))
                crc = crc16_modbus(bytes(f[:-2]))
                f[-2], f[-1] = crc & 0xFF, crc >> 8
            elif fault == "hdr":
                f[7] ^= 0x01
            elif fault == "crc-be":  # CRC sent high byte first: proves the byte order
                f[-2], f[-1] = f[-1], f[-2]
            f = bytes(f)
            what += " [fault=%s]" % fault
        c = self.conn
        if c is None:
            return
        try:
            c.sendall(f)
            self.log("TX %s | %s  [%s]" % (hexs(f), self.describe(f), what))
        except OSError as e:
            self.log("TX failed: %s" % e)

    def report(self, name: str, why: str):
        cls, idx, dev = self.meta[name]
        with self.lock:
            v = self.vals[name]
            self.seq = (self.seq + 1) & 0xFF
            seq = self.seq
        self.send(frame(T_REPORT, seq, bytes([cls, idx, dev]) + enc_value(cls, v)), why)

    def on_frame(self, f: bytes):
        self.log("RX %s | %s" % (hexs(f), self.describe(f)))
        t, seq, plen = f[4], f[3], (f[5] << 8) | f[6]
        p = f[8:8 + plen]
        if t not in (T_READ, T_WRITE) or len(p) < 3:
            self.log("   (ignored: not a request)")
            return
        key = (p[0], p[1], p[2])
        name = self.by_id.get(key)
        if name is None:
            if self.args.answer_unknown:
                self.log("   unknown rid; answering 0")
                self.send(frame(T_READ_RSP, seq, bytes(key) + enc_value(p[0], 0)), "read rsp (unknown)")
            else:
                self.log("   unknown rid; no answer (as the real base presumably does)")
            return
        if t == T_READ:
            with self.lock:
                v = self.vals[name]
            time.sleep(self.args.delay / 1000.0)
            self.send(frame(T_READ_RSP, seq, bytes(key) + enc_value(p[0], v)), "read rsp %s=%r" % (name, v))
            return
        # write
        want = 3 + value_len(p[0])
        if len(p) < want:
            self.log("   short write payload (%d < %d); nack" % (len(p), want))
            self.send(frame(T_WRITE_ACK, seq, b"\x01"), "write nack")
            return
        v = dec_value(p[0], p[3:])
        with self.lock:
            self.vals[name] = v
            if name.startswith("4.") and name.endswith(".85") and name != "4.103.85":
                self.update_power()
        time.sleep(self.args.delay / 1000.0)
        self.send(frame(T_WRITE_ACK, seq, b"\x00"), "write ack %s=%r" % (name, v))
        if self.args.report_after_write:
            self.report(name, "state report after write")

    def update_power(self):
        on = sum(1 for n in ("4.1.85", "4.2.85", "4.3.85")[: self.args.channels] if self.vals[n])
        p = self.load_w * on
        self.vals["0.12.85"] = float(p)
        self.vals["0.14.85"] = round(p / max(self.vals["0.11.85"], 1.0), 3)

    def periodic(self):
        while True:
            time.sleep(self.args.report_interval)
            if self.conn is None or self.args.report_interval <= 0:
                continue
            with self.lock:
                if self.vals["0.12.85"] > 0:
                    jitter = random.uniform(-0.4, 0.4)
                    self.vals["0.12.85"] = max(0.0, round(self.vals["0.12.85"] + jitter, 2))
            self.report("0.12.85", "periodic power report")
            self.report("0.14.85", "periodic current report")

    def stdin_loop(self):
        for line in sys.stdin:
            cmd = line.strip()
            if not cmd:
                continue
            if cmd == "q":
                self.log("quit")
                import os
                os._exit(0)
            elif cmd.startswith("t") and cmd[1:].isdigit():
                n = "4.%s.85" % cmd[1:]
                if n in self.vals:
                    with self.lock:
                        self.vals[n] = 0 if self.vals[n] else 1
                        self.update_power()
                    self.report(n, "simulated physical toggle")
                    self.report("0.12.85", "power after toggle")
            elif cmd.startswith("p"):
                with self.lock:
                    self.vals["0.12.85"] = float(cmd[1:] or 0)
                self.report("0.12.85", "power set from stdin")
            elif cmd == "r":
                self.report("0.12.85", "power report on demand")
            elif cmd.startswith("o") and cmd[1:].isdigit():  # o<state>: over-load state report
                with self.lock:
                    self.vals["200.4.6"] = int(cmd[1:])
                self.report("200.4.6", "over-load state")
            elif cmd == "s":
                with self.lock:
                    self.log("state: %r" % self.vals)

    def serve(self):
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind((self.args.host, self.args.port))
        srv.listen(1)
        self.log("base emulator on %s:%d: model=0x%04x channels=%d fw=%d (200.1.159=0x%08x)" % (
            self.args.host, self.args.port, self.args.model, self.args.channels, self.args.firmware,
            self.vals["200.1.159"]))
        threading.Thread(target=self.periodic, daemon=True).start()
        if self.args.stdin:
            threading.Thread(target=self.stdin_loop, daemon=True).start()
        while True:
            c, addr = srv.accept()
            c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.log("client connected from %s:%d" % addr)
            self.conn = c
            parser = Parser()
            if self.args.hello:
                self.report("0.12.85", "power report on connect (lets the host start its handshake)")
            try:
                while True:
                    r, _, _ = select.select([c], [], [], 1.0)
                    if not r:
                        continue
                    data = c.recv(4096)
                    if not data:
                        break
                    for kind, f in parser.feed(data):
                        if kind == "frame":
                            self.on_frame(f)
                        elif kind == "badcrc":
                            self.log("RX %s | BAD CRC: %s" % (hexs(f), self.describe(f)))
                        else:
                            self.log("RX junk %s" % hexs(f))
            except OSError as e:
                self.log("connection error: %s" % e)
            self.conn = None
            c.close()
            self.log("client disconnected")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=9999)
    ap.add_argument("--model", type=lambda s: int(s, 0), default=0xF110, help="model id (200.1.159 >> 16); libha_bracket accepts 0xF110")
    ap.add_argument("--channels", type=int, default=2)
    ap.add_argument("--firmware", type=lambda s: int(s, 0), default=23, help="8.0.2022 value")
    ap.add_argument("--load", type=float, default=60.0, help="W drawn per relay that is on")
    ap.add_argument("--report-interval", type=float, default=10.0, help="periodic power report period (s), 0 = off")
    ap.add_argument("--delay", type=float, default=5.0, help="reply delay (ms)")
    ap.add_argument("--no-report-after-write", dest="report_after_write", action="store_false")
    ap.add_argument("--no-hello", dest="hello", action="store_false", help="don't send a power report on connect")
    ap.add_argument("--answer-unknown", action="store_true", help="answer reads of unknown rids with 0")
    ap.add_argument("--fault", choices=["crc", "seq", "hdr", "crc-be"], help="corrupt every response (negative tests)")
    ap.add_argument("--transcript", help="append the log to this file")
    ap.add_argument("--no-stdin", dest="stdin", action="store_false")
    args = ap.parse_args()
    Base(args).serve()


if __name__ == "__main__":
    main()
