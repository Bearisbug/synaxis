#!/usr/bin/env bash
# The judge parts of the older client gametest cases, on hand written stand-ins for the outputs of
# the container runs they name (TC-PROD-040 and TC-PROD-130 start a real client in Docker, which this
# machine does not): TC-PROD-041 steps 2 to 4, TC-PROD-042 steps 4 and 5, TC-PROD-130 steps 3 to 6
# (the judge.sh half of each) and TC-PROD-131 steps 4 and 5. The stand-in reports carry the lines
# those cases record; the ledgers, server logs and fake sessionserver logs carry the lines judge.sh
# counts, in the formats the server-app and tools/fake-sessionserver write.
#
#   PY=.venv/bin/python bash old-cases.sh <client-gametest.yml> <judge.sh> playable|none
#
# playable  every CI layout report has a playable.txt of 15 PASS lines beside it and the CI layout's
#           sessionserver.log has the core server's 4 connections after the 2 of the TC-PROD-130 round
#           (TC-PROD-042 and TC-PROD-131 as RUN-073 group F rewrote them)
# none      neither (the two cases as they were written before)
# The steps are extracted from the workflow as they stand. Everything happens in a mktemp -d
# directory that is removed at the end.
set -uo pipefail
workflow=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
judge=$(cd "$(dirname "$2")" && pwd)/$(basename "$2")
form=$3
: "${PY:?the python with PyYAML}"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cd "$work" || exit 2

"$PY" - "$workflow" <<'EOF'
import sys, yaml
steps = yaml.safe_load(open(sys.argv[1]))["jobs"]["gametest"]["steps"]
judge = next(s for s in steps if s.get("name") == "Judge the regression by the reports, the ledger and the server log")
open("judge-step.sh", "w").write(judge["run"])
open("judge-env.sh", "w").write("".join(f"export {k}={v}\n" for k, v in judge.get("env", {}).items()))
EOF

ts=2026-10-11T00:00:00.000000000Z
mods='mods [io_github_bearisbug_synaxis_protocol dev, synaxis 0.1.0]'
r8='screen: Connection Lost. The server rejected a message from this connection as malformed.'
# report <file> <R3 evidence> <unit id> <moved> [R8 line]
report() {
  mkdir -p "$(dirname "$1")"
  printf '%s\n' "R1 PASS $mods" "R2 PASS state=VERIFIED schema_version=1" "R3 PASS $2" \
    "R4 PASS bound=66 commandable=64 unmatched_state_entries=0" "R5 PASS unit_id=$3 selected=$3" \
    "R6 PASS unit_id=$3 answers [OK] target 263.50,40.00,255.50 moved $4 blocks (need 2.0)" \
    "R7 PASS surface BlockPos{x=256, y=39, z=256} sky_above=15 sky_below=0" "${5:-R8 PASS $r8}" > "$1"
}
playable() {
  for step in "core G1" "core G2" "core G3" "core G4" "core G5" "core G6" "core G7" "core G8" "core G9" "core G10" \
    "rts G1" "rts G2" "rts G6" "rts G7" "rts G10"; do echo "$step PASS evidence"; done > "$1"
}
# ledger <dir> <client_verified> <unit_order_issued>
ledger() {
  mkdir -p "$1"; : > "$1/events-20261011.jsonl"
  for ((i = 0; i < $2; i++)); do echo "{\"ts\":\"$ts\",\"event\":\"client_verified\",\"connection_id\":\"c$i\",\"mod_version\":1,\"schema_version\":1}"; done >> "$1/events-20261011.jsonl"
  for ((i = 0; i < $3; i++)); do echo "{\"ts\":\"$ts\",\"event\":\"unit_order_issued\",\"connection_id\":\"c$i\",\"order_seq\":1,\"unit_id\":1}"; done >> "$1/events-20261011.jsonl"
}
# kicks <file> <n>
kicks() {
  : > "$1"
  for ((i = 0; i < $2; i++)); do echo "06:28:34.151 [Syn-Tick-0] INFO  i.g.b.s.n.player.PlayerConnection - Kicking connection_id=c$i identifier=Player$i reason=synaxis:protocol-error"; done >> "$1"
}
# fake <file> <pairs> [first request number]
fake() {
  [[ ${3:-1} -eq 1 ]] && echo "fake sessionserver mode=record listening on http://0.0.0.0:41234, hasJoined at /session/minecraft/hasJoined" > "$1"
  local n=${3:-1}
  for ((i = 0; i < $2; i++)); do
    echo "$n POST /session/minecraft/join -> 204"; echo "$((n + 1)) GET /session/minecraft/hasJoined?username=Player$i&serverId=id$n -> 200"; n=$((n + 2))
  done >> "$1"
}
run() { echo "\$ $*"; "$@"; echo "exit=$?"; echo; }

