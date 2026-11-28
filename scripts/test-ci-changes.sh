#!/usr/bin/env bash
# Tests for scripts/ci-changes.sh, which decides which change-gated CI jobs a pull request or merge group runs, and
# which modules' tests (the coverage legs, #3185 and #3186).
#
# Each case commits a change to a scratch git repository (seeded with this repo's ci.yml, so the workspace decision it
# delegates to can compare jobs, with a small build.sbt, PlatformTests.scala and platform-sensitive-sources.txt, and
# with a small project graph and leg list made for the module rule's cases) and checks what the script prints. Then:
#   - the module rule against this repository's own graph and legs (a core change reaches every leg in full, ...);
#   - every module modules/it depends on is classified for the integration decision;
#   - every test source that names a file of another module is covered by the graph or by READS;
#   - ci.yml's `changes` steps, run as bash in the scratch repo for each event (draft, pull request, label, merge
#     group, push, nightly), write the outputs they should;
#   - ci.yml wires the outputs to the jobs, and the `Code Coverage` gate's and `All Tests Pass`'s steps, run as bash
#     with each job result and output substituted, pass and fail where they should.
# The workspace decision's own cases are scripts/test-workspace-tier-changes.sh's.
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
lazy val performanceTestProjects = Seq("core", "agent")
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
# The graph the module rule's cases run on. Every kind of edge the real graph has: compile (transitive),
# compile->compile;test->test (rag, memory, agent on core), % Test as `test` (rag on memory, providerTestkit on
# testkit, voyage on providerTestkit), provided and optional, a nested base directory (boot4 in the starter), borrowed
# sources (agentTools borrows core's DocsSpec, it borrows a samples source) and the root project at `.`.
cat > "$repo/.github/ci-graph.json" <<'JSON'
{
  "projects": {
    "llm4s": {"base":".","deps":[["core","compile->compile"]],"borrowed":[]},
    "media": {"base":"modules/media","deps":[],"borrowed":[]},
    "core": {"base":"modules/core","deps":[["media","compile->compile"]],"borrowed":[]},
    "testkit": {"base":"modules/testkit","deps":[["core","compile->compile"]],"borrowed":[]},
    "memory": {"base":"modules/memory","deps":[["core","compile->compile;test->test"]],"borrowed":[]},
    "agentTools": {"base":"modules/agent-tools","deps":[["core","compile->compile"]],"borrowed":[["test","modules/core/src/test/java/org/llm4s/tags/DocsSpec.java"]]},
    "knowledgegraph": {"base":"modules/knowledgegraph","deps":[["core","compile->compile"]],"borrowed":[]},
    "rag": {"base":"modules/rag","deps":[["core","compile->compile;test->test"],["knowledgegraph","compile->compile"],["memory","test"]],"borrowed":[]},
    "mcp": {"base":"modules/mcp","deps":[["core","compile->compile"]],"borrowed":[]},
    "providerTestkit": {"base":"modules/provider-testkit","deps":[["core","compile->compile"],["testkit","test"]],"borrowed":[]},
    "voyage": {"base":"modules/providers/voyage","deps":[["core","compile->compile"],["providerTestkit","test"]],"borrowed":[]},
    "springBootStarter": {"base":"modules/spring-boot-starter","deps":[["core","provided"]],"borrowed":[]},
    "springBootStarterBoot4": {"base":"modules/spring-boot-starter/boot4","deps":[["springBootStarter","compile->compile"]],"borrowed":[]},
    "agent": {"base":"modules/agent","deps":[["core","compile->compile;test->test"],["mcp","optional"]],"borrowed":[]},
    "agentCheckpointSqlite": {"base":"modules/agent-checkpoint-sqlite","deps":[["agent","compile->compile;test->test"]],"borrowed":[]},
    "javaApi": {"base":"modules/java-api","deps":[["agent","compile->compile"]],"borrowed":[]},
    "samples": {"base":"modules/samples","deps":[["agent","compile->compile"]],"borrowed":[]},
    "it": {"base":"modules/it","deps":[["core","compile->compile"]],"borrowed":[["test","modules/samples/src/main/scala/org/llm4s/samples/kafka/TicketPipeline.scala"]]},
    "workspaceRunner": {"base":"modules/workspace/workspaceRunner","deps":[],"borrowed":[]}
  }
}
JSON
cat > "$repo/.github/coverage-legs.json" <<'JSON'
[
  {"leg":"core","modules":["media","core","testkit","memory","agentTools"]},
  {"leg":"rag","modules":["rag","knowledgegraph","mcp"]},
  {"leg":"providers","modules":["providerTestkit","voyage","springBootStarter"]},
  {"leg":"agent","modules":["agent","agentCheckpointSqlite"]}
]
JSON
echo base > "$repo/README.md"
g add -A && g commit -qm base
BASE="$(g rev-parse HEAD)"

ORDER='code docs integration workspace windows consumers spring performance other'
# change <command run in the repo to make the change>: a commit on top of BASE
change() {
  g checkout -q --detach "$BASE"
  (cd "$repo" && eval "$1")
  g add -A && g commit -qm change --allow-empty
}
# expect <label> <the decisions that are true, space-separated, or "none"> <command> [env assignments]
expect() {
  change "$3"
  local out want="" n
  for n in $ORDER; do
    if [[ " $2 " == *" $n "* ]]; then want+="$n=true "; else want+="$n=false "; fi
  done
  want="${want% }"
  out="$(cd "$repo" && env ${4:-} scripts/ci-changes.sh HEAD^1 HEAD 2>/dev/null | grep -E "^($(echo "$ORDER" | tr ' ' '|'))=" |
    tr '\n' ' ' | sed 's/ $//')"
  if [ "$out" = "$want" ]; then ok "$1"; else bad "$1" "expected: $want; got: $out"; fi
}
# summary FILE: the modules, legs and full lines of the script's output, in short form:
#   modules=<sorted ids, or ALL> legs=<leg:m1,m2 ..., or ALL, or none> full=<true|false>
summary() {
  ruby -rjson -e '
    graph, legs_file, out = ARGV
    all = JSON.parse(File.read(graph))["projects"].keys.sort
    full_legs = JSON.parse(File.read(legs_file))
    kv = File.readlines(out).map(&:chomp).select { |l| l =~ /\A(modules|legs|full)=/ }.to_h { |l| l.split("=", 2) }
    mods = kv.fetch("modules", "?").split
    legs = JSON.parse(kv.fetch("legs", "null")) rescue "?"
    m = mods.sort == all ? "ALL" : (mods.empty? ? "none" : mods.sort.join(","))
    l = legs == full_legs ? "ALL" : (legs == [] ? "none" : (legs.is_a?(Array) ? legs.map { |r| "#{r["leg"]}:#{r["modules"].join(",")}" }.join(" ") : legs.inspect))
    puts "modules=#{m} legs=#{l} full=#{kv.fetch("full", "?")}"
  ' "${CI_GRAPH:-$repo/.github/ci-graph.json}" "${CI_LEGS:-$repo/.github/coverage-legs.json}" "$1"
}
# pick <label> <modules: ids comma-separated, sorted, ALL or none> <legs: "leg:m1,m2 ...", ALL or none> <full>
#        <command> [env assignments]
pick() {
  change "$5"
  (cd "$repo" && env ${6:-} scripts/ci-changes.sh HEAD^1 HEAD > "$WORK/sel.out" 2> "$WORK/sel.err")
  local want="modules=$2 legs=$3 full=$4" got
  got="$(summary "$WORK/sel.out")"
  if [ "$got" = "$want" ]; then ok "$1"; else bad "$1" "expected: $want; got: $got"; fi
}
touchp() { mkdir -p "$(dirname "$1")" && echo x >> "$1"; }
ALL_BUT_WS='code docs integration windows consumers spring performance other'
# what the graph decides: windows, consumers (javaApi, springBootStarter here), spring (the starter, boot4),
# performance (core, agent) and other (TEST_MODULES: samples, it and boot4 here) follow the projects a change reaches
MOST='code integration consumers spring performance other'

