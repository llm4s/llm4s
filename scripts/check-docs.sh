#!/usr/bin/env bash
# Runs every documentation check that needs no sbt, and the tests of those checks: the same list CI's Quick Checks
# runs (it calls this script), so a docs change can be checked locally before a push (#2833). A check that #2782
# failed in CI only (check-llms-txt.sh) is one of them.
#
# Each check runs even when an earlier one fails; the summary names every failure. Checks that need sbt are not here:
# the snippet compile (`sbt testDocs`), the support matrix (`check-doc-support.sh --model`, after
# `sbt dumpBuildModel`) and the DocsSpec tagging check (`check-docs-tests.sh --model`).
#
# Usage: scripts/check-docs.sh [--cited-issues]
#   --cited-issues  also run scripts/check-cited-issues.sh, which reads each cited issue's state from GitHub (`gh`
#                   must be signed in; a few minutes). CI runs it in docs-cited-issues.yml, weekly and on docs PRs.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

CITED=0
for arg in "$@"; do
  case "$arg" in
    --cited-issues) CITED=1 ;;
    *) echo "usage: scripts/check-docs.sh [--cited-issues]" >&2; exit 2 ;;
  esac
done

# One line per check: a label, a tab, the commands (run with bash -c from the repository root).
CHECKS=$(cat <<'EOF'
Install snippets pin no literal version	scripts/check-doc-versions.sh
nav_order is unique within each parent (#1623)	scripts/check-doc-nav-order.sh
The support table lists the latest release (#2289)	scripts/test-check-support-table.sh && scripts/check-support-table.sh
Every page has its own title and description (#2314)	scripts/test-check-doc-seo.sh && scripts/check-doc-seo.sh
The share-card and structured-data check (#2316)	scripts/test-check-site-share-cards.sh
llms.txt and llms-full.txt build from the docs (#2287)	scripts/check-llms-txt.sh
The cited-issue check, against a fake gh (#2290)	scripts/test-check-cited-issues.sh
The sitemap scripts (#2318)	scripts/test-check-site-sitemaps.sh
The Scaladoc cross-link and site search scripts (#2291)	scripts/test-check-api-links.sh && scripts/test-check-site-search.sh
Internal docs links	scripts/test-check-docs-site-links.sh && scripts/check-docs-site-links.sh
The documented stability tiers match the build	scripts/test-check-tier-drift.sh && scripts/check-tier-drift.sh
The Scala CLI quick start pins the latest release (#1456)	scripts/test-check-scala-cli-quickstart.sh && scripts/check-scala-cli-quickstart.sh
The Mill quick start pins the latest release (#2293)	scripts/test-check-mill-quickstart.sh && scripts/check-mill-quickstart.sh
The notebooks pin the latest release and carry no outputs (#2296)	scripts/test-check-notebooks.sh && scripts/check-notebooks.sh
The Flink guide's snippets are the sample's (#2298)	scripts/check-flink-guide-snippets.sh
EOF
)
if [ "$CITED" -eq 1 ]; then
  CHECKS="$CHECKS"$'\n'"Cited issues are in the state their pages say (online)	scripts/check-cited-issues.sh"
fi

failed=()
total=0
while IFS=$'\t' read -r label cmd; do
  [ -n "$label" ] || continue
  total=$((total + 1))
  if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::group::$label"; else echo "==> $label"; fi
  if bash -c "$cmd"; then status=ok; else status=FAILED; failed+=("$label ($cmd)"); fi
  [ -n "${GITHUB_ACTIONS:-}" ] && echo "::endgroup::"
  if [ "$status" = FAILED ]; then
    [ -n "${GITHUB_ACTIONS:-}" ] && echo "::error::Doc check failed: $label ($cmd)"
    echo "<== FAILED: $label"
  fi
done <<< "$CHECKS"

echo
if [ "${#failed[@]}" -gt 0 ]; then
  echo "${#failed[@]} of $total doc checks failed:" >&2
  printf '  - %s\n' "${failed[@]}" >&2
  exit 1
fi
echo "All $total doc checks passed."
