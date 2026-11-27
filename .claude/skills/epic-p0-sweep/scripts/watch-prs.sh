#!/bin/bash
# usage: bash watch-prs.sh PR...   emits a line whenever a PR's summary state changes
# Previous state per PR lives in prev_<N> variables: macOS's bash 3.2 has no associative arrays.
while true; do
  for p in "$@"; do
    s=$(gh pr view $p --json state,headRefOid,mergeStateStatus,statusCheckRollup 2>/dev/null --jq '"\(.state) head=\(.headRefOid[0:8]) merge=\(.mergeStateStatus) fail=" + ([.statusCheckRollup[]|select(.conclusion=="FAILURE" and .name!="Cited issues are in their stated state")|.name]|join("|")) + " pending=" + ([.statusCheckRollup[]|select(.status!="COMPLETED")]|length|tostring)') || continue
    c=$(gh api repos/llm4s/llm4s/issues/$p/comments 2>/dev/null --jq '[.[]|select(.user.login|test("codex"))|.body|capture("\"code_review\":\\{\"commit_sha\":\"(?<sha>[0-9a-f]{8})[^}]*\"has_unresolved_findings\":(?<u>[a-z]+),\"status\":\"(?<st>[a-z_]+)\"")?|"codex=\(.sha):\(.st):unresolved=\(.u)"]|last // "codex=none"')
    n=$(gh api repos/llm4s/llm4s/pulls/$p/comments 2>/dev/null --jq 'length')
    cur="$s $c inline=$n"
    eval "old=\${prev_$p-}"
    if [ "$old" != "$cur" ]; then echo "PR $p: $cur"; printf -v "prev_$p" '%s' "$cur"; fi
  done
  sleep 60
done