# code, and what reaches each gated job
expect 'core'                  "$MOST"                                   "touchp modules/core/src/main/scala/org/llm4s/util/Redaction.scala"
expect 'a core test: test->test dependents only, no consumer, Spring or other module' 'code integration performance' \
  "touchp modules/core/src/test/scala/org/llm4s/util/RedactionSpec.scala"
expect 'agent: java-api builds on it, the starter does not' 'code integration consumers performance other' \
  "touchp modules/agent/src/main/scala/org/llm4s/agent/Agent.scala"
expect 'media'                 "$MOST"                                   "touchp modules/media/src/main/scala/M.scala"
expect 'a lookalike module'    'code'                                    "touchp modules/agent-tools/src/main/scala/T.scala"
expect 'rag: no consumer builds on it here' 'code integration'           "touchp modules/rag/src/main/scala/Rag.scala"
expect 'a provider the Docker tier does not reach' 'code'                "touchp modules/providers/voyage/src/main/scala/V.scala"
expect 'the it module'         'code integration other'                  "touchp modules/it/src/test/scala/org/llm4s/it/PgSpec.scala"
expect 'deploy/'               'code integration other'                  "touchp deploy/overlays/enterprise/application.conf"
expect 'java-api'              'code consumers'                          "touchp modules/java-api/src/main/scala/J.scala"
expect 'the Spring starter'    'code consumers spring other'             "touchp modules/spring-boot-starter/src/main/scala/S.scala"
expect 'boot4: Spring and other, not the consumers job' 'code spring other' \
  "touchp modules/spring-boot-starter/boot4/src/main/scala/B.scala"
expect 'a Gradle sample'       'code consumers other'                    "touchp modules/samples/micronaut-java/build.gradle"
expect 'the Kafka sample modules/it borrows' 'code integration other'     "touchp modules/samples/src/main/scala/org/llm4s/samples/kafka/TicketPipeline.scala"
expect 'a Scala sample'        'code other'                              "touchp modules/samples/src/main/scala/S.scala"
expect 'kotlin-api'            'code consumers'                          "touchp modules/kotlin-api/build.gradle.kts"
# Windows
expect 'a whole Windows module' 'code integration windows'               "touchp modules/memory/src/main/scala/M.scala"
expect 'a listed source'       "$MOST windows"                           "touchp modules/core/src/main/scala/org/llm4s/config/ApiKeyFile.scala"
expect 'a listed directory'    'code windows consumers performance other' "touchp modules/mcp/src/main/scala/org/llm4s/mcp/stdio/Stdio.scala"
expect 'a named suite'         'code integration windows performance'    "touchp modules/core/src/test/scala/org/llm4s/config/ApiKeyFileConfigSpec.scala"
expect 'a suite by glob'       'code windows'                            "touchp modules/mcp/src/test/scala/org/llm4s/mcp/stdio/StdioSpec.scala"
expect 'another mcp suite'     'code'                                    "touchp modules/mcp/src/test/scala/org/llm4s/mcp/HttpSpec.scala"
expect 'the platform list alone: no test (Quick Checks checks the list)' 'none' \
  "echo 'x y' >> scripts/platform-sensitive-sources.txt"
# not code
expect 'docs only'             'docs'                                    "touchp docs/guide/agents.md"
expect 'a top-level markdown'  'docs'                                    "touchp CONTRIBUTING.md"
expect 'a guide a consumer checks' 'docs consumers'                      "touchp docs/guide/quarkus.md"
expect 'a ScalaDoc script'     'docs'                                    "touchp scripts/check-scaladoc-warnings.sh"
expect 'a changelog fragment'  'none'                                    "touchp changelog.d/core/1.md"
expect 'another script'        'none'                                    "touchp scripts/check-doc-seo.sh"
expect 'a consumer script'     'consumers'                               "touchp scripts/check-bom-consumers.sh"
expect 'another workflow'      'none'                                    "touchp .github/workflows/pages.yml"
expect 'an unknown kind of file: every module, so every module-based job' 'code windows consumers spring performance other' \
  "touchp codecov.yml"
expect 'docs and code'         "$MOST docs"                              "touchp docs/guide/agents.md; touchp modules/core/src/main/scala/C.scala"
# build files, and the ci:full label, set everything but the workspace tier
expect 'build.sbt'             "$ALL_BUT_WS"                             "echo '// x' >> build.sbt"
expect 'project/'              "$ALL_BUT_WS"                             "touchp project/Dependencies.scala"
expect 'ci.yml'                "$ALL_BUT_WS"                             "sed -i.bak 's/name: Quick Checks/name: Quick Checks!/' .github/workflows/ci.yml && rm .github/workflows/ci.yml.bak"
expect 'a composite action'    "$ALL_BUT_WS"                             "touchp .github/actions/restore-sbt-deps/action.yml"
expect 'sbt options'           "$ALL_BUT_WS"                             "touchp .sbtopts"
expect 'the formatter config'  "$ALL_BUT_WS"                             "touchp .scalafmt.conf"
expect 'this script'           "$ALL_BUT_WS"                             "echo '# x' >> scripts/ci-changes.sh"
expect 'the project graph'     "$ALL_BUT_WS"                             "echo ' ' >> .github/ci-graph.json"
expect 'the coverage legs'     "$ALL_BUT_WS"                             "echo ' ' >> .github/coverage-legs.json"
expect 'the ci:full label'     "$ALL_BUT_WS"                             "touchp docs/guide/agents.md" "CI_FULL=label"
# the workspace tier keeps its own rules, and is never run by a merge group (its PR ran it)
expect 'the workspace runner'  'code workspace other'                    "touchp modules/workspace/workspaceRunner/src/main/scala/R.scala"
expect 'a harness file'        'code integration workspace consumers performance other' "touchp modules/agent/src/main/scala/org/llm4s/agent/harness/FileTools.scala"
expect 'a harness file in a merge group' 'code integration consumers performance other' "touchp modules/agent/src/main/scala/org/llm4s/agent/harness/FileTools.scala" "CI_EVENT=merge_group"
expect 'a deleted core file'   "$MOST"                                   "touchp modules/core/x.txt; git add -A && git -c user.name=t -c user.email=t@example.com -c commit.gpgsign=false commit -qm add-x && git rm -q modules/core/x.txt"
expect 'no change'             'none'                                    ":"

# What the module-based jobs run (#3187): windows_modules, performance_modules and test_modules, where empty is all.
# lists <label> <want: "name=value;name=value;..." the list lines, ;-separated> <command> [env assignments]
lists() {
  change "$3"
  local got want="$2"
  got="$(cd "$repo" && env ${4:-} scripts/ci-changes.sh HEAD^1 HEAD 2>/dev/null |
    grep -E '^(windows_modules|performance_modules|test_modules)=' | tr '\n' ';' | sed 's/;$//')"
  if [ "$got" = "$want" ]; then ok "$1"; else bad "$1" "expected: $want; got: $got"; fi
}
lists 'a listed core source: every listed project it reaches, both performance projects, the other modules it reaches' \
  'windows_modules=core mcp memory;performance_modules=core agent;test_modules=samples it springBootStarterBoot4' \
  "touchp modules/core/src/main/scala/org/llm4s/config/ApiKeyFile.scala"
lists 'a whole module: only it on Windows' 'windows_modules=memory' "touchp modules/memory/src/main/scala/M.scala"
lists 'a listed mcp source: mcp on Windows, agent (optional on mcp) for performance, samples' \
  'windows_modules=mcp;performance_modules=agent;test_modules=samples' \
  "touchp modules/mcp/src/main/scala/org/llm4s/mcp/stdio/Stdio.scala"
