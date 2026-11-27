import hashlib
import json
import os
import re
import sqlite3
import sys

root, run, mode = sys.argv[1], sys.argv[2], sys.argv[3]
tables = ["Account", "orders", "Shipment", "SourceRef", "TrackingEvent", "TrackingQueue", "ShipmentAlias", "PendingProfileCleanup"]


def read_text(name):
    path = os.path.join(root, name)
    if not os.path.isfile(path):
        return ""
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read()


def read_lines(name):
    return [line.strip() for line in read_text(name).splitlines() if line.strip()]


def table_payload(con, name):
    con.row_factory = sqlite3.Row
    cur = con.execute('SELECT * FROM "' + name + '" ORDER BY 1')
    rows = [dict(row) for row in cur.fetchall()]
    payload = json.dumps(rows, ensure_ascii=False, separators=(",", ":"), default=str)
    return rows, hashlib.sha256(payload.encode("utf-8")).hexdigest()


def prefs_keys():
    text = read_text("encrypted_credentials.xml")
    return sorted(re.findall(r'<string name="([^"]+)"', text))


def pref_map(name):
    text = read_text(name)
    result = {}
    for item in re.findall(r'<(string|boolean|int|long) name="([^"]+)">([^<]*)</\1>', text):
        result[item[1]] = item[2]
    return result


def pending_profiles():
    text = read_text("webview_profiles.xml")
    block = re.search(r'<set name="pending_deletions">(.*?)</set>', text, re.S)
    if not block:
        return []
    return sorted(re.findall(r'<string>(.*?)</string>', block.group(1)))


def profiles():
    return sorted(line for line in read_lines("webview-dirs.txt") if line.startswith("Profile "))


def evidence():
    names = read_lines("native-evidence.txt")
    sizes = {}
    for line in read_lines("native-evidence-size.txt"):
        parts = line.split()
        if len(parts) == 2:
            sizes[os.path.basename(parts[1])] = int(parts[0])
    counts = {}
    for line in read_lines("native-evidence-files.txt"):
        parts = line.split()
        if len(parts) == 2:
            counts[parts[0]] = int(parts[1])
    return [{"name": name, "kb": sizes.get(name), "files": counts.get(name)} for name in names]


def progress():
    return sorted(read_lines("native-progress.txt"))


def package():
    text = read_text("package.txt")
    version_name = re.search(r"versionName=([^\s]+)", text)
    version_code = re.search(r"versionCode=(\d+)", text)
    last_update = re.search(r"lastUpdateTime=([^\n]+)", text)
    sha = read_text("apk-sha256.txt").split()
    return {
        "versionName": version_name.group(1) if version_name else None,
        "versionCode": int(version_code.group(1)) if version_code else None,
        "lastUpdateTime": last_update.group(1).strip() if last_update else None,
        "apkSha256": sha[0] if sha else None,
    }


con = sqlite3.connect(os.path.join(root, "consistent.db"))
table_out = {}
for table in tables:
    rows, digest = table_payload(con, table)
    table_out[table] = {"count": len(rows), "hash": digest, "rows": rows}
con.close()

snapshot = {
    "mode": mode,
    "run": run,
    "package": package(),
    "tables": table_out,
    "prefs": {
        "credentialKeys": prefs_keys(),
        "pendingDeletions": pending_profiles(),
        "settings": pref_map("settings.xml"),
        "workSchedules": pref_map("work_schedules.xml"),
    },
    "profiles": profiles(),
    "nativeEvidence": evidence(),
    "nativeProgress": progress(),
    "accessibility": {
        "enabled": read_text("enabled-accessibility-services.txt").strip(),
        "accessibilityEnabled": read_text("accessibility-enabled.txt").strip(),
        "touchExploration": read_text("touch-exploration-enabled.txt").strip(),
    },
}

with open(os.path.join(root, "snapshot.json"), "w", encoding="utf-8") as f:
    json.dump(snapshot, f, ensure_ascii=False, indent=1)

print("snapshot written", os.path.join(root, "snapshot.json"))
