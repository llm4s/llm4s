#!/usr/bin/env bash
# Tests for scripts/retry-on-transient-network.sh. No sbt and no network: every case runs the wrapper around a
# fake command whose result for each attempt is scripted, and a stub `sleep` that records what it was asked to
# wait for, so the whole suite takes about a second and the backoff is checked without waiting.
#
# What it pins:
#   - a transient download error is retried, at most RETRY_MAX times, and the last attempt's own exit status wins;
#   - nothing else is ever retried: a formatting error, a test failure, an unknown command, a marker that is not
#     on an `[error]` line, a marker in a run that exited 0;
#   - a rate limit or server error (429, 502-504) is retried with the longer rate-limit backoff, while a missing
#     artifact, a 404 and a 500 are not;
#   - the bounds hold (RETRY_MAX is capped at 5, one wait at 120 s) and bad settings are refused.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# WRAPPER may be overridden to test a modified copy (the mutation checks do this).
WRAPPER="${WRAPPER:-$REPO_ROOT/scripts/retry-on-transient-network.sh}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
ESC="$(printf '\033')"

FAILED=0
pass() { echo "ok   [$1]"; }
fail() { echo "FAIL [$1]: $2"; FAILED=1; }

# A stub `sleep` first on PATH: it records its argument and returns at once.
mkdir -p "$WORK/bin"
cat > "$WORK/bin/sleep" <<'EOF'
#!/usr/bin/env bash
echo "$1" >> "$SLEEP_LOG"
EOF
chmod +x "$WORK/bin/sleep"

# The fake command: attempt N prints and exits as $PLAN/N says (first line: exit status; rest: output). When
# there are fewer plan files than attempts, the last one repeats. $CALLS counts invocations.
cat > "$WORK/fake.sh" <<'EOF'
#!/usr/bin/env bash
n=$(( $(cat "$CALLS" 2>/dev/null || echo 0) + 1 ))
echo "$n" > "$CALLS"
f="$PLAN/$n"
[ -f "$f" ] || f="$PLAN/$(ls "$PLAN" | sort -n | tail -1)"
tail -n +2 "$f"
exit "$(head -1 "$f")"
EOF
chmod +x "$WORK/fake.sh"

TRANSIENT_LINE='[error] (core / Compile / scalafmtCheck) org.scalafmt.sbt.ScalafmtSbtReporter$ScalafmtSbtError: scalafmt: [v3.7.14] failed to download [/home/runner/work/llm4s/llm4s/.scalafmt.conf]'
# sbt colours its prefix: `[` ESC[0m ESC[0m ESC[31m error ESC[0m `] ` ...
COLOURED_LINE="[${ESC}[0m${ESC}[0m${ESC}[31merror${ESC}[0m] ${ESC}[0m${ESC}[0m(core / Test / scalafmtCheck) org.scalafmt.sbt.ScalafmtSbtReporter\$ScalafmtSbtError: scalafmt: [v3.7.14] failed to download [/x/.scalafmt.conf]${ESC}[0m"
GENUINE_LINE='[error] (core / Compile / scalafmtCheck) java.lang.Exception: Unformatted files: modules/core/src/main/scala/Foo.scala'

CASE=0
# plan FILE-CONTENT... : writes one plan file per argument (attempt 1, 2, ...). Content: "<rc>\n<output>".
plan() {
  CASE=$((CASE + 1))
  PLAN="$WORK/plan-$CASE"; CALLS="$WORK/calls-$CASE"; SLEEP_LOG="$WORK/sleep-$CASE"
  mkdir -p "$PLAN"; : > "$SLEEP_LOG"; rm -f "$CALLS"
  local i=1
  for content in "$@"; do printf '%b\n' "$content" > "$PLAN/$i"; i=$((i + 1)); done
  export PLAN CALLS SLEEP_LOG
}
calls() { cat "$CALLS" 2>/dev/null || echo 0; }
run() { PATH="$WORK/bin:$PATH" "$WRAPPER" "$WORK/fake.sh" > "$WORK/stdout-$CASE" 2> "$WORK/stderr-$CASE"; echo $?; }
expect() { # name expected-rc expected-calls actual-rc
  local name="$1" erc="$2" ecalls="$3" arc="$4"
  if [ "$arc" != "$erc" ]; then fail "$name" "exit $arc, expected $erc"; return; fi
  if [ "$(calls)" != "$ecalls" ]; then fail "$name" "$(calls) call(s), expected $ecalls"; return; fi
  pass "$name"
}

