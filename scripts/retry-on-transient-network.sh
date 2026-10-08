#!/usr/bin/env bash
# Runs a command and re-runs it, a bounded number of times, ONLY when it failed because a download hit a
# transient network error. Any other failure - a formatting error, a failing test, a compile error, a bad
# dependency coordinate - is never retried: it fails on the first attempt with its original output.
#
# Why it exists: scalafmt-dynamic fetches scalafmt-core through coursier at run time, and a hiccup on the
# runner's network or on Maven Central fails the `Check formatting` step in about five seconds with
#
#   [error] (core / Compile / scalafmtCheck) org.scalafmt.sbt.ScalafmtSbtReporter$ScalafmtSbtError:
#           scalafmt: [v3.7.14] failed to download [/home/runner/work/llm4s/llm4s/.scalafmt.conf]
#
# for every module. The commit is fine and the same job passes on a re-run; this does that re-run, once or
# twice, and says so in the log.
#
# Usage: scripts/retry-on-transient-network.sh COMMAND [ARGS...]
#
# Environment:
#   RETRY_MAX              retries after the first attempt (default 2, so at most 3 attempts)
#   RETRY_BACKOFF_SECONDS  wait before retry N is N times this (default 15: 15 s, then 30 s)
#
# What counts as transient: a line that starts with `[error]` (after stripping colour codes) and contains one
# of the markers in TRANSIENT_PATTERN below - sbt and coursier print their resolution failures that way.
# A marker on any other line (an `[info]` test name, a log line from the code under test) is ignored, so a
# test about timeouts cannot cause a retry, and a run that exits 0 is never looked at.
#
# The exit status is the last attempt's own: a retry that still fails fails the step, and nothing here can
# turn a failing command into a passing one.
set -uo pipefail

if [ $# -eq 0 ]; then
  echo "usage: $0 COMMAND [ARGS...]" >&2
  exit 2
fi

MAX_RETRIES="${RETRY_MAX:-2}"
BACKOFF="${RETRY_BACKOFF_SECONDS:-15}"
case "$MAX_RETRIES" in ''|*[!0-9]*) echo "RETRY_MAX must be a non-negative integer, got '$MAX_RETRIES'" >&2; exit 2;; esac
case "$BACKOFF" in ''|*[!0-9]*) echo "RETRY_BACKOFF_SECONDS must be a non-negative integer, got '$BACKOFF'" >&2; exit 2;; esac
# A runaway value must not hold CI for long: at most 5 retries, at most 120 s per wait.
[ "$MAX_RETRIES" -gt 5 ] && MAX_RETRIES=5
[ "$BACKOFF" -gt 120 ] && BACKOFF=120

# What sbt, coursier and the JVM print when the network (not the build) failed. Kept narrow on purpose:
# "not found" and "unresolved dependency" are NOT here, because a dependency that does not exist, or a typo
# in a coordinate, is a real failure and retrying it only wastes time.
TRANSIENT_PATTERN='failed to download \[|Connection reset|Connection refused|Read timed out|Connect timed out|SocketTimeoutException|UnknownHostException|Premature EOF|Remote host terminated the handshake|Received fatal alert|Temporary failure in name resolution'

OUT="$(mktemp)"
trap 'rm -f "$OUT"' EXIT
ESC="$(printf '\033')"

is_transient() {
  # Strip colour codes, keep only `[error]` lines, then look for a network marker.
  sed "s/${ESC}\\[[0-9;]*m//g" "$OUT" | grep -E '^\[error\]' | grep -Eq "$TRANSIENT_PATTERN"
}

attempt=1
while :; do
  # `tee` streams the output to the job log as it is produced; PIPESTATUS keeps the command's own status.
  "$@" 2>&1 | tee "$OUT"
  rc="${PIPESTATUS[0]}"

  if [ "$rc" -eq 0 ]; then
    exit 0
  fi

  if [ "$attempt" -le "$MAX_RETRIES" ] && is_transient; then
    wait_seconds=$((BACKOFF * attempt))
    echo "::warning title=Transient network failure::attempt $attempt of $((MAX_RETRIES + 1)) failed on a download error; retrying in ${wait_seconds}s: $*" >&2
    echo "retry-on-transient-network: attempt $attempt failed with a transient download error (exit $rc); retrying in ${wait_seconds}s" >&2
    sleep "$wait_seconds"
    attempt=$((attempt + 1))
    continue
  fi

  if [ "$attempt" -gt 1 ]; then
    echo "retry-on-transient-network: giving up after $attempt attempts (exit $rc)" >&2
  fi
  exit "$rc"
done
