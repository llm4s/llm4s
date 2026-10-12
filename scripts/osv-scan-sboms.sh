#!/usr/bin/env bash
# Runs OSV-Scanner over the CycloneDX SBOMs that `sbt publishedSboms` writes and saves its JSON report
# (docs/reference/sbom.md, "Vulnerability scanning"). Used by .github/workflows/dependency-scan.yml.
#
# Usage: OSV_SCANNER=/path/to/osv-scanner scripts/osv-scan-sboms.sh SBOM_DIR REPORT_JSON
#
# Two details the scanner does not make obvious, both of which make it scan NOTHING and say so only as
# "No package sources found":
#   - it recognises a CycloneDX file by its name only (`bom.json` or `*.cdx.json`), and the SBOMs are
#     `*.bom.json`, so they are copied under a recognised name;
#   - it skips files that .gitignore ignores, and `target/` is ignored, so the copies go to a directory
#     outside the repository and the scan also passes `--no-ignore`.
#
# Exit status: 0 when the scan ran (whether or not it found advisories; scripts/osv-new-findings.sh decides
# what fails), 3 when it scanned no package (a broken setup, never a clean result), and the scanner's own
# status for any other error (for example the advisory database could not be reached).
set -euo pipefail

if [ $# -ne 2 ]; then
  echo "usage: OSV_SCANNER=... $0 SBOM_DIR REPORT_JSON" >&2
  exit 2
fi
SBOM_DIR="$1"
REPORT="$2"
SCANNER="${OSV_SCANNER:-osv-scanner}"

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

count=0
for f in "$SBOM_DIR"/*.bom.json; do
  [ -e "$f" ] || continue
  cp "$f" "$STAGE/$(basename "${f%.bom.json}").cdx.json"
  count=$((count + 1))
done
if [ "$count" -eq 0 ]; then
  echo "no *.bom.json files in $SBOM_DIR: run 'sbt publishedSboms' first" >&2
  exit 3
fi

set +e
"$SCANNER" scan source --recursive --no-ignore --format json --output-file "$REPORT" "$STAGE"
status=$?
set -e

case "$status" in
  0 | 1)
    echo "Scanned $count SBOM(s); report in $REPORT" >&2
    exit 0
    ;;
  128)
    echo "OSV-Scanner found no package in $count SBOM(s): the files were not recognised" >&2
    exit 3
    ;;
  *)
    echo "OSV-Scanner failed with status $status" >&2
    exit "$status"
    ;;
esac
