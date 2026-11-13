#!/usr/bin/env bash
# Task-6 (A03 / D05): real process-restart matrix on an emulator.
#
# Orchestrates ProcessRecoveryTest phases as separate `am instrument` runs so that a process can be
# force-stopped (graceful restart) or killed with `kill -9` (crash) between the "before" and "after"
# assertions. Every phase writes its raw stdout/logcat into ./raw/ and a line into ./matrix-summary.txt.
#
# Prerequisites: the app must already be installed (`:app:installDebug`), device unlocked, no other
# instrumentation running. The script never builds, never clears app data, and never touches files
# outside ./raw and ./matrix-summary.txt.
#
# Usage:
#   ./run-task6-lock-matrix.sh                 # default: emulator-5556
#   SERIAL=emulator-5554 ./run-task6-lock-matrix.sh
#   ADB=/path/to/adb SERIAL=... ./run-task6-lock-matrix.sh
set -uo pipefail

SERIAL="${SERIAL:-emulator-5556}"
ADB="${ADB:-/d/Android/Sdk/platform-tools/adb.exe}"
[ -x "$ADB" ] || ADB=adb
PKG="cn.yunji.journal"
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
CLASS="$PKG.ProcessRecoveryTest"
OUT="$(cd "$(dirname "$0")" && pwd)"
RAW="$OUT/raw"
SUMMARY="$OUT/matrix-summary.txt"
FAILS=0

mkdir -p "$RAW"
: > "$SUMMARY"

log() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }
adb_shell() { "$ADB" -s "$SERIAL" shell "$@"; }
record() { # scenario, phase, result, detail
  log "$1,$2,$3,$4"
  [ "$3" = "PASS" ] || FAILS=$((FAILS + 1))
}

# alive_pid <pid>: true while /proc/<pid> still exists (the process has not been reaped).
alive_pid() {
  adb_shell "test -d /proc/$1 && echo alive" 2>/dev/null | tr -d '\r' | grep -q alive
}

require_device() {
  local state
  state="$("$ADB" devices | awk -v s="$SERIAL" '$1 == s { print $2 }')"
  [ "$state" = "device" ] || { echo "ERROR: device $SERIAL not ready (state='${state:-missing}')" >&2; exit 2; }
  adb_shell pm list packages "$PKG" | grep -q "package:$PKG" \
    || { echo "ERROR: $PKG not installed; run :app:installDebug first" >&2; exit 3; }
}

capture_baseline() {
  {
    echo "serial=$SERIAL"
    echo "date=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    adb_shell getprop ro.build.version.sdk
    adb_shell getprop ro.build.version.release
    adb_shell getprop ro.product.model
    adb_shell getprop ro.product.cpu.abi
    adb_shell getprop dalvik.vm.page_size
    adb_shell getconf PAGE_SIZE
    adb_shell wm size
    adb_shell wm density
    adb_shell settings get system font_scale
    adb_shell settings get system accelerometer_rotation
    adb_shell settings get system user_rotation
    adb_shell cmd uimode night
    adb_shell dumpsys window | grep -E 'mCurrentRotation|mRotation' | head -n 3
  } > "$RAW/device-baseline.txt" 2>&1
}

# run_test <scenario> <phase> <method>
# Foreground instrumentation run. PASS only when the runner reports "OK (".
run_test() {
  local scenario=$1 phase=$2 method=$3 tag="$1.$2"
  adb_shell am instrument -w -e class "$CLASS#$method" "$RUNNER" > "$RAW/$tag.stdout" 2>&1
  local code=$?
  adb_shell logcat -d -t 400 > "$RAW/$tag.logcat" 2>/dev/null || true
  adb_shell logcat -c >/dev/null 2>&1 || true
  if [ "$code" -eq 0 ] && grep -qE '^OK \([0-9]+ test' "$RAW/$tag.stdout"; then
    record "$scenario" "$phase" PASS "$(grep -E '^OK \([0-9]+ test' "$RAW/$tag.stdout" | tail -n 1)"
  else
    record "$scenario" "$phase" FAIL "am instrument exit=$code; see raw/$tag.stdout"
  fi
  return "$code"
}

