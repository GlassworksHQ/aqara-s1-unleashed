#!/usr/bin/env python3
"""Sends Wi-Fi details to S1 Kiosk over Improv Serial, through an adb forward of its socket.

    adb forward tcp:0 localabstract:s1kiosk_improv            # prints the local port
    printf '%s\\0%s' "$ssid" "$password" | improv-send.py <port>

Same protocol as kiln/packages/improv-serial/src/serial.ts (https://www.improv-wifi.com/serial/). The details come on
stdin, never as arguments, and the password is never printed. Exit status: 0 connected, 3 the panel
could not join (wrong password or out of range), 1 anything else.
"""
import socket
import sys
import time

HEADER = b"IMPROV"
STATE, ERROR, RPC, RPC_RESULT = 0x01, 0x02, 0x03, 0x04
WIFI_SETTINGS, REQUEST_STATE = 0x01, 0x02
PROVISIONED = 0x04
ERRORS = {
    0x01: "the panel did not understand the request",
    0x02: "the panel does not support that request",
    0x03: "the panel could not join that network (check the password, or that it is in range)",
    0xFF: "something went wrong on the panel",
}


def frame(kind, data):
    b = HEADER + bytes([1, kind, len(data)]) + data
    return b + bytes([sum(b) & 0xFF])


def rpc(cmd, payload=b""):
    return frame(RPC, bytes([cmd, len(payload)]) + payload)


def strings(*ss):
    out = b""
    for s in ss:
        e = s.encode("utf-8")
        if len(e) > 255:
            raise ValueError("Improv string longer than 255 bytes")
        out += bytes([len(e)]) + e
    return out


class Reader:
    def __init__(self):
        self.buf = b""

    def push(self, chunk):
        self.buf += chunk
        out = []
        while True:
            i = self.buf.find(HEADER)
            if i < 0:
                self.buf = self.buf[-5:]
                return out
            self.buf = self.buf[i:]
            if len(self.buf) < 9:
                return out
            total = 9 + self.buf[8] + 1
            if len(self.buf) < total:
                return out
            if sum(self.buf[: total - 1]) & 0xFF == self.buf[total - 1] and self.buf[6] == 1:
                out.append((self.buf[7], self.buf[9 : total - 1]))
                self.buf = self.buf[total:]
            else:
                self.buf = self.buf[1:]


def exchange(port, request, done, timeout):
    """Sends request, returns the first packet done() accepts; None if the connection drops."""
    with socket.create_connection(("127.0.0.1", port), timeout=5) as s:
        s.settimeout(1)
        s.sendall(request)
        r = Reader()
        until = time.time() + timeout
        while time.time() < until:
            try:
                chunk = s.recv(4096)
            except socket.timeout:
                continue
            if not chunk:
                return None
            for p in r.push(chunk):
                if done(p):
                    return p
    raise TimeoutError("the panel did not answer")


def state(port):
    try:
        p = exchange(port, rpc(REQUEST_STATE), lambda p: p[0] == STATE, 5)
        return p[1][0] if p and p[1] else None
    except (OSError, TimeoutError):
        return None


def main():
    if len(sys.argv) != 2 or not sys.argv[1].isdigit():
        print("usage: improv-send.py <local port>  (ssid NUL password on stdin)", file=sys.stderr)
        return 1
    port = int(sys.argv[1])
    raw = sys.stdin.buffer.read()
    if b"\0" not in raw:
        print("improv-send: expected <ssid> NUL <password> on stdin", file=sys.stderr)
        return 1
    ssid, psk = (x.decode("utf-8") for x in raw.split(b"\0", 1))
    # The app opens its socket a moment after starting.
    for _ in range(20):
        if state(port) is not None:
            break
        time.sleep(1.5)
    else:
        print("improv-send: S1 Kiosk is not answering on its Improv socket", file=sys.stderr)
        return 1
    want = lambda p: (p[0] == RPC_RESULT and p[1][:1] == bytes([WIFI_SETTINGS])) or (p[0] == ERROR and p[1][:1] != b"\0")
    for attempt in range(3):
        try:
            p = exchange(port, rpc(WIFI_SETTINGS, strings(ssid, psk)), want, 60)
        except (OSError, TimeoutError) as e:
            p, err = None, str(e)
        else:
            err = "the connection closed"
        if p and p[0] == RPC_RESULT:
            print(f"improv-send: the panel joined {ssid}")
            return 0
        if p and p[0] == ERROR:
            print(f"improv-send: {ERRORS.get(p[1][0], 'error %d' % p[1][0])}", file=sys.stderr)
            return 3 if p[1][0] == 0x03 else 1
        # The app can restart right after joining and cut the connection: ask where it got to.
        time.sleep(3)
        if state(port) == PROVISIONED:
            print(f"improv-send: the panel joined {ssid}")
            return 0
        print(f"improv-send: try {attempt + 1}: {err}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
