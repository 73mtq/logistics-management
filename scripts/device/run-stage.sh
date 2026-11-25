#!/usr/bin/env bash
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/device-lib.sh"

stage=${1:-s1}

run_s1() {
  local stamp
  stamp=$(date +%Y%m%d-%H%M%S)
  local logs="$LOG_ROOT/S1"
  local evidence="$EVIDENCE_ROOT/S1/$stamp"
  mkdir -p "$logs" "$evidence"
  require_device
  settings_save "$logs/settings-before-$stamp.txt"
  trap 'settings_restore "$logs/settings-before-$stamp.txt"' EXIT
  adb_shell svc power stayon true >/dev/null
  adb_shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  adb_shell wm dismiss-keyguard >/dev/null 2>&1 || true
  install_apks | tee "$logs/install-$stamp.txt"
  enable_accessibility
  db_copy "$evidence/db-before" | tee "$logs/db-before-$stamp.txt"
  adb_shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "$DOUYIN/.splash.SplashActivity" >/dev/null
  wait_foreground "$DOUYIN" 20
  local round
  for round in 1 2; do
    run_round "$round" "$logs/round-$round-$stamp.log" || true
    db_copy "$evidence/db-after-$round" | tee "$logs/db-after-$round-$stamp.txt"
    pull_evidence "$evidence/round-$round"
  done
  python "$ROOT/scripts/device/db_audit.py" "$evidence/db-before" "$evidence/db-after-1" "$evidence/db-after-2" | tee "$evidence/db-audit.txt"
}

case "$stage" in
  s1) run_s1 ;;
  *) echo "未知阶段：$stage" >&2; exit 2 ;;
esac