lists 'a whole module beside a core change that is not platform-sensitive: Windows runs only the whole module' \
  'windows_modules=memory;performance_modules=core agent;test_modules=samples it springBootStarterBoot4' \
  "touchp modules/memory/src/main/scala/M.scala; touchp modules/core/src/main/scala/org/llm4s/util/Redaction.scala"
lists 'a core test: core and agent for performance, no other module' 'performance_modules=core agent' \
  "touchp modules/core/src/test/scala/org/llm4s/util/RedactionSpec.scala"
lists 'a build file: every list empty, so each job runs everything' 'windows_modules=;performance_modules=;test_modules=' \
  "echo '// x' >> build.sbt"
lists 'the ci:full label: every list empty' 'windows_modules=;performance_modules=;test_modules=' \
  "touchp modules/mcp/src/test/scala/MSpec.scala" "CI_FULL=label"
lists 'an unmapped path: every list empty' 'windows_modules=;performance_modules=;test_modules=' "touchp codecov.yml"
label='the decisions log names why Windows runs what it runs'
change "touchp modules/memory/src/main/scala/M.scala"
(cd "$repo" && scripts/ci-changes.sh HEAD^1 HEAD > /dev/null 2> "$WORK/why")
if grep -qF 'windows: the change touches modules/memory/src/main/scala/M.scala (1 file(s)), which reaches memory' "$WORK/why" &&
   grep -qF 'spring: the change reaches none of springBootStarter springBootStarterBoot4' "$WORK/why"; then
  ok "$label"; else bad "$label" "$(cat "$WORK/why")"; fi


# The module rule (#3185) and the legs (#3186), on the scratch graph above.
# main code: compile edges are transitive (media > core > everything), `provided` and `optional` are compile edges
pick 'media main: every project, but not full' \
  'agent,agentCheckpointSqlite,agentTools,core,it,javaApi,knowledgegraph,llm4s,mcp,media,memory,providerTestkit,rag,samples,springBootStarter,springBootStarterBoot4,testkit,voyage' \
  ALL false "touchp modules/media/src/main/scala/M.scala"
pick 'core main: every leg in full, not full' \
  'agent,agentCheckpointSqlite,agentTools,core,it,javaApi,knowledgegraph,llm4s,mcp,memory,providerTestkit,rag,samples,springBootStarter,springBootStarterBoot4,testkit,voyage' \
  'core:core,testkit,memory,agentTools rag:rag,knowledgegraph,mcp providers:providerTestkit,voyage,springBootStarter agent:agent,agentCheckpointSqlite' \
  false "touchp modules/core/src/main/scala/C.scala"
pick 'mcp main: an optional dependency reaches agent and what builds on it' \
  'agent,agentCheckpointSqlite,javaApi,mcp,samples' 'rag:mcp agent:agent,agentCheckpointSqlite' false \
  "touchp modules/mcp/src/main/scala/M.scala"
pick 'a resource is main code' 'agent,agentCheckpointSqlite,javaApi,mcp,samples' 'rag:mcp agent:agent,agentCheckpointSqlite' false \
  "touchp modules/mcp/src/main/resources/reference.conf"
pick 'a file at a module root is main code' 'agent,agentCheckpointSqlite,javaApi,mcp,samples' 'rag:mcp agent:agent,agentCheckpointSqlite' \
  false "touchp modules/mcp/README.md"
# test code: only the module, and what reaches its tests by test->test
pick 'mcp test: mcp alone' 'mcp' 'rag:mcp' false "touchp modules/mcp/src/test/scala/MSpec.scala"
pick 'core test: test->test chains (agent > agentCheckpointSqlite), compile-only dependents stay out' \
  'agent,agentCheckpointSqlite,core,memory,rag' 'core:core,memory rag:rag agent:agent,agentCheckpointSqlite' false \
  "touchp modules/core/src/test/scala/CSpec.scala"
pick 'agent test: its test->test dependent' 'agent,agentCheckpointSqlite' 'agent:agent,agentCheckpointSqlite' false \
  "touchp modules/agent/src/test/scala/ASpec.scala"
# % Test is one hop into the dependency's main code
pick 'memory main: % Test reaches rag' 'memory,rag' 'core:memory rag:rag' false "touchp modules/memory/src/main/scala/M.scala"
pick 'memory test: % Test does not reach the tests' 'memory' 'core:memory' false "touchp modules/memory/src/test/scala/MSpec.scala"
pick 'testkit main: providerTestkit (% Test), not voyage (% Test on providerTestkit)' 'providerTestkit,testkit' \
  'core:testkit providers:providerTestkit' false "touchp modules/testkit/src/main/scala/T.scala"
pick 'providerTestkit main: voyage, by % Test' 'providerTestkit,voyage' 'providers:providerTestkit,voyage' false \
  "touchp modules/provider-testkit/src/main/scala/P.scala"
# the longest base directory wins
pick 'boot4: the nested module only' 'springBootStarterBoot4' none false \
  "touchp modules/spring-boot-starter/boot4/src/main/scala/B.scala"
pick 'the starter: and boot4, which depends on it' 'springBootStarter,springBootStarterBoot4' 'providers:springBootStarter' \
  false "touchp modules/spring-boot-starter/src/main/scala/S.scala"
pick 'a lookalike prefix is another module' 'agentTools' 'core:agentTools' false "touchp modules/agent-tools/src/main/scala/T.scala"
# borrowed sources and files read
pick 'DocsSpec: core and its test->test dependents, and agentTools, which borrows it' \
  'agent,agentCheckpointSqlite,agentTools,core,memory,rag' 'core:core,memory,agentTools rag:rag agent:agent,agentCheckpointSqlite' \
  false "touchp modules/core/src/test/java/org/llm4s/tags/DocsSpec.java"
pick 'the Kafka sample: samples and it, which borrows it' 'it,samples' none false \
  "touchp modules/samples/src/main/scala/org/llm4s/samples/kafka/TicketPipeline.scala"
pick 'a file agentTools reads (READS)' 'agentTools,workspaceRunner' 'core:agentTools' false \
  "touchp modules/workspace/workspaceRunner/src/main/scala/org/llm4s/runner/WindowsBestFit.scala"
pick 'deploy/: the suites that read it (READS), not everything' 'it,samples' none false \
  "touchp deploy/overlays/enterprise/application.conf"
pick 'deploy/ elsewhere: in no project, everything' ALL ALL true "touchp deploy/helm/values.yaml"
pick 'a file read from outside the build (READS, OUTSIDE_SBT)' 'samples' none false \
  "touchp modules/kotlin-api/src/test/kotlin/org/llm4s/kotlin/WrapClientTest.kt"
# a leg's report counts the measurements of the modules ahead of it: those whose tests run its code are added
pick 'knowledgegraph test: rag, ahead of it in its leg, runs its code' 'knowledgegraph,rag' 'rag:rag,knowledgegraph' false \
  "touchp modules/knowledgegraph/src/test/scala/KSpec.scala"
# outside the build, unmapped, not code
pick 'kotlin-api (its own Gradle build): no sbt module' none none false "touchp modules/kotlin-api/build.gradle.kts"
pick 'the REPL examples: no sbt module' none none false "touchp examples/repl/hello.sc"
pick 'a path in no project: everything' ALL ALL true "touchp modules/scalafix-rules/README.md"
pick 'a code file outside modules/: everything' ALL ALL true "touchp codecov.yml"
pick 'docs only: no module (Docs tests runs the suites that read docs/)' none none false "touchp docs/guide/agents.md"
pick 'docs and rag: rag only' 'rag' 'rag:rag' false "touchp docs/guide/agents.md; touchp modules/rag/src/main/scala/R.scala"
pick 'a changelog fragment: no module' none none false "touchp changelog.d/core/1.md"
pick 'no change: no module' none none false ":"
pick 'a deleted file still counts' 'mcp' 'rag:mcp' false \
  "touchp modules/mcp/src/test/scala/x.txt; git add -A && git -c user.name=t -c user.email=t@example.com -c commit.gpgsign=false commit -qm add-x && git rm -q modules/mcp/src/test/scala/x.txt"
