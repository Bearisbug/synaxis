#!/usr/bin/env python3
"""Unit tests of p10.py, standard library only (docs/TEST.md TC-PROD-660):

    python3 -m unittest discover -s client-fabric/gametest-env -p 'test_*.py'
"""

from __future__ import annotations

import json
import os
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

P10 = Path(__file__).with_name("p10.py")


class ShardsTest(unittest.TestCase):

    def test_the_shard_directory_is_0644_whatever_the_umask(self) -> None:
        # A map shard refuses a shards.json group or others may write (docs/DESIGN-M3.md API-M3-002),
        # and a CI runner's umask may be 002 as well as 022
        for umask in (0o002, 0o022, 0o077):
            with self.subTest(umask=oct(umask)), tempfile.TemporaryDirectory() as directory:
                out = Path(directory) / "shards.json"
                subprocess.run([sys.executable, str(P10), "shards", "--host", "127.0.0.1",
                                "--ports", "25611,25612", "--out", str(out)],
                               check=True, preexec_fn=lambda u=umask: os.umask(u))
                self.assertEqual(oct(stat.S_IMODE(out.stat().st_mode)), oct(0o644))
                channels = json.loads(out.read_text(encoding="utf-8"))["channels"]
                self.assertEqual([(c["shard_id"], c["port"]) for c in channels],
                                 [("r0-c1", 25611), ("r0-c2", 25612)])


if __name__ == "__main__":
    unittest.main()
