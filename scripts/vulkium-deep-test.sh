#!/usr/bin/env bash
#
# DEV_ONLY — remove at release time.
#
# Deep-test routine: boot client, compile the full chunk radius via 360° lookaround,
# capture four screenshots 90° apart (N/E/S/W view directions), then accumulate ~2
# minutes of PerfTracker samples before tearing down. Purpose: stress the renderer
# across direction-varied views and collect enough perf windows to separate
# stationary-frame averages from chunk-streaming transients.
#
# Differences vs. vulkium-test.sh:
#   - Takes 4 screenshots at 90° yaw offsets (uses the /tmp/vulkium-rotate-90 trigger).
#   - Waits 120s by default for PerfTracker to fill, not 15s.
#   - Dumps a much larger slice of the perf log at the end (60 lines vs 20).
#
# Usage:
#   scripts/vulkium-deep-test.sh
#   WORLD=other scripts/vulkium-deep-test.sh
#   PROFILE_WAIT=180 SCREEN_W=3840 SCREEN_H=2160 scripts/vulkium-deep-test.sh
#
# Outputs (in $LOG_DIR = /tmp/vulkium-logs by default):
#   - run-<ts>.log                  full client log
#   - ss-<ts>-N.png, ss-<ts>-E.png, ss-<ts>-S.png, ss-<ts>-W.png   the four views

set -u

# --- config -------------------------------------------------------------------
WORLD="${WORLD:-test}"
SCREEN_W="${SCREEN_W:-2560}"
SCREEN_H="${SCREEN_H:-1440}"
RD="${RD:-32}"
BOOT_WAIT="${BOOT_WAIT:-35}"
LOOKAROUND_WAIT="${LOOKAROUND_WAIT:-8}"
# Between each rotate-and-screenshot: let MC compile any newly-visible chunks and
# let PerfTracker accrue a flush window or two for that view direction.
SCREENSHOT_SETTLE="${SCREENSHOT_SETTLE:-3}"
# After the 4 screenshots, accumulate perf samples. 120s matches the "2 minutes" spec
# and captures post-warmup stationary-frame behavior.
PROFILE_WAIT="${PROFILE_WAIT:-120}"
LOG_DIR="${LOG_DIR:-/tmp/vulkium-logs}"
JAVA_HOME_DEFAULT="/home/hawaf/javas/jdk-25.0.1"
JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}"

# --- locate repo root ---------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO"

# --- auto-discover GUI env (SSH sessions won't have DISPLAY/XAUTHORITY) -------
# KDE Plasma on Wayland runs Xwayland on a different :N each session with a random-
# suffix XAUTHORITY path; borrow from a running KDE process.
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
    echo "[deep-test] ERROR: DISPLAY not set and no KDE session process found to borrow it from."
    exit 1
fi
echo "[deep-test] DISPLAY=$DISPLAY XAUTHORITY=${XAUTHORITY:-(unset)} WAYLAND_DISPLAY=${WAYLAND_DISPLAY:-(unset)}"

mkdir -p "$LOG_DIR"
TS=$(date +%s)
LOG="$LOG_DIR/run-$TS.log"
SS_TRIGGER="/tmp/vulkium-screenshot"
LA_TRIGGER="/tmp/vulkium-lookaround"
ROT_TRIGGER="/tmp/vulkium-rotate-90"

echo "[deep-test] repo=$REPO"
echo "[deep-test] log=$LOG"
echo "[deep-test] world=$WORLD ${SCREEN_W}x${SCREEN_H} rd=$RD boot=${BOOT_WAIT}s profile=${PROFILE_WAIT}s"

# --- 0. seed options.txt with baseline render distance + vsync-off ------------
OPTS="$REPO/run/options.txt"
mkdir -p "$(dirname "$OPTS")"
if [[ -f "$OPTS" ]]; then
    grep -q "^renderDistance:" "$OPTS" \
        && sed -i "s/^renderDistance:.*/renderDistance:$RD/" "$OPTS" \
        || echo "renderDistance:$RD" >> "$OPTS"
    grep -q "^enableVsync:" "$OPTS" \
        && sed -i "s/^enableVsync:.*/enableVsync:false/" "$OPTS" \
        || echo "enableVsync:false" >> "$OPTS"
else
    printf 'renderDistance:%s\nenableVsync:false\n' "$RD" > "$OPTS"
fi
echo "[deep-test] options.txt: renderDistance=$RD enableVsync=false"

# --- 1. launch ----------------------------------------------------------------
rm -f "$SS_TRIGGER" "$LA_TRIGGER" "$ROT_TRIGGER"
setsid bash -c "
  export JAVA_HOME='$JAVA_HOME'
  exec ./gradlew runClient --no-daemon --args='--width $SCREEN_W --height $SCREEN_H' > '$LOG' 2>&1
" < /dev/null &
CHILD=$!
PGID=$CHILD
echo "[deep-test] launched pid=$CHILD pgid=$PGID"

# Teardown path shared by all exit paths.
cleanup() {
    local rc=$?
    echo "[deep-test] cleaning up (pgid=$PGID)"
    kill -TERM -"$PGID" 2>/dev/null || true
    for _ in 1 2 3 4 5; do
        sleep 1
        kill -0 -"$PGID" 2>/dev/null || { echo "[deep-test] group exited cleanly"; exit $rc; }
    done
    kill -KILL -"$PGID" 2>/dev/null || true
    exit $rc
}
trap cleanup EXIT INT TERM

