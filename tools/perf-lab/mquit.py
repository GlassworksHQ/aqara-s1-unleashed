"""Quit a local Firefox started with --marionette, so it writes its shutdown profile (MIT).

Talks Marionette's wire protocol (length-prefixed JSON) on 127.0.0.1. Used by profile-view.sh.

  python3 tools/perf-lab/mquit.py [port]
"""
import json
import socket
import sys

port = int(sys.argv[1]) if len(sys.argv) > 1 else 2828
s = socket.create_connection(("127.0.0.1", port), timeout=30)
f = s.makefile("rb")


def read():
    n = b""
    while (c := f.read(1)) != b":":
        n += c
    return json.loads(f.read(int(n)))


def send(i, cmd, params):
    b = json.dumps([0, i, cmd, params]).encode()
    s.sendall(str(len(b)).encode() + b":" + b)
    return read()


read()
send(1, "WebDriver:NewSession", {})
print(send(2, "Marionette:Quit", {"flags": ["eForceQuit"]}))