pick 'a moved file counts at both paths' 'mcp,rag' 'rag:rag,mcp' false \
  "touchp modules/rag/src/test/scala/x.scala; git add -A && git -c user.name=t -c user.email=t@example.com -c commit.gpgsign=false commit -qm add-x && mkdir -p modules/mcp/src/test/scala && git mv modules/rag/src/test/scala/x.scala modules/mcp/src/test/scala/x.scala"
# everything
pick 'a build file: everything' ALL ALL true "echo '// x' >> build.sbt"
pick 'the ci:full label: everything' ALL ALL true "touchp modules/mcp/src/test/scala/MSpec.scala" "CI_FULL=label"
cat > "$WORK/odd-graph.json" <<'JSON'
{"projects": {"core": {"base":"modules/core","deps":[],"borrowed":[]},
              "rag": {"base":"modules/rag","deps":[["core","it->compile"]],"borrowed":[]}}}
JSON
pick 'a configuration the rule does not know: everything' 'core,rag' 'core:core rag:rag' true \
  "touchp modules/core/src/main/scala/C.scala" "CI_GRAPH=$WORK/odd-graph.json"
label='the selection log names why each module was selected'
change "touchp modules/memory/src/main/scala/M.scala"
(cd "$repo" && scripts/ci-changes.sh HEAD^1 HEAD > /dev/null 2> "$WORK/why")
if grep -q '^  rag: rag:test > memory:compile (modules/memory/src/main/scala/M.scala)$' "$WORK/why" &&
   grep -q '^  memory: memory:test > memory:compile (modules/memory/src/main/scala/M.scala)$' "$WORK/why" &&
   grep -q '^leg agent: skipped, no module selected$' "$WORK/why"; then ok "$label"; else bad "$label" "$(cat "$WORK/why")"; fi
# a merge group selects by its whole diff against main: a group stacked on another entry holds both changes
label='a merge group: base_sha..head_sha over two queued changes selects both'
change "touchp modules/mcp/src/test/scala/MSpec.scala; git add -A && git -c user.name=t -c user.email=t@example.com -c commit.gpgsign=false commit -qm first && touchp modules/memory/src/test/scala/MSpec.scala"
(cd "$repo" && CI_EVENT=merge_group scripts/ci-changes.sh "$BASE" HEAD > "$WORK/sel.out" 2>/dev/null)
got="$(summary "$WORK/sel.out")"
if [ "$got" = "modules=mcp,memory legs=core:memory rag:mcp full=false" ]; then ok "$label"; else bad "$label" "$got"; fi
# push, nightly, manual, release: --all prints no decision (empty means run) and every module
label='--all: no decisions, every module, the legs in full'
(cd "$repo" && scripts/ci-changes.sh --all "a push run" > "$WORK/sel.out" 2>/dev/null)
got="$(summary "$WORK/sel.out")"
if [ "$got" = "modules=ALL legs=ALL full=true" ] && ! grep -qE "^($(echo "$ORDER" | tr ' ' '|'))=" "$WORK/sel.out" &&
   [ "$(grep -c '' "$WORK/sel.out")" = 3 ]; then ok "$label"; else bad "$label" "$got; $(cat "$WORK/sel.out")"; fi

# The rule on this repository's own graph and legs.
real() { # real <label> <ruby condition on `sel` (selected ids), `legs` (rows) and `full` (legs file rows)> <command>
  change "$3"
  (cd "$repo" && CI_GRAPH="$REPO_ROOT/.github/ci-graph.json" CI_LEGS="$REPO_ROOT/.github/coverage-legs.json" \
    scripts/ci-changes.sh HEAD^1 HEAD > "$WORK/real.out" 2>/dev/null)
  if out="$(ruby -rjson -e '
      kv = File.readlines(ARGV[0]).map(&:chomp).select { |l| l =~ /\A(modules|legs|full)=/ }.to_h { |l| l.split("=", 2) }
      sel = kv["modules"].to_s.split
      legs = JSON.parse(kv["legs"])
      full = JSON.parse(File.read(ARGV[1]))
      ok = eval(ARGV[2]) # rubocop:disable Security/Eval -- the conditions are this file s own
      puts "modules=#{sel.join(" ")} legs=#{legs.map { |r| "#{r["leg"]}:#{r["modules"].size}" }.join(" ")}" unless ok
      exit(ok ? 0 : 1)
    ' "$WORK/real.out" "$REPO_ROOT/.github/coverage-legs.json" "$2")"; then ok "$1"; else bad "$1" "$out"; fi
}
real 'this repo: a core main change runs all four legs, every module but media (which core depends on)' \
  'legs.map { |r| r["leg"] } == full.map { |r| r["leg"] } && full.flat_map { |r| r["modules"] } - legs.flat_map { |r| r["modules"] } == ["media"] && kv["full"] == "false"' \
  "touchp modules/core/src/main/scala/org/llm4s/types/Result.scala"
real 'this repo: a media change runs all four legs in full' 'legs == full && kv["full"] == "false"' \
  "touchp modules/media/src/main/scala/org/llm4s/media/X.scala"
real 'this repo: a rag-only change runs one or two legs, no core' \
  'legs.size <= 2 && !sel.include?("core") && sel.include?("rag") && legs.sum { |r| r["modules"].size } <= 10' \
  "touchp modules/rag/src/main/scala/org/llm4s/rag/RAG.scala"
real 'this repo: an agent-tools change runs only its leg' 'legs.map { |r| r["leg"] } == ["core"] && legs[0]["modules"].include?("agentTools")' \
  "touchp modules/agent-tools/src/main/scala/org/llm4s/toolapi/builtin/X.scala"
real 'this repo: an openai change reaches the providers leg and the agent, not core' \
  'sel.include?("openai") && sel.include?("agent") && !sel.include?("core")' \
  "touchp modules/openai/src/main/scala/org/llm4s/llmconnect/provider/X.scala"

# The module-based jobs on this repository's own build (#3187): a scratch repo with its build.sbt, PlatformTests, the
# platform-sensitive list, ci.yml, graph and legs, so each job's selection is the one CI would make.
own="$WORK/own"
git init -q "$own"
og() { git -C "$own" -c user.name=t -c user.email=t@example.com -c commit.gpgsign=false "$@"; }
mkdir -p "$own/.github/workflows" "$own/scripts" "$own/project"
cp "$CI" "$own/.github/workflows/ci.yml"
cp "$REPO_ROOT/.github/ci-graph.json" "$REPO_ROOT/.github/coverage-legs.json" "$own/.github/"
cp "$SCRIPT" "$own/scripts/ci-changes.sh"
cp "$REPO_ROOT/scripts/workspace-tier-changes.sh" "$REPO_ROOT/scripts/platform-sensitive-sources.txt" "$own/scripts/"
cp "$REPO_ROOT/build.sbt" "$own/build.sbt"
cp "$REPO_ROOT/project/PlatformTests.scala" "$own/project/PlatformTests.scala"
og add -A && og commit -qm base
OWN_BASE="$(og rev-parse HEAD)"
# owncase <label> <ruby condition on `kv` (every output line, name => value) and `w` (windows_modules as a list)> <command>
owncase() {
  og checkout -q --detach "$OWN_BASE"
  (cd "$own" && eval "$3")
  og add -A && og commit -qm change --allow-empty
  (cd "$own" && scripts/ci-changes.sh HEAD^1 HEAD > "$WORK/own.out" 2> "$WORK/own.err")
  if ruby -e '
      kv = File.readlines(ARGV[0]).map(&:chomp).select { |l| l.include?("=") }.to_h { |l| l.split("=", 2) }
      w = kv["windows_modules"].to_s.split
      exit(eval(ARGV[1]) ? 0 : 1)
    ' "$WORK/own.out" "$2"; then ok "$1"; else bad "$1" "$(grep -vE '^(modules|legs)=' "$WORK/own.out" | tr '\n' ' ')"; fi
}
owncase 'this repo: a rag change runs rag on Windows, not the workspace, speech, image or it' \
  'kv["windows"] == "true" && w.include?("rag") && (w & %w[workspaceShared workspaceRunner workspaceClient speech image it]).empty?' \
  "touchp modules/rag/src/main/scala/org/llm4s/rag/X.scala"
