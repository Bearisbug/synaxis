#!/usr/bin/env bash
# Regenerates the vanilla light truth used by Synaxis' light engine tests:
#   - src/main/resources/io/github/bearisbug/synaxis/instance/light/light_shape_states.txt
#   - src/test/resources/io/github/bearisbug/synaxis/instance/light/parity/<scene>.light.gz for every <scene>.scene
# by running the vanilla 26.2 server's LevelLightEngine through LightOracle.java.
#
# Needs JDK 25 (JAVA_HOME). The server jar is taken from $SERVER_JAR, else Fabric Loom's cache, else
# downloaded from Mojang; whichever it is must match the 26.2 SHA-1 below. Libraries are unpacked from
# the bundler jar into a temporary directory that is removed on exit.
set -euo pipefail

SERVER_SHA1=823e2250d24b3ddac457a60c92a6a941943fcd6a
SERVER_URL=https://piston-data.mojang.com/v1/objects/$SERVER_SHA1/server.jar

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
java="${JAVA_HOME:?set JAVA_HOME to JDK 25}/bin/java"
work="$(mktemp -d "${TMPDIR:-/tmp}/light-oracle.XXXXXX")"
trap 'rm -rf "$work"' EXIT

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

(cd "$work" && unzip -q "$jar" 'META-INF/libraries/*' 'META-INF/versions/*')
cp="$(find "$work/META-INF" -name '*.jar' | sort | tr '\n' ':')"
# The vanilla server logs through log4j; keep its output off the fixture-generation console.
run() { "$java" -Dlog4j2.level=WARN -cp "$cp" "$here/LightOracle.java" "$@"; }

run shapes "$repo/src/main/resources/io/github/bearisbug/synaxis/instance/light/light_shape_states.txt"

scenes="$repo/src/test/resources/io/github/bearisbug/synaxis/instance/light/parity"
for scene in "$scenes"/*.scene; do
  name="$(basename "$scene" .scene)"
  run scene "$scene" "$scenes/$name.light.gz"
  echo "$name"
done
