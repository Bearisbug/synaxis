#!/usr/bin/env bash
# Read-only check of RegressionClientGameTest (path in $1): R1 to R8 go into the report and nowhere else,
# so a FAIL line, or a step that throws, does not end Fabric's gametest run before PlayableClientGameTest.
# RUN-075 group R, docs/TEST.md TC-PROD-757. One line per fact, "ok <fact>" or "MISSING <fact>"; exit 1
# when a fact is missing.
#   bash check-regression.sh client-fabric/src/gametest/java/io/github/bearisbug/synaxis/game/client/gametest/RegressionClientGameTest.java
set -uo pipefail
src=$1
# The body of runTest: from its signature to the first line that closes a member at four spaces
body=$(awk '/public void runTest\(ClientGameTestContext context\)/ {on=1} on {print} on && /^    }$/ {exit}' "$src")
missing=0
fact() { local what=$1; shift; if "$@"; then echo "ok $what"; else echo "MISSING $what"; missing=$((missing + 1)); fi; }
no() { ! grep -qE "$1" <<< "$2"; }
has() { grep -qE "$1" <<< "$2"; }
fact "runTest is in the file" test -n "$body"
fact "no FAIL line is turned into an AssertionError" no 'assertAllPassed|throw new AssertionError' "$(cat "$src")"
fact "runTest catches what the steps throw" has 'catch \(RuntimeException \| AssertionError ' "$body"
fact "runTest throws nothing itself, the catch included" no '(^|[^A-Za-z])throw ' "$body"
fact "the report is written in a finally" has 'finally \{' "$body"
fact "the finally writes the report before it leaves to the title screen" \
  has 'report\.write\([^)]*\);' "$(sed -n '/finally {/,$p' <<< "$body" | head -3)"
fact "the client leaves to the title screen with no level" has 'leaveToTitle\(context\);' "$(sed -n '/finally {/,$p' <<< "$body")"
[[ $missing -eq 0 ]]
