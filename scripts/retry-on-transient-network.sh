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
#   RATE_LIMIT_BACKOFF_SECONDS  the same when the failure was a rate limit or a server error (default 60: 60 s, then
#                          120 s); never less than RETRY_BACKOFF_SECONDS
#
# What counts as transient: a line that starts with `[error]` (after stripping terminal codes) and contains one
# of the markers in TRANSIENT_PATTERN below - sbt and coursier print their resolution failures that way.
# A marker on any other line (an `[info]` test name, a log line from the code under test) is ignored, so a
# test about timeouts cannot cause a retry, and a run that exits 0 is never looked at.
#
# A rate limit is the other transient failure. On 2026-11-20 about twenty CI jobs started within four minutes, each
# resolving what a partial dependency cache lacked, and Maven Central answered
#
#   [error]   download error: Caught java.io.IOException (Server returned HTTP response code: 429 for URL:
#           https://repo1.maven.org/maven2/org/apache/pekko/pekko-stream_3/1.7.1/pekko-stream_3-1.7.1.pom) ...
#
# failing Quick Checks, Kotlin API and MiMa. A 429 (or a 502/503/504 from the CDN in front of Central) clears only when
# the burst does, so it waits longer: RATE_LIMIT_BACKOFF_SECONDS, 60 s then 120 s. The failures that day came 2-4
# minutes into jobs that had started over 4 minutes, so the 15 s and 30 s of a network blip would put both retries
# inside the same burst; 60 s + 120 s puts the last attempt three minutes after the first failure, and costs a passing
# job nothing. The cache saved by .github/workflows/dependency-cache.yml is the fix; this is the safety net.
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
RATE_LIMIT_BACKOFF="${RATE_LIMIT_BACKOFF_SECONDS:-60}"
case "$MAX_RETRIES" in ''|*[!0-9]*) echo "RETRY_MAX must be a non-negative integer, got '$MAX_RETRIES'" >&2; exit 2;; esac
case "$BACKOFF" in ''|*[!0-9]*) echo "RETRY_BACKOFF_SECONDS must be a non-negative integer, got '$BACKOFF'" >&2; exit 2;; esac
case "$RATE_LIMIT_BACKOFF" in ''|*[!0-9]*) echo "RATE_LIMIT_BACKOFF_SECONDS must be a non-negative integer, got '$RATE_LIMIT_BACKOFF'" >&2; exit 2;; esac
# A runaway value must not hold CI for long: at most 5 retries, at most 120 s per wait.
[ "$MAX_RETRIES" -gt 5 ] && MAX_RETRIES=5
[ "$BACKOFF" -gt 120 ] && BACKOFF=120
[ "$RATE_LIMIT_BACKOFF" -gt 120 ] && RATE_LIMIT_BACKOFF=120
[ "$RATE_LIMIT_BACKOFF" -lt "$BACKOFF" ] && RATE_LIMIT_BACKOFF="$BACKOFF"

# What sbt, coursier and the JVM print when the network (not the build) failed. Kept narrow on purpose:
# "not found" and "unresolved dependency" are NOT here, because a dependency that does not exist, or a typo
# in a coordinate, is a real failure and retrying it only wastes time.
# A rate limit or a server error is matched on the status in the JVM's own message ("Server returned HTTP response
# code: 429 for URL"), never on a bare "Error downloading", which sbt prints for a missing coordinate too.
RATE_LIMIT_PATTERN='response code: 429|Too Many Requests|response code: 50[234]'
TRANSIENT_PATTERN="failed to download \\[|Connection reset|Connection refused|Read timed out|Connect timed out|SocketTimeoutException|UnknownHostException|Premature EOF|Remote host terminated the handshake|Received fatal alert|Temporary failure in name resolution|$RATE_LIMIT_PATTERN"

OUT="$(mktemp)"
trap 'rm -f "$OUT"' EXIT
ESC="$(printf '\033')"

# The last attempt's `[error]` lines with terminal codes stripped: sbt's colours, and the line erases (`ESC[0J`) sbt's
# thin client (`sbt --client`) puts before each line.
error_lines() {
  sed "s/${ESC}\\[[0-9;?]*[A-Za-z]//g" "$OUT" | grep -E '^\[error\]'
}
is_transient() { error_lines | grep -Eq "$TRANSIENT_PATTERN"; }
is_rate_limited() { error_lines | grep -Eq "$RATE_LIMIT_PATTERN"; }

attempt=1
while :; do
  # `tee` streams the output to the job log as it is produced; PIPESTATUS keeps the command's own status.
  "$@" 2>&1 | tee "$OUT"
  rc="${PIPESTATUS[0]}"

  if [ "$rc" -eq 0 ]; then
    exit 0
  fi

  if [ "$attempt" -le "$MAX_RETRIES" ] && is_transient; then
    base="$BACKOFF"
    is_rate_limited && base="$RATE_LIMIT_BACKOFF"
    wait_seconds=$((base * attempt))
    [ "$wait_seconds" -gt 120 ] && wait_seconds=120   # the per-wait cap holds for the computed wait, not only the base
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
