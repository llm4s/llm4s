#!/usr/bin/env bash
# Tests for scripts/workspace-tier-changes.sh, which decides whether CI runs the @Workspace tier on a PR (#1982).
#
# Each case commits a change to a scratch git repository seeded with this repo's ci.yml and checks the verdict: a
# change under each watched path runs the tier, an unrelated change does not, and a ci.yml change runs it only when it
# edits the workspace-changes or workspace-integration job. Also checks that ci.yml wires the verdict to the tier.
#
# No network. Usage: scripts/test-workspace-tier-changes.sh      (needs git and ruby, both on GitHub's runners)
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="${WORKSPACE_TIER_SCRIPT:-$REPO_ROOT/scripts/workspace-tier-changes.sh}"   # run against another copy (mutation checks)
CI="$REPO_ROOT/.github/workflows/ci.yml"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

FAIL=0
PASS=0
ok()   { PASS=$((PASS + 1)); echo "ok   [$1]"; }
bad()  { FAIL=$((FAIL + 1)); echo "FAIL [$1]"; [ $# -lt 2 ] || echo "       $2"; }

repo="$WORK/repo"
git init -q "$repo"
g() { git -C "$repo" -c user.name=t -c user.email=t@example.com -c commit.gpgsign=false "$@"; }
mkdir -p "$repo/.github/workflows"
cp "$CI" "$repo/.github/workflows/ci.yml"
# a build.sbt shaped like the real one: projects defined at column 0, their settings indented
cat > "$repo/build.sbt" <<'SBT'
addCommandAlias("testWorkspace", ItTiers.alias(ItTiers.Workspace))

lazy val rag = (project in file("modules/rag"))
  .settings(
    name := "llm4s-rag",
    libraryDependencies ++= Seq(Deps.cats)
  )

lazy val workspaceRunner = (project in file("modules/workspace/workspaceRunner"))
  .settings(
    Compile / mainClass := Some("org.llm4s.runner.RunnerMain"),
    libraryDependencies ++= Seq(Deps.cask)
  )
SBT
echo base > "$repo/README.md"
g add -A && g commit -qm base
BASE="$(g rev-parse HEAD)"

# expect <label> <true|false> <command run in the repo to make the change>
expect() {
  g checkout -q --detach "$BASE"
  (cd "$repo" && eval "$3")
  g add -A && g commit -qm change --allow-empty
  local out
  out="$(cd "$repo" && "$SCRIPT" HEAD^1 HEAD 2>/dev/null)"
  if [ "$out" = "changed=$2" ]; then ok "$1"; else bad "$1" "expected changed=$2, got: $out"; fi
}
touchp() { mkdir -p "$(dirname "$1")" && echo x >> "$1"; }

expect 'the runner'               true  "touchp modules/workspace/workspaceRunner/src/main/scala/Runner.scala"
expect 'the shared protocol'      true  "touchp modules/workspace/workspaceShared/src/main/scala/Cmd.scala"
expect 'a workspace backend'      true  "touchp modules/agent/src/main/scala/org/llm4s/agent/harness/workspace/WorkspaceFileBackend.scala"
expect 'ExecuteTool'              true  "touchp modules/agent/src/main/scala/org/llm4s/agent/harness/ExecuteTool.scala"
expect 'SandboxBackend'           true  "touchp modules/agent/src/main/scala/org/llm4s/agent/harness/SandboxBackend.scala"
expect 'a @Workspace suite'       true  "touchp modules/it/src/test/scala/org/llm4s/workspace/NewSpec.scala"
expect 'the Workspace tag'        true  "touchp modules/it/src/test/java/org/llm4s/it/tags/Workspace.java"
expect 'the workspace sample'     true  "touchp modules/samples/src/main/scala/org/llm4s/samples/harness/CodingWorkspaceExample.scala"
expect 'the image build'          true  "touchp project/WorkspaceRunnerDocker.scala"
expect 'this decision script'     true  "touchp scripts/workspace-tier-changes.sh"
expect 'a deleted watched file'   true  "touchp modules/workspace/x.txt; git add -A && git -c user.name=t -c user.email=t@example.com -c commit.gpgsign=false commit -qm add-x && git rm -q modules/workspace/x.txt"
expect 'a watched file renamed out' true  "touchp modules/workspace/moved.txt; git add -A && git -c user.name=t -c user.email=t@example.com -c commit.gpgsign=false commit -qm add-moved && mkdir -p docs && git mv modules/workspace/moved.txt docs/moved.txt"
expect 'the tier alias helper'    true  "touchp project/ItTiers.scala"
expect 'build.sbt, workspace project' true "sed -i.bak 's/Deps.cask/Deps.cask, Deps.config/' build.sbt && rm build.sbt.bak"
expect 'build.sbt, the tier alias' true "sed -i.bak 's/ItTiers.Workspace))/ItTiers.Workspace)) \/\/ x/' build.sbt && rm build.sbt.bak"
expect 'build.sbt, another project' false "sed -i.bak 's/Deps.cats/Deps.cats, Deps.config/' build.sbt && rm build.sbt.bak"
expect 'an unrelated module'      false "touchp modules/rag/src/main/scala/Rag.scala"
expect 'another harness file'     false "touchp modules/agent/src/main/scala/org/llm4s/agent/harness/TodoMiddleware.scala"
expect 'another @Docker suite'    false "touchp modules/it/src/test/scala/org/llm4s/vectorstore/PgSpec.scala"
expect 'a lookalike path'         false "touchp modules/workspace-ish/README.md"
expect 'docs only'                false "touchp docs/guide/workspace.md"
expect 'ci.yml, another job'      false "sed -i.bak 's/name: Quick Checks/name: Quick Checks!/' .github/workflows/ci.yml && rm .github/workflows/ci.yml.bak"
expect 'ci.yml, the tier job'     true  "sed -i.bak 's/sbt testWorkspace/sbt testWorkspace -v/' .github/workflows/ci.yml && rm .github/workflows/ci.yml.bak"
expect 'ci.yml, the decision job' true  "sed -i.bak 's/fetch-depth: 2/fetch-depth: 3/' .github/workflows/ci.yml && rm .github/workflows/ci.yml.bak"
expect 'no change'                false ":"

# ci.yml wires the verdict to the tier, and the tier into All Tests Pass
label='ci.yml runs the tier on the verdict and All Tests Pass needs it'
# shellcheck disable=SC2016 # the ${{ }} are GitHub expressions, matched literally
if ruby -ryaml -e '
  jobs = YAML.load_file(ARGV[0])["jobs"]
  wc, wi, atp = jobs.values_at("workspace-changes", "workspace-integration", "all-tests-pass")
  step = (wc["steps"] || []).find { |s| s["id"] == "diff" }
  ok = wc["outputs"]["changed"] == "${{ steps.diff.outputs.changed }}" &&
       step["run"].include?("scripts/workspace-tier-changes.sh HEAD^1 HEAD") &&
       Array(wi["needs"]).include?("workspace-changes") &&
       wi["if"].include?("needs.workspace-changes.outputs.changed == '"'"'true'"'"'") &&
       wi["if"].include?("!cancelled()") &&
       (Array(atp["needs"]) & %w[workspace-changes workspace-integration]).size == 2
  exit(ok ? 0 : 1)
' "$CI"; then ok "$label"; else bad "$label"; fi

echo
echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
