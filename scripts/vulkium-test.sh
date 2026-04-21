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
RD="${RD:-32}"                      # render distance (chunks). Baseline was 32.
BOOT_WAIT="${BOOT_WAIT:-35}"        # secs from launch to first screenshot
LOOKAROUND_WAIT="${LOOKAROUND_WAIT:-8}" # secs for 360° spin + chunk-compile catch-up
PROFILE_WAIT="${PROFILE_WAIT:-15}"  # additional secs for PerfTracker samples
LOG_DIR="${LOG_DIR:-/tmp/vulkium-logs}"
JAVA_HOME_DEFAULT="/home/hawaf/javas/jdk-25.0.1"
JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}"

# --- locate repo root ---------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO"

# --- auto-discover GUI env (SSH sessions won't have DISPLAY/XAUTHORITY) -------
# KDE Plasma on Wayland runs Xwayland on a different :N each session and uses a
# random-suffix XAUTHORITY path, so we can't hardcode. Read them out of a
# running kwin/plasmashell /proc/<pid>/environ. Respects anything the caller
# already exported.
if [[ -z "${DISPLAY:-}" || -z "${XAUTHORITY:-}" ]]; then
    for proc in plasmashell kwin_wayland kded6 kactivitymanagerd; do
        for p in $(pgrep -u "$USER" -x "$proc" 2>/dev/null); do
            [[ -r /proc/$p/environ ]] || continue
            while IFS='=' read -r k v; do
                case "$k" in
                    DISPLAY)         [[ -z "${DISPLAY:-}" ]]         && export DISPLAY="$v" ;;
                    XAUTHORITY)      [[ -z "${XAUTHORITY:-}" ]]      && export XAUTHORITY="$v" ;;
                    WAYLAND_DISPLAY) [[ -z "${WAYLAND_DISPLAY:-}" ]] && export WAYLAND_DISPLAY="$v" ;;
                    XDG_RUNTIME_DIR) [[ -z "${XDG_RUNTIME_DIR:-}" ]] && export XDG_RUNTIME_DIR="$v" ;;
                esac
            done < <(tr '\0' '\n' < /proc/$p/environ)
            [[ -n "${DISPLAY:-}" ]] && break 2
        done
    done
fi
if [[ -z "${DISPLAY:-}" ]]; then
    echo "[test] ERROR: DISPLAY not set and no KDE session process found to borrow it from."
    echo "[test] Make sure someone is logged in to the Plasma session on the console, or"
    echo "[test] export DISPLAY/XAUTHORITY manually before running this script."
    exit 1
fi
echo "[test] DISPLAY=$DISPLAY XAUTHORITY=${XAUTHORITY:-(unset)} WAYLAND_DISPLAY=${WAYLAND_DISPLAY:-(unset)}"

mkdir -p "$LOG_DIR"
TS=$(date +%s)
LOG="$LOG_DIR/run-$TS.log"
SS_TRIGGER="/tmp/vulkium-screenshot"
LA_TRIGGER="/tmp/vulkium-lookaround"

echo "[test] repo=$REPO"
echo "[test] log=$LOG"
echo "[test] world=$WORLD ${SCREEN_W}x${SCREEN_H} rd=$RD boot=${BOOT_WAIT}s profile=${PROFILE_WAIT}s"

# --- 0. seed options.txt with the baseline render distance --------------------
# Baseline methodology: RD=32 + 360° spin so every chunk in the loaded radius is
# compiled before measurement. Writing options.txt before launch guarantees we
# don't inherit whatever the save file last persisted.
OPTS="$REPO/run/options.txt"
mkdir -p "$(dirname "$OPTS")"
if [[ -f "$OPTS" ]]; then
    # Update in place; add line if missing.
    if grep -q "^renderDistance:" "$OPTS"; then
        sed -i "s/^renderDistance:.*/renderDistance:$RD/" "$OPTS"
    else
        echo "renderDistance:$RD" >> "$OPTS"
    fi
    # Turn vsync off so frame times reflect actual GPU throughput.
    if grep -q "^enableVsync:" "$OPTS"; then
        sed -i "s/^enableVsync:.*/enableVsync:false/" "$OPTS"
    else
        echo "enableVsync:false" >> "$OPTS"
    fi
else
    cat > "$OPTS" <<EOT
renderDistance:$RD
enableVsync:false
EOT
    echo "[test] wrote fresh options.txt (MC will fill defaults for the rest)"
fi
echo "[test] options.txt: renderDistance=$RD enableVsync=false"

# --- 1. launch ----------------------------------------------------------------
# Use setsid so we can signal the whole process group at shutdown (gradle spawns
# a daemon which spawns the MC JVM; killing only the gradle PID can orphan the
# JVM). --args lets us pass --width/--height to MC without touching gradle.
rm -f "$SS_TRIGGER" "$LA_TRIGGER"
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

# --- 2.5. look around to force all chunks in the RD sphere to compile ---------
# Baseline methodology: spin player yaw through 360° so MC's SectionRenderDispatcher
# sees every quadrant and schedules compiles for the full loaded radius. Without
# this, only the cone in front of the camera streams in and FPS numbers aren't
# comparable to pre-dev-branch baselines.
echo "[test] triggering 360° lookaround"
touch "$LA_TRIGGER"
# Wait for the in-mod hook to pick up the trigger (~0.5s) + 4s rotation +
# a few seconds for chunk-compile backpressure to drain through the ingest
# queue. LOOKAROUND_WAIT covers the whole window.
sleep "$LOOKAROUND_WAIT"
if [[ -f "$LA_TRIGGER" ]]; then
    echo "[test] WARN: lookaround trigger not consumed (world may still be loading)"
    rm -f "$LA_TRIGGER"
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