owncase 'this repo: an image change runs image, and it'"'"'s vision-client suites (WINDOWS_VIA)' \
  '(w & %w[image it]).size == 2' "touchp modules/image/src/main/scala/org/llm4s/imageprocessing/X.scala"
owncase 'this repo: a listed core source runs core'"'"'s suites, and it'"'"'s because it reaches image' \
  '(w & %w[core image it rag]).size == 4' "touchp modules/core/src/main/scala/org/llm4s/util/PrivateSqliteFile.scala"
owncase 'this repo: a listed it suite runs it on Windows (its own file, despite WINDOWS_VIA)' \
  'kv["windows"] == "true" && w == %w[it]' \
  "touchp modules/it/src/test/scala/org/llm4s/imageprocessing/provider/OpenAIVisionClientTest.scala"
owncase 'this repo: another it suite does not start Windows' 'kv["windows"] == "false"' \
  "touchp modules/it/src/test/scala/org/llm4s/imageprocessing/provider/GeminiVisionClientTest.scala"
owncase 'this repo: an agent-tools change runs agentTools on Windows, and no consumer, Spring or performance job' \
  'w.include?("agentTools") && kv["consumers"] == "false" && kv["spring"] == "false" && kv["performance"] == "false"' \
  "touchp modules/agent-tools/src/main/scala/org/llm4s/toolapi/builtin/X.scala"
owncase 'this repo: a core change that is not platform-sensitive: no Windows; consumers, Spring, both performance projects' \
  'kv["windows"] == "false" && kv["consumers"] == "true" && kv["spring"] == "true" && kv["performance_modules"] == "core agent"' \
  "touchp modules/core/src/main/scala/org/llm4s/util/X.scala"
owncase 'this repo: an agent change runs only agent'"'"'s performance tests' 'kv["performance_modules"] == "agent"' \
  "touchp modules/agent/src/main/scala/org/llm4s/agent/X.scala"
owncase 'this repo: a java-api change runs the consumers and Spring jobs' 'kv["consumers"] == "true" && kv["spring"] == "true"' \
  "touchp modules/java-api/src/main/scala/org/llm4s/javaapi/X.scala"
owncase 'this repo: a voyage change runs no consumer, Spring, Windows or performance job' \
  '%w[consumers spring windows performance].all? { |n| kv[n] == "false" }' \
  "touchp modules/providers/voyage/src/main/scala/org/llm4s/X.scala"
owncase 'this repo: a build file BUILD lists selects every list in full' \
  '%w[windows consumers spring performance other].all? { |n| kv[n] == "true" } && %w[windows_modules performance_modules test_modules].all? { |n| kv[n] == "" }' \
  "touchp project/plugins.sbt"
# Build files by the graph diff (#3189): the change edits build.sbt and regenerates the graph, as a PR must (Quick
# Checks fails a stale one); `fp` edits the committed graph as sbt generateCiGraph would.
fp() { # fp <ruby on `g` (the parsed graph)>: rewrites .github/ci-graph.json
  ruby -rjson -e 'g = JSON.parse(File.read(ARGV[0])); eval(ARGV[1]); File.write(ARGV[0], JSON.pretty_generate(g))' \
    .github/ci-graph.json "$1"
}
NOTHING='kv["full"] == "false" && kv["modules"] == "" && kv["legs"] == "[]" && %w[windows consumers spring performance other integration].all? { |n| kv[n] == "false" }'
owncase 'this repo: a comment in build.sbt selects nothing' "$NOTHING" "echo '// x' >> build.sbt"
owncase 'this repo: Dependencies.scala with no setting changed selects nothing' "$NOTHING" "echo '// x' >> project/Dependencies.scala"
owncase 'this repo: a floor bump (memory fp.test) selects memory and its Windows suites, not core' \
  'kv["modules"].split == %w[memory] && w == %w[memory] && kv["full"] == "false"' \
  "echo '// x' >> build.sbt; fp 'g[\"projects\"][\"memory\"][\"fp\"][\"test\"] = \"0\"'"
owncase 'this repo: a library bump in rag (fp.compile) selects rag and its dependents' \
  'm = kv["modules"].split; m.include?("rag") && m.include?("javaRag") && !m.include?("core") && kv["consumers"] == "true"' \
  "echo '// x' >> build.sbt; fp 'g[\"projects\"][\"rag\"][\"fp\"][\"compile\"] = \"0\"'"
owncase 'this repo: a new test dependency of voyage selects voyage only' \
  'kv["modules"].split == %w[voyage] && kv["windows"] == "false"' \
  "echo '// x' >> build.sbt; fp 'g[\"projects\"][\"voyage\"][\"deps\"] << [\"testkit\", \"test\"]'"
owncase 'this repo: a memoryPostgres setting runs the Docker tier' \
  'kv["integration"] == "true" && kv["modules"].split.include?("memoryPostgres")' \
  "echo '// x' >> build.sbt; fp 'g[\"projects\"][\"memoryPostgres\"][\"fp\"][\"test\"] = \"0\"'"
owncase 'this repo: changed aliases (global) select everything' 'kv["full"] == "true"' \
  "echo '// x' >> build.sbt; fp 'g[\"global\"] = \"0\"'"
owncase 'this repo: a project that moved selects everything' 'kv["full"] == "true"' \
  "echo '// x' >> build.sbt; fp 'g[\"projects\"][\"voyage\"][\"base\"] = \"modules/voyage\"'"
owncase 'this repo: a base graph without fingerprints selects everything' 'kv["full"] == "true"' \
  "fp 'g[\"projects\"].each_value { |p| p.delete(\"fp\") }'; git -c user.name=t -c user.email=t@example.com -c commit.gpgsign=false commit -qam strip; git checkout -q $OWN_BASE -- .github/ci-graph.json; echo '// x' >> build.sbt"
owncase 'this repo: no base graph selects everything' 'kv["full"] == "true"' \
  "git rm -q .github/ci-graph.json; git -c user.name=t -c user.email=t@example.com -c commit.gpgsign=false commit -qm drop; git checkout -q $OWN_BASE -- .github/ci-graph.json; echo '// x' >> build.sbt"
owncase 'this repo: PlatformTests.scala runs every Windows entry and no module' \
  'kv["windows"] == "true" && w.empty? && kv["modules"] == "" && kv["consumers"] == "false"' "echo '// x' >> project/PlatformTests.scala"
owncase 'this repo: ItTiers.scala runs it and the Docker tier' \
  'kv["integration"] == "true" && kv["modules"].split.include?("it") && kv["test_modules"].split.include?("it")' \
  "echo '// x' >> project/ItTiers.scala"
owncase 'this repo: DocSnippets.scala runs samples'"'"' tests' 'kv["modules"].split == %w[samples]' \
  "echo '// x' >> project/DocSnippets.scala"
owncase 'this repo: PomDescriptions.scala runs the consumers job and no module' \
  'kv["consumers"] == "true" && kv["modules"] == "" && kv["full"] == "false"' "echo '// x' >> project/PomDescriptions.scala"
owncase 'this repo: StabilityTiers.scala needs only Quick Checks' "$NOTHING" "echo '// x' >> project/StabilityTiers.scala"
owncase 'this repo: CiGraph.scala (the fingerprints themselves) selects everything' 'kv["full"] == "true"' \
  "echo '// x' >> project/CiGraph.scala"
