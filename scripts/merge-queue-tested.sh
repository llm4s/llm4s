#!/usr/bin/env bash
# Whether the merge queue already tested a commit that was pushed to main, for CI's `changes` job (#3190).
#
# Prints `tested=true|false` on stdout (for $GITHUB_OUTPUT) and why on stderr. `true` when a `merge_group` run of this
# workflow has the commit as its head and its `All Tests Pass` job succeeded: `main` lands on exactly the commit the
# queue tested (#3183), so that run's results are main's, and the push run keeps only the main-only tiers. Anything
# else - no such run (a push that bypassed the queue), a run whose gate did not pass, a lookup that failed - is
# `false`, and the push run tests everything, as before.
#
# Usage: scripts/merge-queue-tested.sh SHA      (needs gh; GH_TOKEN and GITHUB_REPOSITORY set)
set -euo pipefail

SHA="${1:?usage: $0 SHA}"
WORKFLOW="${CI_WORKFLOW_FILE:-ci.yml}"

say() { echo "$1" >&2; echo "tested=$2"; exit 0; }

runs="$(gh api "repos/$GITHUB_REPOSITORY/actions/workflows/$WORKFLOW/runs?event=merge_group&head_sha=$SHA&per_page=20" \
  --jq '.workflow_runs[].id' 2> /dev/null)" || say "the merge-group runs of $SHA could not be listed" false
[ -n "$runs" ] || say "no merge-group run tested $SHA (a push that bypassed the queue?)" false
while IFS= read -r id; do
  [ -n "$id" ] || continue
  gate="$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$id/jobs?per_page=100" \
    --jq '.jobs[] | select(.name == "All Tests Pass") | .conclusion' 2> /dev/null)" || continue
  if [ "$gate" = success ]; then say "merge-group run $id tested $SHA and its All Tests Pass succeeded" true; fi
done <<< "$runs"
say "no merge-group run of $SHA has a successful All Tests Pass" false
