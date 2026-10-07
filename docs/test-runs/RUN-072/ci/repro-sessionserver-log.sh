# Reproduces the race in client-gametest.yml's "Start the fake sessionserver in record mode" step (RUN-072 group ci).
# Run it the way the runner runs a step, from an empty scratch directory:
#   GITHUB_ENV=$PWD/github_env bash -e repro-sessionserver-log.sh before   # the step as on main f7a9756f7b
#   GITHUB_ENV=$PWD/github_env bash -e repro-sessionserver-log.sh after    # with the `: > sessionserver.log` line
# fake_server stands in for the JVM and prints FakeSessionServer's first line; `sleep 1` ahead of the redirection widens
# the window in which the background fork has not opened the log yet, so the first sed always finds no file.
fake_server() {
  echo "fake sessionserver mode=record listening on http://127.0.0.1:40541, hasJoined at http://127.0.0.1:40541/session/minecraft/hasJoined"
  sleep 2
}
if [ "$1" = after ]; then
  : > sessionserver.log
fi
( (sleep 1; fake_server > sessionserver.log 2>&1) &)
# From here on, verbatim from the step
for _ in $(seq 1 60); do
  port=$(sed -n 's|.*listening on http://127.0.0.1:\([0-9]*\),.*|\1|p' sessionserver.log)
  if [ -n "$port" ]; then
    echo "SESSION_HOST=http://127.0.0.1:$port" >> "$GITHUB_ENV"
    exit 0
  fi
  sleep 1
done
cat sessionserver.log
exit 1