# 1. Success on the first attempt: one call, no waiting.
plan "0\nall good"
rc=$(RETRY_BACKOFF_SECONDS=15 run); expect "success runs once" 0 1 "$rc"
[ ! -s "$SLEEP_LOG" ] && pass "success never waits" || fail "success never waits" "slept: $(cat "$SLEEP_LOG")"

# 2. A transient download failure, then success: retried once, exits 0, says so.
plan "1\n$TRANSIENT_LINE" "0\nformatted fine"
rc=$(run); expect "transient then success is retried once and passes" 0 2 "$rc"
grep -q "retrying in 15s" "$WORK/stderr-$CASE" && pass "the retry is announced with its wait" || fail "the retry is announced with its wait" "$(cat "$WORK/stderr-$CASE")"
grep -q "::warning title=Transient network failure" "$WORK/stderr-$CASE" && pass "a GitHub warning annotation is emitted" || fail "a GitHub warning annotation is emitted" "none"

# 3. The original output is streamed to stdout unchanged.
grep -qF "failed to download [/home/runner/work/llm4s/llm4s/.scalafmt.conf]" "$WORK/stdout-$CASE" && grep -q "formatted fine" "$WORK/stdout-$CASE" && pass "every attempt's output reaches the log" || fail "every attempt's output reaches the log" "$(cat "$WORK/stdout-$CASE")"

# 4. Transient every time: exactly 1 + RETRY_MAX attempts, then the command's own exit status (7, not 1).
plan "7\n$TRANSIENT_LINE"
rc=$(run); expect "persistent transient failure stops after 3 attempts with the command's status" 7 3 "$rc"
grep -q "giving up after 3 attempts" "$WORK/stderr-$CASE" && pass "giving up is announced" || fail "giving up is announced" "$(cat "$WORK/stderr-$CASE")"

# 5. A real formatting failure is never retried and keeps its status.
plan "3\n$GENUINE_LINE"
rc=$(run); expect "an unformatted file fails on the first attempt" 3 1 "$rc"
grep -qF "Unformatted files" "$WORK/stdout-$CASE" && pass "the original failure text is preserved" || fail "the original failure text is preserved" "missing"
[ ! -s "$SLEEP_LOG" ] && pass "a real failure never waits" || fail "a real failure never waits" "slept"

# 6. A test failure is never retried, even when a test name mentions a timeout.
plan "1\n[info] - should fail with a Read timed out error *** FAILED ***\n[error] Failed tests:\n[error] \torg.llm4s.FooSpec"
rc=$(run); expect "a failing test is not retried" 1 1 "$rc"

# 7. A marker that is not on an [error] line is ignored.
plan "1\n[info] Connection reset by peer (logged by the code under test)\n[warn] failed to download [x]"
rc=$(run); expect "a marker outside an [error] line is ignored" 1 1 "$rc"

# 8. A run that exits 0 is never inspected, even if its output carries an [error]-prefixed marker.
plan "0\n$TRANSIENT_LINE"
rc=$(run); expect "a marker in a passing run does not matter" 0 1 "$rc"

# 9. sbt's coloured prefix is understood.
plan "1\n$COLOURED_LINE" "0\nok"
rc=$(run); expect "coloured [error] output is recognised" 0 2 "$rc"

# 9b. So is the thin client's (`sbt --client`), which starts each line with a line erase, ESC[0J.
plan "1\n${ESC}[0J${COLOURED_LINE}${ESC}[0J" "0\nok"
rc=$(run); expect "the thin client's [error] output is recognised" 0 2 "$rc"

# 10. An unknown command is a real failure (127), not retried.
CASE=$((CASE + 1)); SLEEP_LOG="$WORK/sleep-$CASE"; : > "$SLEEP_LOG"; export SLEEP_LOG
PATH="$WORK/bin:$PATH" "$WRAPPER" /no/such/command > /dev/null 2>&1; rc=$?
[ "$rc" = 127 ] && [ ! -s "$SLEEP_LOG" ] && pass "a missing command is not retried (127)" || fail "a missing command is not retried (127)" "exit $rc"

