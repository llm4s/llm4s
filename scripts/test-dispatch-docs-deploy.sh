#!/usr/bin/env bash
# Tests for scripts/dispatch-docs-deploy.sh and for the workflow files that use it (#1152).
#
# No network, no Actions run, no token. `gh` is replaced by a fake that logs every call and answers `workflow run`,
# `run list` and `run view` from per-case settings. The fake feeds JSON through the real `jq` with the very filter
# expression the script passes to `gh --jq`, so the script's run-matching is exercised, not stubbed.
#
# The cases cover: the tag check (valid tags pass; injection attempts, branches and malformed tags are refused before
# anything runs), finding the dispatched run among other runs, every way the deploy can end (success, failure,
# cancelled, still running, never appeared, unreadable), and that pages.yml, release.yml and the script agree on the
# run's title and the tag pattern, with no workflow input interpolated into a shell.
#
# Usage: scripts/test-dispatch-docs-deploy.sh      (needs bash, jq and ruby, all on GitHub's runners)
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="${DISPATCH_SCRIPT:-$REPO_ROOT/scripts/dispatch-docs-deploy.sh}"   # DISPATCH_SCRIPT: run the tests against another copy (mutation checks)
PAGES="$REPO_ROOT/.github/workflows/pages.yml"
RELEASE="$REPO_ROOT/.github/workflows/release.yml"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

for tool in jq ruby; do
  command -v "$tool" >/dev/null || { echo "FAIL: $tool is needed to run these tests"; exit 1; }
done

