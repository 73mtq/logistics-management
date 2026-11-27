import argparse
import json
from pathlib import Path
from types import SimpleNamespace

import s6_audit


def capture(args):
    s6_audit.capture(SimpleNamespace(state=args.state, adb=args.adb, serial=args.serial,
                                     force_stop=not getattr(args, "no_force_stop", False)))


def snapshot(args):
    s6_audit.snapshot(SimpleNamespace(state=args.state, adb=args.adb, serial=args.serial))


def account_sync_jobs(path):
    lines = Path(path).read_text(encoding="utf-8", errors="replace").splitlines()
    return [line.strip() for line in lines if "JOB " in line and "AccountSyncWorker" in line]


def compare(args):
    base = SimpleNamespace(before=args.before, after=args.after, after_start=args.after_start,
                           removed_account=args.removed_account, removed_profile=args.removed_profile)
    code = s6_audit.compare(base)
    before = s6_audit.load(args.before)
    after = s6_audit.load(args.after)
    after_start = s6_audit.load(args.after_start) if args.after_start else None
    failures = []
    if before["nativeEvidence"] != after["nativeEvidence"]:
        failures.append("移除临时账号后原生证据目录发生变化")
    if after_start is not None and before["nativeEvidence"] != after_start["nativeEvidence"]:
        failures.append("重启后原生证据目录发生变化")
    if after_start is not None and after["nativeEvidence"] != after_start["nativeEvidence"]:
        failures.append("移除后与重启后的原生证据目录不一致")
    before_jobs = account_sync_jobs(Path(args.before) / "jobscheduler.txt")
    after_jobs = account_sync_jobs(Path(args.after) / "jobscheduler.txt")
    started_jobs = account_sync_jobs(Path(args.after_start) / "jobscheduler.txt") if after_start else []
    summary = {
        "ok": code == 0 and not failures,
        "failures": failures,
        "s6CompareExit": code,
        "removedAccount": args.removed_account,
        "removedProfile": args.removed_profile,
        "nativeEvidenceBefore": before["nativeEvidence"],
        "nativeEvidenceAfter": after["nativeEvidence"],
        "nativeEvidenceAfterStart": after_start["nativeEvidence"] if after_start else None,
        "accountSyncJobsBefore": before_jobs,
        "accountSyncJobsAfter": after_jobs,
        "accountSyncJobsAfterStart": started_jobs,
        "profilesBefore": len(before["profiles"]),
        "profilesAfter": len(after["profiles"]),
        "profilesAfterStart": len(after_start["profiles"]) if after_start else None,
        "pendingBefore": before["prefs"]["webview"]["pending"],
        "pendingAfter": after["prefs"]["webview"]["pending"],
        "pendingAfterStart": after_start["prefs"]["webview"]["pending"] if after_start else None,
    }
    Path(args.after, "s8-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=1))
    return 0 if summary["ok"] else 1


def parse_args():
    parser = argparse.ArgumentParser(description="S8 账号清理与后台任务副本审计")
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("capture", "snapshot"):
        item = sub.add_parser(name)
        item.add_argument("--state", required=True)
        item.add_argument("--adb", default=s6_audit.DEFAULT_ADB)
        item.add_argument("--serial", default=s6_audit.DEFAULT_SERIAL)
        if name == "capture":
            item.add_argument("--no-force-stop", action="store_true")
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
    raise SystemExit(main())
