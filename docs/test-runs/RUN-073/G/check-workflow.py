"""Reads a client-gametest.yml (path in argv[1]) and prints one line per property of the job p11-smoke (playable item
P11) and of the weekly job p10 it is copied from. Read only; RUN-073 group G, docs/TEST.md TC-PROD-741."""
import sys

import yaml

d = yaml.safe_load(open(sys.argv[1], encoding="utf-8"))
# PyYAML reads the key on as the boolean True
on = d.get("on", d.get(True))
jobs = d["jobs"]


def say(ok, good, bad):
    print(good if ok else "MISSING " + bad)


def runs(job, needle):
    return [s.get("run") or "" for s in jobs[job]["steps"] if needle in (s.get("run") or "")]


if "p11-smoke" not in jobs:
    print("MISSING job p11-smoke")
    sys.exit(1)
smoke, weekly = jobs["p11-smoke"], jobs["p10"]
say(on["push"]["branches"] == ["main"] and "pull_request" in on, "workflow triggers include push main and pull_request",
    "push main or pull_request trigger")
say(smoke["if"] == "github.event_name == 'pull_request' || github.event_name == 'push'",
    "p11-smoke runs on pull_request and push only", "p11-smoke condition pull_request || push, has " + repr(smoke.get("if")))
say(smoke["runs-on"] == "ubuntu-24.04" and weekly["runs-on"] == "ubuntu-24.04", "p11-smoke and p10 run on ubuntu-24.04",
    f"ubuntu-24.04, has {smoke['runs-on']} and {weekly['runs-on']}")
say("needs" not in smoke, "p11-smoke needs no other job", "independence, needs " + repr(smoke.get("needs")))
for command in ("p10.py drive", "p10.py judge"):
    say([r for r in runs("p11-smoke", command) if "--round-trips 2 " in r], f"p11-smoke {command} --round-trips 2",
        f"--round-trips 2 on p11-smoke {command}")
    say(runs("p10", command) and not [r for r in runs("p10", command) if "--round-trips" in r],
        f"p10 {command} keeps the default 100", f"default round trips on p10 {command}")
steps = smoke["steps"]
tests = [i for i, s in enumerate(steps) if "unittest discover -s client-fabric/gametest-env" in (s.get("run") or "")]
first = next(i for i, s in enumerate(steps) if "gametest-env/p10.py" in (s.get("run") or ""))
say(tests and tests[0] < first, "p11-smoke unit tests before first p10.py use", "p11-smoke unit tests before first p10.py use")
# The same fixture: every step as in p10 but for the round trips, the names and timeouts that say so and the artifact name
different = []
for a, b in zip(weekly["steps"], steps):
    name = b.get("name")
    a = {k: v for k, v in a.items() if k not in ("name", "timeout-minutes")}
    b = {k: v for k, v in b.items() if k not in ("name", "timeout-minutes")}
    if "run" in b:
        b["run"] = b["run"].replace("--round-trips 2 ", "")
    if b.get("with", {}).get("name") == "client-gametest-p11-smoke":
        b["with"] = dict(b["with"], name="client-gametest-p10")
    if a != b:
        different.append(name)
say(len(weekly["steps"]) == len(steps) and not different and smoke["services"] == weekly["services"]
    and smoke["env"] == weekly["env"], f"p11-smoke repeats p10's {len(steps)} steps, services and env",
    f"p10's steps, services and env in p11-smoke: {len(weekly['steps'])} vs {len(steps)} steps, differing {different}")
say(weekly["if"] == "github.event.schedule == '0 19 * * 0' || (github.event_name == 'workflow_dispatch' && inputs.p10)",
    "p10 stays weekly and manual", "p10 weekly and manual condition")
