#!/usr/bin/env bash
# Deploys the docs for a release tag from `main`, then waits for the deploy and reports its result.
#
# Usage: scripts/dispatch-docs-deploy.sh <release-tag>          (release.yml's `docs` job runs it)
#
# Why it exists (#1152): the `github-pages` environment allows deployments only from the `main` branch. A job that
# release.yml called as a reusable workflow ran on the TAG, so the environment rejected it before the job started, on
# every release. A workflow_dispatch run takes the ref it is dispatched on, so this dispatches pages.yml on `main`
# with the tag as its `ref` input: the run (and so the deploy) is on `main`, and the build checks out the tag.
#
# It WAITS for that run and fails when the deploy fails. Dispatching and returning would turn the release green
# while the deploy could still fail unseen, which is the same false signal in the other direction.
#
# Environment:
#   GITHUB_REPOSITORY      owner/name (set by Actions)           GH_TOKEN  a token with `actions: write` (the job's)
#   DOCS_DEPLOY_WORKFLOW   workflow file to dispatch             default pages.yml
#   DOCS_DEPLOY_BRANCH     the branch the environment accepts    default main
#   DOCS_RUN_TITLE         printf format of the run's title      default "Deploy Documentation (%s)"; must equal
#                          pages.yml's `run-name`, which is how the dispatched run is told apart from other runs
#   DOCS_FIND_TIMEOUT      seconds to wait for the run to appear default 180
#   DOCS_RUN_TIMEOUT       seconds to wait for it to finish      default 2700
#   DOCS_POLL_INTERVAL     seconds between polls                 default 10 (may be fractional)
#
# Exit codes: 0 deployed | 1 dispatch failed or the deploy failed | 2 not a release tag, or bad settings |
#             3 the run was cancelled | 4 still running at the timeout | 5 the dispatched run never appeared.
# Only 0 means the docs are deployed. 3 usually means a newer deploy replaced this one (the workflow's concurrency
# group keeps one pending run): check that the site shows the new version before re-running the job.
set -euo pipefail

TAG="${1:-}"
WORKFLOW="${DOCS_DEPLOY_WORKFLOW:-pages.yml}"
BRANCH="${DOCS_DEPLOY_BRANCH:-main}"
TITLE_FORMAT="${DOCS_RUN_TITLE:-Deploy Documentation (%s)}"
FIND_TIMEOUT="${DOCS_FIND_TIMEOUT:-180}"
RUN_TIMEOUT="${DOCS_RUN_TIMEOUT:-2700}"
POLL_INTERVAL="${DOCS_POLL_INTERVAL:-10}"
REPO="${GITHUB_REPOSITORY:-}"

fail() { echo "::error::$2"; exit "$1"; }

# --- the tag is the only input that reaches a command, and it is checked before anything runs -------------------------
# Same pattern as pages.yml's own check: v1.2.3, or v1.2.3-RC1. No shell metacharacter, space or newline can match.
TAG_PATTERN='^v[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$'
[ -n "$TAG" ] || fail 2 "usage: dispatch-docs-deploy.sh <release-tag>"
[ "${#TAG}" -le 64 ] || fail 2 "'$TAG' is too long to be a release tag."
[[ "$TAG" =~ $TAG_PATTERN ]] || fail 2 "'$TAG' is not a release tag such as v0.5.0 or v1.0.0-RC1."

[[ "$FIND_TIMEOUT" =~ ^[0-9]+$ ]] || fail 2 "DOCS_FIND_TIMEOUT must be a whole number of seconds."
[[ "$RUN_TIMEOUT" =~ ^[0-9]+$ ]] || fail 2 "DOCS_RUN_TIMEOUT must be a whole number of seconds."
[[ "$POLL_INTERVAL" =~ ^[0-9]+(\.[0-9]+)?$ ]] || fail 2 "DOCS_POLL_INTERVAL must be a number of seconds."
[ -n "$REPO" ] || fail 2 "GITHUB_REPOSITORY is not set."

# shellcheck disable=SC2059  # the format is ours, the tag was validated above
TITLE="$(printf "$TITLE_FORMAT" "$TAG")"

pause() { sleep "$POLL_INTERVAL"; }

# Ids of this tag's dispatched runs (newest first). Safe to put the title in a jq string: the tag is [A-Za-z0-9.-].
run_ids() {
  gh run list -R "$REPO" --workflow "$WORKFLOW" --event workflow_dispatch --branch "$BRANCH" --limit 30 \
    --json databaseId,displayTitle --jq ".[] | select(.displayTitle == \"$TITLE\") | .databaseId"
}

# Runs that already exist for this tag (an earlier attempt, or this job being re-run) are not ours.
BEFORE="$(run_ids || true)"

echo "Dispatching $WORKFLOW on $BRANCH to deploy the docs for $TAG"
gh workflow run "$WORKFLOW" -R "$REPO" --ref "$BRANCH" -f "ref=$TAG" \
  || fail 1 "Could not dispatch $WORKFLOW on $BRANCH (does the job have 'actions: write', and is the workflow on $BRANCH?)."

# --- find the run we just started ---------------------------------------------------------------------------------------
RUN_ID=""
START=$SECONDS
while [ -z "$RUN_ID" ]; do
  for id in $(run_ids || true); do
    if ! printf '%s\n' "$BEFORE" | grep -qx "$id"; then RUN_ID="$id"; break; fi
  done
  [ -n "$RUN_ID" ] && break
  [ $((SECONDS - START)) -lt "$FIND_TIMEOUT" ] || fail 5 "The dispatched run ('$TITLE') did not appear within ${FIND_TIMEOUT}s. Check the Actions tab: the deploy may still have started."
  pause
done

RUN_URL="$(gh run view "$RUN_ID" -R "$REPO" --json url --jq .url 2>/dev/null || echo "run $RUN_ID")"
echo "Waiting for the docs deploy: $RUN_URL"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then echo "Docs deploy for \`$TAG\`: $RUN_URL" >> "$GITHUB_STEP_SUMMARY"; fi

# --- wait for it ----------------------------------------------------------------------------------------------------------
START=$SECONDS
UNREADABLE=0
while :; do
  if STATE="$(gh run view "$RUN_ID" -R "$REPO" --json status,conclusion --jq '.status + " " + (.conclusion // "")' 2>/dev/null)"; then
    UNREADABLE=0
  else
    # A blip is retried; a run that cannot be read at all (a missing permission) must not wait out the whole timeout.
    UNREADABLE=$((UNREADABLE + 1))
    [ "$UNREADABLE" -lt 10 ] || fail 1 "Could not read the state of the docs deploy ten times in a row: $RUN_URL"
    STATE="unknown"
  fi
  case "$STATE" in
    "completed success")   echo "Docs deployed for $TAG."; exit 0 ;;
    "completed cancelled") fail 3 "The docs deploy was cancelled: $RUN_URL. A newer deploy may have replaced it; check that the site shows $TAG before re-running this job." ;;
    completed*)            fail 1 "The docs deploy failed (${STATE#completed }): $RUN_URL" ;;
  esac
  [ $((SECONDS - START)) -lt "$RUN_TIMEOUT" ] || fail 4 "The docs deploy was still running after ${RUN_TIMEOUT}s: $RUN_URL. It may yet finish; check it before re-running this job."
  pause
done