# --- 2. wait for boot + world load --------------------------------------------
echo "[deep-test] waiting up to ${BOOT_WAIT}s for world to load..."
deadline=$(( $(date +%s) + BOOT_WAIT ))
READY=0
while [[ $(date +%s) -lt $deadline ]]; do
    if grep -q "RegionManager bound" "$LOG" 2>/dev/null \
       && grep -q "Renderer initialized" "$LOG" 2>/dev/null; then
        READY=1
        break
    fi
    if ! kill -0 -"$PGID" 2>/dev/null; then
        echo "[deep-test] client exited prematurely; tail of log:"
        tail -30 "$LOG"
        exit 1
    fi
    sleep 1
done
if [[ $READY -eq 1 ]]; then
    echo "[deep-test] renderer ready; extra 5s for quickPlay world-load..."
    sleep 5
else
    echo "[deep-test] readiness signals not seen in ${BOOT_WAIT}s; proceeding anyway"
fi

# --- 3. 360° lookaround to force chunks in all directions to compile ----------
echo "[deep-test] triggering 360° lookaround (compile every RD direction)"
touch "$LA_TRIGGER"
sleep "$LOOKAROUND_WAIT"
[[ -f "$LA_TRIGGER" ]] && { echo "[deep-test] WARN: lookaround trigger not consumed"; rm -f "$LA_TRIGGER"; }

# --- 4. four screenshots at 90° increments ------------------------------------
# After the 360° spin player faces whatever the starting yaw was. Screenshots are
# taken at that yaw (call it N), then at +90° (E), +180° (S), +270° (W). The
# instant-rotate trigger snaps yaw without the motion-blur window you'd get from
# smooth rotation.
SS_DIR="$REPO/run/screenshots"
LABELS=(N E S W)
take_labeled_screenshot() {
    local label="$1"
    # Clear any stale screenshots-newer-than markers by taking a timestamp before.
    local before_ts; before_ts=$(date +%s)
    touch "$SS_TRIGGER"
    # Hook polls at ~4Hz + Screenshot.grab is async; give it 3s at most.
    for _ in 1 2 3 4 5 6; do
        sleep 0.5
        [[ ! -f "$SS_TRIGGER" ]] && break
    done
    if [[ -f "$SS_TRIGGER" ]]; then
        echo "[deep-test] WARN: screenshot $label trigger not consumed"
        rm -f "$SS_TRIGGER"
        return 1
    fi
    # Find the newest file in SS_DIR modified after our before_ts.
    local newest
    if [[ -d "$SS_DIR" ]]; then
        newest=$(find "$SS_DIR" -maxdepth 1 -type f -newermt "@$before_ts" 2>/dev/null | head -1)
        if [[ -n "$newest" ]]; then
            cp "$newest" "$LOG_DIR/ss-$TS-$label.png"
            echo "[deep-test] screenshot $label → $LOG_DIR/ss-$TS-$label.png"
        else
            echo "[deep-test] WARN: no screenshot $label appeared in $SS_DIR"
        fi
    fi
    return 0
}

for i in 0 1 2 3; do
    label="${LABELS[$i]}"
    if [[ $i -gt 0 ]]; then
        echo "[deep-test] rotating +90° → $label"
        touch "$ROT_TRIGGER"
        # Small wait for the trigger poll to fire + let MC draw the new view +
        # any freshly-visible chunks that escaped the initial lookaround (e.g.
        # very near chunks whose quads ingest in a second wave) to compile.
        sleep "$SCREENSHOT_SETTLE"
        [[ -f "$ROT_TRIGGER" ]] && { echo "[deep-test] WARN: rotate trigger not consumed"; rm -f "$ROT_TRIGGER"; }
    else
        echo "[deep-test] screenshot $label (initial view)"
    fi
    take_labeled_screenshot "$label"
done

# --- 5. accumulate 2 minutes of PerfTracker samples ---------------------------
echo "[deep-test] accumulating PerfTracker samples for ${PROFILE_WAIT}s (use this window to see steady-state averages, not warmup)..."
# Print a progress line every 20s so it's obvious the test is still running.
elapsed=0
while [[ $elapsed -lt $PROFILE_WAIT ]]; do
    step=20
    [[ $((PROFILE_WAIT - elapsed)) -lt 20 ]] && step=$((PROFILE_WAIT - elapsed))
    sleep "$step"
    elapsed=$((elapsed + step))
    if ! kill -0 -"$PGID" 2>/dev/null; then
        echo "[deep-test] client exited during profile window after ${elapsed}s"
        tail -40 "$LOG"
        exit 1
    fi
    echo "[deep-test] ...${elapsed}/${PROFILE_WAIT}s"
done

# --- 6. dump perf slice -------------------------------------------------------
echo
echo "=== PerfTracker flushes (last 60 lines) ==="
grep -E "\[vulkium/perf\]|vulkium/perf" "$LOG" | tail -60
echo
echo "=== Frame-draw lines (last 20) ==="
grep -E "\[vulkium/frame\] draw:" "$LOG" | tail -20
echo
echo "=== Warnings / errors from vulkium subsystems ==="
grep -E "\[vulkium[^\]]*\]" "$LOG" | grep -iE "WARN|ERROR|arena full|fail|crash" | tail -20
echo
echo "=== F6-style diagnostic lines ==="
grep -E "\[vulkium\] (ENABLED|DISABLED|frames=)" "$LOG" | tail -10
echo
echo "[deep-test] screenshots: $LOG_DIR/ss-$TS-{N,E,S,W}.png"
echo "[deep-test] log:         $LOG"
echo "[deep-test] done; triggering cleanup trap"
exit 0
