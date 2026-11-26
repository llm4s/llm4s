#!/usr/bin/env bash
# Runs every documentation check that CI's Quick Checks job runs without sbt, in one command (#2833), so a docs change
# can be checked locally before it is pushed: check-llms-txt.sh failed #2782 in CI because nothing ran it locally.
#
# Each check runs even when one before it failed; the summary at the end names every check that failed, and the
# exit status is non-zero if any did. The compiled code blocks are not here: they need sbt (`sbt samples/Test/compile`
# compiles them, `sbt testDocs` runs the suites that read docs/).
#
# CI keeps one step per check (.github/workflows/ci.yml, quick-checks), each with the comment that says why it exists,
# so a failure is reported under its own name. scripts/test-check-docs.sh fails when Quick Checks runs a script that
# is in none of the lists below, so the two cannot drift apart.
#
# Usage: scripts/check-docs.sh [--model FILE] [--online] [--list]
#   --model FILE  also run the checks that read the build model (write it with `sbt "dumpBuildModel FILE"`)
#   --online      also check each cited issue's state on GitHub (needs `gh`, authenticated)
#   --list        print each script Quick Checks runs, with its category here, and exit
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODEL=""
ONLINE=false
LIST=false
while [ $# -gt 0 ]; do
  case "$1" in
    --model) MODEL="${2:?--model needs a file}"; shift 2 ;;
    --online) ONLINE=true; shift ;;
    --list) LIST=true; shift ;;
    -h|--help) sed -n '2,/^set -uo/p' "${BASH_SOURCE[0]}" | sed '$d'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

# The documentation checks, each `script [args]`, in the order Quick Checks runs them.
DOCS=(
  "check-doc-versions.sh"
  "check-doc-nav-order.sh"
  "test-check-support-table.sh"
  "check-support-table.sh"
  "test-check-doc-seo.sh"
  "check-doc-seo.sh"
  "test-check-site-share-cards.sh"
  "check-llms-txt.sh"
  "test-check-cited-issues.sh"
  "check-cited-issues.sh --offline"
  "test-new-release-post.sh"
  "test-check-site-sitemaps.sh"
  "test-check-api-links.sh"
  "test-check-site-search.sh"
  "test-check-docs-site-links.sh"
  "check-docs-site-links.sh"
  "test-check-tier-drift.sh"
  "check-tier-drift.sh"
  "test-check-scala-cli-quickstart.sh"
  "test-check-mill-quickstart.sh"
  "test-check-notebooks.sh"
  "check-notebooks.sh"
  "check-flink-guide-snippets.sh"
  "test-check-docs-tests.sh"
  "test-check-docs.sh"
  "test-changelog-fragments.sh"
  "changelog-fragments.sh check"
)
# Documentation checks that read the build model: run with --model.
MODEL_CHECKS=(
  "test-check-doc-support.sh MODEL"
  "check-doc-support.sh --model MODEL"
  "check-docs-tests.sh --model MODEL"
)
# Documentation checks Quick Checks runs that fetch the latest release from Maven Central and run its tool, so they
# are left to CI.
NETWORK=(
  "check-scala-cli-quickstart.sh"
  "check-mill-quickstart.sh"
)
# Scripts Quick Checks runs that check something other than the documentation.
NOT_DOCS=(
  check-coverage-legs.sh test-check-coverage-legs.sh check-performance-tests.sh test-check-performance-tests.sh
  check-platform-tests.sh test-check-platform-tests.sh check-sbom.sh test-check-sbom.sh test-osv-scan.sh
  check-workflow-security.sh test-check-workflow-security.sh test-merge-queue-workflows.sh test-ci-changes.sh
  test-workspace-tier-changes.sh test-dependency-footprint.sh test-dispatch-docs-deploy.sh
  retry-on-transient-network.sh test-retry-on-transient-network.sh test-mima-dry-run.sh test-verify-release.sh
  test-publish-kotlin-api.sh
)

if [ "$LIST" = true ]; then
  for c in "${DOCS[@]}"; do echo "docs ${c%% *}"; done
  for c in "${MODEL_CHECKS[@]}"; do echo "model ${c%% *}"; done
  for c in "${NETWORK[@]}"; do echo "network ${c%% *}"; done
  for c in "${NOT_DOCS[@]}"; do echo "other $c"; done
  exit 0
fi

checks=("${DOCS[@]}")
if [ -n "$MODEL" ]; then
  [ -f "$MODEL" ] || { echo "No such build model: $MODEL" >&2; exit 2; }
  for c in "${MODEL_CHECKS[@]}"; do checks+=("${c//MODEL/$MODEL}"); done
fi
[ "$ONLINE" = true ] && checks+=("check-cited-issues.sh")

failed=()
skipped=()
cd "$REPO_ROOT"
for check in "${checks[@]}"; do
  script="${check%% *}"
  read -r -a args <<< "${check#"$script"}"
  if [ "$script" = "test-check-site-search.sh" ] && ! command -v npm > /dev/null; then
    skipped+=("$script (needs npm)")
    continue
  fi
  echo "==> scripts/$check"
  if ! scripts/"$script" ${args[@]+"${args[@]}"}; then
    failed+=("$check")
  fi
done

echo
echo "${#checks[@]} documentation check(s) run, ${#failed[@]} failed, ${#skipped[@]} skipped."
[ -n "$MODEL" ] || echo "Not run: the checks that read the build model; pass --model FILE (sbt \"dumpBuildModel FILE\")."
for s in ${skipped[@]+"${skipped[@]}"}; do echo "SKIPPED: $s"; done
if [ "${#failed[@]}" -gt 0 ]; then
  for f in "${failed[@]}"; do echo "FAILED: scripts/$f" >&2; done
  exit 1
fi
