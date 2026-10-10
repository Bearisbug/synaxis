"""Reads a client-gametest.yml (path in argv[1]) and the gametest source set's fabric.mod.json and mixin list (paths in
argv[2] and argv[3]) and prints one line per fact the playable script of docs/DESIGN-PROD.md API-PROD-026 needs in job
gametest: "ok <fact>", or "MISSING <fact>". Read only; RUN-073 group F, docs/TEST.md TC-PROD-731."""
import json
import sys

import yaml


def say(ok, fact):
    print(("ok " if ok else "MISSING ") + fact)


d = yaml.safe_load(open(sys.argv[1]))
job = d["jobs"]["gametest"]
steps = job["steps"]
names = [s.get("name", "") for s in steps]


def index(name):
    return names.index(name) if name in names else -1


def run_of(name):
    i = index(name)
    return steps[i].get("run", "") if i >= 0 else ""


say(job.get("runs-on") == "ubuntu-24.04", "gametest runs on ubuntu-24.04")
say(d["jobs"]["p10"].get("runs-on") == "ubuntu-24.04", "p10 runs on ubuntu-24.04")
shown = [i for i, s in enumerate(steps) if "nproc" in (s.get("run") or "") and "free -m" in (s.get("run") or "")]
say(bool(shown), "a step prints nproc and free -m")
build = [i for i, s in enumerate(steps) if ":server-app:installDist" in (s.get("run") or "")]
game = index("Start server-app in game mode")
core = index("Start server-app in core mode")
say(bool(build) and 0 <= build[0] < game, "installDist is built before the game mode server starts")
core_run = run_of("Start server-app in core mode")
say(core > game >= 0, "the core mode server starts after the game mode server")
for fact, needle in [
    ("core server in its own working directory under RUNNER_TEMP", 'cd "$RUNNER_TEMP/playable"'),
    ("core server from the installDist start script", "server-app/build/install/server-app/bin/server-app"),
    ("core server with stdin from /dev/null", "< /dev/null > server.log"),
    ("core server JAVA_OPTS 1G heap without pretouch", 'JAVA_OPTS="-Xms1G -Xmx1G -XX:-AlwaysPreTouch"'),
    ("core server core-mode=on", "-Dsynaxis.app.core-mode=on"),
    ("core server on port 25566", "-Dsynaxis.app.port=25566"),
    ("core server metrics on any free port", "-Dsynaxis.app.metrics.bind=127.0.0.1:0"),
    ("core server online against the job's fake sessionserver", "-Dsynaxis.app.auth.session-server=$SESSION_HOST/session/minecraft/hasJoined"),
    ("core server waits for server-app listening", "server-app listening"),
]:
    say(needle in core_run, fact)
say(core >= 0 and "synaxis.app.store" not in core_run, "core server keeps the default in-memory store")
clients = [i for i, s in enumerate(steps) if "runClientGameTest" in (s.get("run") or "") or "runProductionClientGameTest" in (s.get("run") or "")]
say(core >= 0 and len(clients) == 2 and all(i > core for i in clients), "both client launches start after the core mode server")
say(all((steps[i].get("env") or {}).get("SYNAXIS_PLAYABLE_SERVER") == "127.0.0.1:25566" for i in clients) and len(clients) == 2,
    "both client launches set SYNAXIS_PLAYABLE_SERVER=127.0.0.1:25566")
cpl = job["env"].get("CONNECTIONS_PER_LAUNCH", "")
say("'4'" in cpl and "'3'" in cpl, "CONNECTIONS_PER_LAUNCH is 3, 4 with the spikes")
judge = run_of("Judge the regression by the reports, the ledger and the server log")
say("CORE_CONNECTIONS=$((LAUNCHES * 2))" in judge, "the judge step passes CORE_CONNECTIONS = 2 per launch")
upload = next((s.get("with", {}).get("path", "") for s in steps if s.get("uses", "").startswith("actions/upload-artifact")), "")
for fact, needle in [("playable.txt", "synaxis-gametest/playable.txt"), ("the core server's log", "${{ runner.temp }}/playable/server.log"),
                     ("the core server's ledger", "${{ runner.temp }}/playable/logs/")]:
    say(needle in upload, "the artifact takes " + fact)

entries = json.load(open(sys.argv[2]))["entrypoints"]["fabric-client-gametest"]
regression = "io.github.bearisbug.synaxis.game.client.gametest.RegressionClientGameTest"
playable = "io.github.bearisbug.synaxis.game.client.gametest.PlayableClientGameTest"
say(regression in entries and playable in entries and entries.index(playable) == entries.index(regression) + 1,
    "PlayableClientGameTest comes right after RegressionClientGameTest")
say("DisconnectReasonMixin" in json.load(open(sys.argv[3]))["client"], "DisconnectReasonMixin is registered")
