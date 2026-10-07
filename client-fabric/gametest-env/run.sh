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
# spike (gametest EntityCullingSpike.java) pass through. So do SYNAXIS_P10_SERVER and SYNAXIS_P10_DONE, which
# run P10 (gametest TransferRoundTripClientGameTest.java, docs/TEST.md TC-PROD-550) instead of the regression;
# SYNAXIS_P10_DONE is a path inside the container, under /out. SYNAXIS_LIGHT_SERVER=<host:port> and
# SYNAXIS_LIGHT_MODE (default, resend or real) run the runtime light cases (gametest light/LightClientGameTest.java);
# in mode real the client writes the empty file /out/light-ready, which the host waits for in $OUT while the
# container runs: the end of this script clears /out before it copies the run's files.
# SYNAXIS_FARVIEW_SERVER=<host:port>, SYNAXIS_FARVIEW_HTTP=http://<host>:<port> and SYNAXIS_FARVIEW_MODE (comma separated, default v1)
# run the far view cases (gametest farview/FarViewClientGameTest.java, docs/DESIGN-M3.md §26 M3-G) against the far view fixture;
# SYNAXIS_FARVIEW_MODS=dh or dh-iris fetches Distant Horizons (and Sodium, Iris and the shader pack) inside the container with
# docs/research/client-spikes-2026-09/dh-iris/Fetch.java and hands them to Fabric Loader. The run's synaxis/farview/ file list and
# completion markers go to $OUT/farview/. SYNAXIS_GAMETEST_ARGS (e.g. "--width 1920 --height 1080") goes to the game as its
# program arguments, Gradle's --args: the gametest renders at that size, 854x480 without it.
# SYNAXIS_FARVIEW_SUN_ELEVATION (the sun elevation rts-shots expects of a fixture that stopped its clock), SYNAXIS_SHADER_PACK
# (the shader pack by the start of its file name, with SYNAXIS_FARVIEW_MODS=dh-iris-photon: photon), SYNAXIS_LIGHT_RENDER_DISTANCE
# and SYNAXIS_LIGHT_RING (the light-frames mode of LightClientGameTest) and SYNAXIS_DH_THREADS (DH's worker threads) pass through
# (docs/DESIGN-M3.md §26 M3-S).
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
  -e SYNAXIS_P10_SERVER -e SYNAXIS_P10_DONE \
  -e SYNAXIS_LIGHT_SERVER -e SYNAXIS_LIGHT_MODE -e SYNAXIS_LIGHT_MARKER=/out/light-ready \
  -e SYNAXIS_FARVIEW_SERVER -e SYNAXIS_FARVIEW_HTTP -e SYNAXIS_FARVIEW_MODE -e SYNAXIS_FARVIEW_MODS -e SYNAXIS_GAMETEST_ARGS \
  -e SYNAXIS_FARVIEW_SUN_ELEVATION -e SYNAXIS_SHADER_PACK -e SYNAXIS_LIGHT_RENDER_DISTANCE -e SYNAXIS_LIGHT_RING -e SYNAXIS_DH_THREADS \
  -e SYNAXIS_DH_SHADERPACKS=/dh/shaderpacks \
  -e JAVA_TOOL_OPTIONS="-Dhttps.proxyHost=${PROXY%:*} -Dhttps.proxyPort=${PROXY#*:} -Dhttp.proxyHost=${PROXY%:*} -Dhttp.proxyPort=${PROXY#*:} -Dhttp.nonProxyHosts=maven.aliyun.com|*.aliyun.com|host.docker.internal -Dfabric.noGui=true${SYNAXIS_FARVIEW_MODS:+ -Dfabric.addMods=/dh/mods}" \
  synaxis-mc-client:dev bash -c '
    set -e
    mkdir -p /work/protocol/src/test/resources
    cp -r /src/protocol/src/test/resources/vectors /src/protocol/src/test/resources/farview-vectors /work/protocol/src/test/resources/
    tar -C /src -cf - --exclude=client-fabric/build --exclude=client-fabric/.gradle client-fabric | tar -C /work -xf -
    cd /work/client-fabric
    if [ -n "$SYNAXIS_FARVIEW_MODS" ]; then java /src/docs/research/client-spikes-2026-09/dh-iris/Fetch.java "$SYNAXIS_FARVIEW_MODS" /dh; fi
    set +e
    xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runClientGameTest ${SYNAXIS_GAMETEST_ARGS:+"--args=$SYNAXIS_GAMETEST_ARGS"} --no-daemon --console=plain --stacktrace \
      --init-script gametest-env/mirror.init.gradle.kts -Dorg.gradle.internal.repository.max.tentatives=6
    code=$?
    run=$(find build/run -maxdepth 1 -mindepth 1 -type d | head -1)
    rm -rf /out/*; cp -r "$run"/screenshots "$run"/synaxis-gametest "$run"/logs "$run"/hs_err_pid*.log /out/ 2>/dev/null
    if [ -d "$run"/synaxis/farview ]; then
      mkdir -p /out/farview
      find "$run"/synaxis/farview -type f -printf "%s %P\n" | sort -k2 > /out/farview/files.txt
      for m in "$run"/synaxis/farview/dh/*/synaxis-library.json; do [ -f "$m" ] && cp "$m" "/out/farview/$(basename "$(dirname "$m")").json"; done
    fi
    exit $code'
