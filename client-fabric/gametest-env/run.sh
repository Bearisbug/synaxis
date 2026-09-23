#!/usr/bin/env bash
# Runs the client gametests of client-fabric in the headless container and copies the run's
# screenshots, report and logs to $OUT (default /tmp/synaxis-gametest). The repository is mounted
# read only and copied inside, so the host's build/ and .gradle/ are never touched.
#   SYNAXIS_GAMETEST_SERVER=host.docker.internal:25566 client-fabric/gametest-env/run.sh
# Against an online mode server, SYNAXIS_GAMETEST_SESSION_HOST=http://host.docker.internal:<port> points
# the client's authlib at tools/fake-sessionserver (record mode), the one the server verifies against.
# SYNAXIS_SPIKES (unset runs only the regression R1-R8; all, or e.g. s2,s5, adds the spikes), SYNAXIS_GAMETEST_NIGHT_VISION (true adds
# client-side night vision to the in-world shots), the SYNAXIS_CAMERA_* variables of the spike
# camera (gametest camera/SpikeCamera.java) and the SYNAXIS_S3_* variables of the entity culling
# spike (gametest EntityCullingSpike.java) pass through.
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
  -e SYNAXIS_SPIKES -e SYNAXIS_GAMETEST_NIGHT_VISION \
  -e SYNAXIS_CAMERA_MODES -e SYNAXIS_CAMERA_PITCHES -e SYNAXIS_CAMERA_DISTANCE \
  -e SYNAXIS_CAMERA_ORTHO_HEIGHT -e SYNAXIS_CAMERA_YAW -e SYNAXIS_CAMERA_ANCHOR \
  -e SYNAXIS_S3_DISTANCES -e SYNAXIS_S3_SCALINGS -e SYNAXIS_S3_HORIZONTALS \
  -e JAVA_TOOL_OPTIONS="-Dhttps.proxyHost=${PROXY%:*} -Dhttps.proxyPort=${PROXY#*:} -Dhttp.proxyHost=${PROXY%:*} -Dhttp.proxyPort=${PROXY#*:} -Dhttp.nonProxyHosts=maven.aliyun.com|*.aliyun.com -Dfabric.noGui=true" \
  synaxis-mc-client:dev bash -c '
    set -e
    mkdir -p /work/protocol/src/test/resources
    cp -r /src/protocol/src/test/resources/vectors /work/protocol/src/test/resources/
    tar -C /src -cf - --exclude=client-fabric/build --exclude=client-fabric/.gradle client-fabric | tar -C /work -xf -
    cd /work/client-fabric
    set +e
    xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runClientGameTest --no-daemon --console=plain --stacktrace \
      --init-script gametest-env/mirror.init.gradle.kts -Dorg.gradle.internal.repository.max.tentatives=6
    code=$?
    run=$(find build/run -maxdepth 1 -mindepth 1 -type d | head -1)
    rm -rf /out/*; cp -r "$run"/screenshots "$run"/synaxis-gametest "$run"/logs "$run"/hs_err_pid*.log /out/ 2>/dev/null
    exit $code'
