#!/usr/bin/env python3
"""The panel's side of Clarity's transport during a run (MIT, #202). Read-only.

Clarity reaches the panel through `adb reverse`: the frontend's websocket and HTTP on --port, the
WebRTC relay on --port + 1. When video stalls (the S1's heavy view, smoke 5), the bytes wait
somewhere: queued on the computer (the relay's `seconds` in fakeha.jsonl show it), in adb, or in the
panel's sockets, unread. This samples the panel's side while run.sh runs:

- every second, the TCP sockets on those ports (`/proc/net/tcp`, `/proc/net/tcp6`): state, tx_queue and
  rx_queue (bytes the kernel holds: rx_queue on the kiosk's socket is data it hasn't read yet), uid;
- every --cpu-every seconds, each thread's share of a core for the kiosk's processes and adbd
  (threadcpu.py), the busiest first;
- with --logcat, the whole logcat into a file.

It only reads: `cat` of /proc files and `ps` over adb, and `logcat`. Nothing on the device changes.

  tools/perf/transportwatch.py --out DIR [-s SERIAL] [--port 8089] [--seconds 900] [--logcat]

Out: DIR/transport.jsonl, one line per sample; `t` is this computer's clock, as in fakeha.jsonl.
"""
import argparse
import json
import os
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import threadcpu as T  # noqa: E402

STATES = {"01": "ESTABLISHED", "02": "SYN_SENT", "03": "SYN_RECV", "04": "FIN_WAIT1", "05": "FIN_WAIT2",
          "06": "TIME_WAIT", "07": "CLOSE", "08": "CLOSE_WAIT", "09": "LAST_ACK", "0A": "LISTEN", "0B": "CLOSING"}


def address(hexaddr):
    """'0100007F:1F91' -> ('127.0.0.1', 8081); IPv6 (and IPv4-mapped) as hex groups."""
    ip, port = hexaddr.split(":")
    if len(ip) == 8:
        b = bytes.fromhex(ip)[::-1]
        return ".".join(str(x) for x in b), int(port, 16)
    words = [bytes.fromhex(ip[i:i + 8])[::-1].hex() for i in range(0, 32, 8)]
    if words[:2] == ["00000000", "00000000"] and words[2] == "0000ffff":
        return ".".join(str(x) for x in bytes.fromhex(words[3])), int(port, 16)
    return ":".join(words), int(port, 16)


def sockets(text, ports):
    """/proc/net/tcp{,6} text -> the sockets with a local or remote port in `ports`:
    [local, remote, state, tx_queue, rx_queue, uid]."""
    out = []
    for line in text.splitlines():
        f = line.split()
        if len(f) < 8 or ":" not in f[1] or f[0] == "sl":
            continue
        (lip, lport), (rip, rport) = address(f[1]), address(f[2])
        if lport not in ports and rport not in ports:
            continue
        tx, rx = (int(x, 16) for x in f[4].split(":"))
        out.append([f"{lip}:{lport}", f"{rip}:{rport}", STATES.get(f[3], f[3]), tx, rx, int(f[7])])
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("-s", "--serial", default=os.environ.get("ANDROID_SERIAL"))
    ap.add_argument("--out", required=True)
    ap.add_argument("--port", type=int, default=8089, help="the fake's port; the relay's is this + 1")
    ap.add_argument("--seconds", type=float, default=900)
    ap.add_argument("--cpu-every", type=float, default=3)
    ap.add_argument("--package", default="sh.edm.s1kiosk")
    ap.add_argument("--hz", type=int, default=100, help="CLK_TCK on the device")
    ap.add_argument("--logcat", action="store_true", help="also save the whole logcat (read-only) to DIR/logcat.txt")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    ports = {a.port, a.port + 1}
    logcat = None
    if a.logcat:
        args = [T.ADB] + (["-s", a.serial] if a.serial else []) + ["logcat", "-v", "threadtime", "-T", "1"]
        logcat = subprocess.Popen(args, stdout=open(os.path.join(a.out, "logcat.txt"), "w"), stderr=subprocess.DEVNULL)
    end = time.time() + a.seconds
    out = open(os.path.join(a.out, "transport.jsonl"), "a")
    lock = threading.Lock()

    def write(obj):
        with lock:
            out.write(json.dumps(obj) + "\n")
            out.flush()

    def cpu():
        """Each thread's share of a core over each --cpu-every window, in its own thread: one sample
        of every kiosk thread takes a few seconds over adb, longer than the sockets' 1 s cadence."""
        before = None
        while time.time() < end:
            t0 = time.time()
            try:
                procs = T.pids(a.serial, a.package, ["adbd"])
                now = T.sample(a.serial, procs)
                if before and before[0] == procs and now[0] > before[1][0]:
                    window = now[0] - before[1][0]
                    totals, rows = T.shares(before[1], now, procs, a.hz, window)
                    rows = sorted(rows, key=lambda r: -r[3])[:15]
                    write({"t": round(time.time(), 3), "cpu_window_s": round(window, 2),
                           "processes": {k: round(v, 1) for k, v in totals.items()},
                           "threads": [[p, tid, n, round(pct, 1)] for p, tid, n, pct in rows]})
                before = (procs, now)
            except subprocess.CalledProcessError as e:
                write({"t": round(time.time(), 3), "cpu_error": str(e)})
                before = None
            time.sleep(max(0.0, a.cpu_every - (time.time() - t0)))

    threading.Thread(target=cpu, daemon=True).start()
    try:
        while time.time() < end:
            t = time.time()
            try:
                write({"t": round(t, 3), "tcp": sockets(T.adb(a.serial, "cat /proc/net/tcp /proc/net/tcp6"), ports)})
            except subprocess.CalledProcessError as e:
                write({"t": round(t, 3), "error": str(e)})
            time.sleep(max(0.0, 1.0 - (time.time() - t)))
    except KeyboardInterrupt:
        pass
    finally:
        if logcat:
            logcat.terminate()


if __name__ == "__main__":
    main()
