"""python3 -m unittest discover -s tools/perf/test

lanprobe.py (#202, option B): the panel's two checks through a fake adb that really sends the token
to this computer (test/fake_adb.py, FAKE_LAN), and the choice: lan-udp, else lan-turn, else adb."""
import json
import os
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import lanprobe  # noqa: E402
import webrtc  # noqa: E402


class Choose(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        adb = os.path.join(self.tmp, "adb")
        with open(adb, "w") as f:
            f.write(f'#!/bin/sh\nexec "{sys.executable}" "{os.path.join(HERE, "fake_adb.py")}" "$@"\n')
        os.chmod(adb, 0o755)
        os.makedirs(os.path.join(self.tmp, "state"))
        p = mock.patch.dict(os.environ, {"FAKE_ADB_STATE": os.path.join(self.tmp, "state")})
        p.start()
        self.addCleanup(p.stop)
        a = mock.patch.object(lanprobe, "ADB", adb)
        a.start()
        self.addCleanup(a.stop)
        # The media address the checks go to: loopback here (no LAN in CI), as GW_PERF_WEBRTC_ADDR allows.
        m = mock.patch.object(webrtc, "media_address", lambda: "127.0.0.1")
        m.start()
        self.addCleanup(m.stop)

    def port(self):
        import socket
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            return s.getsockname()[1]

    def checks(self):
        with open(os.path.join(self.tmp, "state", "lan_checks.json")) as f:
            return json.load(f)

    def test_udp_reached_is_lan_udp(self):
        with mock.patch.dict(os.environ, {"FAKE_LAN": "udp"}):
            got = lanprobe.choose(self.port())
        self.assertEqual((got["transport"], got["chosen_by"]), ("lan-udp", "auto"))
        self.assertEqual(got["checks"], {"udp": {"reached": True, "nc_status": 0}})
        self.assertEqual(self.checks(), ["udp"])                  # no TCP check when UDP worked

    def test_only_tcp_reached_is_lan_turn(self):
        with mock.patch.dict(os.environ, {"FAKE_LAN": "tcp"}):
            got = lanprobe.choose(self.port())
        self.assertEqual(got["transport"], "lan-turn")
        self.assertEqual((got["checks"]["udp"]["reached"], got["checks"]["tcp"]["reached"]), (False, True))
        self.assertEqual(self.checks(), ["udp", "tcp"])

    def test_nothing_reached_falls_back_to_adb(self):
        with mock.patch.dict(os.environ, {"FAKE_LAN": ""}):
            got = lanprobe.choose(self.port())
        self.assertEqual(got["transport"], "adb")
        self.assertIn("couldn't reach this computer over the LAN", got["reason"])

    def test_no_lan_address_here_falls_back_to_adb_without_asking_the_panel(self):
        def none():
            raise RuntimeError("no IPv4 address")
        with mock.patch.object(webrtc, "media_address", none):
            got = lanprobe.choose(self.port())
        self.assertEqual(got["transport"], "adb")
        self.assertFalse(os.path.exists(os.path.join(self.tmp, "state", "lan_checks.json")))

    def test_the_flag_overrides_and_asks_nothing(self):
        got = lanprobe.choose(self.port(), "adb")
        self.assertEqual((got["transport"], got["chosen_by"]), ("adb", "flag"))
        self.assertFalse(os.path.exists(os.path.join(self.tmp, "state", "lan_checks.json")))

    def test_the_cli_writes_transport_json_without_addresses(self):
        env = dict(os.environ, ADB=lanprobe.ADB, FAKE_LAN="udp", GW_PERF_WEBRTC_ADDR="127.0.0.1")
        out = tempfile.mkdtemp()
        p = subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "lanprobe.py"), "--port", str(self.port()),
                            "--out", out], env=env, capture_output=True, text=True, timeout=60)
        self.assertEqual(p.stdout.strip(), "lan-udp", p.stderr)
        with open(os.path.join(out, "transport.json")) as f:
            text = f.read()
        self.assertEqual(json.loads(text)["transport"], "lan-udp")
        self.assertNotIn("127.0.0.1", text)


if __name__ == "__main__":
    unittest.main()
