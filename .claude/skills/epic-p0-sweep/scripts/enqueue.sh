#!/bin/bash
# usage: bash enqueue.sh PR HEAD_SHA
# Adds a PR to main's merge queue, pinned to the head that was reviewed.
# `gh pr merge --squash` fails on llm4s ("Auto merge is not allowed"); the queue is the only merge route.
set -euo pipefail
pr=$1; sha=$2
id=$(gh pr view "$pr" --json id -q .id)
gh api graphql \
  -f query='mutation($id:ID!,$sha:GitObjectID){enqueuePullRequest(input:{pullRequestId:$id,expectedHeadOid:$sha}){mergeQueueEntry{position state}}}' \
  -f id="$id" -f sha="$sha" -q .data.enqueuePullRequest.mergeQueueEntry
