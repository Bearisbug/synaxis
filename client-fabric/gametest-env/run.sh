#!/usr/bin/env bash
# Runs the client gametests of client-fabric in the headless container and copies the run's
# screenshots, report and logs to $OUT (default /tmp/synaxis-gametest). The repository is mounted
# read only and copied inside, so the host's build/ and .gradle/ are never touched.
#   SYNAXIS_GAMETEST_SERVER=host.docker.internal:25566 client-fabric/gametest-env/run.sh
# Against an online mode server, SYNAXIS_GAMETEST_SESSION_HOST=http://host.docker.internal:<port> points
# the client's authlib at tools/fake-sessionserver (record mode), the one the server verifies against.
# SYNAXIS_P10_SERVER and SYNAXIS_P10_DONE pass through, which run P10 (gametest TransferRoundTripClientGameTest.java,
# docs/TEST.md TC-PROD-550) instead of the regression; SYNAXIS_P10_DONE is a path inside the container, under /out.
# SYNAXIS_LIGHT_SERVER=<host:port> and SYNAXIS_LIGHT_MODE (default, resend or real) run the runtime light cases (gametest
# light/LightClientGameTest.java); in mode real the client writes the empty file /out/light-ready, which the host waits
# for in $OUT while the container runs: the end of this script clears /out before it copies the run's files.
# SYNAXIS_LIGHT_RENDER_DISTANCE and SYNAXIS_LIGHT_RING (the light-frames mode of LightClientGameTest) pass through too.
# SYNAXIS_GAMETEST_ARGS (e.g. "--width 1920 --height 1080") goes to the game as its program arguments, Gradle's --args:
# the gametest renders at that size, 854x480 without it.
set -euo pipefail
REPO=$(cd "$(dirname "$0")/../.." && pwd)
OUT=${OUT:-/tmp/synaxis-gametest}
PROXY=${PROXY:-host.docker.internal:7897}
mkdir -p "$OUT"
# ALSOFT_DRIVERS=null gives OpenAL a silent device, the container has no sound card. fabric.noGui makes
# a mod resolution failure end the run instead of waiting on an error window nobody can see.
docker run --rm --name "${CONTAINER:-synaxis-mc-client}" \
  -v synaxis-mc-gradle:/root/.gradle \
  -v "$HOME/.gradle/caches":/ro-cache:ro -e GRADLE_RO_DEP_CACHE=/ro-cache \
  -v "$REPO":/src:ro \
  -v "$HOME/.m2/repository/io/github/bearisbug":/root/.m2/repository/io/github/bearisbug:ro \
  -v "$OUT":/out \
  -e ALSOFT_DRIVERS=null \
  -e SYNAXIS_GAMETEST_SERVER="${SYNAXIS_GAMETEST_SERVER:-}" \
  -e SYNAXIS_GAMETEST_SESSION_HOST \
  -e SYNAXIS_P10_SERVER -e SYNAXIS_P10_DONE \
  -e SYNAXIS_LIGHT_SERVER -e SYNAXIS_LIGHT_MODE -e SYNAXIS_LIGHT_MARKER=/out/light-ready \
  -e SYNAXIS_LIGHT_RENDER_DISTANCE -e SYNAXIS_LIGHT_RING -e SYNAXIS_GAMETEST_ARGS \
  -e JAVA_TOOL_OPTIONS="-Dhttps.proxyHost=${PROXY%:*} -Dhttps.proxyPort=${PROXY#*:} -Dhttp.proxyHost=${PROXY%:*} -Dhttp.proxyPort=${PROXY#*:} -Dhttp.nonProxyHosts=maven.aliyun.com|*.aliyun.com|host.docker.internal -Dfabric.noGui=true" \
  synaxis-mc-client:dev bash -c '
    set -e
    mkdir -p /work/protocol/src/test/resources
    cp -r /src/protocol/src/test/resources/vectors /work/protocol/src/test/resources/
    tar -C /src -cf - --exclude=client-fabric/build --exclude=client-fabric/.gradle client-fabric | tar -C /work -xf -
    cd /work/client-fabric
    set +e
    xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runClientGameTest ${SYNAXIS_GAMETEST_ARGS:+"--args=$SYNAXIS_GAMETEST_ARGS"} --no-daemon --console=plain --stacktrace \
      --init-script gametest-env/mirror.init.gradle.kts -Dorg.gradle.internal.repository.max.tentatives=6
    code=$?
    run=$(find build/run -maxdepth 1 -mindepth 1 -type d | head -1)
    rm -rf /out/*; cp -r "$run"/screenshots "$run"/synaxis-gametest "$run"/logs "$run"/hs_err_pid*.log /out/ 2>/dev/null
    exit $code'