FAIL=0
PASS=0
ok()   { PASS=$((PASS + 1)); echo "ok   [$1]"; }
bad()  { FAIL=$((FAIL + 1)); echo "FAIL [$1]"; [ $# -lt 2 ] || echo "       $2"; }
check() { if [ "$2" = "yes" ]; then ok "$1"; else bad "$1" "${3:-}"; fi; }

# ---- the fake gh ------------------------------------------------------------------------------------------------
FAKE_BIN="$WORK/bin"
mkdir -p "$FAKE_BIN"
cat > "$FAKE_BIN/gh" <<'FAKE'
#!/usr/bin/env bash
echo "gh $*" >> "$FAKE_LOG"
jq_expr=""; prev=""
for a in "$@"; do if [ "$prev" = "--jq" ]; then jq_expr="$a"; fi; prev="$a"; done
bump() { n=0; [ -f "$FAKE_STATE/$1" ] && n=$(cat "$FAKE_STATE/$1"); n=$((n + 1)); echo "$n" > "$FAKE_STATE/$1"; echo "$n"; }
case "$1 $2" in
  "workflow run")
    touch "$FAKE_STATE/dispatched"
    exit "${FAKE_DISPATCH_RC:-0}" ;;
  "run list")
    json="$FAKE_LIST_BEFORE"
    if [ -f "$FAKE_STATE/dispatched" ]; then
      polls=$(bump polls_after_dispatch)
      if [ "$polls" -gt "${FAKE_APPEAR_AFTER:-0}" ]; then json="$FAKE_LIST_AFTER"; fi
    fi
    printf '%s' "$json" | jq -r "$jq_expr" ;;
  "run view")
    case "$*" in
      *"--json url"*)
        printf '{"url":"https://github.com/o/r/actions/runs/%s"}' "$3" | jq -r "$jq_expr" ;;
      *"--json status,conclusion"*)
        if [ -n "${FAKE_VIEW_FAIL:-}" ]; then exit 1; fi
        n=$(bump views)
        seq=($FAKE_STATUS_SEQ)
        idx=$((n - 1)); [ "$idx" -lt "${#seq[@]}" ] || idx=$((${#seq[@]} - 1))
        s="${seq[$idx]}"; status="${s%%:*}"; conclusion="null"
        case "$s" in *:*) conclusion="\"${s#*:}\"" ;; esac
        printf '{"status":"%s","conclusion":%s}' "$status" "$conclusion" | jq -r "$jq_expr" ;;
    esac ;;
esac
FAKE
chmod +x "$FAKE_BIN/gh"

TITLE_OF() { printf 'Deploy Documentation (%s)' "$1"; }
RUN_JSON() { printf '{"databaseId":%s,"displayTitle":"%s"}' "$1" "$2"; }

# new_case: fresh state and log; defaults are the happy path (an older run of the same tag exists; the new one
# appears on the first poll and finishes on the third).
new_case() {
  CASE_DIR="$(mktemp -d "$WORK/case.XXXXXX")"
  export FAKE_STATE="$CASE_DIR/state" FAKE_LOG="$CASE_DIR/log"
  mkdir -p "$FAKE_STATE"; : > "$FAKE_LOG"
  TAG="${1:-v0.5.0}"
  export FAKE_DISPATCH_RC=0 FAKE_APPEAR_AFTER=0 FAKE_STATUS_SEQ="queued in_progress completed:success"
  unset FAKE_VIEW_FAIL
  export FAKE_LIST_BEFORE="[$(RUN_JSON 11 "$(TITLE_OF "$TAG")")]"
  export FAKE_LIST_AFTER="[$(RUN_JSON 12 "$(TITLE_OF "$TAG")"),$(RUN_JSON 11 "$(TITLE_OF "$TAG")")]"
}

# run_script <tag> -> sets OUT and RC
# The script runs in the background under a watchdog: one that never returns (a lost timeout) is killed after
# WATCHDOG seconds and its case fails with exit 124, instead of hanging this whole test (and a CI job with it).
run_script() {
  local out="$CASE_DIR/script.out" pid wd
  : > "$out"
  ( PATH="$FAKE_BIN:$PATH" GITHUB_REPOSITORY=o/r DOCS_POLL_INTERVAL="${POLL:-0.05}" \
      DOCS_FIND_TIMEOUT="${FIND_T:-3}" DOCS_RUN_TIMEOUT="${RUN_T:-3}" exec "$SCRIPT" "$1" ) > "$out" 2>&1 &
  pid=$!
  ( sleep "${WATCHDOG:-30}"; kill "$pid" 2>/dev/null ) &
  wd=$!
  wait "$pid" && RC=0 || RC=$?
  kill "$wd" 2>/dev/null || true
  wait "$wd" 2>/dev/null || true
  [ "$RC" -ne 143 ] || RC=124
  OUT="$(cat "$out")"
}
logged() { grep -c "$1" "$FAKE_LOG" || true; }

echo "== the happy path"
new_case v0.5.0
run_script "$TAG"
check "deploys: exit 0" "$([ "$RC" = 0 ] && echo yes || echo no)" "rc=$RC: $OUT"
check "dispatches pages.yml on main with the tag as ref" "$(grep -q 'gh workflow run pages.yml -R o/r --ref main -f ref=v0.5.0' "$FAKE_LOG" && echo yes || echo no)" "$(cat "$FAKE_LOG")"
check "lists runs on main only (--branch main), so a same-titled run elsewhere is never waited for" "$(grep 'run list' "$FAKE_LOG" | grep -q -- '--branch main' && ! grep 'run list' "$FAKE_LOG" | grep -vq -- '--branch main' && echo yes || echo no)" "$(grep 'run list' "$FAKE_LOG")"
check "dispatches exactly once" "$([ "$(logged 'workflow run')" = 1 ] && echo yes || echo no)"
check "waits for run 12, not the older run 11" "$(grep -q 'run view 12' "$FAKE_LOG" && ! grep -q 'run view 11 ' "$FAKE_LOG" && echo yes || echo no)" "$(cat "$FAKE_LOG")"
check "reports the run's URL" "$(echo "$OUT" | grep -q 'actions/runs/12' && echo yes || echo no)" "$OUT"
check "says the docs are deployed" "$(echo "$OUT" | grep -q 'Docs deployed for v0.5.0' && echo yes || echo no)" "$OUT"

new_case v1.0.0-RC1
run_script "$TAG"
check "a pre-release tag (v1.0.0-RC1) deploys" "$([ "$RC" = 0 ] && echo yes || echo no)" "rc=$RC: $OUT"
new_case v1.2.3-rc.1
run_script "$TAG"
check "a dotted pre-release tag (v1.2.3-rc.1) deploys" "$([ "$RC" = 0 ] && echo yes || echo no)" "rc=$RC: $OUT"

echo "== finding the dispatched run"
new_case v0.5.0
export FAKE_APPEAR_AFTER=3
run_script "$TAG"
check "a run that appears after a few polls is still found" "$([ "$RC" = 0 ] && echo yes || echo no)" "rc=$RC: $OUT"
# Until the new run is listed, the list still shows the older run of the same tag: it must not be mistaken for ours.
check "and the older run of the same tag is never the one waited for" "$(grep -q 'run view 12' "$FAKE_LOG" && ! grep -q 'run view 11 ' "$FAKE_LOG" && echo yes || echo no)" "$(cat "$FAKE_LOG")"

new_case v0.5.0
export FAKE_LIST_BEFORE="[]"
export FAKE_LIST_AFTER="[$(RUN_JSON 40 "$(TITLE_OF v0.4.1)"),$(RUN_JSON 41 'Deploy Documentation'),$(RUN_JSON 42 'Deploy Documentation (v0.5.0-RC1)'),$(RUN_JSON 12 "$(TITLE_OF v0.5.0)")]"
run_script "$TAG"
check "other tags' runs and a manual run with no tag are ignored" "$(grep -q 'run view 12' "$FAKE_LOG" && ! grep -qE 'run view (40|41|42)' "$FAKE_LOG" && [ "$RC" = 0 ] && echo yes || echo no)" "rc=$RC: $(cat "$FAKE_LOG")"

new_case v0.5.0
export FAKE_LIST_BEFORE="[]"
export FAKE_LIST_AFTER="[$(RUN_JSON 40 "$(TITLE_OF v0.4.1)"),$(RUN_JSON 41 'Deploy Documentation')]"
FIND_T=1 run_script "$TAG"
check "no run for this tag ever appears: exit 5" "$([ "$RC" = 5 ] && echo yes || echo no)" "rc=$RC: $OUT"

echo "== every way the deploy can end"
new_case v0.5.0
export FAKE_STATUS_SEQ="queued in_progress completed:failure"
run_script "$TAG"
check "a failed deploy fails the release job: exit 1" "$([ "$RC" = 1 ] && echo yes || echo no)" "rc=$RC: $OUT"
check "the failure says it failed and links the run" "$(echo "$OUT" | grep -q 'deploy failed (failure)' && echo "$OUT" | grep -q 'actions/runs/12' && echo yes || echo no)" "$OUT"

new_case v0.5.0
export FAKE_STATUS_SEQ="queued completed:cancelled"
run_script "$TAG"
check "a cancelled deploy is not a pass: exit 3" "$([ "$RC" = 3 ] && echo yes || echo no)" "rc=$RC: $OUT"
check "the cancelled message tells the maintainer what to check" "$(echo "$OUT" | grep -q 'shows v0.5.0' && echo yes || echo no)" "$OUT"

new_case v0.5.0
export FAKE_STATUS_SEQ="in_progress"
RUN_T=1 run_script "$TAG"
check "a deploy that never finishes times out: exit 4" "$([ "$RC" = 4 ] && echo yes || echo no)" "rc=$RC: $OUT"

new_case v0.5.0
export FAKE_STATUS_SEQ="completed:timed_out"
run_script "$TAG"
check "any other conclusion (timed_out) is a failure: exit 1" "$([ "$RC" = 1 ] && echo yes || echo no)" "rc=$RC: $OUT"

new_case v0.5.0
export FAKE_VIEW_FAIL=1
RUN_T=60 run_script "$TAG"
check "a run that cannot be read fails fast instead of waiting out the timeout: exit 1" "$([ "$RC" = 1 ] && echo yes || echo no)" "rc=$RC: $OUT"
check "after ten unreadable polls, not sixty seconds' worth" "$([ "$(logged 'json status,conclusion')" -le 11 ] && echo yes || echo no)" "$(logged 'json status,conclusion') polls"

new_case v0.5.0
export FAKE_DISPATCH_RC=1
run_script "$TAG"
check "a failed dispatch fails the job: exit 1" "$([ "$RC" = 1 ] && echo yes || echo no)" "rc=$RC: $OUT"
check "and it does not go on to wait for a run" "$(! grep -q 'run view' "$FAKE_LOG" && echo yes || echo no)" "$(cat "$FAKE_LOG")"

echo "== the tag check: nothing reaches gh unless it is a release tag"
SENTINEL="$WORK/pwned"
refuse() {
  new_case v0.5.0
  run_script "$1"
  check "refused: $2" "$([ "$RC" = 2 ] && [ ! -s "$FAKE_LOG" ] && [ ! -e "$SENTINEL" ] && echo yes || echo no)" "rc=$RC log=$(cat "$FAKE_LOG") out=$OUT"
}
refuse 'v1.0.0; rm -rf /' "a command after a semicolon"
refuse "\$(touch $SENTINEL)" "command substitution"
refuse "v1.0.0\`touch $SENTINEL\`" "a backtick command"
refuse "v1.0.0
foo" "a newline"
refuse 'v1.0.0 ' "a trailing space"
refuse 'v1.0.0"' "a quote"
refuse 'main' "a branch name"
refuse 'refs/heads/main' "a full ref"
refuse 'refs/tags/v1.0.0' "a full tag ref"
refuse '1.2.3' "no v prefix"
refuse 'v1.2' "two components"
refuse 'v1.2.3/../../x' "a path"
refuse '' "an empty tag"
refuse "v1.0.0-$(printf 'a%.0s' $(seq 1 70))" "an overlong tag"

new_case v0.5.0
OUT="$(PATH="$FAKE_BIN:$PATH" GITHUB_REPOSITORY=o/r DOCS_FIND_TIMEOUT='3; echo x' "$SCRIPT" v0.5.0 2>&1)" && RC=0 || RC=$?
check "a non-numeric timeout is refused: exit 2" "$([ "$RC" = 2 ] && [ ! -s "$FAKE_LOG" ] && echo yes || echo no)" "rc=$RC: $OUT"
new_case v0.5.0
OUT="$(PATH="$FAKE_BIN:$PATH" GITHUB_REPOSITORY= "$SCRIPT" v0.5.0 2>&1)" && RC=0 || RC=$?
check "a missing GITHUB_REPOSITORY is refused: exit 2" "$([ "$RC" = 2 ] && [ ! -s "$FAKE_LOG" ] && echo yes || echo no)" "rc=$RC: $OUT"

echo "== the workflow files and the script agree"
YAML_CHECK="$WORK/check.rb"
cat > "$YAML_CHECK" <<'RUBY'
require 'yaml'
pages   = YAML.load_file(ARGV[0])
release = YAML.load_file(ARGV[1])
script  = File.read(ARGV[2])
pages_text = File.read(ARGV[0])
errs = []
trig = pages[true] || pages['on']            # YAML 1.1 reads the key `on` as true
errs << "pages.yml still has workflow_call (a tag-ref caller would be rejected by the environment)" if trig.key?('workflow_call')
errs << "pages.yml has no workflow_dispatch" unless trig.key?('workflow_dispatch')
input = (trig['workflow_dispatch'] || {}).dig('inputs', 'ref')
errs << "workflow_dispatch has no `ref` input" if input.nil?
errs << "the ref input must be an optional string" unless input && input['type'] == 'string' && input['required'] == false
errs << "the push trigger changed (must still be main with the docs paths)" unless trig['push']['branches'] == ['main'] && trig['push']['paths'].include?('docs/**') && trig['push']['paths'].include?('.github/workflows/pages.yml')
errs << "permissions changed" unless pages['permissions'] == { 'contents' => 'read', 'pages' => 'write', 'id-token' => 'write' }
errs << "concurrency changed" unless pages['concurrency'] == { 'group' => 'pages', 'cancel-in-progress' => false }
errs << "the deploy job must use the github-pages environment" unless pages['jobs']['deploy']['environment']['name'] == 'github-pages'
errs << "the deploy job must still wait for the build" unless pages['jobs']['deploy']['needs'] == 'build'

steps = pages['jobs']['build']['steps']
checkout = steps.find { |s| s['uses'].to_s.start_with?('actions/checkout') }
errs << "the build checkout must pin a requested ref to refs/tags/" unless checkout['with']['ref'].to_s.include?("format('refs/tags/{0}', inputs.ref)")
errs << "the build checkout must fall back to github.ref" unless checkout['with']['ref'].to_s.include?('github.ref')
validate = steps.find { |s| s['name'] == 'Validate the requested ref' }
errs << "there must be a validation step before the checkout" unless validate && steps.index(validate) < steps.index(checkout)
errs << "the validation step must read the input through env" unless validate && validate['env'] && validate['env']['REQUESTED_REF'] == '${{ inputs.ref }}'

# no workflow input may be interpolated into a shell
[[pages, 'pages.yml'], [release, 'release.yml']].each do |wf, name|
  wf['jobs'].each do |jid, job|
    (job['steps'] || []).each do |s|
      errs << "#{name} job #{jid}: an input is interpolated into a script" if s['run'].to_s =~ /\$\{\{\s*(inputs|github\.event\.inputs|github\.head_ref|github\.ref_name)/
    end
  end
end

docs = release['jobs']['docs']
errs << "release.yml docs job is missing" if docs.nil?
if docs
  errs << "the docs job must still wait for github-release" unless docs['needs'] == 'github-release'
  errs << "the docs job must not call pages.yml as a reusable workflow (it would run on the tag)" if docs.key?('uses')
  errs << "the docs job needs `actions: write` to dispatch" unless (docs['permissions'] || {})['actions'] == 'write'
  errs << "the docs job should not ask for more than it needs" unless (docs['permissions'].keys - %w[actions contents]).empty?
  run = (docs['steps'] || []).map { |s| s['run'].to_s }.join("\n")
  errs << "the docs job must run the dispatch script with the tag" unless run.include?('scripts/dispatch-docs-deploy.sh "$GITHUB_REF_NAME"')
  errs << "the docs job must have a job timeout" unless docs['timeout-minutes'].is_a?(Integer)
  errs << "the docs job needs GH_TOKEN" unless (docs['steps'] || []).any? { |s| (s['env'] || {})['GH_TOKEN'] == '${{ github.token }}' }
end

# the run's title and the tag pattern are defined in two places each: they must agree
rn = pages['run-name'].to_s
title_in_pages = rn[/format\('([^']*)\{0\}\)'/, 1]
fmt_in_script  = script[/DOCS_RUN_TITLE:-([^}]*)\}/, 1]
errs << "pages.yml run-name (#{rn}) and the script's default title (#{fmt_in_script}) differ" unless title_in_pages && fmt_in_script && "#{title_in_pages}%s)" == fmt_in_script
pat_pages  = pages_text[/=~ (\^v[^ ]+\$) \]\]/, 1]
pat_script = script[/TAG_PATTERN='([^']*)'/, 1]
errs << "the tag pattern differs: pages.yml #{pat_pages.inspect} vs script #{pat_script.inspect}" unless pat_pages && pat_pages == pat_script
puts errs
exit(errs.empty? ? 0 : 1)
RUBY
CHECK_OUT="$(ruby "$YAML_CHECK" "$PAGES" "$RELEASE" "$SCRIPT" 2>&1)" && CHECK_RC=0 || CHECK_RC=$?
check "pages.yml, release.yml and the script agree (trigger, ref handling, permissions, no interpolation, title, pattern)" "$([ "$CHECK_RC" = 0 ] && echo yes || echo no)" "$CHECK_OUT"

echo
echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
