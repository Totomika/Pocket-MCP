#!/usr/bin/env python3
"""Pocket-MCP release tag validator and VersionCode allocator.

Implements the rules documented in docs/release.md (authoritative):
  format     version name is YY.MM.PATCH[-rcN] (leading "v" optional on input)
  month      YY.MM must equal the tag creation month (fixed TZ Asia/Shanghai, UTC+8, no
             DST); this alone forces candidates to close within their named month
  number     a fresh number must be the month's max used number + 1 (candidates occupy
             numbers, no gaps); continuing (rcN+1) or finalizing (official) an
             already-open number is always allowed
  duplicate  the tag must not already exist (releases are immutable)
  sealed     a number that already went official accepts no more candidates
  candidate  rc sequence strictly +1: a fresh number starts at rc1
  code       VersionCode = YYMMDD + two-digit daily sequence, strictly monotonic,
             at most 99 published builds per day

CLI usage (CI):
  git for-each-ref 'refs/tags' \
      --format='%(refname:short)%00%(creatordate:iso-strict)%00%(contents:body)%00' \
    | python3 tools/release_tag_check.py --tag <version>

  --tag   version to release, e.g. "26.08.6-rc2" or "v26.08.6-rc2" (leading v stripped)
  --now   optional UTC ISO-8601 moment, for deterministic tests (default: real now)

The registry (NUL-separated refname / ISO date / tag message body) is read from
stdin. NUL never occurs in git tag messages, so it is a safe separator.

Output: one line of JSON on stdout (errors are also echoed to stderr when invalid):
  { valid, errors[], warnings[], yy, mm, patch, isCandidate, name, code }
Exit code 0 when valid, 1 when invalid.
"""
import argparse
import datetime
import json
import re
import sys

_NAME_RE = re.compile(r"^(\d{2})\.(0[1-9]|1[0-2])\.([1-9]\d*)(-rc([1-9]\d*))?$")
_RELEASE_TAG_RE = re.compile(r"^v\d{2}\.(0[1-9]|1[0-2])\.([1-9]\d*)(-rc([1-9]\d*))?$")
_CODE_RE = re.compile(r"(?m)^VersionCode:\s*(\d{8})\s*$")


def parse_iso(value):
    """ISO-8601 -> aware UTC datetime, or None when unparseable."""
    if not value:
        return None
    try:
        return datetime.datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(
            datetime.timezone.utc
        )
    except ValueError:
        return None


def parse_registry(raw):
    """NUL-separated records: name / ISO date / message body."""
    records = []
    parts = raw.split("\x00")
    for i in range(0, len(parts) - 2, 3):
        name = parts[i].strip()
        if not name:
            continue
        records.append({"name": name, "date": parse_iso(parts[i + 1]), "body": parts[i + 2]})
    return records


def month_slots(records, yy, mm):
    """Scan one YY.MM prefix: per number, whether it went official and its latest rcN."""
    slot_re = re.compile(rf"^v{yy}\.{mm}\.([1-9]\d*)(?:-rc([1-9]\d*))?$")
    slots = {}
    for r in records:
        sm = slot_re.match(r["name"])
        if not sm:
            continue
        slot = slots.setdefault(int(sm.group(1)), {"official": False, "max_rc": 0})
        if sm.group(2) is None:
            slot["official"] = True
        else:
            slot["max_rc"] = max(slot["max_rc"], int(sm.group(2)))
    return slots


