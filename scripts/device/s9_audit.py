import json
import os
import sys


def load(directory):
    path = os.path.join(directory, "snapshot.json")
    if not os.path.isfile(path):
        raise SystemExit("missing snapshot: " + path)
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def account_map(snapshot):
    return {row["id"]: row for row in snapshot["tables"]["Account"]["rows"]}


def diff_lines(base, target, label):
    removed = sorted(set(base) - set(target))
    added = sorted(set(target) - set(base))
    print("  " + label + ": removed=" + str(removed) + " added=" + str(added))


def main():
    if len(sys.argv) < 4 or sys.argv[1] != "compare":
        raise SystemExit("usage: s9_audit.py compare <base-dir> <target-dir> [--account <id>]")
    base_dir, target_dir = sys.argv[2], sys.argv[3]
    account = None
    if "--account" in sys.argv:
        account = sys.argv[sys.argv.index("--account") + 1]
    base = load(base_dir)
    target = load(target_dir)
    print("base  " + base_dir + " (" + base["mode"] + ") package=" + str(base["package"]))
    print("after " + target_dir + " (" + target["mode"] + ") package=" + str(target["package"]))

    print("tables:")
    for name, before in base["tables"].items():
        after = target["tables"][name]
        if name != "Account":
            status = "equal" if before["hash"] == after["hash"] else "different"
            print("  %-22s %5d -> %5d  %s" % (name, before["count"], after["count"], status))
    before_accounts = account_map(base)
    after_accounts = account_map(target)
    removed_ids = sorted(set(before_accounts) - set(after_accounts))
    added_ids = sorted(set(after_accounts) - set(before_accounts))
    print("  Account rows removed=" + str(removed_ids) + " added=" + str(added_ids))
    for account_id in sorted(set(before_accounts) & set(after_accounts)):
        old, new = before_accounts[account_id], after_accounts[account_id]
        changed = {key: (old.get(key), new.get(key)) for key in new if old.get(key) != new.get(key)}
        if changed:
            print("  Account " + account_id + " changed " + str(changed))
    if account is not None:
        row = after_accounts.get(account)
        if row is None:
            print("  target " + account + " -> missing")
        else:
            summary = {key: row.get(key) for key in ("platform", "enabled", "deleted", "state", "profileName", "error", "lastSyncedAt")}
            print("  target " + account + " -> " + str(summary))

    print("prefs:")
    diff_lines(base["prefs"]["credentialKeys"], target["prefs"]["credentialKeys"], "credentialKeys")
    diff_lines(base["prefs"]["pendingDeletions"], target["prefs"]["pendingDeletions"], "pendingDeletions")
    for name in ("settings", "workSchedules"):
        old, new = base["prefs"][name], target["prefs"][name]
        changed = {key: (old.get(key), new.get(key)) for key in sorted(set(old) | set(new)) if old.get(key) != new.get(key)}
        print("  " + name + " changed=" + str(changed))

    print("files:")
    diff_lines([item["name"] for item in base["nativeEvidence"]], [item["name"] for item in target["nativeEvidence"]], "nativeEvidence")
    diff_lines(base["nativeProgress"], target["nativeProgress"], "nativeProgress")
    diff_lines(base["profiles"], target["profiles"], "profiles")
    print("  accessibility before=" + str(base["accessibility"]))
    print("  accessibility after =" + str(target["accessibility"]))
    print("  nativeEvidence after=" + str([{"name": item["name"], "files": item["files"], "kb": item["kb"]} for item in target["nativeEvidence"]]))


main()
