#!/usr/bin/env bash
# Judges one client gametest run by API-PROD-026 (REQ-PROD-030): the reports, the server's event
# ledger and its log decide, the client's exit codes do not — they are wrong both ways (0 when the
# client never got into the world, 134 from libopenal's segfault on the way out in the container).
#
#   LAUNCHES=2 CONNECTIONS=2 SERVER_LOG=server.log LEDGER_DIR=logs \
#     client-fabric/gametest-env/judge.sh <report.txt of each client launch>...
#
# LAUNCHES      client launches in this run (N); every one has to have left a report
# CONNECTIONS   connections those launches made to the server, N unless the spikes ran too (their S1
#               connects once more per launch)
# SERVER_LOG    the server-app's stdout
# LEDGER_DIR    where the server-app writes events-<shard_id>-YYYYMMDD.jsonl
# SESSION_LOG   the stdout of tools/fake-sessionserver in record mode, in the full online tier: every
#               connection has to have joined there and the server's hasJoined to have been answered
#               200, which record mode only does for a serverId a client joined. Unset against an
#               offline server
# CORE_CONNECTIONS  connections the playable script made to the flat core server (2 per launch), set
#               when that script ran: every report.txt then has a playable.txt beside it with the ten
#               core and five rts steps all PASS, and the sessionserver counts take these connections
#               in as well, the core server verifying against the same fake sessionserver. CONNECTIONS
#               counts the script's 2 connections per launch to the server of SERVER_LOG and LEDGER_DIR
set -uo pipefail
: "${LAUNCHES:?}" "${SERVER_LOG:?}" "${LEDGER_DIR:?}"
CONNECTIONS=${CONNECTIONS:-$LAUNCHES}
failures=0
fail() { echo "FAIL $*"; failures=$((failures + 1)); }
pass() { echo "ok   $*"; }
# expect <what was checked> <test(1) expression>
expect() { local what=$1; shift; if test "$@"; then pass "$what"; else fail "$what"; fi; }

reports=0
for report in "$@"; do
  if [[ ! -f "$report" ]]; then fail "no report at $report"; continue; fi
  reports=$((reports + 1))
  passed=$(grep -cE '^R[1-8] PASS' "$report")
  failed=$(grep -cE '^R[1-8] FAIL' "$report")
  if [[ "$passed" -eq 8 && "$failed" -eq 0 ]]; then pass "$report: 8 PASS"
  else fail "$report: $passed PASS, $failed FAIL"; grep -E '^R[1-8] FAIL' "$report" | sed 's/^/     /'; fi
done
expect "$reports reports for $LAUNCHES launches" "$reports" -eq "$LAUNCHES"

if [[ -n "${CORE_CONNECTIONS:-}" ]]; then
  playable_reports=0
  for report in "$@"; do
    playable="$(dirname "$report")/playable.txt"
    if [[ ! -f "$playable" ]]; then fail "no playable report at $playable"; continue; fi
    playable_reports=$((playable_reports + 1))
    core=$(grep -cE '^core G([1-9]|10) PASS' "$playable")
    rts=$(grep -cE '^rts G(1|2|6|7|10) PASS' "$playable")
    bad=$(grep -cE '^(core|rts) G[0-9]+ (FAIL|SKIP)' "$playable")
    if [[ "$core" -eq 10 && "$rts" -eq 5 && "$bad" -eq 0 ]]; then pass "$playable: core 10 PASS, rts 5 PASS"
    else fail "$playable: core $core PASS, rts $rts PASS, $bad FAIL or SKIP"; grep -E '^(core|rts) G[0-9]+ (FAIL|SKIP)' "$playable" | sed 's/^/     /'; fi
  done
  expect "$playable_reports playable reports for $LAUNCHES launches" "$playable_reports" -eq "$LAUNCHES"
fi

# A run that crosses midnight finds the previous day already gzipped by the ledger's housekeeping
events() { { cat "$LEDGER_DIR"/events-*.jsonl; gzip -dc "$LEDGER_DIR"/events-*.jsonl.gz; } 2>/dev/null | grep -c "\"event\":\"$1\""; }
verified=$(events client_verified)
rejected=$(events client_rejected)
orders=$(events unit_order_issued)
kicks=$(grep -cE 'Kicking connection_id=.* reason=synaxis:protocol-error' "$SERVER_LOG")
expect "client_verified $verified, expected $CONNECTIONS" "$verified" -eq "$CONNECTIONS"
expect "client_rejected $rejected, expected 0" "$rejected" -eq 0
expect "unit_order_issued $orders, expected >= $LAUNCHES" "$orders" -ge "$LAUNCHES"
expect "protocol-error kicks $kicks, expected $LAUNCHES" "$kicks" -eq "$LAUNCHES"

if [[ -n "${SESSION_LOG:-}" && ! -f "$SESSION_LOG" ]]; then
  fail "no sessionserver log at $SESSION_LOG"
elif [[ -n "${SESSION_LOG:-}" ]]; then
  joins=$(grep -cE '^[0-9]+ POST /session/minecraft/join -> 204$' "$SESSION_LOG")
  asked=$(grep -cE '^[0-9]+ GET /session/minecraft/hasJoined' "$SESSION_LOG")
  answered=$(grep -cE '^[0-9]+ GET /session/minecraft/hasJoined.* -> 200$' "$SESSION_LOG")
  logins=$((CONNECTIONS + ${CORE_CONNECTIONS:-0}))
  expect "sessionserver join $joins, expected $logins" "$joins" -eq "$logins"
  expect "sessionserver hasJoined 200 $answered of $asked, expected $logins of $logins" \
    "$answered" -eq "$logins" -a "$asked" -eq "$logins"
fi

if [[ "$failures" -gt 0 ]]; then echo "client regression: $failures check(s) failed"; exit 1; fi
echo "client regression: all checks passed"