label='this repo: every project a module-based decision names is in the project graph'
if out="$(cd "$own" && ruby -ryaml -rjson -e '
    projects = JSON.parse(File.read(".github/ci-graph.json"))["projects"]
    sh = File.read("scripts/ci-changes.sh")
    pt = File.read("project/PlatformTests.scala")
    lists = {
      "CONSUMER_ROOTS" => sh[/^CONSUMER_ROOTS=.([^\x27]*)/, 1].to_s.split,
      "SPRING_ROOTS" => sh[/^SPRING_ROOTS=.([^\x27]*)/, 1].to_s.split,
      "WINDOWS_VIA" => sh[/^WINDOWS_VIA=.([^\x27]*)/, 1].to_s.split.flat_map { |e| e.split(":") },
      "wholeModules" => pt[/val wholeModules[^=]*=\s*Seq\((.*?)\)/m, 1].to_s.scan(/"(\w+)"/).flatten,
      "suites" => pt[/val suites[^=]*=\s*Seq\((.*)\)/m, 1].to_s.scan(/"(\w+)"\s*->/).flatten,
      "performanceTestProjects" => File.read("build.sbt")[/lazy val performanceTestProjects\s*=\s*Seq\(([^)]*)\)/, 1].to_s.scan(/"(\w+)"/).flatten,
      "TEST_MODULES" => YAML.load_file(".github/workflows/ci.yml").dig("jobs", "test", "env", "TEST_MODULES").to_s.split
    }
    errs = lists.flat_map do |name, ids|
      ids.empty? ? ["#{name}: read nothing"] : ids.reject { |m| projects.key?(m) }.map { |m| "#{name}: #{m} is not a project" }
    end
    puts errs
    exit(errs.empty? ? 0 : 1)
  ')"; then ok "$label"; else bad "$label" "$out"; fi

