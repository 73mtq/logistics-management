#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
ADB=${ADB:-D:/Android/Sdk/platform-tools/adb.exe}
export JAVA_HOME=${JAVA_HOME:-D:/Program Files/Android/Android Studio/jbr}
export ANDROID_HOME=${ANDROID_HOME:-D:/Android/Sdk}
PKG=com.logisticsmanagement.app
TEST_PKG=com.logisticsmanagement.app.test
SERVICE=$PKG/$PKG.platform.DouyinCaptureService
DOUYIN=com.ss.android.ugc.aweme
LOG_ROOT=$ROOT/work/logs/platform-logistics-v2
EVIDENCE_ROOT=$ROOT/work/evidence/platform-logistics-v2

adb_shell() { "$ADB" shell "$@"; }

require_device() {
  local state
  state=$("$ADB" get-state 2>/dev/null || true)
  if [[ "$state" != "device" ]]; then
    echo "没有可用的 adb 设备" >&2
    exit 2
  fi
  adb_shell getprop ro.product.model | tr -d '\r'
  adb_shell getprop ro.build.version.release | tr -d '\r'
}

settings_save() {
  local file=$1
  {
    echo "enabled_accessibility_services=$(adb_shell settings get secure enabled_accessibility_services | tr -d '\r')"
    echo "accessibility_enabled=$(adb_shell settings get secure accessibility_enabled | tr -d '\r')"
    echo "touch_exploration_enabled=$(adb_shell settings get secure touch_exploration_enabled | tr -d '\r')"
    echo "screen_off_timeout=$(adb_shell settings get system screen_off_timeout | tr -d '\r')"
    echo "stay_on_while_plugged_in=$(adb_shell settings get global stay_on_while_plugged_in | tr -d '\r')"
  } > "$file"
}

settings_restore() {
  local file=$1
  [[ -f "$file" ]] || return 0
  local key value
  while IFS='=' read -r key value; do
    [[ -n "$key" && "$value" != "null" ]] || continue
    case "$key" in
      enabled_accessibility_services) adb_shell settings put secure enabled_accessibility_services "$value" ;;
      accessibility_enabled) adb_shell settings put secure accessibility_enabled "$value" ;;
      touch_exploration_enabled) adb_shell settings put secure touch_exploration_enabled "$value" ;;
      screen_off_timeout) adb_shell settings put system screen_off_timeout "$value" ;;
      stay_on_while_plugged_in) adb_shell settings put global stay_on_while_plugged_in "$value" ;;
    esac
  done < "$file"
}

enable_accessibility() {
  local current
  current=$(adb_shell settings get secure enabled_accessibility_services | tr -d '\r')
  [[ "$current" == "null" ]] && current=""
  case ":$current:" in
    *":$SERVICE:"*) ;;
    *) current="${current:+$current:}$SERVICE" ;;
  esac
  adb_shell settings put secure enabled_accessibility_services "$current"
  adb_shell settings put secure accessibility_enabled 1
}

foreground() {
  adb_shell dumpsys activity activities | grep topResumedActivity | head -1 | sed -n 's/.* \([A-Za-z0-9_.]*\)\/.*/\1/p'
}

wait_foreground() {
  local target=$1
  local timeout=${2:-20}
  local deadline=$((SECONDS + timeout))
  local current=""
  while (( SECONDS < deadline )); do
    current=$(foreground)
    if [[ "$current" == "$target" ]]; then return 0; fi
    sleep 0.5
  done
  echo "前台不是 $target：${current:-未知}" >&2
  return 1
}

install_apks() {
  local apk=$ROOT/app/build/outputs/apk/debug/app-debug.apk
  local test=$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  [[ -f "$apk" && -f "$test" ]] || { echo "缺少 APK" >&2; exit 2; }
  sha256sum "$apk" "$test"
  "$ADB" install -r "$apk"
  "$ADB" install -r "$test"
  adb_shell dumpsys package "$PKG" | grep -E 'versionName|versionCode|lastUpdateTime' | head -4
}

db_copy() {
  local out=$1
  mkdir -p "$out"
  adb_shell am force-stop "$PKG" >/dev/null 2>&1 || true
  local name
  for name in logistics.db logistics.db-wal logistics.db-shm; do
    "$ADB" exec-out run-as "$PKG" cat "databases/$name" > "$out/$name" 2>/dev/null || true
  done
  ls -la "$out"
}

pull_evidence() {
  local dest=$1
  mkdir -p "$dest"
  "$ADB" pull "/sdcard/Android/data/$PKG/files/s1" "$dest/" 2>&1 | tail -2 || true
}

run_round() {
  local round=$1 log=$2
  local extra=()
  if [[ -n "${TARGET_TRACKING:-}" ]]; then extra+=(-e trackingNumber "$TARGET_TRACKING"); fi
  if [[ -n "${CAPTURE_TIMEOUT:-}" ]]; then extra+=(-e captureTimeoutMillis "$CAPTURE_TIMEOUT"); fi
  "$ADB" shell am instrument -w -r \
    -e class com.logisticsmanagement.app.DouyinLogisticsAutomationTest#importLogisticsAutomatically \
    -e round "$round" \
    -e targetOrderId "${TARGET_ORDER_ID:?需要设置 TARGET_ORDER_ID}" \
    "${extra[@]+"${extra[@]}"}" \
    "$TEST_PKG/androidx.test.runner.AndroidJUnitRunner" | tee "$log"
}
