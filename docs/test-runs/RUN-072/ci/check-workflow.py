"""Reads a client-gametest.yml (path in argv[1]) and prints, per job, whether the fake sessionserver step creates its log
before it starts the server in the background, and whether the p10 job runs p10.py's unit tests before its first use of p10.py.
Read only; RUN-072 group ci, docs/TEST.md TC-PROD-688."""
import sys

import yaml

d = yaml.safe_load(open(sys.argv[1]))
for job in ("gametest", "p10"):
    steps = d["jobs"][job]["steps"]
    run = next(s["run"] for s in steps if s.get("name") == "Start the fake sessionserver in record mode").splitlines()
    start = next(i for i, line in enumerate(run) if "FakeSessionServer.java" in line)
    print(job, "log created before start" if ": > sessionserver.log" in run[:start] else "MISSING log creation before start")
steps = d["jobs"]["p10"]["steps"]
tests = [i for i, s in enumerate(steps) if "unittest discover -s client-fabric/gametest-env" in (s.get("run") or "")]
first = next(i for i, s in enumerate(steps) if "gametest-env/p10.py" in (s.get("run") or ""))
print("p10 unit tests before first p10.py use" if tests and tests[0] < first else "MISSING p10 unit tests before first p10.py use")
