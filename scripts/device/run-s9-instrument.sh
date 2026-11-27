#!/usr/bin/env bash
set -euo pipefail

run=${1:?run}
class=${2:?class}
expected=${3:?expected}
shift 3
adb=${ADB:-C:/platform-tools/adb.exe}
log="work/logs/platform-logistics-v2/${run}.log"

if [ -e "${log}" ]; then
  echo "日志已存在：${log}"
  exit 1
fi

args=(-s 95f79212 shell am instrument -w -r -e class "${class}" -e runName "${run}")
for item in "$@"; do
  args+=(-e "${item%%=*}" "${item#*=}")
done
args+=(com.logisticsmanagement.app.test/androidx.test.runner.AndroidJUnitRunner)

"${adb}" "${args[@]}" > "${log}" 2>&1 || true

if grep -q "FAILURES!!!" "${log}" || grep -q "INSTRUMENTATION_FAILED" "${log}"; then
  echo "失败 ${class}，日志 ${log}"
  tail -40 "${log}"
  exit 1
fi
if grep -q "OK (${expected} test)" "${log}" || grep -q "OK (${expected} tests)" "${log}"; then
  echo "通过 ${class} 预期 ${expected} 项，日志 ${log}"
  exit 0
fi
echo "失败 ${class}，未找到 OK (${expected} tests)，日志 ${log}"
tail -40 "${log}"
exit 1
