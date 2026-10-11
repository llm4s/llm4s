#!/usr/bin/env bash
# Compares two OSV-Scanner JSON reports (`osv-scanner scan source --format json`) of the llm4s SBOMs, one for
# the pull request's base and one for its head, and fails only when the head has a HIGH or CRITICAL advisory
# that the base does not (see docs/reference/sbom.md, "Vulnerability scanning").
#
# Usage: scripts/osv-new-findings.sh HEAD_JSON [BASE_JSON]
#   HEAD_JSON  the report for the pull request's head (or for main, on the weekly run)
#   BASE_JSON  optional: the report for the base. Without it nothing is "new": every finding is reported and
#              the exit status is 0 (report only), as on the weekly run or when the base could not be scanned.
#
# Why a diff and not a threshold on the head alone: main already carries known advisories (they are reported
# every week and fixed in their own pull requests), and a contributor whose change does not touch them must not
# be blocked by them. Both reports are made by the same scanner in the same job, against the same advisory
# database, so a finding is "new" only because the pull request changed a dependency.
#
# A finding is the pair (advisory, package). It is new when the base has no finding with that advisory for that
# package; bumping a package from one affected version to another is therefore not new. HIGH or CRITICAL means a
# CVSS score of 7.0 or more (OSV's `max_severity`), or, when the advisory has no score, a database severity of
# HIGH or CRITICAL.
#
# Writes a Markdown report to stdout (the workflow appends it to the job summary) and exits:
#   0  no new HIGH or CRITICAL finding
#   1  at least one, listed first in the report
#   2  a report could not be read
# Needs python3. Works with bash 3.2 (macOS).
set -euo pipefail

if [ $# -lt 1 ] || [ $# -gt 2 ]; then
  echo "usage: $0 HEAD_JSON [BASE_JSON]" >&2
  exit 2
fi

python3 - "$1" "${2:-}" <<'PYEOF'
import json
import os
import re
import sys

head_path, base_path = sys.argv[1], sys.argv[2]
HIGH = 7.0


def load(path):
    try:
        with open(path, encoding="utf-8") as f:
            report = json.load(f)
    except (OSError, ValueError) as e:
        print(f"cannot read the OSV report {path}: {e}", file=sys.stderr)
        sys.exit(2)
    if not isinstance(report, dict):
        print(f"{path} is not an OSV-Scanner JSON report", file=sys.stderr)
        sys.exit(2)
    return report


def severity(group, vulns):
    """The group's CVSS score, or the database severity of its advisories when it has none."""
    try:
        return float(group.get("max_severity") or ""), None
    except ValueError:
        labels = {
            str((v.get("database_specific") or {}).get("severity", "")).upper()
            for v in vulns
            if v.get("id") in group.get("ids", [])
        }
        for label in ("CRITICAL", "HIGH", "MODERATE", "MEDIUM", "LOW"):
            if label in labels:
                return None, label
        return None, None


def is_high(score, label):
    return (score is not None and score >= HIGH) or label in ("HIGH", "CRITICAL")


def findings(report):
    """{(advisory id, ecosystem, package): (score, label, version, {source files})}"""
    out = {}
    for result in report.get("results") or []:
        source = os.path.basename(str((result.get("source") or {}).get("path", "")))
        for pkg in result.get("packages") or []:
            p = pkg.get("package") or {}
            vulns = pkg.get("vulnerabilities") or []
            for group in pkg.get("groups") or []:
                ids = group.get("ids") or []
                if not ids:
                    continue
                score, label = severity(group, vulns)
                key = (ids[0], p.get("ecosystem", ""), p.get("name", ""))
                prev = out.get(key)
                sources = (prev[3] if prev else set()) | {source}
                out[key] = (score, label, p.get("version", ""), sources)
    return out


head = findings(load(head_path))
base = findings(load(base_path)) if base_path else None


def describe(score, label):
    return f"{score:.1f}" if score is not None else (label or "unscored")


rows = []
for key, (score, label, version, sources) in head.items():
    new = base is not None and key not in base
    rows.append((new and is_high(score, label), new, key, score, label, version, sources))
# Blocking first, then other new findings, then the rest; most severe first within each.
rows.sort(key=lambda r: (not r[0], not r[1], -(r[3] or 0.0), r[2]))
blocking = [r for r in rows if r[0]]

print("### Dependency scan (OSV-Scanner)")
print()
if base is None:
    print(f"Report only: {len(head)} known advisory finding(s) in the published modules' dependencies. "
          "Nothing fails on this run; fix them in their own pull requests.")
else:
    new_count = sum(1 for r in rows if r[1])
    print(f"{len(head)} finding(s) on this head, {new_count} not on the base. "
          f"The check fails only for a new finding with CVSS 7.0 or more (HIGH or CRITICAL): {len(blocking)}.")
print()
if rows:
    print("| | Advisory | Severity | Package | Version | Modules |")
    print("|---|---|---|---|---|---|")
    for blocks, new, (adv, eco, name), score, label, version, sources in rows:
        mark = "**new, blocks**" if blocks else ("new" if new else "")
        # `llm4s-rag-0.5.0.cdx.json` -> `llm4s-rag`: module names never contain `-<digit>`.
        modules = ", ".join(sorted({re.sub(r"-\d.*$", "", s.replace(".cdx.json", "")) for s in sources}))
        print(f"| {mark} | [{adv}](https://osv.dev/{adv}) | {describe(score, label)} | {eco} {name} | {version} | {modules} |")
else:
    print("No known advisories.")

sys.exit(1 if blocking else 0)
PYEOF
