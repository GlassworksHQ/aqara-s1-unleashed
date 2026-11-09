#!/usr/bin/env python3
"""Evaluate JavaScript in the S1 Kiosk's GeckoView page over the Firefox
Remote Debugging Protocol (no Firefox desktop needed).

Prereqs: kiosk built with remote debugging on (debug builds default to on, or
`am start -n sh.edm.s1kiosk/.MainActivity --ez remote_debug true`), then:

    adb forward tcp:6000 localabstract:sh.edm.s1kiosk/firefox-debugger-socket
    python3 gecko_eval.py 'document.title'
    python3 gecko_eval.py -f snippet.js

Prints the evaluation result (or exception) as JSON.
"""
import argparse
import json
import socket
import sys


class RDP:
    def __init__(self, host, port, timeout=20):
        self.s = socket.create_connection((host, port), timeout=timeout)
        self.buf = b""

    def _read_packet(self):
        while b":" not in self.buf:
            chunk = self.s.recv(65536)
            if not chunk:
                raise EOFError("connection closed")
            self.buf += chunk
        n, rest = self.buf.split(b":", 1)
        n = int(n)
        while len(rest) < n:
            chunk = self.s.recv(65536)
            if not chunk:
                raise EOFError("connection closed")
            rest += chunk
        self.buf = rest[n:]
        return json.loads(rest[:n].decode("utf-8"))

    def send(self, pkt):
        data = json.dumps(pkt).encode("utf-8")
        self.s.sendall(str(len(data)).encode() + b":" + data)

    def request(self, pkt, want=None):
        """Send pkt and return the first reply from pkt['to'] (optionally matching a predicate)."""
        self.send(pkt)
        while True:
            r = self._read_packet()
            if r.get("from") != pkt["to"]:
                continue
            if want is None or want(r):
                return r

    def wait(self, pred):
        while True:
            r = self._read_packet()
            if pred(r):
                return r


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("expr", nargs="?", help="JS expression to evaluate")
    ap.add_argument("-f", "--file", help="read JS from file")
    ap.add_argument("--port", type=int, default=6000)
    ap.add_argument("--host", default="127.0.0.1")
    a = ap.parse_args()
    js = open(a.file).read() if a.file else a.expr
    if not js:
        ap.error("give an expression or -f file")

    c = RDP(a.host, a.port)
    c._read_packet()  # greeting from root
    tabs = c.request({"to": "root", "type": "listTabs"}).get("tabs", [])
    if not tabs:
        sys.exit("no tabs exposed (is a GeckoSession open?)")
    tab = next((t for t in tabs if t.get("selected")), tabs[0])
    target = c.request({"to": tab["actor"], "type": "getTarget"})["frame"]
    console = target["consoleActor"]
    start = c.request({"to": console, "type": "evaluateJSAsync", "text": js, "mapped": {"await": True}})
    rid = start.get("resultID")
    res = c.wait(lambda r: r.get("type") == "evaluationResult" and r.get("resultID") == rid)
    result = res.get("result")
    # Large strings come back as a longString actor; fetch the full text.
    if isinstance(result, dict) and result.get("type") == "longString":
        result = c.request({"to": result["actor"], "type": "substring",
                            "start": 0, "end": result["length"]})["substring"]
    out = {"url": target.get("url"), "result": result}
    if res.get("exception") is not None:
        out["exception"] = res.get("exceptionMessage") or res.get("exception")
    print(json.dumps(out, indent=1, ensure_ascii=False))


if __name__ == "__main__":
    main()