# Every module modules/it depends on is either reached by the integration decision or listed as not reached.
label='every module modules/it depends on is classified for the integration decision'
if out="$(ruby -rjson -e '
  # The build as sbt loads it, from the committed CI project graph (#3184, `sbt generateCiGraph`), not build.sbt text.
  script = File.read(ARGV[0])
  graph  = JSON.parse(File.read(ARGV[1]))["projects"]
  pats = script[/^INTEGRATION=\((.*?)^\)/m, 1].to_s.scan(/'"'"'([^'"'"']+)'"'"'/).flatten.map { |p| Regexp.new(p) }
  unreached = script[/^INTEGRATION_UNREACHED=.([^'"'"']*)/m, 1].to_s.split
  dirs = graph.transform_values { |p| p["base"] }
  it   = graph.fetch("it") { abort "no project it in .github/ci-graph.json" }
  deps = it["deps"].map(&:first)
  abort "no dependencies of it in .github/ci-graph.json" if deps.empty?
  errs = deps.reject do |d|
    unreached.include?(d) || (dirs[d] && pats.any? { |p| p.match?("#{dirs[d]}/x") })
  end
  errs = errs.map { |d| "#{d}: add its directory to INTEGRATION or its id to INTEGRATION_UNREACHED in scripts/ci-changes.sh" }
  # sources modules/it compiles from outside its own directory (`Test / unmanagedSources ++= ...`)
  it["borrowed"].map(&:last).each do |path|
    next if pats.any? { |p| p.match?(path) }
    errs << "modules/it borrows #{path}: add it to INTEGRATION in scripts/ci-changes.sh"
  end
  puts errs
  extra = unreached.reject { |d| deps.include?(d) }
  puts extra.map { |d| "#{d} is in INTEGRATION_UNREACHED, but modules/it does not depend on it" }
  exit(errs.empty? && extra.empty? ? 0 : 1)
' "$SCRIPT" "$REPO_ROOT/.github/ci-graph.json")"; then ok "$label"; else bad "$label" "$out"; fi

# A test source that names a file of another module or outside modules/ ("modules/<path>", deploy/, config/,
# examples/) reads it: the graph must reach that file's
# node from the reader's tests, or READS must list it, or a change to it would not run the suite.
label='every file a suite reads from another module is reached by the graph or listed in READS'
if out="$(cd "$REPO_ROOT" && ruby -rjson -e '
  script = File.read(ARGV[0])
  graph  = JSON.parse(File.read(ARGV[1]))["projects"]
  reads = script[/^READS=\((.*?)^\)/m, 1].to_s.scan(/'"'"'([^'"'"']+)'"'"'/).flatten.map { |l| r, p = l.split(" ", 2); [Regexp.new(r), p] }
  side = ->(c) { c == "test" ? "test" : "compile" }
  deps = Hash.new { |h, k| h[k] = [] }
  graph.each do |id, p|
    deps["#{id}:test"] << "#{id}:compile"
    p["deps"].each { |d, conf| conf.split(";").each { |part| f, t = part.split("->", 2); deps["#{id}:#{side.(f)}"] << "#{d}:#{side.((t || "compile").strip)}" } }
  end
  bases = graph.reject { |_, p| p["base"] == "." }.map { |id, p| ["#{p["base"]}/", id] }.sort_by { |b, _| -b.size }
  node = ->(f) { b, id = bases.find { |base, _| f.start_with?(base) }; id && "#{id}:#{f[b.size..].start_with?("src/test/") ? "test" : "compile"}" }
  reach = lambda do |n|
    seen = { n => true }; q = [n]
    until q.empty?; deps[q.shift].each { |d| q << d unless seen[d]; seen[d] = true }; end
    seen
  end
  errs = []
  Dir.glob("modules/**/src/test/**/*.{scala,java}").each do |src|
    reader = node.(src) or next
    id = reader.split(":").first
    File.read(src).scan(%r{"((?:modules|deploy|config|examples)/[A-Za-z0-9_./-]+)}).flatten.uniq.each do |path|
      path = path.sub(%r{/+\z}, "")
      next unless File.exist?(path)
      next if path.start_with?(graph[id]["base"] + "/")
      files = File.directory?(path) ? Dir.glob("#{path}/**/*").select { |f| File.file?(f) } : [path]
      files.each do |f|
        target = node.(f)
        next if target && reach.(reader).key?(target)
        next if reads.any? { |r, p| p == id && r.match?(f) }
        errs << "#{src} reads #{f}: add `<regex> #{id}` to READS in scripts/ci-changes.sh"
        break
      end
    end
  end
  puts errs.uniq
  exit(errs.empty? ? 0 : 1)
' "$SCRIPT" "$REPO_ROOT/.github/ci-graph.json")"; then ok "$label"; else bad "$label" "$out"; fi

# ci.yml's `changes` job: run its steps' bash as the runner would, for each event, in the scratch repo.
# run_changes <label> <event> <draft> <full_label> <want: "name=value ..." the outputs that must be written>
run_changes() {
  local label="$1" event="$2" draft="$3" lbl="$4" want="$5"
  ruby -ryaml -e '
    step = YAML.load_file(ARGV[0])["jobs"]["changes"]["steps"].find { |s| s["id"] == "diff" } or abort "no step diff"
    File.write(ARGV[1], step["run"])
  ' "$repo/.github/workflows/ci.yml" "$WORK/diff-step.sh" || { bad "$label" "cannot read the diff step"; return; }
  : > "$WORK/gh-out"
  (cd "$repo" && EVENT="$event" DRAFT="$draft" FULL_LABEL="$lbl" GROUP_BASE="$BASE" GROUP_HEAD="$(g rev-parse HEAD)" \
    GITHUB_OUTPUT="$WORK/gh-out" bash -e "$WORK/diff-step.sh" > /dev/null 2>&1) || { bad "$label" "the step failed"; return; }
  local w missing=""
  for w in $want; do grep -qxF -- "$w" "$WORK/gh-out" || missing+=" $w"; done
  if [ -z "$missing" ]; then ok "$label"; else bad "$label" "missing:$missing; wrote: $(tr '\n' ' ' < "$WORK/gh-out")"; fi
}
change "touchp modules/mcp/src/test/scala/MSpec.scala; touchp modules/agent/src/main/scala/org/llm4s/agent/harness/H.scala"
run_changes 'changes step: a draft writes every decision false and no leg' pull_request true false \
  'draft=true code=false docs=false windows=false integration=false consumers=false workspace=false performance=false spring=false other=false modules= legs=[] full=false'
run_changes 'changes step: a pull request runs the script on HEAD^1..HEAD' pull_request false false \
  'draft=false code=true workspace=true full=false'
run_changes 'changes step: the ci:full label selects everything' pull_request false true 'draft=false code=true windows=true full=true'
run_changes 'changes step: a merge group selects by base_sha..head_sha, without the workspace tier' merge_group '' false \
  'draft=false code=true workspace=false full=false'
run_changes 'changes step: a merge group whose labels could not be read runs everything' merge_group '' unknown 'full=true'
run_changes 'changes step: a push selects every module and leaves the decisions empty' push '' '' 'full=true'
run_changes 'changes step: the nightly run selects every module' schedule '' '' 'full=true'
label='changes step: a push writes no decision'
if ! grep -qE "^(draft|$(echo "$ORDER" | tr ' ' '|'))=" "$WORK/gh-out"; then ok "$label"; else bad "$label" "$(cat "$WORK/gh-out")"; fi

# ci.yml wires the outputs: the `changes` job runs on every event, publishes every output, and each gated job reads
# its own decision; the coverage legs' matrix is the `legs` output.
label='ci.yml: the changes job publishes every output, and each gated job reads its own'
# shellcheck disable=SC2016 # the ${{ }} are GitHub expressions, matched literally
if out="$(ruby -ryaml -e '
  jobs = YAML.load_file(ARGV[0])["jobs"]
  ch = jobs["changes"] or abort "no changes job"
  errs = []
  errs << "the changes job has a condition (#{ch["if"]}); it must run on every event: the legs matrix is its output" if ch["if"]
  steps = ch["steps"] || []
  step = steps.find { |s| s["id"] == "diff" } or abort "no step with id diff"
  run = step["run"].to_s
  errs << "the diff step does not run scripts/ci-changes.sh HEAD^1 HEAD" unless run.include?("scripts/ci-changes.sh HEAD^1 HEAD")
  errs << "the diff step does not run a merge group on its base and head" unless
    run.include?(%q{CI_EVENT=merge_group scripts/ci-changes.sh "$GROUP_BASE" "$GROUP_HEAD"}) &&
    step.dig("env", "GROUP_BASE") == "${{ github.event.merge_group.base_sha }}" &&
    step.dig("env", "GROUP_HEAD") == "${{ github.event.merge_group.head_sha }}"
  errs << "the diff step does not select everything on the other events (--all)" unless run.include?("scripts/ci-changes.sh --all")
  pr = steps.find { |s| s["id"] == "pr" }
  errs << "no pull-request step reading the ci:full label" unless pr && pr["if"] == "github.event_name == '"'"'pull_request'"'"'" &&
    pr.dig("env", "FULL_LABEL") == "${{ contains(github.event.pull_request.labels.*.name, '"'"'ci:full'"'"') }}"
  names = %w[draft] + ARGV[1].split + %w[windows_modules performance_modules test_modules modules legs full]
  names.each do |n|
    errs << "changes has no output #{n}" unless ch.dig("outputs", n) == "${{ steps.diff.outputs.#{n} }}"
  end
  { "coverage-leg" => "code", "coverage" => "code", "test" => "other", "test-windows" => "windows",
    "integration-tests" => "integration", "consumers" => "consumers", "spring-compat" => "spring",
    "performance-tests" => "performance", "workspace-integration" => "workspace", "scaladoc" => "docs",
    "docs-tests" => "docs" }.each do |job, n|
    j = jobs[job] or (errs << "no job #{job}"; next)
    errs << "#{job} does not need changes" unless Array(j["needs"]).include?("changes")
    errs << "#{job} is not gated on needs.changes.outputs.#{n}" unless j["if"].to_s.include?("needs.changes.outputs.#{n}")
  end
  leg = jobs["coverage-leg"]
  errs << "coverage-leg does not skip an empty matrix (legs == [])" unless leg["if"].to_s.include?(%q{needs.changes.outputs.legs != '"'"'[]'"'"'})
  errs << "coverage-leg matrix is not the legs output" unless leg.dig("strategy", "matrix", "include") == "${{ fromJSON(needs.changes.outputs.legs) }}"
  errs << "docs-tests does not run when the selection is not full" unless jobs["docs-tests"]["if"].to_s.include?("needs.changes.outputs.full == '"'"'false'"'"'")
  agg = (leg["steps"] + jobs["coverage"]["steps"]).select { |s| s["if"].to_s.include?("github.event_name != '"'"'pull_request'"'"'") }
  errs << "no aggregate-report step found" if agg.empty?
  agg.each { |s| errs << "#{s["name"] || s["uses"]} builds the aggregate report from a partial run" unless s["if"].include?("needs.changes.outputs.full == '"'"'true'"'"'") }
  puts errs
  exit(errs.empty? ? 0 : 1)
' "$CI" "$ORDER")"; then ok "$label"; else bad "$label" "$out"; fi

# The gates' own logic: the `Code Coverage` gate's first step and `All Tests Pass`'s step, run as bash with each
# `${{ }}` replaced by a scenario's value (an expression the substitution does not know fails the case).
# gate <label> <job> <step index> <want: pass|fail> <event> <ref> <"need=result ..."> <"output=value ...">
gate() {
  local label="$1" want="$4"
  if ruby -ryaml - "$CI" "$2" "$3" "$5" "$6" "$7" "$8" "$WORK/gate.sh" <<'RUBY'
ci, job, idx, event, ref, results, outputs, outf = ARGV
step = YAML.load_file(ci)["jobs"][job]["steps"][idx.to_i]
res = results.split.to_h { |kv| kv.split("=", 2) }
outs = outputs.split.to_h { |kv| kv.split("=", 2) }
sub = lambda do |s|
  s.gsub(/\$\{\{\s*(.*?)\s*\}\}/) do
    e = Regexp.last_match(1)
    case e
    when /\Aneeds\.([\w-]+)\.result\z/ then res.fetch(Regexp.last_match(1)) { abort "no result for #{e}" }
    when /\Aneeds\.changes\.outputs\.(\w+)\z/ then outs.fetch(Regexp.last_match(1), "")
    when "github.event_name" then event
    when "github.event_name == 'push' && github.ref == 'refs/heads/main'" then (event == "push" && ref == "refs/heads/main").to_s
    else abort "unknown expression #{e}"
    end
  end
end
env = (step["env"] || {}).map { |k, v| "export #{k}='#{sub.(v.to_s)}'" }
File.write(outf, (env + [sub.(step["run"].to_s)]).join("\n"))
RUBY
  then
    if bash "$WORK/gate.sh" > "$WORK/gate.out" 2>&1; then got=pass; else got=fail; fi
    if [ "$got" = "$want" ]; then ok "$label"; else bad "$label" "expected $want, got $got: $(tr '\n' ' ' < "$WORK/gate.out")"; fi
  else
    bad "$label" "could not build the step"
  fi
}
LEGS1='[{"leg":"rag","modules":["rag"]}]'
gate 'coverage gate: legs passed'                      coverage 0 pass pull_request x "coverage-leg=success" "legs=$LEGS1"
gate 'coverage gate: no leg had work'                  coverage 0 pass pull_request x "coverage-leg=skipped" "legs=[]"
gate 'coverage gate: legs skipped with work fails'     coverage 0 fail pull_request x "coverage-leg=skipped" "legs=$LEGS1"
gate 'coverage gate: a failed leg fails'               coverage 0 fail pull_request x "coverage-leg=failure" "legs=$LEGS1"
gate 'coverage gate: a cancelled leg fails'            coverage 0 fail merge_group x "coverage-leg=cancelled" "legs=$LEGS1"
gate 'coverage gate: a push with skipped legs fails'   coverage 0 fail push refs/heads/main "coverage-leg=skipped" ""

# All Tests Pass. Results default to success; each case overrides some.
atp() { # atp <label> <want> <event> <ref> <"need=result ..." overrides> <"output=value ...">
  local base="changes=success quick-checks=success test=success test-windows=success performance-tests=success
    coverage=success scaladoc=success docs-tests=skipped integration-tests=success consumers=success spring-compat=success
    workspace-integration=skipped"
  local r="$base" kv
  for kv in $5; do r="$(printf '%s\n' $r | grep -v "^${kv%%=*}=") $kv"; done
  gate "All Tests Pass: $1" all-tests-pass 0 "$2" "$3" "$4" "$(echo $r)" "$6"
}
PART='draft=false code=true docs=false windows=false integration=false consumers=false workspace=false performance=false spring=false other=true full=false'
SKIPS='test-windows=skipped performance-tests=skipped integration-tests=skipped consumers=skipped spring-compat=skipped'
atp 'a selective PR with its gated jobs skipped passes'        pass pull_request x "$SKIPS" "$PART legs=$LEGS1"
atp 'a selective PR with Code Coverage skipped fails'          fail pull_request x "$SKIPS coverage=skipped" "$PART legs=$LEGS1"
atp 'a selective PR with Test (other modules) skipped fails'   fail pull_request x "$SKIPS test=skipped" "$PART legs=$LEGS1"
atp 'a PR reaching no other module with Test (other modules) skipped passes' pass pull_request x "$SKIPS test=skipped" "${PART/other=true/other=false} legs=$LEGS1"
atp 'a PR reaching a consumer root with the consumers job skipped fails' fail pull_request x "$SKIPS" "${PART/consumers=false/consumers=true} legs=$LEGS1"
atp 'a PR reaching the consumers but not Spring passes with Spring skipped' pass pull_request x "${SKIPS/consumers=skipped/consumers=success}" "${PART/consumers=false/consumers=true} legs=$LEGS1"
atp 'a PR reaching Spring with the Spring job skipped fails'   fail pull_request x "$SKIPS" "${PART/spring=false/spring=true} legs=$LEGS1"
atp 'a PR reaching Windows with Windows skipped fails'         fail pull_request x "$SKIPS" "${PART/windows=false/windows=true} legs=$LEGS1"
atp 'a PR reaching performance with it skipped fails'          fail pull_request x "$SKIPS" "${PART/performance=false/performance=true} legs=$LEGS1"
atp 'a PR whose change reaches no leg passes (the gate passed)' pass pull_request x "$SKIPS" "$PART legs=[]"
atp 'a PR with docs and partial code without Docs tests fails' fail pull_request x "$SKIPS" "${PART/docs=false/docs=true} legs=$LEGS1"
atp 'a PR with docs and partial code with Docs tests passes'   pass pull_request x "$SKIPS docs-tests=success" "${PART/docs=false/docs=true} legs=$LEGS1"
atp 'a full PR with docs needs no Docs tests'                  pass pull_request x "" "${PART/docs=false/docs=true} full=true"
atp 'a docs-only PR with Docs tests passes'                    pass pull_request x "test=skipped coverage=skipped $SKIPS docs-tests=success" "draft=false code=false docs=true windows=false integration=false consumers=false workspace=false performance=false spring=false other=false full=false legs=[]"
atp 'a docs-only PR without Docs tests fails'                  fail pull_request x "test=skipped coverage=skipped $SKIPS" "draft=false code=false docs=true windows=false integration=false consumers=false workspace=false performance=false spring=false other=false full=false legs=[]"
atp 'a failed Docs tests fails'                                fail pull_request x "$SKIPS docs-tests=failure" "$PART legs=$LEGS1"
atp 'a selective merge group passes'                           pass merge_group x "$SKIPS" "$PART legs=$LEGS1"
atp 'a merge group with Code Coverage skipped fails'           fail merge_group x "$SKIPS coverage=skipped" "$PART legs=$LEGS1"
atp 'a push to main with the nightly-only jobs skipped passes' pass push refs/heads/main "test-windows=skipped performance-tests=skipped scaladoc=skipped consumers=skipped spring-compat=skipped workspace-integration=success" "full=true"
atp 'a push to main with Code Coverage skipped fails'          fail push refs/heads/main "test-windows=skipped coverage=skipped" "full=true"
atp 'the nightly run with Windows skipped fails'               fail schedule x "test-windows=skipped" "full=true"
atp 'the nightly run with everything passed passes'            pass schedule x "" "full=true"
atp 'a failed Changes job fails'                               fail pull_request x "changes=failure" ""
atp 'a skipped Changes job fails, on a push too'               fail push refs/heads/main "changes=skipped" ""
atp 'a workspace change without the tier fails'                fail pull_request x "$SKIPS" "${PART/workspace=false/workspace=true} legs=$LEGS1"

# scripts/merge-group-labels.sh: every pull request of a merge group is looked up for ci:full (#3185)
LABELS="$(cd "$(dirname "$SCRIPT")" && pwd)/merge-group-labels.sh"
mg="$WORK/mg"; rm -rf "$mg"; mkdir -p "$mg/bin"
git init -q "$mg/repo"
mgc() { (cd "$mg/repo" && git -c user.name=t -c user.email=t@example.com -c commit.gpgsign=false commit -q --allow-empty -m "$1"); }
mgc "base"; MG_BASE="$(git -C "$mg/repo" rev-parse HEAD)"
mgc "fix: first queued change (#3087) (#101)"
mgc "feat: second queued change (#102)"
MG_HEAD="$(git -C "$mg/repo" rev-parse HEAD)"
# a stub gh: `gh api repos/<r>/pulls/<n> --jq ...` prints the labels in $mg/labels-<n>, fails when the file is missing
cat > "$mg/bin/gh" <<'GH'
#!/usr/bin/env bash
n="${2##*/}"; f="$MG_LABELS/labels-$n"; [ -f "$f" ] && cat "$f" || exit 1
GH
chmod +x "$mg/bin/gh"
labels_case() { # labels_case <label> <want> [<head_ref>]
  local got
  got="$(cd "$mg/repo" && PATH="$mg/bin:$PATH" MG_LABELS="$mg" GITHUB_REPOSITORY=o/r \
    "$LABELS" "$MG_BASE" "$MG_HEAD" "${3:-gh-readonly-queue/main/pr-102-$MG_HEAD}" 2>/dev/null)"
  if [ "$got" = "full_label=$2" ]; then ok "$1"; else bad "$1" "got '$got', want full_label=$2"; fi
}
printf 'bug\n' > "$mg/labels-101"; printf '\n' > "$mg/labels-102"
labels_case 'merge-group labels: no pull request in the group has ci:full' false
printf 'bug\nci:full\n' > "$mg/labels-101"
labels_case 'merge-group labels: ci:full on an earlier pull request of a stacked group runs everything' true
printf 'bug\n' > "$mg/labels-101"; printf 'ci:full\n' > "$mg/labels-102"
labels_case 'merge-group labels: ci:full on the last pull request runs everything' true
printf '\n' > "$mg/labels-102"; rm -f "$mg/labels-101"
labels_case 'merge-group labels: a pull request whose labels cannot be read is unknown' unknown
printf '\n' > "$mg/labels-101"
mgc "a commit with no pull request number"; MG_HEAD="$(git -C "$mg/repo" rev-parse HEAD)"
labels_case 'merge-group labels: a commit naming no pull request is unknown' unknown
MG_BASE=0000000000000000000000000000000000000000 labels_case 'merge-group labels: an unreachable base is unknown' unknown

echo
echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