def validate(tag_name, records, now_utc):
    """Run the release rules (docs/release.md). Returns the result dict (see module docstring)."""
    errors = []
    warnings = []

    zh_now = now_utc + datetime.timedelta(hours=8)  # Asia/Shanghai, no DST
    cur_yymm = zh_now.strftime("%y%m")
    cur_yymmdd = zh_now.strftime("%y%m%d")

    raw = tag_name.strip()
    if raw.startswith("v"):
        raw = raw[1:]

    result = {
        "valid": False,
        "errors": errors,
        "warnings": warnings,
        "yy": "",
        "mm": "",
        "patch": "",
        "isCandidate": "false",
        "name": raw,
        "code": "",
    }

    # --- format: YY.MM.PATCH[-rcN] ---
    m = _NAME_RE.match(raw)
    if not m:
        errors.append(
            f"format: invalid version name '{tag_name}'; expected YY.MM.PATCH[-rcN], "
            "e.g. 26.08.6 or 26.08.6-rc2"
        )
        return result

    yy, mm, patch = m.group(1), m.group(2), m.group(3)
    is_rc = m.group(4) is not None
    rc_n = int(m.group(5)) if is_rc else 0
    result.update({"yy": yy, "mm": mm, "patch": patch, "isCandidate": "true" if is_rc else "false"})

    # --- duplicate: releases are immutable ---
    if any(r["name"] == "v" + raw for r in records):
        errors.append(
            f"duplicate: tag v{raw} already exists; releases are immutable, use the next number instead"
        )

    # --- month: name month == creation month (this alone forces candidates to close in-month) ---
    if yy + mm != cur_yymm:
        errors.append(
            f"month: version month {yy}.{mm} != current month {cur_yymm[:2]}.{cur_yymm[2:]} "
            "(Asia/Shanghai); releases must be tagged within their named month"
        )

    # --- numbering: slots of the named month ---
    slots = month_slots(records, yy, mm)
    p = int(patch)
    slot = slots.get(p)

    if slot is None:
        # fresh number: must be the month's next free one (candidates occupy numbers too)
        next_free = max(slots) + 1 if slots else 1
        if p != next_free:
            used = ", ".join(str(n) for n in sorted(slots)) or "none"
            errors.append(
                f"number: PATCH {p} is not this month's next free number; {yy}.{mm} has used "
                f"[{used}], so PATCH must be {next_free}"
            )
        if is_rc and rc_n != 1:
            errors.append(f"candidate: a fresh candidate must start at rc1, not rc{rc_n}")
    elif is_rc:
        if slot["official"]:
            errors.append(
                f"sealed: PATCH {p} already went official (v{yy}.{mm}.{p}); no more -rcN accepted for it"
            )
        elif rc_n != slot["max_rc"] + 1:
            errors.append(
                f"candidate: PATCH {p} is at rc{slot['max_rc']}; only rc{slot['max_rc'] + 1} may "
                f"follow, not rc{rc_n}"
            )
    # official on a used number: finalizes a pending candidate (always allowed); a repeat
    # official is already caught by the duplicate check above

    # --- code: VersionCode from the tag messages, strictly monotonic ---
    codes = []
    for t in records:
        cm = _CODE_RE.search(t["body"])
        if cm:
            codes.append(int(cm.group(1)))
        elif _RELEASE_TAG_RE.match(t["name"]):
            warnings.append(f"tag {t['name']} has no VersionCode line in its message body (annotated tag required)")
        stripped = t["name"][1:] if t["name"].startswith("v") else None
        tm = _NAME_RE.match(stripped) if stripped else None
        if tm and t["date"] is not None:
            named_month = tm.group(1) + tm.group(2)
            actual_month = (t["date"] + datetime.timedelta(hours=8)).strftime("%y%m")
            if actual_month != named_month:
                warnings.append(
                    f"tag {t['name']} was created in month {actual_month}, not its name month "
                    f"{named_month} (midnight-boundary artifact; that chain can no longer continue)"
                )

    day_base = int(cur_yymmdd) * 100  # YYMMDD00
    day_count = sum(1 for c in codes if day_base <= c < day_base + 100)
    seq = day_count + 1
    max_code = max(codes) if codes else 0
    code = str(day_base + seq)  # YYMMDDNN
    if seq > 99:
        errors.append(
            f"code: {day_count} published build(s) already today ({cur_yymmdd}); "
            "two-digit daily sequence caps at 99"
        )
    elif codes and int(code) <= max_code:
        errors.append(f"code: VersionCode {code} is not greater than historical max {max_code}; monotonicity broken")

    result["valid"] = len(errors) == 0
    if result["valid"]:
        result["code"] = code
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description="Pocket-MCP release tag validator")
    parser.add_argument("--tag", required=True, help="version to release, e.g. 26.08.6-rc2")
    parser.add_argument("--now", default=None, help="UTC ISO-8601 moment (tests only)")
    args = parser.parse_args(argv)

    now_utc = parse_iso(args.now) if args.now else datetime.datetime.now(datetime.timezone.utc)
    if args.now and now_utc is None:
        sys.stderr.write(f"format: invalid --now value '{args.now}'\n")
        return 1

    raw = sys.stdin.read()
    records = parse_registry(raw)
    result = validate(args.tag, records, now_utc)

    if not result["valid"]:
        for e in result["errors"]:
            sys.stderr.write(e + "\n")
    sys.stdout.write(json.dumps(result, ensure_ascii=False) + "\n")
    return 0 if result["valid"] else 1


if __name__ == "__main__":
    sys.exit(main())
