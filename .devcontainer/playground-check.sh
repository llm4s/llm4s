#!/usr/bin/env bash
# Runs the five playground commands of docs/getting-started/playground.md inside the dev container (#2286), against
# the Ollama sidecar, and fails unless each one exits 0 and shows that it worked. Most samples log an error and still
# exit 0, so each command also has a marker its output must contain; the first call must also print a non-empty
# answer. .github/workflows/devcontainer.yml runs this; keep the commands in step with the page.
# Usage (from the repository root, inside the dev container): .devcontainer/playground-check.sh
set -uo pipefail

LOG_DIR="${PLAYGROUND_LOG_DIR:-target/playground-check}"
mkdir -p "$LOG_DIR"
fail=0

# run <name> <marker regex> <sbt command>
run() {
  local name="$1" marker="$2" cmd="$3" log="$LOG_DIR/$1.log" status
  echo "=== $name: sbt \"$cmd\""
  local start=$SECONDS
  sbt -batch "$cmd" 2>&1 | tee "$log"
  status=${PIPESTATUS[0]}
  echo "=== $name: exit $status in $((SECONDS - start))s"
  if [ "$status" -ne 0 ]; then
    echo "FAIL: $name exited $status"
    fail=1
  elif ! grep -Eq "$marker" "$log"; then
    echo "FAIL: $name exited 0 but its output has no '$marker'"
    fail=1
  fi
}

run first-call '--- End Response ---' 'samples/runMain org.llm4s.samples.basic.BasicLLMCallingExample'
# The answer is logged between the two markers; strip the logback prefix and require some text.
answer="$(sed -n '/--- Response ---/,/--- End Response ---/p' "$LOG_DIR/first-call.log" \
  | sed '1d;$d' \
  | sed -E 's/^[0-9:.]+ \[[^]]*\] +[A-Z]+ +[^ ]+ -[^ ]*- ?//')"
if [ -z "$(printf '%s' "$answer" | tr -d '[:space:]')" ]; then
  echo "FAIL: first-call printed an empty answer"
  fail=1
fi

run streaming 'Streaming completed successfully!' 'samples/runMain org.llm4s.samples.streaming.BasicStreamingExample'
run agent 'Final status: Completed' 'samples/runMain org.llm4s.samples.agent.SingleStepAgentExample'
run rag 'RAG Demo Complete!' 'samples/runMain org.llm4s.samples.rag.DocumentQAExample docs/getting-started'
run doctor 'PASS +Live call' \
  'configPolicy/runMain org.llm4s.configpolicy.DoctorCli --config modules/samples/src/main/resources/application.conf --local --live'

if [ "$fail" -ne 0 ]; then
  echo "Playground check FAILED (logs in $LOG_DIR)"
  exit 1
fi
echo "Playground check passed: all five commands exited 0 and worked"
