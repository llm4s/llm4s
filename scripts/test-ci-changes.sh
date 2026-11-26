#!/usr/bin/env bash
# Tests for scripts/ci-changes.sh, which decides which change-gated CI jobs a pull request runs.
#
# Each case commits a change to a scratch git repository (seeded with this repo's ci.yml, so the workspace decision it
# delegates to can compare jobs, and with a small build.sbt, PlatformTests.scala and platform-sensitive-sources.txt)
# and checks every decision the script prints. Then it checks, against this repository, that every module modules/it
# depends on is classified for the integration decision, and that ci.yml wires the decisions to the jobs. The
# workspace decision's own cases are scripts/test-workspace-tier-changes.sh's.
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
cat > "$repo/build.sbt" <<'SBT'
lazy val core = (project in file("modules/core"))
lazy val memory = (project in file("modules/memory"))
lazy val mcp = (project in file("modules/mcp"))
SBT
cat > "$repo/project/PlatformTests.scala" <<'SCALA'
object PlatformTests {
  val wholeModules: Seq[String] = Seq(
    "memory"
  )
  val suites: Seq[(String, Seq[String])] = Seq(
    "core" -> Seq("org.llm4s.config.ApiKeyFileConfigSpec"),
    "mcp"  -> Seq("org.llm4s.mcp.stdio.*")
  )
}
SCALA
cat > "$repo/scripts/platform-sensitive-sources.txt" <<'TXT'
# comment
modules/core/src/main/scala/org/llm4s/config/ApiKeyFile.scala   core:org.llm4s.config.ApiKeyFileConfigSpec
modules/mcp/src/main/scala/org/llm4s/mcp/stdio/                  mcp:org.llm4s.mcp.stdio.*
TXT
echo base > "$repo/README.md"
g add -A && g commit -qm base
BASE="$(g rev-parse HEAD)"

ORDER='code docs windows integration consumers workspace performance'
# expect <label> <the decisions that are true, space-separated, or "none"> <command run in the repo to make the change>
expect() {
  g checkout -q --detach "$BASE"
  (cd "$repo" && eval "$3")
  g add -A && g commit -qm change --allow-empty
  local out want="" n
  for n in $ORDER; do
    if [[ " $2 " == *" $n "* ]]; then want+="$n=true "; else want+="$n=false "; fi
  done
  want="${want% }"
  out="$(cd "$repo" && scripts/ci-changes.sh HEAD^1 HEAD 2>/dev/null | tr '\n' ' ' | sed 's/ $//')"
  if [ "$out" = "$want" ]; then ok "$1"; else bad "$1" "expected: $want; got: $out"; fi
}
touchp() { mkdir -p "$(dirname "$1")" && echo x >> "$1"; }
ALL_BUT_WS='code docs windows integration consumers performance'

