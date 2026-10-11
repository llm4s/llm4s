#!/usr/bin/env bash
# Tests for the dependency-scan scripts, offline:
#   - scripts/osv-new-findings.sh: only a HIGH or CRITICAL advisory that the head has and the base does not
#     fails; everything else is reported. Every case writes small OSV-Scanner JSON reports.
#   - scripts/osv-scan-sboms.sh: hands the scanner the SBOMs under a name it recognises, with --no-ignore, and
#     maps its exit status. A fake scanner stands in for OSV-Scanner.
#
# Usage: scripts/test-osv-scan.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="$REPO_ROOT/scripts/osv-new-findings.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FAILED=0

# report FILE [ID:PACKAGE:VERSION:SEVERITY[:DBSEVERITY] ...]: an OSV report with one finding per argument.
# SEVERITY is a CVSS score or `-` for none; DBSEVERITY is the advisory database's label (HIGH, MODERATE, ...).
report() {
  python3 - "$@" <<'PY'
import json, sys

out, specs = sys.argv[1], sys.argv[2:]
packages = []
for spec in specs:
    parts = spec.split(":")
    adv, name, version, score = parts[:4]
    db = parts[4] if len(parts) > 4 else "MODERATE"
    packages.append({
        "package": {"name": name, "version": version, "ecosystem": "Maven"},
        "groups": [{"ids": [adv], "aliases": [adv], "max_severity": "" if score == "-" else score}],
        "vulnerabilities": [{"id": adv, "database_specific": {"severity": db}}],
    })
results = [{"source": {"path": "/x/target/osv/llm4s-rag-0.5.0.cdx.json", "type": "sbom"}, "packages": packages}]
json.dump({"results": results if packages else []}, open(out, "w"))
PY
}

# expect STATUS NAME FRAGMENT HEAD [BASE]: exits with STATUS and prints FRAGMENT
expect() {
  local want="$1" name="$2" fragment="$3"
  shift 3
  set +e
  out="$("$CHECK" "$@" 2>&1)"
  got=$?
  set -e
  if [ "$got" -ne "$want" ]; then
    echo "FAIL [$name]: exit $got, expected $want"; echo "$out" | sed 's/^/       /'; FAILED=1
  elif ! printf '%s\n' "$out" | grep -qF -- "$fragment"; then
    echo "FAIL [$name]: output does not mention '$fragment'"; echo "$out" | sed 's/^/       /'; FAILED=1
  else
    echo "ok   [$name]"
  fi
}

report "$WORK/base.json" "GHSA-old:netty-handler:4.1.135.Final:9.1"
report "$WORK/clean.json"

report "$WORK/same.json" "GHSA-old:netty-handler:4.1.135.Final:9.1"
expect 0 "an advisory the base already has does not fail" "0 not on the base" "$WORK/same.json" "$WORK/base.json"

report "$WORK/bump.json" "GHSA-old:netty-handler:4.1.136.Final:9.1"
expect 0 "the same advisory after a version bump is not new" "0 not on the base" "$WORK/bump.json" "$WORK/base.json"

report "$WORK/new-high.json" "GHSA-old:netty-handler:4.1.135.Final:9.1" "GHSA-new:jackson-core:2.19.4:7.5"
expect 1 "a new HIGH advisory fails" "**new, blocks** | [GHSA-new]" "$WORK/new-high.json" "$WORK/base.json"

report "$WORK/new-seven.json" "GHSA-new:jackson-core:2.19.4:7.0"
expect 1 "a new advisory scored exactly 7.0 fails" "**new, blocks**" "$WORK/new-seven.json" "$WORK/base.json"

report "$WORK/new-moderate.json" "GHSA-old:netty-handler:4.1.135.Final:9.1" "GHSA-mod:jackson-core:2.19.4:6.9"
expect 0 "a new MODERATE advisory is reported, not failed" "| new | [GHSA-mod]" "$WORK/new-moderate.json" "$WORK/base.json"

report "$WORK/new-unscored-high.json" "GHSA-uns:jackson-core:2.19.4:-:HIGH"
expect 1 "a new unscored advisory the database calls HIGH fails" "| HIGH |" "$WORK/new-unscored-high.json" "$WORK/base.json"

