#!/usr/bin/env bash
# Task-8 / D02: two-phase real-process-termination matrix for the encrypted local backup.
#
# Each scenario is two `am instrument` runs:
#   phase 1: the test parks at a deliberately chosen point, writes files/task8-markers/<scenario>.marker,
#            then sleeps (its own 120 s watchdog).
#   host:    waits for the marker, appends `forceStoppedAt=<epoch>` to the marker, then
#            `am force-stop cn.yunji.journal` = ActivityManager SIGKILL, no lifecycle callbacks.
#   phase 2: a brand-new process (asserted via pid in the marker) checks what survived and that the
#            production cold-start recovery repaired it. Phase 2 skips itself when the marker has no
#            `forceStoppedAt`, so a plain full-suite run can never fake a cold start.
#
# Semantics: force-stop is a process kill, not a power cut. Filesystem writes already issued to the
# kernel may still land; nothing here proves fsync/durability across sudden power loss.
#
# Usage (Git Bash / WSL, from the repository root):
#   ADB=/d/Android/Sdk/platform-tools/adb.exe SERIAL=emulator-5556 ./docs/verification/2026-10-22-D02-process-matrix/run-task8-d02-matrix.sh
set -u

ADB="${ADB:-adb}"
SERIAL="${SERIAL:-emulator-5556}"
APP_ID="cn.yunji.journal"
RUNNER="cn.yunji.journal.test/androidx.test.runner.AndroidJUnitRunner"
CLASS="cn.yunji.journal.D02ProcessMatrixTest"
OUT="$(cd "$(dirname "$0")" && pwd)"
MARKERS="files/task8-markers"
POLL_TIMEOUT="${POLL_TIMEOUT:-180}"
PASS=0
FAIL=0
declare -a RESULTS=()

say() { printf '%s\n' "$*"; }
adb_() { "$ADB" -s "$SERIAL" "$@"; }
marker_cat() { adb_ shell "run-as $APP_ID cat $MARKERS/$1.marker" 2>/dev/null; }
marker_clear() { adb_ shell "run-as $APP_ID rm -f $MARKERS/$1.marker" >/dev/null 2>&1; }
device_readback() {
  say "device: $("$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')  " \
      "font_scale=$("$ADB" -s "$SERIAL" shell settings get system font_scale | tr -d '\r')  " \
      "night=$("$ADB" -s "$SERIAL" shell settings get secure ui_night_mode | tr -d '\r')  " \
      "rotation=$("$ADB" -s "$SERIAL" shell settings get system user_rotation | tr -d '\r')"
}

# phase1 <scenario> <method>: start the blocking phase and wait for its marker.
phase1() {
  local scenario="$1" method="$2" pid i
  local log="$OUT/$scenario-phase1.log"
  marker_clear "$scenario"
  adb_ shell am force-stop "$APP_ID"
  adb_ logcat -c
  say "== $scenario phase1: $method"
  adb_ shell "am instrument -w -r -e class $CLASS#$method -e d02Phase $scenario $RUNNER" >"$log" 2>&1 &
  pid=$!
  for ((i = 0; i < POLL_TIMEOUT; i++)); do
    if marker_cat "$scenario" | grep -q 'phase='; then break; fi
    sleep 1
  done
  if ! marker_cat "$scenario" | grep -q 'phase='; then
    say "FAIL $scenario phase1: marker never appeared (see $log)"
    kill "$pid" >/dev/null 2>&1
    FAIL=$((FAIL + 1)); RESULTS+=("FAIL  $scenario phase1  marker-timeout")
    return 1
  fi
  say "   marker: $(marker_cat "$scenario" | tr '\n' ' ')"
  say "   force-stopping $APP_ID (SIGKILL)"
  adb_ shell am force-stop "$APP_ID"
  local now
  now=$(date +%s)
  adb_ shell "run-as $APP_ID sh -c 'echo forceStoppedAt=$now >> $MARKERS/$scenario.marker'" >/dev/null 2>&1
  kill "$pid" >/dev/null 2>&1
  wait "$pid" >/dev/null 2>&1
  say "   marker after kill: $(marker_cat "$scenario" | tr '\n' ' ')"
}

# phase2 <scenario> <method>: new process, must pass.
phase2() {
  local scenario="$1" method="$2"
  local log="$OUT/$scenario-phase2.log"
  say "== $scenario phase2: $method"
  adb_ shell "am instrument -w -r -e class $CLASS#$method -e d02Phase $scenario $RUNNER" >"$log" 2>&1
  if grep -qE 'OK \(1 test\)' "$log"; then
    say "PASS $scenario phase2"
    PASS=$((PASS + 1)); RESULTS+=("PASS  $scenario phase2")
  else
    say "FAIL $scenario phase2 (see $log)"
    sed -n '/FAILURES!!!\|AssertionError\|java\./p' "$log" | head -n 20
    FAIL=$((FAIL + 1)); RESULTS+=("FAIL  $scenario phase2")
  fi
}

scenario() { # <name> <phase1-method> <phase2-method>
  local name="$1"
  phase1 "$name" "$2" || return
  phase2 "$name" "$3"
}

say "== device before =="; device_readback
adb_ shell am force-stop "$APP_ID"

scenario "export-selfcheck" "phase1ExportSelfCheckBlocked" "phase2ExportSelfCheckRecovered"
scenario "export-write" "phase1ExportDestinationWriteBlocked" "phase2TruncatedDestinationIsRejectedAndRetryWorks"
scenario "verify" "phase1VerifyBlocked" "phase2VerifyKilledProcessKeepsArchiveAndRetryVerifies"
scenario "purge" "phase1PurgeStarted" "phase2PurgingIsNotCompleteAndResumeFinishesIt"
scenario "orphan" "phase1OrphanArtifactsCreated" "phase2ColdStartCleansPrivatePlaintextWorkspaces"

say "== device after =="; device_readback
adb_ shell am force-stop "$APP_ID"

say ""
say "== PASS/FAIL =="
printf '%s\n' "${RESULTS[@]}"
say "TOTAL: pass=$PASS fail=$FAIL"
[ "$FAIL" -eq 0 ]
