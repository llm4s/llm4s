#!/usr/bin/env bash
# Tests for scripts/ci-changes.sh, which decides which change-gated CI jobs a pull request runs.
#
# Each case commits a change to a scratch git repository (seeded with this repo's ci.yml, so the workspace decision it
# delegates to can compare jobs) and checks every decision the script prints. Then it checks that ci.yml wires the
# decisions to the jobs. The workspace decision's own cases are scripts/test-workspace-tier-changes.sh's.
#
# No network. Usage: scripts/test-ci-changes.sh      (needs git and ruby, both on GitHub's runners)
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="${CI_CHANGES_SCRIPT:-$REPO_ROOT/scripts/ci-changes.sh}"   # run against another copy (mutation checks)
CI="$REPO_ROOT/.github/workflows/ci.yml"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

FAIL=0
PASS=0
ok()  { PASS=$((PASS + 1)); echo "ok   [$1]"; }
bad() { FAIL=$((FAIL + 1)); echo "FAIL [$1]"; [ $# -lt 2 ] || echo "       $2"; }

repo="$WORK/repo"
git init -q "$repo"
g() { git -C "$repo" -c user.name=t -c user.email=t@example.com -c commit.gpgsign=false "$@"; }
mkdir -p "$repo/.github/workflows" "$repo/scripts" "$repo/project"
cp "$CI" "$repo/.github/workflows/ci.yml"
# the script calls the workspace decision beside it, so both are copied into the scratch repo's scripts/
cp "$SCRIPT" "$repo/scripts/ci-changes.sh"
cp "$REPO_ROOT/scripts/workspace-tier-changes.sh" "$repo/scripts/workspace-tier-changes.sh"
printf 'lazy val core = project\n' > "$repo/build.sbt"
printf 'object Versions\n' > "$repo/project/Dependencies.scala"
echo base > "$repo/README.md"
g add -A && g commit -qm base
BASE="$(g rev-parse HEAD)"

# expect <label> <expected output, the decisions in order, space-separated> <command run in the repo to make the change>
expect() {
  g checkout -q --detach "$BASE"
  (cd "$repo" && eval "$3")
  g add -A && g commit -qm change --allow-empty
  local out
  out="$(cd "$repo" && scripts/ci-changes.sh HEAD^1 HEAD 2>/dev/null | tr '\n' ' ' | sed 's/ $//')"
  if [ "$out" = "$2" ]; then ok "$1"; else bad "$1" "expected: $2; got: $out"; fi
}
touchp() { mkdir -p "$(dirname "$1")" && echo x >> "$1"; }

expect 'core'                 'workspace=false performance=true'  "touchp modules/core/src/main/scala/org/llm4s/util/Redaction.scala"
expect 'a core test'          'workspace=false performance=true'  "touchp modules/core/src/test/scala/org/llm4s/util/RedactionSpec.scala"
expect 'agent'                'workspace=false performance=true'  "touchp modules/agent/src/main/scala/org/llm4s/agent/Agent.scala"
expect 'media (core depends on it)' 'workspace=false performance=true' "touchp modules/media/src/main/scala/M.scala"
expect 'a lookalike module'   'workspace=false performance=false' "touchp modules/agent-tools/src/main/scala/T.scala"
expect 'rag'                  'workspace=false performance=false' "touchp modules/rag/src/main/scala/Rag.scala"
expect 'docs only'            'workspace=false performance=false' "touchp docs/guide/agents.md"
expect 'build.sbt'            'workspace=false performance=true'  "echo '// x' >> build.sbt"
expect 'project/'             'workspace=false performance=true'  "touchp project/Dependencies.scala"
expect 'ci.yml'               'workspace=false performance=true'  "sed -i.bak 's/name: Quick Checks/name: Quick Checks!/' .github/workflows/ci.yml && rm .github/workflows/ci.yml.bak"
expect 'a composite action'   'workspace=false performance=true'  "touchp .github/actions/restore-sbt-deps/action.yml"
expect 'sbt options'          'workspace=false performance=true'  "touchp .sbtopts"
expect 'this script'          'workspace=false performance=true'  "echo '# x' >> scripts/ci-changes.sh"
expect 'another workflow'     'workspace=false performance=false' "touchp .github/workflows/pages.yml"
expect 'the workspace runner' 'workspace=true performance=false'  "touchp modules/workspace/workspaceRunner/src/main/scala/R.scala"
expect 'a harness file'       'workspace=true performance=true'   "touchp modules/agent/src/main/scala/org/llm4s/agent/harness/FileTools.scala"
expect 'a deleted core file'  'workspace=false performance=true'  "touchp modules/core/x.txt; git add -A && git -c user.name=t -c user.email=t@example.com -c commit.gpgsign=false commit -qm add-x && git rm -q modules/core/x.txt"
expect 'no change'            'workspace=false performance=false' ":"

# ci.yml wires the decisions: the `changes` job runs the script into its outputs (all false on a draft), and each
# gated job reads its own decision.
label='ci.yml: the changes job publishes every decision, and each gated job reads its own'
# shellcheck disable=SC2016 # the ${{ }} are GitHub expressions, matched literally
if out="$(ruby -ryaml -e '
  jobs = YAML.load_file(ARGV[0])["jobs"]
  ch = jobs["changes"] or abort "no changes job"
  step = (ch["steps"] || []).find { |s| s["id"] == "diff" } or abort "no step with id diff"
  run = step["run"].to_s
  errs = []
  errs << "the diff step does not run scripts/ci-changes.sh HEAD^1 HEAD" unless run.include?("scripts/ci-changes.sh HEAD^1 HEAD")
  names = %w[draft workspace performance]
  names.each do |n|
    errs << "changes has no output #{n}" unless ch.dig("outputs", n) == "${{ steps.diff.outputs.#{n} }}"
    errs << "the diff step does not write #{n}=false for a draft" unless n == "draft" || run.include?("#{n}=false")
  end
  {"performance-tests" => "performance", "workspace-integration" => "workspace"}.each do |job, n|
    j = jobs[job] or (errs << "no job #{job}"; next)
    errs << "#{job} does not need changes" unless Array(j["needs"]).include?("changes")
    errs << "#{job} is not gated on needs.changes.outputs.#{n}" unless j["if"].to_s.include?("needs.changes.outputs.#{n}")
  end
  puts errs
  exit(errs.empty? ? 0 : 1)
' "$CI")"; then ok "$label"; else bad "$label" "$out"; fi

echo
echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