# code, and what reaches each gated job
expect 'core'                  'code integration consumers performance' "touchp modules/core/src/main/scala/org/llm4s/util/Redaction.scala"
expect 'a core test'           'code integration consumers performance' "touchp modules/core/src/test/scala/org/llm4s/util/RedactionSpec.scala"
expect 'agent'                 'code integration consumers performance' "touchp modules/agent/src/main/scala/org/llm4s/agent/Agent.scala"
expect 'media'                 'code integration consumers performance' "touchp modules/media/src/main/scala/M.scala"
expect 'a lookalike module'    'code'                                    "touchp modules/agent-tools/src/main/scala/T.scala"
expect 'rag'                   'code integration consumers'              "touchp modules/rag/src/main/scala/Rag.scala"
expect 'a provider the Docker tier does not reach' 'code'                "touchp modules/providers/voyage/src/main/scala/V.scala"
expect 'the it module'         'code integration'                        "touchp modules/it/src/test/scala/org/llm4s/it/PgSpec.scala"
expect 'deploy/'               'code integration'                        "touchp deploy/overlays/enterprise/application.conf"
expect 'java-api'              'code consumers'                          "touchp modules/java-api/src/main/scala/J.scala"
expect 'the Spring starter'    'code consumers'                          "touchp modules/spring-boot-starter/src/main/scala/S.scala"
expect 'a Gradle sample'       'code consumers'                          "touchp modules/samples/micronaut-java/build.gradle"
expect 'the Kafka sample modules/it borrows' 'code integration'           "touchp modules/samples/src/main/scala/org/llm4s/samples/kafka/TicketPipeline.scala"
expect 'a Scala sample'        'code'                                    "touchp modules/samples/src/main/scala/S.scala"
expect 'kotlin-api'            'code consumers'                          "touchp modules/kotlin-api/build.gradle.kts"
# Windows
expect 'a whole Windows module' 'code windows integration'               "touchp modules/memory/src/main/scala/M.scala"
expect 'a listed source'       'code windows integration consumers performance' "touchp modules/core/src/main/scala/org/llm4s/config/ApiKeyFile.scala"
expect 'a listed directory'    'code windows'                            "touchp modules/mcp/src/main/scala/org/llm4s/mcp/stdio/Stdio.scala"
expect 'a named suite'         'code windows integration consumers performance' "touchp modules/core/src/test/scala/org/llm4s/config/ApiKeyFileConfigSpec.scala"
expect 'a suite by glob'       'code windows'                            "touchp modules/mcp/src/test/scala/org/llm4s/mcp/stdio/StdioSpec.scala"
expect 'another mcp suite'     'code'                                    "touchp modules/mcp/src/test/scala/org/llm4s/mcp/HttpSpec.scala"
expect 'the platform list'     'windows'                                 "echo 'x y' >> scripts/platform-sensitive-sources.txt"
# not code
expect 'docs only'             'docs'                                    "touchp docs/guide/agents.md"
expect 'a top-level markdown'  'docs'                                    "touchp CONTRIBUTING.md"
expect 'a guide a consumer checks' 'docs consumers'                      "touchp docs/guide/quarkus.md"
expect 'a ScalaDoc script'     'docs'                                    "touchp scripts/check-scaladoc-warnings.sh"
expect 'a changelog fragment'  'none'                                    "touchp changelog.d/core/1.md"
expect 'another script'        'none'                                    "touchp scripts/check-doc-seo.sh"
expect 'a consumer script'     'consumers'                               "touchp scripts/check-bom-consumers.sh"
expect 'another workflow'      'none'                                    "touchp .github/workflows/pages.yml"
expect 'an unknown kind of file' 'code'                                  "touchp codecov.yml"
expect 'docs and code'         'code docs integration consumers performance' "touchp docs/guide/agents.md; touchp modules/core/src/main/scala/C.scala"
# build files set everything but the workspace tier
expect 'build.sbt'             "$ALL_BUT_WS"                             "echo '// x' >> build.sbt"
expect 'project/'              "$ALL_BUT_WS"                             "touchp project/Dependencies.scala"
expect 'ci.yml'                "$ALL_BUT_WS"                             "sed -i.bak 's/name: Quick Checks/name: Quick Checks!/' .github/workflows/ci.yml && rm .github/workflows/ci.yml.bak"
expect 'a composite action'    "$ALL_BUT_WS"                             "touchp .github/actions/restore-sbt-deps/action.yml"
expect 'sbt options'           "$ALL_BUT_WS"                             "touchp .sbtopts"
expect 'the formatter config'  "$ALL_BUT_WS"                             "touchp .scalafmt.conf"
expect 'this script'           "$ALL_BUT_WS"                             "echo '# x' >> scripts/ci-changes.sh"
# the workspace tier keeps its own rules
expect 'the workspace runner'  'code workspace'                                  "touchp modules/workspace/workspaceRunner/src/main/scala/R.scala"
expect 'a harness file'        'code integration consumers workspace performance' "touchp modules/agent/src/main/scala/org/llm4s/agent/harness/FileTools.scala"
expect 'a deleted core file'   'code integration consumers performance' "touchp modules/core/x.txt; git add -A && git -c user.name=t -c user.email=t@example.com -c commit.gpgsign=false commit -qm add-x && git rm -q modules/core/x.txt"
expect 'no change'             'none'                                    ":"

