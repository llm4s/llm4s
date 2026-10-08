#!/usr/bin/env bash
# Tests for scripts/retry-on-transient-network.sh. No sbt and no network: every case runs the wrapper around a
# fake command whose result for each attempt is scripted, and a stub `sleep` that records what it was asked to
# wait for, so the whole suite takes about a second and the backoff is checked without waiting.
#
# What it pins:
#   - a transient download error is retried, at most RETRY_MAX times, and the last attempt's own exit status wins;
#   - nothing else is ever retried: a formatting error, a test failure, an unknown command, a marker that is not
#     on an `[error]` line, a marker in a run that exited 0;
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
[ "$(tr '\n' ' ' < "$SLEEP_LOG")" = "120 240 " ] && pass "a base wait is capped at 120 s" || fail "a base wait is capped at 120 s" "$(tr '\n' ' ' < "$SLEEP_LOG")"

# 14. Bad settings and no command are refused with status 2, before anything runs.
plan "0\nshould not run"
rc=$(RETRY_MAX=abc run); expect "a non-numeric RETRY_MAX is refused" 2 0 "$rc"
plan "0\nshould not run"
rc=$(RETRY_BACKOFF_SECONDS=-1 run); expect "a negative backoff is refused" 2 0 "$rc"
PATH="$WORK/bin:$PATH" "$WRAPPER" > /dev/null 2>&1; rc=$?
[ "$rc" = 2 ] && pass "no command is refused" || fail "no command is refused" "exit $rc"

if [ "$FAILED" -ne 0 ]; then
  echo
  echo "FAILED"
  exit 1
fi
echo
echo "all retry-on-transient-network cases pass"