report "$WORK/new-unscored-low.json" "GHSA-uns:jackson-core:2.19.4:-:LOW"
expect 0 "a new unscored LOW advisory is reported, not failed" "| new | [GHSA-uns]" "$WORK/new-unscored-low.json" "$WORK/base.json"

report "$WORK/other-package.json" "GHSA-old:netty-codec:4.1.135.Final:9.1"
expect 1 "a known advisory reaching a new package is new" "**new, blocks**" "$WORK/other-package.json" "$WORK/base.json"

expect 1 "against a clean base every HIGH finding is new" "2 not on the base" "$WORK/new-high.json" "$WORK/clean.json"
expect 0 "without a base argument nothing fails" "Report only: 2 known advisory" "$WORK/new-high.json"
expect 0 "no findings at all" "No known advisories." "$WORK/clean.json" "$WORK/base.json"
expect 0 "modules are named without their version" "| llm4s-rag |" "$WORK/same.json" "$WORK/base.json"

printf '{ not json' > "$WORK/broken.json"
expect 2 "an unreadable head report is an error" "cannot read the OSV report" "$WORK/broken.json" "$WORK/base.json"
expect 2 "an unreadable base report is an error" "cannot read the OSV report" "$WORK/same.json" "$WORK/broken.json"
expect 2 "a missing report is an error" "cannot read the OSV report" "$WORK/nope.json"
expect 2 "no arguments is a usage error" "usage:"

# ---- scripts/osv-scan-sboms.sh, with a fake scanner that records its arguments and the files it was given
SCAN="$REPO_ROOT/scripts/osv-scan-sboms.sh"
FAKE="$WORK/fake-osv-scanner"
cat > "$FAKE" <<'SH'
#!/usr/bin/env bash
# Records its arguments and the files in the directory it was given, then exits with $FAKE_STATUS.
echo "$*" > "$FAKE_LOG"
for last; do :; done
ls "$last" >> "$FAKE_LOG"
exit "${FAKE_STATUS:-0}"
SH
chmod +x "$FAKE"
mkdir -p "$WORK/sboms" "$WORK/no-sboms"
echo '{}' > "$WORK/sboms/llm4s-core-0.5.0.bom.json"
echo '{}' > "$WORK/sboms/llm4s-rag-0.5.0.bom.json"

# scan_expect STATUS NAME FAKE_STATUS SBOM_DIR
scan_expect() {
  set +e
  OSV_SCANNER="$FAKE" FAKE_LOG="$WORK/fake.log" FAKE_STATUS="$3" "$SCAN" "$4" "$WORK/report.json" >"$WORK/scan.out" 2>&1
  got=$?
  set -e
  if [ "$got" -eq "$1" ]; then echo "ok   [$2]"; else echo "FAIL [$2]: exit $got, expected $1"; sed 's/^/       /' "$WORK/scan.out"; FAILED=1; fi
}

scan_expect 0 "a scan with no findings succeeds" 0 "$WORK/sboms"
if grep -q -- "--no-ignore" "$WORK/fake.log" && grep -q "llm4s-core-0.5.0.cdx.json" "$WORK/fake.log" \
  && grep -q "llm4s-rag-0.5.0.cdx.json" "$WORK/fake.log" && ! grep -q "bom.json" "$WORK/fake.log"; then
  echo "ok   [the scanner gets every SBOM as *.cdx.json, with --no-ignore]"
else
  echo "FAIL [the scanner gets every SBOM as *.cdx.json, with --no-ignore]"; sed 's/^/       /' "$WORK/fake.log"; FAILED=1
fi
scan_expect 0 "a scan with findings succeeds (the comparison decides)" 1 "$WORK/sboms"
scan_expect 3 "a scan that found no package is an error, not a clean result" 128 "$WORK/sboms"
scan_expect 127 "a scanner error keeps its status" 127 "$WORK/sboms"
scan_expect 3 "no SBOMs to scan is an error" 0 "$WORK/no-sboms"

if [ "$FAILED" -ne 0 ]; then
  echo "OSV scan tests FAILED"
  exit 1
fi
echo "All OSV scan cases passed"
