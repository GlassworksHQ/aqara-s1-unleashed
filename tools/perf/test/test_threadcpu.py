"""threadcpu.py: /proc/<pid>/task/<tid>/stat lines copied from the S1 (Android 10), made-up jiffies."""
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import threadcpu as T  # noqa: E402

MIC = "6769 (voice-mic) S 32616 1359 796 0 -1 1077936192 1024 0 0 0 35000 120 0 0 1 -19 91 0 13399"
TAB = "6947 (TaskController ) S 6706 1359 796 0 -1 1077936192 10 0 0 0 7 3 0 0 20 0 91 0 13500"
ODD = "7001 (a) b (c) R 6443 1359 796 0 -1 0 0 0 0 0 1 2 0 0 20 0 1 0 1"


class ParseStat(unittest.TestCase):
    def test_reads_utime_plus_stime(self):
        self.assertEqual(T.parse_stat(MIC), (6769, "voice-mic", 35120))

    def test_keeps_spaces_and_parentheses_in_thread_names(self):
        self.assertEqual(T.parse_stat(TAB), (6947, "TaskController ", 10))
        self.assertEqual(T.parse_stat(ODD), (7001, "a) b (c", 3))


class Shares(unittest.TestCase):
    def test_percent_of_one_core_per_thread(self):
        procs = {6443: "sh.edm.s1kiosk"}
        owner = {6769: 6443, 6800: 6443}
        before = {6769: ("voice-mic", 1000), 6800: ("gone", 5)}
        after = {6769: ("voice-mic", 1000 + 2700), 6900: ("new", 50)}
        rows = T.shares(before, after, owner, procs, hz=100, window=60.0)
        self.assertEqual(rows, [("sh.edm.s1kiosk", 6769, "voice-mic", 45.0)])


if __name__ == "__main__":
    unittest.main()
