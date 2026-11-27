#!/bin/bash
# usage: bash watch-prs.sh PR...   emits a line whenever a PR's summary state changes
# Line: PR N: <state> head=<sha8> merge=<status> fail=<jobs> pending=<n> codex=<sha8>:<status>:unresolved=<bool> inline=<n>
# codex=<sha8>:stale:... means the latest Codex review is of an older commit than the head: not a clean review.
# Previous state per PR lives in prev_<N> variables: macOS's bash 3.2 has no associative arrays.
repo=llm4s/llm4s
while true; do
  for p in "$@"; do
    s=$(gh pr view "$p" --json state,headRefOid,mergeStateStatus,statusCheckRollup 2>/dev/null --jq '"\(.headRefOid[0:8]) \(.state) head=\(.headRefOid[0:8]) merge=\(.mergeStateStatus) fail=" + ([.statusCheckRollup[]|select((.conclusion // .state) as $c | ["FAILURE","TIMED_OUT","CANCELLED","ACTION_REQUIRED","STARTUP_FAILURE","ERROR"] | index($c)) | select(.name!="Cited issues are in their stated state")|.name]|join("|")) + " pending=" + ([.statusCheckRollup[]|select((.status // "COMPLETED") != "COMPLETED")]|length|tostring)') || continue
    head=${s%% *}; s=${s#* }
    # Every page of comments: the latest Codex review can be past the first page on a long PR.
    c=$(gh api --paginate --slurp "repos/$repo/issues/$p/comments" 2>/dev/null | jq -r --arg head "$head" '
      [add // [] | .[] | select(.user.login|test("codex")) | .body
       | capture("\"code_review\":\\{\"commit_sha\":\"(?<sha>[0-9a-f]{8})[^}]*\"has_unresolved_findings\":(?<u>[a-z]+),\"status\":\"(?<st>[a-z_]+)\"")?]
      | last
      | if . == null then "codex=none"
        elif .sha != $head then "codex=\(.sha):stale:unresolved=\(.u)"
        else "codex=\(.sha):\(.st):unresolved=\(.u)" end') || continue
    n=$(gh api --paginate --slurp "repos/$repo/pulls/$p/comments" 2>/dev/null | jq 'add // [] | length') || continue
    cur="$s $c inline=$n"
    eval "old=\${prev_$p-}"
    if [ "$old" != "$cur" ]; then echo "PR $p: $cur"; printf -v "prev_$p" '%s' "$cur"; fi
  done
  sleep 60
done
