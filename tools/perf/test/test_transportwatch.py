"""python3 -m unittest discover -s tools/perf/test

transportwatch.py (#202): the panel's TCP sockets on the fake's ports, from /proc/net/tcp and tcp6,
with their queues. The lines are shaped as the S1 printed them (Android 10, 2026-11-13); the
addresses are loopback and RFC 5737."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import transportwatch as W  # noqa: E402

TCP = """  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 0100007F:1F91 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 35284 1 0000000000000000 99 0 0 10 0
   1: 0100007F:1F9A 0100007F:D2F0 01 00000000:00000000 00:00000000 00000000  2000        0 1 1 0000000000000000 20 4 30 10 -1
   2: 0100007F:D2F0 0100007F:1F9A 01 00000000:0003A980 00:00000000 00000000 10083        0 2 1 0000000000000000 20 4 30 10 -1
   3: 0702000C:B3E0 0100A8C0:01BB 01 00000000:00000000 00:00000000 00000000 10083        0 3 1 0000000000000000 20 4 30 10 -1
  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 0000000000000000FFFF00000100007F:1F99 0000000000000000FFFF00000100007F:D2F4 01 00012000:00000000 00:00000000 00000000  2000        0 4 1
"""


class Sockets(unittest.TestCase):
    def test_only_the_fakes_ports_with_their_queues(self):
        got = W.sockets(TCP, {8089, 8090})
        # adbd's end (uid 2000) and the kiosk's end (uid 10083) of one relay connection: 240 kB not read yet.
        self.assertIn(["127.0.0.1:8090", "127.0.0.1:54000", "ESTABLISHED", 0, 0, 2000], got)
        self.assertIn(["127.0.0.1:54000", "127.0.0.1:8090", "ESTABLISHED", 0, 240000, 10083], got)
        # IPv4 in tcp6 (the websocket's port), with bytes not yet sent.
        self.assertIn(["127.0.0.1:8089", "127.0.0.1:54004", "ESTABLISHED", 73728, 0, 2000], got)
        self.assertEqual(len(got), 3)                 # 8081 and the HTTPS connection aren't the fake's


if __name__ == "__main__":
    unittest.main()
