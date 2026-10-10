#!/usr/bin/env bash
# Runs a judge.sh (path in $1) on hand written runs of one client launch and prints each case's exit
# code and the judge's own lines. RUN-073 group F, docs/TEST.md TC-PROD-730. Every case is one PR run
# (LAUNCHES=1) of the full online tier: report.txt with R1 to R8 PASS, the game mode server's ledger
# with client_verified for its 3 connections (R1 to R8 one, rts G1 and rts G10 two), one protocol-error
# kick, and a sessionserver log with a join and a hasJoined 200 for each connection of both servers.
#   bash judge-cases.sh client-fabric/gametest-env/judge.sh
# Writes only under a temporary directory it removes again.
set -uo pipefail
JUDGE=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
WORK=$(mktemp -d "${TMPDIR:-/tmp}/judge-cases.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

steps() { # every step PASS
  for step in "core G1" "core G2" "core G3" "core G4" "core G5" "core G6" "core G7" "core G8" "core G9" "core G10" \
              "rts G1" "rts G2" "rts G6" "rts G7" "rts G10"; do
    echo "$step PASS evidence"
  done
}

# case <name> <sessionserver connections> [<step>=<FAIL|SKIP>|no-playable]...
case_() {
  local name=$1 logins=$2; shift 2
  local dir="$WORK/$name"
  local playable="$dir/run/synaxis-gametest/playable.txt"
  mkdir -p "$dir/run/synaxis-gametest" "$dir/logs"
  steps > "$playable"
  for arg in "$@"; do
    if [[ "$arg" == no-playable ]]; then rm "$playable"
    else sed -i.bak "s/^${arg%=*} PASS /${arg%=*} ${arg#*=} /" "$playable" && rm "$playable.bak"; fi
  done
  for n in 1 2 3 4 5 6 7 8; do echo "R$n PASS evidence"; done > "$dir/run/synaxis-gametest/report.txt"
  for _ in 1 2 3; do echo '{"event":"client_verified"}'; done > "$dir/logs/events-20261011.jsonl"
  echo '{"event":"unit_order_issued"}' >> "$dir/logs/events-20261011.jsonl"
  echo 'Kicking connection_id=1 reason=synaxis:protocol-error' > "$dir/server.log"
  : > "$dir/sessionserver.log"
  for _ in $(seq 1 "$logins"); do
    echo '1 POST /session/minecraft/join -> 204' >> "$dir/sessionserver.log"
    echo '1 GET /session/minecraft/hasJoined?username=x -> 200' >> "$dir/sessionserver.log"
  done
  local out code
  out=$(LAUNCHES=1 CONNECTIONS=3 CORE_CONNECTIONS=2 SERVER_LOG="$dir/server.log" LEDGER_DIR="$dir/logs" \
    SESSION_LOG="$dir/sessionserver.log" "$JUDGE" "$dir/run/synaxis-gametest/report.txt" 2>&1)
  code=$?
  echo "== case $name exit=$code"
  echo "$out" | sed "s|$WORK/||g"
}

case_ all-pass 5
case_ core-step-fails 5 "core G4=FAIL"
case_ rts-step-skipped 5 "rts G10=SKIP"
case_ no-playable-report 5 no-playable
case_ core-logins-missing 3
