#!/usr/bin/env bash
# Runs a judge.sh (path in $1) on hand written two-launch runs laid out as on CI (push to main: LAUNCHES=2,
# CONNECTIONS=6, CORE_CONNECTIONS=4) and prints each case's exit code and the judge's own lines.
# RUN-075 group R, docs/TEST.md TC-PROD-758. A launch that passes leaves report.txt with R1 to R8 PASS and
# playable.txt with its 15 steps PASS, 3 client_verified and 1 unit_order_issued in the game mode server's
# ledger, 1 protocol-error kick in its log, and 5 joins with 5 hasJoined 200 in the sessionserver log (3 to
# the game mode server, 2 to the flat core one). The cases differ in the production launch only:
#   all-pass          it passes too
#   r8-threw          R8 FAIL and no playable.txt, nor the script's 2 connections to each server: what the
#                     test that threw on a FAIL line left (run 38118309531, attempt 1)
#   r8-reported       R8 FAIL, the playable script ran all the same: what the test that only reports leaves
#   step-threw        R5 threw, R5 to R8 "FAIL not reached: the test threw ...", no order and no kick
#   no-report         no synaxis-gametest/ at all, the server side as if it had passed
#   bash judge-cases.sh client-fabric/gametest-env/judge.sh
# Writes only under a temporary directory it removes again.
set -uo pipefail
JUDGE=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
WORK=$(mktemp -d "${TMPDIR:-/tmp}/judge-cases.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
RUNS=client-fabric/build/run

report() { for n in 1 2 3 4 5 6 7 8; do echo "R$n PASS evidence"; done; }
playable() {
  for step in "core G1" "core G2" "core G3" "core G4" "core G5" "core G6" "core G7" "core G8" "core G9" "core G10" \
              "rts G1" "rts G2" "rts G6" "rts G7" "rts G10"; do
    echo "$step PASS evidence"
  done
}
# launch <case dir> <run dir>: one launch that passed
launch() { mkdir -p "$1/$RUNS/$2/synaxis-gametest"; report > "$1/$RUNS/$2/synaxis-gametest/report.txt"; playable > "$1/$RUNS/$2/synaxis-gametest/playable.txt"; }
# server <case dir> <client_verified> <unit_order_issued> <kicks> <sessionserver logins>
server() {
  mkdir -p "$1/logs"; : > "$1/logs/events-20261011.jsonl"; : > "$1/server.log"; : > "$1/sessionserver.log"
  for _ in $(seq 1 "$2"); do echo '{"event":"client_verified"}' >> "$1/logs/events-20261011.jsonl"; done
  for _ in $(seq 1 "$3"); do echo '{"event":"unit_order_issued"}' >> "$1/logs/events-20261011.jsonl"; done
  for n in $(seq 1 "$4"); do echo "Kicking connection_id=$n reason=synaxis:protocol-error" >> "$1/server.log"; done
  for _ in $(seq 1 "$5"); do
    echo '1 POST /session/minecraft/join -> 204' >> "$1/sessionserver.log"
    echo '1 GET /session/minecraft/hasJoined?username=x -> 200' >> "$1/sessionserver.log"
  done
}
judge() {
  local out code
  out=$(cd "$WORK/$1" && LAUNCHES=2 CONNECTIONS=6 CORE_CONNECTIONS=4 SERVER_LOG=server.log LEDGER_DIR=logs \
    SESSION_LOG=sessionserver.log "$JUDGE" "$RUNS/productionClientGameTest/synaxis-gametest/report.txt" \
    "$RUNS/clientGameTest/synaxis-gametest/report.txt" 2>&1)
  code=$?
  echo "== case $1 exit=$code"
  echo "$out"
}

c=$WORK/all-pass; launch "$c" clientGameTest; launch "$c" productionClientGameTest; server "$c" 6 2 2 10; judge all-pass

c=$WORK/r8-threw; launch "$c" clientGameTest; launch "$c" productionClientGameTest
sed -i.bak 's/^R8 PASS evidence$/R8 FAIL screen: Connection Lost. Disconnected/' "$c/$RUNS/productionClientGameTest/synaxis-gametest/report.txt"
rm "$c/$RUNS/productionClientGameTest/synaxis-gametest/report.txt.bak" "$c/$RUNS/productionClientGameTest/synaxis-gametest/playable.txt"
server "$c" 4 2 2 6; judge r8-threw

c=$WORK/r8-reported; launch "$c" clientGameTest; launch "$c" productionClientGameTest
sed -i.bak 's/^R8 PASS evidence$/R8 FAIL screen: Connection Lost. Disconnected/' "$c/$RUNS/productionClientGameTest/synaxis-gametest/report.txt"
rm "$c/$RUNS/productionClientGameTest/synaxis-gametest/report.txt.bak"
server "$c" 6 2 2 10; judge r8-reported

c=$WORK/step-threw; launch "$c" clientGameTest; launch "$c" productionClientGameTest
{ for n in 1 2 3 4; do echo "R$n PASS evidence"; done
  for n in 5 6 7 8; do echo "R$n FAIL not reached: the test threw java.lang.IllegalStateException: SynaxisClientMod.RTS is not readable; the regression reads it by reflection"; done
} > "$c/$RUNS/productionClientGameTest/synaxis-gametest/report.txt"
server "$c" 6 1 1 10; judge step-threw

c=$WORK/no-report; launch "$c" clientGameTest; server "$c" 6 2 2 10; judge no-report