# TC-PROD-040's three reports, its server log and ledger; TC-PROD-041 step 1's report
report out1/report.txt "in world at BlockPos{x=256, y=40, z=256}" 1 2.27
report out2/report.txt "in world at BlockPos{x=256, y=40, z=256}" 2 2.10
report out3/report.txt "in world at BlockPos{x=256, y=40, z=256}" 3 2.20 "R8 FAIL screen: Connection Lost. synaxis:protocol-error"
mkdir -p out4 empty
printf '%s\n' "R1 PASS $mods" "R2 FAIL state=IDLE" \
  "R3 FAIL not in world: Failed to connect to the server. finishConnect(..) failed with error(-111): Connection refused" \
  "R4 FAIL not reached: R3 failed" "R5 FAIL not reached: R3 failed" "R6 FAIL not reached: R3 failed" \
  "R7 FAIL not reached: R3 failed" "R8 FAIL not reached: R3 failed" > out4/report.txt

echo "=== TC-PROD-041 step 2"
LAUNCHES=1 SERVER_LOG=/dev/null LEDGER_DIR=empty run bash "$judge" out4/report.txt
echo "=== TC-PROD-041 step 3"
LAUNCHES=1 SERVER_LOG=/dev/null LEDGER_DIR=empty run bash "$judge" out1/report.txt
echo "=== TC-PROD-041 step 4"
LAUNCHES=2 SERVER_LOG=/dev/null LEDGER_DIR=empty run bash "$judge" out1/report.txt missing/report.txt

# TC-PROD-042 steps 4 and 5: the judge step's run block in a CI layout
ci42() {
  rm -rf ci42 && mkdir -p ci42/client-fabric/gametest-env && cp "$judge" ci42/client-fabric/gametest-env/judge.sh && chmod +x ci42/client-fabric/gametest-env/judge.sh
  for launch in clientGameTest:out1 productionClientGameTest:out2; do
    dir=ci42/client-fabric/build/run/${launch%%:*}/synaxis-gametest
    mkdir -p "$dir" && cp "${launch##*:}/report.txt" "$dir/"
    [[ $form == playable ]] && playable "$dir/playable.txt"
  done
  cp judge-step.sh ci42/step.sh
}
ci42; kicks ci42/server.log 2; ledger ci42/logs 2 2
echo "=== TC-PROD-042 step 4 (two launches)"
(cd ci42 && LAUNCHES=2 CONNECTIONS_PER_LAUNCH=1 SERVER_LOG=server.log LEDGER_DIR=logs run bash step.sh)
echo "=== TC-PROD-042 step 4 (one launch: the dev report gone, the ledger and log of one launch)"
rm ci42/client-fabric/build/run/clientGameTest/synaxis-gametest/report.txt; kicks ci42/server.log 1; ledger ci42/logs 1 1
(cd ci42 && LAUNCHES=1 CONNECTIONS_PER_LAUNCH=1 SERVER_LOG=server.log LEDGER_DIR=logs run bash step.sh)
echo "=== TC-PROD-042 step 5 (one launch, the production report has R8 FAIL)"
cp out3/report.txt ci42/client-fabric/build/run/productionClientGameTest/synaxis-gametest/report.txt
(cd ci42 && LAUNCHES=1 CONNECTIONS_PER_LAUNCH=1 SERVER_LOG=server.log LEDGER_DIR=logs run bash step.sh)
echo "=== TC-PROD-042 step 5 (two launches, the dev launch left nothing)"
ci42; kicks ci42/server.log 2; ledger ci42/logs 2 2
rm -r ci42/client-fabric/build/run/clientGameTest/synaxis-gametest
(cd ci42 && LAUNCHES=2 CONNECTIONS_PER_LAUNCH=1 SERVER_LOG=server.log LEDGER_DIR=logs run bash step.sh)