# Every module modules/it depends on is either reached by the integration decision or listed as not reached.
label='every module modules/it depends on is classified for the integration decision'
if out="$(ruby -e '
  script, sbt = File.read(ARGV[0]), File.read(ARGV[1])
  pats = script[/^INTEGRATION=\((.*?)^\)/m, 1].to_s.scan(/'"'"'([^'"'"']+)'"'"'/).flatten.map { |p| Regexp.new(p) }
  unreached = script[/^INTEGRATION_UNREACHED=.([^'"'"']*)/m, 1].to_s.split
  dirs = sbt.scan(/^lazy val (\w+)\s*=\s*\(?project in file\("([^"]+)"\)/).to_h.transform_values { |d| d.squeeze("/") }
  deps = sbt[/^lazy val it = .*?\.dependsOn\((.*?)\)\s*\.settings/m, 1].to_s.gsub(%r{//[^\n]*}, "").scan(/^\s*(\w+)/).flatten
  abort "no dependsOn of it in build.sbt" if deps.empty?
  errs = deps.reject do |d|
    unreached.include?(d) || (dirs[d] && pats.any? { |p| p.match?("#{dirs[d]}/x") })
  end
  errs = errs.map { |d| "#{d}: add its directory to INTEGRATION or its id to INTEGRATION_UNREACHED in scripts/ci-changes.sh" }
  # sources borrowed with `(<project> / baseDirectory).value / "a" / "b"` in the it project settings
  block = sbt[/^lazy val it = .*?(?=^lazy val )/m].to_s
  block.scan(/\((\w+)\s*\/\s*baseDirectory\)\.value((?:\s*\/\s*"[^"]+")+)/).each do |proj, segs|
    path = ([dirs[proj]] + segs.scan(/"([^"]+)"/).flatten).join("/")
    next if pats.any? { |p| p.match?("#{path}/x") || p.match?(path) }
    errs << "modules/it borrows #{path}: add it to INTEGRATION in scripts/ci-changes.sh"
  end
  puts errs
  extra = unreached.reject { |d| deps.include?(d) }
  puts extra.map { |d| "#{d} is in INTEGRATION_UNREACHED, but modules/it does not depend on it" }
  exit(errs.empty? && extra.empty? ? 0 : 1)
' "$SCRIPT" "$REPO_ROOT/build.sbt")"; then ok "$label"; else bad "$label" "$out"; fi

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
  names = %w[draft] + ARGV[1].split
  names.each do |n|
    errs << "changes has no output #{n}" unless ch.dig("outputs", n) == "${{ steps.diff.outputs.#{n} }}"
    errs << "the diff step does not write #{n}=false for a draft" unless n == "draft" || run.include?("#{n}=false")
  end
  { "coverage-leg" => "code", "coverage" => "code", "test" => "code", "test-windows" => "windows",
    "integration-tests" => "integration", "consumers" => "consumers", "spring-compat" => "consumers",
    "performance-tests" => "performance", "workspace-integration" => "workspace", "scaladoc" => "docs",
    "docs-tests" => "docs" }.each do |job, n|
    j = jobs[job] or (errs << "no job #{job}"; next)
    errs << "#{job} does not need changes" unless Array(j["needs"]).include?("changes")
    errs << "#{job} is not gated on needs.changes.outputs.#{n}" unless j["if"].to_s.include?("needs.changes.outputs.#{n}")
  end
  puts errs
  exit(errs.empty? ? 0 : 1)
' "$CI" "$ORDER")"; then ok "$label"; else bad "$label" "$out"; fi

echo
echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
