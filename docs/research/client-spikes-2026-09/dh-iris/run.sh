#!/usr/bin/env bash
# The DH / Iris spike in the headless client container (README.md next to this file). Same container
# as client-fabric/gametest-env/run.sh, plus Fetch.java downloading the third-party jars and the shader
# pack to /dh inside the container before the client starts. Nothing downloaded lands on the host.
#   MODS=dh|iris|dh-iris SYNAXIS_GAMETEST_SERVER=host.docker.internal:25577 OUT=/tmp/synaxis-dhiris/out run.sh
set -euo pipefail
REPO=$(cd "$(dirname "$0")/../../../.." && pwd)
OUT=${OUT:-/tmp/synaxis-dhiris/out}
PROXY=${PROXY:-host.docker.internal:7897}
mkdir -p "$OUT"
docker run --rm --name "${CONTAINER:-synaxis-mc-client-dhiris}" \
  -v synaxis-mc-gradle:/root/.gradle \
  -v "$HOME/.gradle/caches":/ro-cache:ro -e GRADLE_RO_DEP_CACHE=/ro-cache \
  -v "$REPO":/src:ro \
  -v "$HOME/.m2/repository/io/github/bearisbug":/root/.m2/repository/io/github/bearisbug:ro \
  -v "$OUT":/out \
  -e ALSOFT_DRIVERS=null \
  -e SYNAXIS_GAMETEST_SERVER="${SYNAXIS_GAMETEST_SERVER:-}" \
  -e SYNAXIS_SPIKES="${SYNAXIS_SPIKES:-dh}" -e MODS="${MODS:-dh-iris}" \
  -e JAVA_TOOL_OPTIONS="-Dhttps.proxyHost=${PROXY%:*} -Dhttps.proxyPort=${PROXY#*:} -Dhttp.proxyHost=${PROXY%:*} -Dhttp.proxyPort=${PROXY#*:} -Dhttp.nonProxyHosts=maven.aliyun.com|*.aliyun.com -Dfabric.noGui=true -Dfabric.addMods=/dh/mods" \
  -e SYNAXIS_DH_SHADERPACKS=/dh/shaderpacks \
  synaxis-mc-client:dev bash -c '
    set -e
    mkdir -p /work/protocol/src/test/resources
    cp -r /src/protocol/src/test/resources/vectors /work/protocol/src/test/resources/
    tar -C /src -cf - --exclude=client-fabric/build --exclude=client-fabric/.gradle client-fabric | tar -C /work -xf -
    cd /work/client-fabric
    java /src/docs/research/client-spikes-2026-09/dh-iris/Fetch.java "$MODS" /dh
    set +e
    xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runClientGameTest --no-daemon --console=plain --stacktrace \
      --init-script gametest-env/mirror.init.gradle.kts -Dorg.gradle.internal.repository.max.tentatives=6
    code=$?
    run=build/run/clientGameTest
    rm -rf /out/*; cp -r "$run"/screenshots "$run"/synaxis-gametest "$run"/logs "$run"/config "$run"/crash-reports "$run"/hs_err_pid*.log /out/ 2>/dev/null
    ls "$run"/mods /dh/mods /dh/shaderpacks > /out/mods.txt
    exit $code'
