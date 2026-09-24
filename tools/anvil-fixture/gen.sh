#!/usr/bin/env bash
# Regenerates the vanilla 26.2 Anvil save used by :baker's Anvil reader tests (REQ-M3-020, REQ-M3-022):
#   baker/src/test/resources/io/github/bearisbug/synaxis/baker/anvil/world/
#     dimensions/minecraft/overworld/region/r.0.0.mca     chunk 0,0 (full) + the first non-full chunk, if any
#     dimensions/minecraft/overworld/entities/r.0.0.mca   chunk 0,0
#
# Runs the vanilla 26.2 dedicated server on a superflat world in a temporary directory, places the
# fixture content in chunk 0,0 through console commands, saves, stops, then copies the chunks above
# byte for byte into fresh region files (TrimRegion.java) so the fixture is a few KiB, not the whole
# spawn area. Content of chunk 0,0 (the superflat surface is grass at y -61):
#   (1,-60,1) oak_sign "Hello" / "Synaxis"   (2,-60,1) white_banner   (3,-60,1) player_head
#   (4,-60,1) chest, under a DATA structure block at (4,-59,1): metadata synaxis:loot {table:"t1"}
#   (5,-60,5) lever, under a DATA structure block at (5,-59,5): metadata synaxis:door {locked:1b}
#   (9,-60,9) DATA structure block: metadata synaxis:hidden 0 0 15 15
#   item_frame at (7,-60,7) facing up, text_display "hi" at (8.5,-59,8.5)
#
# Needs JDK 25 (JAVA_HOME). The server jar is taken from $SERVER_JAR, else Fabric Loom's cache, else
# downloaded from Mojang; whichever it is must match the 26.2 SHA-1 below (same as tools/light-oracle).
# Everything the server writes (world, libraries, logs) stays in the temporary directory, which is
# removed on exit together with the server process.
set -euo pipefail

SERVER_SHA1=823e2250d24b3ddac457a60c92a6a941943fcd6a
SERVER_URL=https://piston-data.mojang.com/v1/objects/$SERVER_SHA1/server.jar

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
java="${JAVA_HOME:?set JAVA_HOME to JDK 25}/bin/java"
out="$repo/baker/src/test/resources/io/github/bearisbug/synaxis/baker/anvil/world/dimensions/minecraft/overworld"
work="$(mktemp -d "${TMPDIR:-/tmp}/anvil-fixture.XXXXXX")"
server_pid=""
cleanup() {
  if [[ -n "$server_pid" ]] && kill -0 "$server_pid" 2>/dev/null; then kill "$server_pid"; wait "$server_pid" || true; fi
  rm -rf "$work"
}
trap cleanup EXIT

jar="${SERVER_JAR:-$HOME/.gradle/caches/fabric-loom/26.2/minecraft-server.jar}"
if [[ ! -f "$jar" ]]; then
  jar="$work/server.jar"
  curl -fsSL -o "$jar" "$SERVER_URL"
fi
actual="$(shasum -a 1 "$jar" | cut -d' ' -f1)"
if [[ "$actual" != "$SERVER_SHA1" ]]; then
  echo "server jar $jar has SHA-1 $actual, expected $SERVER_SHA1 (vanilla 26.2)" >&2
  exit 1
fi

port=25590
while lsof -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; do port=$((port + 1)); done

cd "$work"
echo "eula=true" > eula.txt
cat > server.properties <<EOF
level-type=minecraft\:flat
generate-structures=false
online-mode=false
server-port=$port
spawn-protection=0
spawn-monsters=false
spawn-animals=false
EOF

mkfifo console
"$java" -Xmx1G -jar "$jar" nogui < console > server.log 2>&1 &
server_pid=$!
# Held open for the whole run: the server stops reading its console at the first end of file.
exec 3> console

wait_for() {
  for _ in $(seq 1 180); do
    grep -q "$1" server.log && return 0
    kill -0 "$server_pid" 2>/dev/null || { cat server.log >&2; return 1; }
    sleep 1
  done
  echo "timed out waiting for: $1" >&2
  return 1
}

wait_for 'Done ('
echo 'forceload add 0 0' >&3
wait_for 'Marked chunk'
cat >&3 <<'EOF'
setblock 1 -60 1 minecraft:oak_sign[rotation=0]{front_text:{messages:["Hello","Synaxis","",""]}}
setblock 2 -60 1 minecraft:white_banner
setblock 3 -60 1 minecraft:player_head
setblock 4 -60 1 minecraft:chest
setblock 4 -59 1 minecraft:structure_block[mode=data]{mode:"DATA",metadata:'synaxis:loot {table:"t1"}'}
setblock 5 -60 5 minecraft:lever[face=floor]
setblock 5 -59 5 minecraft:structure_block[mode=data]{mode:"DATA",metadata:"synaxis:door {locked:1b}"}
setblock 9 -60 9 minecraft:structure_block[mode=data]{mode:"DATA",metadata:"synaxis:hidden 0 0 15 15"}
summon minecraft:item_frame 7 -60 7 {Facing:1b}
summon minecraft:text_display 8.5 -59 8.5 {text:"hi"}
EOF
sleep 2
echo 'save-all flush' >&3
wait_for 'Saved the game'
echo 'stop' >&3
wait "$server_pid" || true
server_pid=""

placed="$(grep -c 'Changed the block' server.log || true)"
summoned="$(grep -c 'Summoned new' server.log || true)"
if [[ "$placed" != 8 || "$summoned" != 2 ]]; then
  grep -v '^\s*at ' server.log >&2
  echo "expected 8 blocks and 2 entities, the server reports $placed and $summoned" >&2
  exit 1
fi

cp="$(find "$work/libraries" "$work/versions" -name '*.jar' | sort | tr '\n' ':')"
world="$work/world/dimensions/minecraft/overworld"
mkdir -p "$out/region" "$out/entities"
"$java" -cp "$cp" "$here/TrimRegion.java" "$world/region/r.0.0.mca" "$out/region/r.0.0.mca" 0,0 +proto
"$java" -cp "$cp" "$here/TrimRegion.java" "$world/entities/r.0.0.mca" "$out/entities/r.0.0.mca" 0,0
ls -l "$out/region" "$out/entities"