# 11. RETRY_MAX=0 disables retrying.
plan "1\n$TRANSIENT_LINE"
rc=$(RETRY_MAX=0 run); expect "RETRY_MAX=0 means no retry" 1 1 "$rc"

# 12. RETRY_MAX is capped at 5 retries (6 attempts) however large it is set.
plan "1\n$TRANSIENT_LINE"
rc=$(RETRY_MAX=99 run); expect "RETRY_MAX is capped at 5 retries" 1 6 "$rc"

# 13. Backoff grows with the attempt number, and one wait is capped at 120 s.
plan "1\n$TRANSIENT_LINE"
rc=$(RETRY_BACKOFF_SECONDS=15 run)
[ "$(tr '\n' ' ' < "$SLEEP_LOG")" = "15 30 " ] && pass "backoff is 15 s then 30 s" || fail "backoff is 15 s then 30 s" "$(tr '\n' ' ' < "$SLEEP_LOG")"
plan "1\n$TRANSIENT_LINE"
rc=$(RETRY_BACKOFF_SECONDS=9999 RETRY_MAX=2 run)
[ "$(tr '\n' ' ' < "$SLEEP_LOG")" = "120 120 " ] && pass "every wait is capped at 120 s, the later multiples too" || fail "every wait is capped at 120 s, the later multiples too" "$(tr '\n' ' ' < "$SLEEP_LOG")"

# 14. Bad settings and no command are refused with status 2, before anything runs.
plan "0\nshould not run"
rc=$(RETRY_MAX=abc run); expect "a non-numeric RETRY_MAX is refused" 2 0 "$rc"
plan "0\nshould not run"
rc=$(RETRY_BACKOFF_SECONDS=-1 run); expect "a negative backoff is refused" 2 0 "$rc"
PATH="$WORK/bin:$PATH" "$WRAPPER" > /dev/null 2>&1; rc=$?
[ "$rc" = 2 ] && pass "no command is refused" || fail "no command is refused" "exit $rc"

