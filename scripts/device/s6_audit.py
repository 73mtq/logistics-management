import argparse
import hashlib
import json
import shutil
import sqlite3
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

TABLES = ["Account", "orders", "Shipment", "SourceRef", "TrackingEvent", "ShipmentAlias", "TrackingQueue", "PendingProfileCleanup"]
PREFS = ["settings.xml", "encrypted_credentials.xml", "webview_profiles.xml", "work_schedules.xml"]
PKG = "com.logisticsmanagement.app"
DEFAULT_ADB = "D:/Android/Sdk/platform-tools/adb.exe"
DEFAULT_SERIAL = "95f79212"


def adb_text(adb, serial, *arguments):
    result = subprocess.run([adb, "-s", serial, *arguments], capture_output=True)
    if result.returncode != 0:
        raise SystemExit(f"adb 失败：{' '.join(arguments)}：{result.stderr.decode('utf-8', 'replace')}")
    return result.stdout.decode("utf-8", "replace")


def adb_bytes(adb, serial, *arguments):
    result = subprocess.run([adb, "-s", serial, *arguments], capture_output=True)
    return result.returncode, result.stdout


def capture(args):
    state = Path(args.state)
    (state / "db").mkdir(parents=True, exist_ok=True)
    (state / "prefs").mkdir(parents=True, exist_ok=True)
    if getattr(args, "force_stop", True):
        adb_text(args.adb, args.serial, "shell", "am", "force-stop", PKG)
    for name in ["logistics.db", "logistics.db-wal", "logistics.db-shm"]:
        code, data = adb_bytes(args.adb, args.serial, "exec-out", "run-as", PKG, "cat", f"databases/{name}")
        if code != 0:
            if name == "logistics.db":
                raise SystemExit("缺少生产数据库副本")
            continue
        (state / "db" / name).write_bytes(data)
    for name in PREFS:
        code, data = adb_bytes(args.adb, args.serial, "exec-out", "run-as", PKG, "cat", f"shared_prefs/{name}")
        if code != 0:
            raise SystemExit(f"缺少配置副本：{name}")
        (state / "prefs" / name).write_bytes(data)
    for remote, local in [("app_webview", "webview.txt"), ("files/native-sync-progress", "native-progress.txt"), ("files/native-entry-evidence", "native-evidence.txt")]:
        code, data = adb_bytes(args.adb, args.serial, "exec-out", "run-as", PKG, "ls", "-1", remote)
        (state / local).write_bytes(data if code == 0 else b"")
    (state / "jobscheduler.txt").write_text(adb_text(args.adb, args.serial, "shell", "dumpsys", "jobscheduler"), encoding="utf-8")
    (state / "package.txt").write_text(adb_text(args.adb, args.serial, "shell", "dumpsys", "package", PKG), encoding="utf-8")
    (state / "package-path.txt").write_text(adb_text(args.adb, args.serial, "shell", "pm", "path", PKG).strip(), encoding="utf-8")
    for key, name in [("enabled_accessibility_services", "accessibility.txt"), ("accessibility_enabled", "accessibility-enabled.txt")]:
        (state / name).write_text(adb_text(args.adb, args.serial, "shell", "settings", "get", "secure", key).strip(), encoding="utf-8")
    snapshot(args)
    print(f"已捕获状态：{state}")


def checkpoint(state):
    work = state / "snapshot-work"
    if work.exists():
        shutil.rmtree(work)
    work.mkdir()
    for name in ["logistics.db", "logistics.db-wal", "logistics.db-shm"]:
        source = state / "db" / name
        if source.exists():
            shutil.copy2(source, work / name)
    connection = sqlite3.connect(str(work / "logistics.db"))
    connection.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
    version = connection.execute("PRAGMA user_version").fetchone()[0]
    connection.close()
    if integrity != "ok":
        raise SystemExit(f"数据库完整性检查失败：{integrity}")
    shutil.copy2(work / "logistics.db", state / "snapshot.db")
    shutil.rmtree(work)
    return version