# start_test_background <scenario> <phase> <method>
start_test_background() {
  local scenario=$1 phase=$2 method=$3 tag="$1.$2"
  ( adb_shell am instrument -w -e class "$CLASS#$method" "$RUNNER" > "$RAW/$tag.stdout" 2>&1; \
    echo "exit=$?" >> "$RAW/$tag.stdout" ) &
}

# wait_marker <file name under files/task6-markers> -> writes ./raw/<name>.contents, returns 0
wait_marker() {
  local name=$1 i
  for i in $(seq 1 60); do
    if adb_shell run-as "$PKG" cat "files/task6-markers/$name" > "$RAW/$name.contents" 2>/dev/null; then
      return 0
    fi
    sleep 1
  done
  return 1
}

kill_9() { # <scenario> <marker name>: try kill -9, fall back to ActivityManager force-stop (also SIGKILL)
  local scenario=$1 marker=$2 pid method="adb shell kill -9"
  wait_marker "$marker" || { record "$scenario" kill FAIL "marker files/task6-markers/$marker not written"; return 1; }
  pid="$(sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' "$RAW/$marker.contents" | head -n 1)"
  [ -n "$pid" ] || { record "$scenario" kill FAIL "marker has no pid: $(cat "$RAW/$marker.contents")"; return 1; }
  adb_shell kill -9 "$pid" >/dev/null 2>&1 || true
  if alive_pid "$pid"; then
    # ActivityManager.forceStopPackage() kills the process with SIGKILL and runs no lifecycle
    # callbacks, so it is the Observable "sudden termination" equivalent on a non-root device.
    method="am force-stop (kill -9 rejected by shell uid)"
    adb_shell am force-stop "$PKG" >/dev/null 2>&1 || true
  fi
  local i
  for i in $(seq 1 20); do
    alive_pid "$pid" || break
    sleep 0.5
  done
  if alive_pid "$pid"; then
    record "$scenario" kill FAIL "pid=$pid method=$method still alive after kill"
    return 1
  fi
  record "$scenario" kill PASS "pid=$pid method=$method"
  return 0
}

reset_app() {
  adb_shell am force-stop "$PKG" >/dev/null 2>&1 || true
  adb_shell logcat -c >/dev/null 2>&1 || true
}

require_device
capture_baseline
reset_app

# --- scenario 1: draft saved under an immediate lock survives a graceful (force-stop) restart ------
log "scenario,phase,result,detail"
reset_app
run_test draft phase1 phase1DraftSurvivesLockedGracefulRestart
reset_app
sleep 1
run_test draft phase2 phase2LockedColdStartKeepsDraftAndNeverShowsJournal

# --- scenario 2: pending-backup marker survives a graceful restart and is not consumed ------------
reset_app
run_test backup phase1 phase1PendingBackupMarkerSurvivesRestart
reset_app
sleep 1
run_test backup phase2 phase2ColdStartKeepsPendingBackupMarkerWithoutUpload

# --- scenario 3: vault open + writes, process killed with -9, data read back once -----------------
reset_app
start_test_background crash phase1 phase1CrashWithOpenVaultKeepsDurableData
if kill_9 crash crash.marker; then
  sleep 1
  run_test crash phase2 phase2CrashRestartReadsDurableDataWithoutDuplicates
else
  record crash phase2 FAIL "skipped because the kill step failed"
fi
reset_app

# --- device readback + release --------------------------------------------------------------------
reset_app
{
  echo "font_scale=$(adb_shell settings get system font_scale | tr -d '\r')"
  echo "accelerometer_rotation=$(adb_shell settings get system accelerometer_rotation | tr -d '\r')"
  echo "user_rotation=$(adb_shell settings get system user_rotation | tr -d '\r')"
  adb_shell cmd uimode night
  adb_shell wm size
  adb_shell wm density
  echo "yunji_processes=$(adb_shell ps -A 2>/dev/null | grep -E 'cn\.yunji|androidx\.test' | tr -d '\r' | tr '\n' ';')"
} > "$RAW/device-readback.txt" 2>&1
log "device readback written to raw/device-readback.txt"

log ""
if [ "$FAILS" -eq 0 ]; then
  log "RESULT: ALL PHASES PASS"
  exit 0
fi
log "RESULT: $FAILS phase(s) failed"
exit 1