# TC-PROD-130: the online tier round's reports, ledger, server log and fake sessionserver log
session="encrypted=true session_host=http://host.docker.internal:41234"
report srv/out1/report.txt "in world at BlockPos{x=256, y=40, z=256} $session" 1 2.10
report srv/out2/report.txt "in world at BlockPos{x=256, y=40, z=256} $session" 2 2.10
kicks srv/server.log 2; ledger srv/logs 2 2; fake srv/fake.log 2
echo "=== TC-PROD-130 step 3"
(cd srv && LAUNCHES=2 SERVER_LOG=server.log LEDGER_DIR=logs SESSION_LOG=fake.log run bash "$judge" out1/report.txt out2/report.txt)
report off/out3/report.txt "in world at BlockPos{x=256, y=40, z=256} encrypted=false session_host=http://host.docker.internal:41234" 1 2.10
sed -i.bak 's/^R3 PASS/R3 FAIL/' off/out3/report.txt && rm off/out3/report.txt.bak
kicks off/server.log 1; ledger off/logs 1 1; fake off/fake.log 0
echo "=== TC-PROD-130 step 4 (offline server: R3 FAIL encrypted=false, no join)"
(cd off && LAUNCHES=1 SERVER_LOG=server.log LEDGER_DIR=logs SESSION_LOG=fake.log run bash "$judge" out3/report.txt)
report offenc/out4/report.txt "in world at BlockPos{x=256, y=40, z=256} $session" 1 2.10
kicks offenc/server.log 1; ledger offenc/logs 1 1; fake offenc/fake.log 0
echo "=== TC-PROD-130 step 5 (offline-encrypted server: 8 PASS, no join)"
(cd offenc && LAUNCHES=1 SERVER_LOG=server.log LEDGER_DIR=logs SESSION_LOG=fake.log run bash "$judge" out4/report.txt)
report spike/out5/report.txt "in world at BlockPos{x=256, y=40, z=256} $session" 1 2.10
printf '%s\n' "S0 PASS a" "S0 PASS b" "S0 PASS c" "S1 in world at BlockPos{x=256, y=40, z=256}" "S2 PASS a" "S2 PASS b" >> spike/out5/report.txt
kicks spike/server.log 1; ledger spike/logs 2 1; fake spike/fake.log 2
echo "=== TC-PROD-130 step 6 (spikes: one launch, two connections)"
(cd spike && LAUNCHES=1 CONNECTIONS=2 SERVER_LOG=server.log LEDGER_DIR=logs SESSION_LOG=fake.log run bash "$judge" out5/report.txt)

# TC-PROD-131 steps 4 and 5: the judge step's env and run block in a CI layout with that round's files
rm -rf ci131 && mkdir -p ci131/client-fabric/gametest-env && cp "$judge" ci131/client-fabric/gametest-env/judge.sh && chmod +x ci131/client-fabric/gametest-env/judge.sh
for launch in clientGameTest:out1 productionClientGameTest:out2; do
  dir=ci131/client-fabric/build/run/${launch%%:*}/synaxis-gametest
  mkdir -p "$dir" && cp "srv/${launch##*:}/report.txt" "$dir/"
  [[ $form == playable ]] && playable "$dir/playable.txt"
done
cp srv/server.log ci131/ && cp -R srv/logs ci131/ && cp srv/fake.log ci131/sessionserver.log
[[ $form == playable ]] && fake ci131/sessionserver.log 4 5
cp judge-env.sh judge-step.sh ci131/
echo "=== TC-PROD-131 step 4"
echo "sessionserver.log: $(grep -c ' -> 204$' ci131/sessionserver.log) joins, $(grep -c 'hasJoined.* -> 200$' ci131/sessionserver.log) hasJoined 200"
(cd ci131 && source judge-env.sh && LAUNCHES=2 CONNECTIONS_PER_LAUNCH=1 run bash judge-step.sh)
echo "=== TC-PROD-131 step 5 (the sessionserver log of the offline-encrypted round)"
cp offenc/fake.log ci131/sessionserver.log
(cd ci131 && source judge-env.sh && LAUNCHES=2 CONNECTIONS_PER_LAUNCH=1 run bash judge-step.sh)
echo "=== TC-PROD-131 step 5 (no sessionserver log)"
rm ci131/sessionserver.log
(cd ci131 && source judge-env.sh && LAUNCHES=2 CONNECTIONS_PER_LAUNCH=1 run bash judge-step.sh)