# 15. Maven Central's rate limit, as the MiMa job printed it on 2026-11-20 (job 136603391059): retried, and with the
#     longer rate-limit backoff (60 s, then 120 s), not the 15 s and 30 s of a network blip.
RATE_LIMITED='[error] sbt.librarymanagement.ResolveException: Error downloading org.apache.pekko:pekko-stream_3:1.7.1
[error]   not found: /home/runner/.ivy2/localorg.apache.pekko/pekko-stream_3/1.7.1/ivys/ivy.xml
[error]   download error: Caught java.io.IOException (Server returned HTTP response code: 429 for URL: https://repo1.maven.org/maven2/org/apache/pekko/pekko-stream_3/1.7.1/pekko-stream_3-1.7.1.pom) while downloading https://repo1.maven.org/maven2/org/apache/pekko/pekko-stream_3/1.7.1/pekko-stream_3-1.7.1.pom
[error]   not found: https://repo.scala-sbt.org/scalasbt/sbt-plugin-releases/org.apache.pekko/pekko-stream_3/1.7.1/ivys/ivy.xml
[error]   not found: https://repo.typesafe.com/typesafe/ivy-releases/org.apache.pekko/pekko-stream_3/1.7.1/ivys/ivy.xml'
plan "1\n$RATE_LIMITED" "1\n$RATE_LIMITED" "0\nresolved"
rc=$(run); expect "a 429 from Maven Central is retried until it passes" 0 3 "$rc"
[ "$(tr '\n' ' ' < "$SLEEP_LOG")" = "60 120 " ] && pass "a 429 waits 60 s, then 120 s" || fail "a 429 waits 60 s, then 120 s" "$(tr '\n' ' ' < "$SLEEP_LOG")"

# 16. The same for the other 429 releases of 2026-11-20 (the llm4s-rag 0.5.1 baseline of the scalafix tests, and the
#     Scala 2.13 compiler bridge), sbt-coloured, and for "Too Many Requests" and a 502/503/504 from the CDN.
for line in \
  "[${ESC}[0m${ESC}[31merror${ESC}[0m]   download error: Caught java.io.IOException (Server returned HTTP response code: 429 for URL: https://repo1.maven.org/maven2/org/llm4s/llm4s-rag_3/0.5.1/llm4s-rag_3-0.5.1.pom) while downloading https://repo1.maven.org/maven2/org/llm4s/llm4s-rag_3/0.5.1/llm4s-rag_3-0.5.1.pom" \
  "[error]   download error: Caught java.io.IOException (Server returned HTTP response code: 429 for URL: https://repo1.maven.org/maven2/org/scala-lang/scala2-sbt-bridge/2.13.14/scala2-sbt-bridge-2.13.14.pom) while downloading https://repo1.maven.org/maven2/org/scala-lang/scala2-sbt-bridge/2.13.14/scala2-sbt-bridge-2.13.14.pom" \
  "[error]   download error: Caught java.io.IOException (429 Too Many Requests) while downloading https://repo1.maven.org/maven2/x.pom" \
  "[error]   download error: Caught java.io.IOException (Server returned HTTP response code: 502 for URL: https://repo1.maven.org/maven2/x.pom)" \
  "[error]   download error: Caught java.io.IOException (Server returned HTTP response code: 503 for URL: https://repo1.maven.org/maven2/x.pom)" \
  "[error]   download error: Caught java.io.IOException (Server returned HTTP response code: 504 for URL: https://repo1.maven.org/maven2/x.pom)"; do
  plan "1\n$line" "0\nok"
  label="$(printf %s "$line" | grep -oE "response code: [0-9]+|Too Many Requests") for $(printf %s "$line" | grep -oE "[^/]+\.pom" | head -1)"
  rc=$(run); expect "retried: $label" 0 2 "$rc"
  [ "$(cat "$SLEEP_LOG")" = "60" ] || fail "rate-limit backoff for: $line" "slept $(cat "$SLEEP_LOG")"
done

# 17. A missing artifact is not a rate limit: "Error downloading" with only "not found" lines fails at once, and so does
#     a 404 or a 500 (an error in the repository, not a pause that clears).
for line in \
  '[error] sbt.librarymanagement.ResolveException: Error downloading org.llm4s:llm4s-nope_3:0.5.1\n[error]   not found: https://repo1.maven.org/maven2/org/llm4s/llm4s-nope_3/0.5.1/llm4s-nope_3-0.5.1.pom' \
  '[error]   download error: Caught java.io.IOException (Server returned HTTP response code: 404 for URL: https://repo1.maven.org/maven2/x.pom)' \
  '[error]   download error: Caught java.io.IOException (Server returned HTTP response code: 500 for URL: https://repo1.maven.org/maven2/x.pom)'; do
  plan "1\n$line"
  label="$(printf %b "$line" | grep -oE "response code: [0-9]+|not found" | head -1) for $(printf %b "$line" | grep -oE "[^/]+\.pom" | head -1)"
  rc=$(run); expect "not retried: $label" 1 1 "$rc"
done

# 18. A 429 marker outside an [error] line (a test's own log) is ignored, like every other marker.
plan "1\n[info] - should map HTTP response code: 429 to RateLimitError *** FAILED ***\n[error] Failed tests:"
rc=$(run); expect "a 429 in a test name is not retried" 1 1 "$rc"

# 19. The rate-limit backoff is bounded like the other: a bad value is refused, a large one is capped at 120 s, and it
#     is never shorter than RETRY_BACKOFF_SECONDS.
plan "0\nshould not run"
rc=$(RATE_LIMIT_BACKOFF_SECONDS=soon run); expect "a non-numeric RATE_LIMIT_BACKOFF_SECONDS is refused" 2 0 "$rc"
plan "1\n$RATE_LIMITED"
rc=$(RATE_LIMIT_BACKOFF_SECONDS=9999 run)
[ "$(tr '\n' ' ' < "$SLEEP_LOG")" = "120 120 " ] && pass "the rate-limit wait is capped at 120 s" || fail "the rate-limit wait is capped at 120 s" "$(tr '\n' ' ' < "$SLEEP_LOG")"
plan "1\n$RATE_LIMITED"
rc=$(RETRY_BACKOFF_SECONDS=90 RATE_LIMIT_BACKOFF_SECONDS=10 run)
[ "$(tr '\n' ' ' < "$SLEEP_LOG")" = "90 120 " ] && pass "the rate-limit wait is never shorter than the ordinary one" || fail "the rate-limit wait is never shorter than the ordinary one" "$(tr '\n' ' ' < "$SLEEP_LOG")"

if [ "$FAILED" -ne 0 ]; then
  echo
  echo "FAILED"
  exit 1
fi
echo
echo "all retry-on-transient-network cases pass"
