#!/usr/bin/env bash
#
# DEV_ONLY — remove at release time.
#
# Remote-test routine for vulkium on the Linux+NVIDIA host reached over SSH.
# Steps:
#   1. launch runClient at 2560x1440 (quickPlay into the configured world)
#   2. wait for boot + world-load
#   3. trigger a screenshot via the /tmp/vulkium-screenshot file hook
#   4. wait for PerfTracker to accrue a couple of flush windows
#   5. print the PerfTracker lines from the log
#   6. kill the client cleanly
#
# Usage:
#   scripts/vulkium-test.sh                      # defaults below
#   WORLD=other scripts/vulkium-test.sh          # different save folder
#   BOOT_WAIT=45 PROFILE_WAIT=20 scripts/vulkium-test.sh
#   SCREEN_W=1920 SCREEN_H=1080 scripts/vulkium-test.sh
#
# Output:
#   - run log:    $LOG_DIR/run-<ts>.log
#   - screenshot: run/screenshots/<mc-timestamp>.png  (newest is copied to $LOG_DIR)
#   - profile:    stdout (also still in the run log under "vulkium/perf")

set -u

# --- config -------------------------------------------------------------------
WORLD="${WORLD:-test}"
SCREEN_W="${SCREEN_W:-2560}"
SCREEN_H="${SCREEN_H:-1440}"
BOOT_WAIT="${BOOT_WAIT:-35}"        # secs from launch to first screenshot
PROFILE_WAIT="${PROFILE_WAIT:-15}"  # additional secs for PerfTracker samples
LOG_DIR="${LOG_DIR:-/tmp/vulkium-logs}"
JAVA_HOME_DEFAULT="/home/hawaf/javas/jdk-25.0.1"
JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}"

# --- locate repo root ---------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO"

mkdir -p "$LOG_DIR"
TS=$(date +%s)
LOG="$LOG_DIR/run-$TS.log"
SS_TRIGGER="/tmp/vulkium-screenshot"

echo "[test] repo=$REPO"
echo "[test] log=$LOG"
echo "[test] world=$WORLD ${SCREEN_W}x${SCREEN_H} boot=${BOOT_WAIT}s profile=${PROFILE_WAIT}s"

# --- 1. launch ----------------------------------------------------------------
# Use setsid so we can signal the whole process group at shutdown (gradle spawns
# a daemon which spawns the MC JVM; killing only the gradle PID can orphan the
# JVM). --args lets us pass --width/--height to MC without touching gradle.
rm -f "$SS_TRIGGER"
setsid bash -c "
  export JAVA_HOME='$JAVA_HOME'
  exec ./gradlew runClient --no-daemon --args='--width $SCREEN_W --height $SCREEN_H' > '$LOG' 2>&1
" < /dev/null &
CHILD=$!
# Process group ID = PID when started via setsid.
PGID=$CHILD
echo "[test] launched pid=$CHILD pgid=$PGID"

# Define a single cleanup path so any failure below still tears down the client.
cleanup() {
    local rc=$?
    echo "[test] cleaning up (pgid=$PGID)"
    # TERM the whole group; give MC's shutdown hooks ~5s; then KILL.
    kill -TERM -"$PGID" 2>/dev/null || true
    for _ in 1 2 3 4 5; do
        sleep 1
        kill -0 -"$PGID" 2>/dev/null || { echo "[test] group exited cleanly"; exit $rc; }
    done
    kill -KILL -"$PGID" 2>/dev/null || true
    exit $rc
}
trap cleanup EXIT INT TERM

# --- 2. wait for boot + world load --------------------------------------------
# Watch the log for the F3 overlay's region-bound line as a readiness signal,
# with BOOT_WAIT as a hard ceiling. If we never see it, still proceed — the
# screenshot will just show an empty terminal / loading screen, which is
# itself informative for debugging boot-time regressions.
echo "[test] waiting up to ${BOOT_WAIT}s for world to load..."
deadline=$(( $(date +%s) + BOOT_WAIT ))
READY=0
while [[ $(date +%s) -lt $deadline ]]; do
    if grep -q "RegionManager bound" "$LOG" 2>/dev/null \
       && grep -q "Renderer initialized" "$LOG" 2>/dev/null; then
        READY=1
        break
    fi
    # Bail if the client died (build failure, crash, etc.).
    if ! kill -0 -"$PGID" 2>/dev/null; then
        echo "[test] client exited prematurely; tail of log:"
        tail -25 "$LOG"
        exit 1
    fi
    sleep 1
done
if [[ $READY -eq 1 ]]; then
    echo "[test] renderer ready; extra 5s for quickPlay to finish world load..."
    sleep 5
else
    echo "[test] readiness signals not seen in ${BOOT_WAIT}s; proceeding anyway"
fi

# --- 3. screenshot ------------------------------------------------------------
echo "[test] triggering screenshot"
touch "$SS_TRIGGER"
# The hook polls at ~4Hz and Screenshot.grab is async; 3s covers both.
for _ in 1 2 3 4 5 6; do
    sleep 0.5
    [[ ! -f "$SS_TRIGGER" ]] && break
done
if [[ -f "$SS_TRIGGER" ]]; then
    echo "[test] WARN: screenshot trigger not consumed (hook unreachable?)"
    rm -f "$SS_TRIGGER"
fi

# Copy the newest screenshot alongside the log for easy scp.
SS_DIR="$REPO/run/screenshots"
if [[ -d "$SS_DIR" ]]; then
    NEW_SS=$(ls -t "$SS_DIR" 2>/dev/null | head -1)
    if [[ -n "$NEW_SS" ]]; then
        cp "$SS_DIR/$NEW_SS" "$LOG_DIR/ss-$TS.png"
        echo "[test] screenshot: $LOG_DIR/ss-$TS.png (src: $SS_DIR/$NEW_SS)"
    else
        echo "[test] WARN: no screenshot appeared in $SS_DIR"
    fi
else
    echo "[test] WARN: $SS_DIR does not exist yet"
fi

# --- 4. wait for more PerfTracker samples -------------------------------------
echo "[test] accruing PerfTracker samples for ${PROFILE_WAIT}s..."
sleep "$PROFILE_WAIT"

# --- 5. profile ---------------------------------------------------------------
echo
echo "=== PerfTracker flushes ==="
grep -E "\[vulkium/perf\]|vulkium/perf" "$LOG" | tail -20
echo
echo "=== F6-style diagnostic lines ==="
grep -E "\[vulkium\] (ENABLED|DISABLED|frames=)" "$LOG" | tail -10
echo

# --- 6. implicit kill via trap ------------------------------------------------
# cleanup() handles shutdown. Exit 0 so trap reports success.
echo "[test] done; script triggering trap cleanup"
exit 0
