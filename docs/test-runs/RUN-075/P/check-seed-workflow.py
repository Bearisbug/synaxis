"""Reads a client-gametest.yml (path in argv[1]) and prints one line per property of the seeded character in the
jobs p10 and p11-smoke, a missing one starting with MISSING; exits 1 when one is missing. Read only; RUN-075 group P,
docs/TEST.md TC-PROD-755."""
import sys

import yaml

jobs = yaml.safe_load(open(sys.argv[1], encoding="utf-8"))["jobs"]
missing = 0


def say(ok, good, bad):
    global missing
    print(good if ok else "MISSING " + bad)
    missing += not ok


def first(steps, needle):
    return next((i for i, s in enumerate(steps) if needle in (s.get("run") or "")), None)


for name in ("p10", "p11-smoke"):
    job = jobs[name]
    steps = job["steps"]
    username = job.get("env", {}).get("SYNAXIS_GAMETEST_USERNAME")
    say(username, f"{name} plays as {username}", f"SYNAXIS_GAMETEST_USERNAME in the env of {name}")
    seed = first(steps, "p10.py seed")
    run = steps[seed]["run"] if seed is not None else ""
    say(seed is not None and '--name "$SYNAXIS_GAMETEST_USERNAME"' in run
        and '--manifest "$P10/maps/p10/$P10_BUILD/manifest.json"' in run and '--out "$P10/seed.json"' in run,
        f"{name} seeds the character of that name from the baked manifest into $P10/seed.json",
        f"p10.py seed --name, --manifest and --out in {name}")
    migrate, client = first(steps, "--migrate-only"), first(steps, "runProductionClientGameTest")
    say(seed is not None and migrate is not None and client is not None and migrate < seed < client,
        f"{name} seeds after the migration and before the client starts",
        f"seed between the migration and the client in {name}, steps {migrate}, {seed}, {client}")
    judge = first(steps, "p10.py judge")
    say(judge is not None and '--seed "$P10/seed.json"' in steps[judge]["run"], f"{name} judges against $P10/seed.json",
        f'--seed "$P10/seed.json" on {name} p10.py judge')
    upload = next((s for s in steps if s.get("uses", "").startswith("actions/upload-artifact")), {})
    say("p10/seed.json" in upload.get("with", {}).get("path", ""), f"{name} uploads p10/seed.json",
        f"p10/seed.json in the artifact of {name}")
say("SYNAXIS_GAMETEST_USERNAME" not in str(jobs["gametest"]), "gametest leaves the production launch its own name",
    "no SYNAXIS_GAMETEST_USERNAME in gametest")
sys.exit(1 if missing else 0)
