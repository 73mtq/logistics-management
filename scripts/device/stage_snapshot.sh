#!/usr/bin/env bash
set -euo pipefail

stage=${1:?stage}
mode=${2:?mode}
run=${3:?run}
stop=${4:-no}
adb=${ADB:-C:/platform-tools/adb.exe}
pkg=com.logisticsmanagement.app
root="work/evidence/platform-logistics-v2/${stage}/${run}/${mode}"

mkdir -p "${root}"

if [ "${stop}" = "yes" ]; then
  "${adb}" shell am force-stop ${pkg}
  sleep 1
fi

for file in logistics.db logistics.db-wal logistics.db-shm; do
  if "${adb}" shell run-as ${pkg} test -f "databases/${file}"; then
    "${adb}" exec-out run-as ${pkg} cat "databases/${file}" > "${root}/${file}"
  fi
done

for file in settings.xml encrypted_credentials.xml webview_profiles.xml work_schedules.xml notification_events.xml; do
  if "${adb}" shell run-as ${pkg} test -f "shared_prefs/${file}"; then
    "${adb}" exec-out run-as ${pkg} cat "shared_prefs/${file}" > "${root}/${file}"
  fi
done

"${adb}" shell run-as ${pkg} ls shared_prefs | tr -d '\r' > "${root}/shared-prefs-list.txt"
"${adb}" shell run-as ${pkg} ls app_webview | tr -d '\r' > "${root}/webview-dirs.txt"
"${adb}" shell run-as ${pkg} ls -l app_webview | tr -d '\r' > "${root}/webview-dirs-long.txt"
"${adb}" shell run-as ${pkg} ls files/native-entry-evidence | tr -d '\r' > "${root}/native-evidence.txt"
"${adb}" shell run-as ${pkg} sh -c "du -s files/native-entry-evidence/*" | tr -d '\r' > "${root}/native-evidence-size.txt" || true
: > "${root}/native-evidence-files.txt"
if [ -s "${root}/native-evidence.txt" ]; then
  while IFS= read -r name; do
    count=$("${adb}" shell run-as ${pkg} ls "files/native-entry-evidence/${name}" < /dev/null | wc -l)
    echo "${name} ${count}" >> "${root}/native-evidence-files.txt"
  done < "${root}/native-evidence.txt"
fi
"${adb}" shell run-as ${pkg} ls files/native-sync-progress | tr -d '\r' > "${root}/native-progress.txt" || true
"${adb}" shell run-as ${pkg} ls -l files/native-sync-progress | tr -d '\r' > "${root}/native-progress-long.txt" || true

"${adb}" shell settings get secure enabled_accessibility_services | tr -d '\r' > "${root}/enabled-accessibility-services.txt"
"${adb}" shell settings get secure accessibility_enabled | tr -d '\r' > "${root}/accessibility-enabled.txt"
"${adb}" shell settings get system touch_exploration_enabled | tr -d '\r' > "${root}/touch-exploration-enabled.txt"
"${adb}" shell dumpsys accessibility > "${root}/accessibility.txt"
"${adb}" shell dumpsys jobscheduler | grep -E "com\.logisticsmanagement|AccountSyncWorker|Job [0-9]+" > "${root}/jobscheduler.txt" || true
"${adb}" shell dumpsys package ${pkg} > "${root}/package.txt"
"${adb}" shell pm path ${pkg} | tr -d '\r' > "${root}/apk-path.txt"
apk=$(sed 's/^package://' "${root}/apk-path.txt" | head -1)
"${adb}" shell sha256sum "${apk}" | tee "${root}/apk-sha256.txt"
"${adb}" shell stat -c %s "${apk}" | tee "${root}/apk-size.txt"

cp "${root}/logistics.db" "${root}/consistent.db" 2>/dev/null || true
if [ -f "${root}/logistics.db-wal" ]; then cp "${root}/logistics.db-wal" "${root}/consistent.db-wal"; fi
if [ -f "${root}/logistics.db-shm" ]; then cp "${root}/logistics.db-shm" "${root}/consistent.db-shm"; fi
sqlite3 "${root}/consistent.db" "PRAGMA wal_checkpoint(TRUNCATE);" > /dev/null 2>&1 || true
sqlite3 "${root}/consistent.db" "PRAGMA integrity_check;" > "${root}/integrity-check.txt"
sqlite3 "${root}/consistent.db" "PRAGMA user_version;" > "${root}/user-version.txt"

echo "snapshot stage=${stage} mode=${mode} run=${run} dir=${root}"
