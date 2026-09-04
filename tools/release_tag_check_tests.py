#!/usr/bin/env python3
"""Self-tests for tools/release_tag_check.py (rules: docs/release.md). No third-party dependencies.

Runs anywhere with Python 3.8+ (CI: `python3 tools/release_tag_check_tests.py`).
Exit code 0 = all cases pass. Prints one line per case.
"""
import datetime
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import release_tag_check as v  # noqa: E402

AUG23 = datetime.datetime(2026, 8, 23, 2, 0, 0, tzinfo=datetime.timezone.utc)
SEP01 = datetime.datetime(2026, 9, 1, 2, 0, 0, tzinfo=datetime.timezone.utc)


def rec(name, date, code=None, body=None):
    msg = body if body is not None else (f"Pocket-MCP {name}\n\nVersionCode: {code}" if code else "")
    return {"name": name, "date": v.parse_iso(date), "body": msg}


OFFICIALS15 = [
    rec("v26.08.1", "2026-08-01T02:00:00Z", 26080101),
    rec("v26.08.2", "2026-08-05T02:00:00Z", 26080501),
    rec("v26.08.3", "2026-08-10T02:00:00Z", 26081001),
    rec("v26.08.4", "2026-08-15T02:00:00Z", 26081501),
    rec("v26.08.5", "2026-08-20T02:00:00Z", 26082001),
]
RC1 = rec("v26.08.6-rc1", "2026-08-20T02:00:00Z", 26082002)
RC2 = rec("v26.08.6-rc2", "2026-08-21T02:00:00Z", 26082101)
OFF6 = rec("v26.08.6", "2026-08-22T02:00:00Z", 26082201)

PASS = 0
FAIL = 0


def check(cond, label):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"PASS: {label}")
    else:
        FAIL += 1
        print(f"FAIL: {label}")


def run(tag, records, now=AUG23):
    return v.validate(tag, records, now)


def errs(r):
    return " ".join(r["errors"])


# --- format ---
for bad in ["26.08.0", "26.1.2", "26.13.1", "26.08.6-alpha1", "26.08.6-rc0", "26.08.6-rc02", "", "v26.08.6.1"]:
    check(not run(bad, [])["valid"], f"format rejects '{bad}'")
r = run("26.08.1", [])
check(r["valid"] and r["name"] == "26.08.1", "format accepts plain official 26.08.1")
r = run("v26.08.1-rc1", [])
check(r["valid"] and r["name"] == "26.08.1-rc1", "format accepts rc with optional leading v (name normalized)")

# --- month: name month == creation month (candidates closing in-month is a corollary) ---
r = run("26.08.1", [], now=SEP01)
check(not r["valid"] and "month" in errs(r), "month rejects September tag named 26.08.x")
check(not run("26.08.6-rc2", OFFICIALS15 + [RC1], now=SEP01)["valid"],
      "month rejects continuing an August candidate in September")
check(not run("26.08.6", OFFICIALS15 + [RC1], now=SEP01)["valid"],
      "month rejects finalizing an August candidate in September")
check(run("26.09.1", OFFICIALS15 + [RC1], now=SEP01)["valid"],
      "month reset: September starts at 26.09.1 even with an August candidate pending")

# --- number: a fresh number must be the month's max used + 1 (candidates occupy numbers) ---
r = run("26.08.7", OFFICIALS15)
check(not r["valid"] and "number" in errs(r), "number rejects skipped 7 when 1..5 exist")
check(run("26.08.6", OFFICIALS15)["valid"], "number accepts fresh 6 when 1..5 exist")
r = run("26.08.7-rc1", OFFICIALS15)
check(not r["valid"] and "number" in errs(r), "number rejects skipping to 7-rc1 when next free is 6")
r = run("26.08.4", OFFICIALS15 + [RC1])
check(not r["valid"] and "number" in errs(r), "number rejects fresh 4 while 6-rc1 pending (next free is 7)")
r = run("26.08.7-rc1", OFFICIALS15 + [RC1])
check(r["valid"] and r["isCandidate"] == "true", "number allows a parallel candidate 7-rc1 while 6-rc1 is pending")

# --- candidate: rc sequence strictly +1 on an open number ---
check(not run("26.08.6-rc2", OFFICIALS15)["valid"], "candidate rejects rc2 on a fresh number (must start at rc1)")
r = run("26.08.6-rc3", OFFICIALS15 + [RC1])
check(not r["valid"] and "candidate" in errs(r), "candidate rejects rc1 -> rc3 jump")
r = run("26.08.6-rc2", OFFICIALS15 + [RC1])
check(r["valid"] and r["code"] == "26082301" and r["isCandidate"] == "true", "candidate accepts rc1 -> rc2")

# --- sealed: an official number accepts no more candidates ---
r = run("26.08.6-rc1", OFFICIALS15 + [OFF6])
check(not r["valid"] and "sealed" in errs(r), "sealed rejects rc1 for already-official 6")

# --- finalize: official closes an open number ---
r = run("26.08.6", OFFICIALS15 + [RC1])
check(r["valid"] and r["isCandidate"] == "false", "official finalizes directly after rc1")
r = run("26.08.6", OFFICIALS15 + [RC1, RC2])
check(r["valid"] and r["code"] == "26082301" and r["isCandidate"] == "false", "official finalizes after rc1 -> rc2")

# --- duplicate: releases are immutable ---
r = run("26.08.6-rc1", OFFICIALS15 + [RC1])
check(not r["valid"] and "duplicate" in errs(r), "duplicate rejects re-issuing rc1")

# --- code: same-day second build ---
same_day_rc1 = rec("v26.08.6-rc1", "2026-08-23T02:00:00Z", 26082301)
r = run("26.08.6", OFFICIALS15 + [same_day_rc1])
check(r["valid"] and r["code"] == "26082302", "code second build on the same day gets suffix 02")

