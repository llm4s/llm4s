#!/usr/bin/env bash
# Whether any pull request in a merge group carries the `ci:full` label, for CI's `changes` job (#3185).
#
# Prints `full_label=true|false|unknown` on stdout (for $GITHUB_OUTPUT) and why on stderr. A merge group holds one
# squash commit per queued pull request between BASE and HEAD, each subject ending `(#<number>)`; the queue's branch,
# gh-readonly-queue/<base>/pr-<number>-<sha>, names only the last. Every pull request found is looked up, so a group
# stacked on an earlier entry that asked for a full run gets one. `unknown` - a commit with no `(#N)`, no pull request
# found, or a lookup that failed - makes the selection run everything.
#
# Usage: scripts/merge-group-labels.sh BASE HEAD HEAD_REF      (needs git and gh; GH_TOKEN and GITHUB_REPOSITORY set)
set -euo pipefail

BASE="${1:?usage: $0 BASE HEAD HEAD_REF}"
HEAD="${2:?usage: $0 BASE HEAD HEAD_REF}"
HEAD_REF="${3:-}"

say() { echo "$1" >&2; echo "full_label=$2"; exit 0; }

# The checkout is shallow; the group's commits are fetched if the base is not yet reachable.
git cat-file -e "$BASE^{commit}" 2> /dev/null || git fetch --no-tags --quiet origin "$BASE" 2> /dev/null ||
  say "the group's base $BASE could not be fetched" unknown
subjects="$(git log --format=%s "$BASE..$HEAD" 2> /dev/null)" || say "git log $BASE..$HEAD failed" unknown

numbers=()
while IFS= read -r s; do
  [ -z "$s" ] && continue
  n="$(sed -nE 's/.*\(#([0-9]+)\)[[:space:]]*$/\1/p' <<< "$s")"
  [ -n "$n" ] || say "a commit in the group names no pull request: $s" unknown
  numbers+=("$n")
done <<< "$subjects"
# the branch's own number, in case a squash subject was edited
ref_n="$(sed -nE 's#^(refs/heads/)?gh-readonly-queue/.+/pr-([0-9]+)-[0-9a-f]+$#\2#p' <<< "$HEAD_REF")"
[ -n "$ref_n" ] && numbers+=("$ref_n")
[ "${#numbers[@]}" -gt 0 ] || say "no pull request found in the group ($HEAD_REF)" unknown

mapfile -t unique < <(printf '%s\n' "${numbers[@]}" | sort -un)
for n in "${unique[@]}"; do
  names="$(gh api "repos/$GITHUB_REPOSITORY/pulls/$n" --jq '.labels[].name')" ||
    say "the labels of #$n could not be read" unknown
  if grep -qx 'ci:full' <<< "$names"; then say "#$n in the group has ci:full" true; fi
done
say "no pull request in the group ($(printf '#%s ' "${unique[@]}")) has ci:full" false