def snapshot(args):
    state = Path(args.state)
    version = checkpoint(state)
    database = state / "snapshot.db"
    connection = sqlite3.connect(database.resolve().as_uri() + "?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    tables = {}
    for table in TABLES:
        rows = [dict(row) for row in connection.execute(f'SELECT * FROM "{table}"')]
        rows.sort(key=lambda row: str(row.get("id") or row.get("sourceId") or row.get("accountId")))
        tables[table] = {"count": len(rows), "rows": rows}
    connection.close()
    prefs = {name: parse_pref_map(state / "prefs" / name) for name in PREFS}
    settings = json.loads(prefs["settings.xml"]["settings"])
    encrypted = {name: hashlib.sha256(value.encode("utf-8")).hexdigest() for name, value in prefs["encrypted_credentials.xml"].items()}
    webview = {"pending": prefs["webview_profiles.xml"].get("pending_deletions", [])}
    work = prefs["work_schedules.xml"]
    profiles = sorted(name for name in (item.replace(chr(92) + " ", " ") for item in listing(state / "webview.txt")) if name.startswith("Profile "))
    progress = sorted(name for name in listing(state / "native-progress.txt") if name.endswith(".enc") or name.endswith(".enc.bak"))
    evidence = sorted(listing(state / "native-evidence.txt"))
    payload = {
        "userVersion": version,
        "tables": tables,
        "prefs": {"settings": settings, "encrypted": {"keys": encrypted}, "webview": webview, "workSchedules": work},
        "profiles": profiles,
        "nativeProgress": progress,
        "nativeEvidence": evidence,
        "accessibility": (state / "accessibility.txt").read_text(encoding="utf-8") if (state / "accessibility.txt").exists() else "",
        "packagePath": (state / "package-path.txt").read_text(encoding="utf-8") if (state / "package-path.txt").exists() else "",
    }
    (state / "snapshot.json").write_text(json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"已生成快照：{state / 'snapshot.json'}")


def listing(path):
    if not path.exists():
        return []
    return [line.strip() for line in path.read_text(encoding="utf-8", errors="replace").splitlines() if line.strip()]


def parse_pref_map(path):
    root = ET.parse(path).getroot()
    values = {}
    for element in root:
        name = element.get("name")
        if not name:
            continue
        tag = element.tag
        if tag == "string":
            values[name] = element.text or ""
        elif tag == "int":
            values[name] = int(element.get("value"))
        elif tag == "boolean":
            values[name] = element.get("value") == "true"
        elif tag == "set":
            values[name] = sorted(child.text or "" for child in element if child.tag == "string")
        elif tag == "float":
            values[name] = float(element.get("value"))
        elif tag == "long":
            values[name] = int(element.get("value"))
    return values


def load(state):
    return json.loads((Path(state) / "snapshot.json").read_text(encoding="utf-8"))


def compare(args):
    before = load(args.before)
    after = load(args.after)
    after_start = load(args.after_start) if args.after_start else None
    failures = []

    def fail(message):
        failures.append(message)
        print(f"差异：{message}")

    for table in TABLES:
        if table in ("Account", "TrackingQueue"):
            continue
        left = before["tables"][table]["rows"]
        right = after["tables"][table]["rows"]
        if left != right:
            fail(f"业务表 {table} 行不一致：before {len(left)}，after {len(right)}")

    before_accounts = {row["id"]: row for row in before["tables"]["Account"]["rows"]}
    after_accounts = {row["id"]: row for row in after["tables"]["Account"]["rows"]}
    if set(before_accounts) != set(after_accounts):
        fail("Account 表的账号集合发生变化")
    for account_id in before_accounts:
        if account_id == args.removed_account:
            continue
        if before_accounts[account_id] != after_accounts.get(account_id):
            fail(f"非目标账号发生变化：{account_id}")
    target = after_accounts.get(args.removed_account)
    original = before_accounts.get(args.removed_account)
    if original is None:
        fail("移除前快照缺少目标账号")
    elif target is None:
        fail("移除后快照缺少目标账号行")
    else:
        if target.get("enabled") != 0:
            fail("目标账号 enabled 不为 0")
        if target.get("deleted") != 1:
            fail("目标账号 deleted 不为 1")
        if target.get("state") != "REMOVED":
            fail("目标账号 state 不为 REMOVED")
        for key, value in original.items():
            if key in ("enabled", "deleted", "state"):
                continue
            if target.get(key) != value:
                fail(f"目标账号字段发生变化：{key}")

    before_queue = [row for row in before["tables"]["TrackingQueue"]["rows"] if row.get("accountId") != args.removed_account]
    after_queue = [row for row in after["tables"]["TrackingQueue"]["rows"] if row.get("accountId") != args.removed_account]
    if before_queue != after_queue:
        fail("其他账号的查询项发生变化")
    if any(row.get("accountId") == args.removed_account for row in after["tables"]["TrackingQueue"]["rows"]):
        fail("目标账号仍有查询项")

    if before["prefs"]["settings"] != after["prefs"]["settings"]:
        fail("settings 语义值不一致")
    prefix = f"account.{args.removed_account}."
    expected_keys = {name: value for name, value in before["prefs"]["encrypted"]["keys"].items() if not name.startswith(prefix)}
    if expected_keys != after["prefs"]["encrypted"]["keys"]:
        fail("凭据键集合或剩余密文不一致")
    if any(name.startswith(prefix) for name in after["prefs"]["encrypted"]["keys"]):
        fail("目标账号凭据键未清空")
    if before["prefs"]["webview"]["pending"]:
        fail("移除前待删除 profile 不为空")
    if not set(after["prefs"]["webview"]["pending"]).issubset({args.removed_profile}):
        fail("移除后待删除 profile 不是目标 profile")
    if after_start is not None and after_start["prefs"]["webview"]["pending"]:
        fail("启动后待删除 profile 未清空")
    if before["prefs"]["workSchedules"] != after["prefs"]["workSchedules"]:
        fail("work_schedules 不一致")

    before_profiles = len(before["profiles"])
    after_profiles = len(after["profiles"])
    expected_after_profiles = before_profiles - 1
    if after_profiles not in (before_profiles, expected_after_profiles):
        fail(f"profile 目录数量异常：before {before_profiles}，after {after_profiles}")
    if after_profiles == before_profiles and args.removed_profile not in after["prefs"]["webview"]["pending"]:
        fail("profile 目录仍存在，但待删除列表缺少目标 profile")
    if after_start is not None and len(after_start["profiles"]) != expected_after_profiles:
        fail(f"启动后 profile 目录数量不是 {expected_after_profiles}")

    def without_account(items, account_id):
        return [item for item in items if item not in (f"{account_id}.enc", f"{account_id}.enc.bak")]

    if without_account(before["nativeProgress"], args.removed_account) != after["nativeProgress"]:
        fail("原生进度文件集合发生变化")
    if after_start is not None and without_account(before["nativeProgress"], args.removed_account) != after_start["nativeProgress"]:
        fail("启动后原生进度文件集合发生变化")

    result = {
        "ok": not failures,
        "failures": failures,
        "removedAccount": args.removed_account,
        "removedProfile": args.removed_profile,
        "beforeAccounts": len(before_accounts),
        "afterAccounts": len(after_accounts),
        "beforeProfiles": before_profiles,
        "afterProfiles": after_profiles,
        "afterStartProfiles": len(after_start["profiles"]) if after_start else None,
        "beforeTableCounts": {table: before["tables"][table]["count"] for table in TABLES},
        "afterTableCounts": {table: after["tables"][table]["count"] for table in TABLES},
        "beforeNativeProgress": before["nativeProgress"],
        "afterNativeProgress": after["nativeProgress"],
    }
    Path(args.after, "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=1))
    return 0 if not failures else 1


def parse_args():
    parser = argparse.ArgumentParser(description="S6 数据保留与账号移除副本审计")
    sub = parser.add_subparsers(dest="command", required=True)
    capture_parser = sub.add_parser("capture")
    capture_parser.add_argument("--state", required=True)
    capture_parser.add_argument("--adb", default=DEFAULT_ADB)
    capture_parser.add_argument("--serial", default=DEFAULT_SERIAL)
    snapshot_parser = sub.add_parser("snapshot")
    snapshot_parser.add_argument("--state", required=True)
    compare_parser = sub.add_parser("compare")
    compare_parser.add_argument("--before", required=True)
    compare_parser.add_argument("--after", required=True)
    compare_parser.add_argument("--after-start")
    compare_parser.add_argument("--removed-account", required=True)
    compare_parser.add_argument("--removed-profile", required=True)
    return parser.parse_args()


def main():
    args = parse_args()
    if args.command == "capture":
        capture(args)
        return 0
    if args.command == "snapshot":
        snapshot(args)
        return 0
    return compare(args)


if __name__ == "__main__":
    sys.exit(main())