# --- code: day limit 99 ---
many = [rec(f"vlt{n}", "2026-08-23T02:00:00Z", 26082300 + n) for n in range(1, 100)]
r = run("26.08.1", many)
check(not r["valid"] and "code" in errs(r), "code rejects the 100th build of a day")
r = run("26.08.1", many[:98])
check(r["valid"] and r["code"] == "26082399", "code accepts the 99th build of a day")

# --- code: monotonicity guard (backdated release) ---
future = [rec("v26.09.1", "2026-09-01T02:00:00Z", 26090101)]
r = run("26.08.1", future)
check(not r["valid"] and "code" in errs(r), "code rejects a code not greater than historical max")

# --- old-style semver tags are ignored ---
legacy = [rec("v1.2.3", "2026-01-01T02:00:00Z", body="legacy semver tag")]
r = run("26.08.1", legacy)
check(r["valid"] and r["code"] == "26082301", "legacy v1.2.3 tag does not affect numbers or codes")
check(r["warnings"] == [], "legacy v1.2.3 tag raises no warnings")

# --- warning: release-format tag without VersionCode line ---
no_ledger = [rec("v26.08.1-rc1", "2026-08-20T02:00:00Z", body="no VersionCode line here")]
r = run("26.08.1", no_ledger)
check(r["valid"] and any("no VersionCode line" in w for w in r["warnings"]),
      "warns when a release-format tag lacks a VersionCode line")

# --- warning: midnight-boundary artifact (created month != name month) ---
stale = [rec("v26.07.3-rc1", "2026-08-01T02:00:00Z", 26073199)]
r = run("26.08.6", OFFICIALS15 + stale)
check(r["valid"] and any("not its name month" in w for w in r["warnings"]),
      "warns when a tag was created outside its name month (midnight artifact)")

# --- empty registry, fresh official ---
r = run("26.08.1", [])
check(r["valid"] and r["code"] == "26082301" and r["patch"] == "1" and r["yy"] == "26" and r["mm"] == "08",
      "fresh repo first release works")

# --- parse_registry: real `git for-each-ref` byte layout ---
# 实测 (git 2.43) 每条记录为: name \x00 iso-date \x00 body \x00 \n, 且
# %(contents:body) 已剥离主题行、body 自带尾部 \n。因此上一条 body 的尾部 \n
# 会粘到下一条名字前 (e.g. "...\n\x00\nv26.08.2..."); parse_registry 靠
# name.strip() 吸收。body 保留尾部 \n, VersionCode 行的正则 (^VersionCode:...$) 依赖此结构。
def git_rec(name, iso_date, code):
    # 模拟真实 for-each-ref 输出: body 只含 VersionCode 行 + 尾部 \n
    body = f"VersionCode: {code}\n"
    return f"{name}\x00{iso_date}\x00{body}\x00\n"


GIT_TAGS = [
    git_rec("v26.08.1", "2026-08-01T02:00:00Z", 26080101),
    git_rec("v26.08.2", "2026-08-05T02:00:00Z", 26080501),
    git_rec("v26.08.3", "2026-08-10T02:00:00Z", 26081001),
    git_rec("v26.08.4-rc1", "2026-08-23T02:00:00Z", 26082301),  # first AUG23 build
]
GIT_STREAM = "".join(GIT_TAGS)

recs = v.parse_registry(GIT_STREAM)
check(len(recs) == 4, "git layout parses 4 records (3 official + 1 rc)")
check([r["name"] for r in recs] == ["v26.08.1", "v26.08.2", "v26.08.3", "v26.08.4-rc1"],
      "git layout: names clean (previous body's trailing \\n absorbed by strip)")
expected_dates = [v.parse_iso("2026-08-01T02:00:00Z"), v.parse_iso("2026-08-05T02:00:00Z"),
                  v.parse_iso("2026-08-10T02:00:00Z"), AUG23]
check([r["date"] for r in recs] == expected_dates, "git layout: ISO dates parsed as UTC")
expected_bodies = [f"VersionCode: {c}\n" for c in (26080101, 26080501, 26081001, 26082301)]
check([r["body"] for r in recs] == expected_bodies, "git layout: bodies keep trailing \\n for the VersionCode regex")

# --- numbering on the real layout: 1..3 official, 4 open as rc1 ---
r = run("26.08.4", recs)
check(r["valid"] and r["isCandidate"] == "false", "git layout: 26.08.4 finalizes the open 26.08.4-rc1")
r = run("26.08.5", recs)
check(r["valid"] and r["code"] == "26082302", "git layout: 5 is the next free number (rc1 occupies 4)")
r = run("26.08.6", recs)
check(not r["valid"] and "number" in errs(r), "git layout: skipping to 6 rejected")

# --- empty pipe: parse_registry("") -> no records; fresh repo still valid ---
check(v.parse_registry("") == [], "git layout: empty stream gives no records")
r = run("26.08.1", v.parse_registry(""))
check(r["valid"] and r["code"] == "26082301", "git layout: fresh repo works from empty stream")

# --- single record with trailing \x00\n separator ---
one = git_rec("v26.08.1", "2026-08-01T02:00:00Z", 26080101)
recs1 = v.parse_registry(one)
check(len(recs1) == 1, "git layout: single trailing \\x00\\n record parses to one")
check(recs1[0]["name"] == "v26.08.1" and recs1[0]["date"] == expected_dates[0]
      and recs1[0]["body"] == expected_bodies[0], "git layout: single record name/date/body correct")

print("")
print(f"result: {PASS} passed, {FAIL} failed")
sys.exit(1 if FAIL else 0)
