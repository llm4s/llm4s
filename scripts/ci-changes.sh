#!/usr/bin/env bash
# Decides which of CI's change-gated jobs a pull request or merge group needs, and which modules' tests, from the paths
# it changes.
#
# Prints one `<name>=<value>` line per output on stdout, for $GITHUB_OUTPUT, and why on stderr (the `Changes` log):
#
#   code         `Test (other modules)` and the Code Coverage gate: anything but the files NONCODE matches (docs/,
#                the top-level markdown, other workflows, scripts/ that no test job runs, ...)
#   docs         `ScalaDoc`, and, when `code` is false, `Docs tests` (the suites tagged DocsSpec, which read docs/):
#                docs/, a top-level markdown file, or a script the ScalaDoc job runs
#   windows      `Test (Windows)`: a module that runs whole on Windows (PlatformTests.wholeModules), a file
#                scripts/platform-sensitive-sources.txt lists, or a suite PlatformTests.suites names
#   integration  `Integration Tests`: modules/it, deploy/, config/, or a module the @Docker suites reach (INTEGRATION)
#   consumers    the consumers and Spring jobs: the Java, Kotlin, Spring and Quarkus modules, what they depend on, the
#                Gradle and Maven samples, and the scripts and guides those jobs check (CONSUMERS)
#   workspace    the @Workspace tier (scripts/workspace-tier-changes.sh, which has its own, narrower rules); always
#                false in a merge group, where the queued pull request has already run it
#   performance  `Performance tests`: `sbt testPerformance` runs only core's and agent's tagged tests
#                (`performanceTestProjects` in build.sbt), so core, agent and media (core's one in-build dependency)
#   modules      the projects whose tests the change reaches (#3185), space-separated, by the rule below
#   legs         the coverage legs' matrix (#3186): .github/coverage-legs.json's rows, each cut to its modules in
#                `modules`, and a row left with none dropped, as compact JSON (`[]` when no leg has work)
#   full         true when every module is selected (a build file, the `ci:full` label, an unmapped path, --all)
#
# The module rule, over the committed project graph (.github/ci-graph.json, `sbt generateCiGraph`): each changed file
# belongs to the project whose base directory is its longest prefix, as that project's `test` node (under src/test/)
# or its `compile` node (anything else in the module). A project's tests are selected when its test node reaches a
# changed node: `X:test` needs `X:compile`; a dependency's configuration `a->b` (`;`-separated, a bare `a` is
# `a->compile`) is an edge from X's `a` node to the dependency's `b` node, where `test` is the test node and every
# other configuration (compile, provided, optional, runtime, default) the compile node. So compile dependencies are
# transitive, `% Test` (`test` = test->compile) is one hop into the dependency's main code, and `test->test` chains
# through the dependency's tests. A source a project borrows (`borrowed` in the graph) is a changed node of the
# borrower too. A project in READS is selected directly when a file its suites read changed. A code file in no
# project and not in OUTSIDE_SBT selects everything, as does a build file (BUILD, until #3189) and the `ci:full` label
# (CI_FULL). docs/ is not code: the suites that read it (tagged DocsSpec) run in `Docs tests` whenever docs changed
# and the selection is not full (ci.yml), so a docs edit beside a rag change does not select core's whole test suite.
#
# A build file (BUILD: build.sbt, project/, ci.yml, the composite actions, sbt's and the formatters' options, the
# scripts every sbt job runs, the graph and the leg lists) sets every decision but `workspace` to true, and the
# `ci:full` label does the same. Renames are not folded: a moved file counts as a change to its old path too.
#
# Usage: scripts/ci-changes.sh BASE HEAD    a pull request (CI passes HEAD^1 HEAD on its merge commit) or a merge group
#                                           (its base_sha and head_sha); run from the repo root
#        scripts/ci-changes.sh --all WHY    every module (push to main, nightly, manual, release): prints only
#                                           modules, legs and full, so the decisions stay empty, which every job reads
#                                           as "run"
# Environment: CI_FULL=<why> selects everything (the PR's `ci:full` label); CI_EVENT=merge_group sets workspace=false.
# Needs git and ruby.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
GRAPH="${CI_GRAPH:-$ROOT/.github/ci-graph.json}"
LEGS="${CI_LEGS:-$ROOT/.github/coverage-legs.json}"

ALL=""
if [ "${1:-}" = "--all" ]; then
  ALL="${2:?usage: $0 --all WHY}"
else
  BASE="${1:?usage: $0 BASE HEAD | --all WHY}"
  HEAD="${2:?usage: $0 BASE HEAD | --all WHY}"
fi

# Extended regular expressions, matched against repo-relative paths.
BUILD=(
  '^build\.sbt$'
  '^project/'
  '^\.github/workflows/ci\.yml$'
  '^\.github/actions/'
  '^\.github/ci-graph\.json$'
  '^\.github/coverage-legs\.json$'
  '^\.sbtopts$'
  '^\.jvmopts$'
  '^\.scalafmt\.conf$'
  '^\.scalafix\.conf$'
  '^scripts/ci-changes\.sh$'
  '^scripts/retry-on-transient-network\.sh$'
)
# Not code: no compile or test reads them. Everything else is code, so a new kind of file runs the tests until it is
# listed here. scripts/ is here because Quick Checks runs every script's own tests; the scripts a test job runs are
# BUILD or in that job's list below.
NONCODE=(
  '^docs/'
  '^[^/]+\.md$'
  '^changelog\.d/'
  '^\.github/'
  '^scripts/'
  '^hooks/'
  '^\.devcontainer/'
  '^LICENSE'
  '^NOTICE'
  '^\.git(ignore|attributes)$'
  '^\.editorconfig$'
)
DOCS=(
  '^docs/'
  '^[^/]+\.md$'
  '^scripts/(check-scaladoc-|check-api-links|scaladoc-)'
)
# Code outside the sbt build: a change here selects no sbt module (the jobs that build it have their own decision,
# `consumers`), rather than everything, as an unmapped path would.
OUTSIDE_SBT=(
  '^modules/kotlin-api/'   # its own Gradle build (the consumers job)
  '^modules/quarkus/'      # its own Maven build (the consumers job)
  '^examples/repl/'        # REPL scripts the consumers job runs (scripts/check-repl-scripts.sh)
)
# Files a project's suites read (not compile) from another module or from outside modules/: `<regex> <project>`. The
# graph cannot see a read, so scripts/test-ci-changes.sh fails when a test source names a path (modules/, deploy/,
# config/, examples/) of another module that no entry here covers. A path in no project that an entry covers selects
# that project rather than everything.
# Known and deliberately not listed: core's TroubleshootingLinksSpec and samples' CapabilityMatrixSpec walk every
# module's main sources; selecting them on every change would undo the selection, and the nightly run covers them.
READS=(
  '^modules/workspace/workspaceRunner/src/main/scala/org/llm4s/runner/WindowsBestFit\.scala$ agentTools'
  '^modules/(java-rag|reactor)/src/test/java/ javaApi'
  '^modules/spring-boot-starter/src/test/web/ javaApi'
  '^modules/(it|java-api|kotlin-api|llm4s-effect|llm4s-zio)/src/test/ samples'
  '^deploy/overlays/enterprise/ it'
  '^deploy/overlays/enterprise/ samples'
  '^config/examples/ configPolicy'
)
# The modules the @Docker suites in modules/it import, and what they need: modules/it depends on more (every provider,
# the workspace client, image, speech, mcp, ...), and INTEGRATION_UNREACHED lists those, so that a module `it` starts
# to depend on must be put in one list or the other, and a source file it borrows from another module must be in
# INTEGRATION (scripts/test-ci-changes.sh checks the project graph against both).
INTEGRATION=(
  '^modules/it/'
  '^deploy/'
  '^config/'
  '^modules/(media|core|rag|rag-testkit|vectorstore-elasticsearch|vectorstore-redis|knowledgegraph|knowledgegraph-neo4j)/'
  '^modules/(memory|memory-postgres|observability|openai|openai-compatible|testkit|provider-testkit)/'
  '^modules/(agent|agent-testkit|agent-checkpoint-sqlite|agent-checkpoint-postgres|deploy-service)/'
  '^modules/(secrets-vault|secrets-aws)/'
  # sources modules/it compiles into its own tests (`Test / unmanagedSources`): the Kafka pipeline sample
  '^modules/samples/src/main/scala/org/llm4s/samples/kafka/'
)
# shellcheck disable=SC2034 # read by scripts/test-ci-changes.sh
INTEGRATION_UNREACHED='mcp image speech ollama gemini anthropic azureIdentity voyage bedrock jina jev cohere watsonx
  perplexity jlama workspaceClient observabilityPrometheus traceOpentelemetry schemaDerivation'
# The consumers job builds the Java, Maven, Gradle, Quarkus, Micronaut and Helidon consumers of llm4s-java-api, the BOM,
# the doctor and the REPL against the published library; the Spring job tests the starter and llm4s-spring-ai.
CONSUMERS=(
  '^modules/(java-api|java-rag|reactor|kotlin-api|quarkus|spring-boot-starter|spring-ai|bom|doctor|repl)/'
  '^modules/(media|core|agent|rag|knowledgegraph|observability|observability-micrometer|testkit)/'
  '^modules/(openai|anthropic|ollama|gemini|openai-compatible)/'
  '^modules/samples/(gradle-java|maven-java|quarkus-java|micronaut-java|helidon-java)/'
  '^examples/repl/'
  '^docs/guide/(quarkus|micronaut|helidon|spring-boot|spring-ai)\.md$'
  '^docs/getting-started/maven\.md$'
  '^docs/reference/doctor\.md$'
  '^scripts/check-(kotlin-publication|bom-consumers|doctor-launch|repl-scripts|quarkus-guide-snippets|framework-guide-snippets)\.sh$'
)
PERFORMANCE=(
  '^modules/media/'
  '^modules/core/'
  '^modules/agent/'
)

re() { (IFS='|'; echo "$*"); }

# select_modules FULL_WHY CODE_FILES: prints modules=, legs= and full=, and why on stderr
select_modules() {
  FULL_WHY="$1" CODE_FILES="$2" OUTSIDE_RE="$(re "${OUTSIDE_SBT[@]}")" READS_LIST="$(printf '%s\n' "${READS[@]}")" \
  ruby -rjson - "$GRAPH" "$LEGS" <<'RUBY'
graph_file, legs_file = ARGV
graph = JSON.parse(File.read(graph_file))
projects = graph.fetch("projects")
legs = JSON.parse(File.read(legs_file))
files = ENV["CODE_FILES"].to_s.split("\n").reject(&:empty?)
full_why = ENV["FULL_WHY"].to_s
outside = ENV["OUTSIDE_RE"].to_s.empty? ? nil : Regexp.new(ENV["OUTSIDE_RE"])
reads = ENV["READS_LIST"].to_s.split("\n").reject(&:empty?).map { |l| r, p = l.split(" ", 2); [Regexp.new(r), p.strip] }

KNOWN = %w[compile default runtime provided optional test].freeze
side = ->(c) { c == "test" ? "test" : "compile" }
deps_of = Hash.new { |h, k| h[k] = [] } # node => the nodes it depends on
projects.each do |id, p|
  deps_of["#{id}:test"] << "#{id}:compile"
  Array(p["deps"]).each do |dep, conf|
    conf.to_s.split(";").each do |part|
      from, to = part.split("->", 2).map { |s| s.to_s.strip }
      to = "compile" if to.nil? || to.empty?
      unless KNOWN.include?(from) && KNOWN.include?(to)
        full_why = "#{id} depends on #{dep} in a configuration this script does not know (#{conf})" if full_why.empty?
        next
      end
      deps_of["#{id}:#{side.(from)}"] << "#{dep}:#{side.(to)}"
    end
  end
end
bases = projects.reject { |_, p| p["base"] == "." }.map { |id, p| ["#{p['base']}/", id] }.sort_by { |b, _| -b.size }
borrowers = Hash.new { |h, k| h[k] = [] } # file => the nodes that compile it from outside their own directory
projects.each { |id, p| Array(p["borrowed"]).each { |conf, path| borrowers[path] << "#{id}:#{side.(conf)}" } }

changed = {} # node => the first changed file in it
direct = {}  # project => why its tests are selected without the graph
unless full_why.empty?
  # every project: nothing to trace
else
  files.each do |f|
    b, id = bases.find { |base, _| f.start_with?(base) }
    nodes = borrowers.fetch(f, []).dup
    nodes << "#{id}:#{f[b.size..].start_with?('src/test/') ? 'test' : 'compile'}" if id
    reads.each { |r, p| direct[p] ||= "its suites read #{f}" if r.match?(f) }
    if nodes.empty?
      next if outside&.match?(f)
      next if reads.any? { |r, _| r.match?(f) }
      full_why = "#{f} is in no project of .github/ci-graph.json"
      break
    end
    nodes.each { |n| changed[n] ||= f }
  end
end

selected = {} # project => why
if !full_why.empty?
  projects.each_key { |id| selected[id] = full_why }
else
  # walk back from the changed nodes; toward[n] is the next node on a shortest path from n to a changed one
  dependents = Hash.new { |h, k| h[k] = [] }
  deps_of.each { |n, ds| ds.each { |d| dependents[d] << n } }
  toward = {}
  queue = changed.keys
  queue.each { |n| toward[n] = nil }
  until queue.empty?
    n = queue.shift
    dependents[n].each do |m|
      next if toward.key?(m)
      toward[m] = n
      queue << m
    end
  end
  projects.each_key do |id|
    node = "#{id}:test"
    if toward.key?(node)
      path = [node]
      path << toward[path.last] while toward[path.last]
      selected[id] = "#{path.join(' > ')} (#{changed[path.last]})"
    elsif direct.key?(id)
      selected[id] = direct[id]
    end
  end
  # A leg's report for a module counts every measurement taken so far in the leg, so a selected module's report is the
  # full run's only if the modules ahead of it in its leg whose tests run its code run too: add them, to a fixpoint.
  reach = Hash.new do |h, n|
    seen = { n => true }
    queue = [n]
    until queue.empty?
      deps_of.fetch(queue.shift, []).each { |d| queue << d unless seen[d]; seen[d] = true }
    end
    h[n] = seen
  end
  loop do
    added = false
    legs.each do |r|
      r["modules"].each_with_index do |b, i|
        next unless selected.key?(b)
        r["modules"][0...i].each do |a|
          next if selected.key?(a) || !reach["#{a}:test"].key?("#{b}:compile")
          selected[a] = "runs ahead of #{b} in leg #{r['leg']} and its tests run #{b}'s code, which #{b}'s report counts"
          added = true
        end
      end
    end
    break unless added
  end
end

warn "modules: #{full_why.empty? ? "#{selected.size} of #{projects.size} projects" : "every project: #{full_why}"}"
if full_why.empty?
  selected.sort.each { |id, why| warn "  #{id}: #{why}" }
end
rows = legs.map { |r| { "leg" => r["leg"], "modules" => r["modules"].select { |m| selected.key?(m) } } }
rows.each { |r| warn "leg #{r['leg']}: #{r['modules'].empty? ? 'skipped, no module selected' : "#{r['modules'].size} of #{legs.find { |l| l['leg'] == r['leg'] }['modules'].size}: #{r['modules'].join(' ')}"}" }
puts "modules=#{selected.keys.sort.join(' ')}"
puts "legs=#{JSON.generate(rows.reject { |r| r['modules'].empty? })}"
puts "full=#{full_why.empty? ? 'false' : 'true'}"
RUBY
}

if [ -n "$ALL" ]; then
  select_modules "$ALL" ""
  exit 0
fi

files="$(git diff --name-only --no-renames "$BASE" "$HEAD")"
any() { # any PATTERN...: the changed files matching any of the patterns
  printf '%s\n' "$files" | grep -E "$(re "$@")" || true
}
count() { [ -z "$1" ] && echo 0 || printf '%s\n' "$1" | wc -l | tr -d ' '; }
say() { # say NAME VALUE WHY
  echo "$1: $3" >&2
  echo "$1=$2"
}

build="$(any "${BUILD[@]}")"
full_why=""
if [ -n "$build" ]; then
  full_why="a build file changed ($(printf '%s\n' "$build" | head -1))"
elif [ -n "${CI_FULL:-}" ]; then
  full_why="$CI_FULL"
fi
# decide NAME [FILES]: NAME is true when a build file changed (or CI_FULL), or FILES (those it covers) is not empty
decide() {
  if [ -n "$full_why" ]; then
    say "$1" true "$full_why"
  elif [ -n "${2:-}" ]; then
    say "$1" true "the change touches $(printf '%s\n' "$2" | head -1) ($(count "$2") file(s))"
  else
    say "$1" false "nothing it covers changed"
  fi
}

code_files="$(printf '%s\n' "$files" | grep -vE "$(re "${NONCODE[@]}")" | grep -v '^$' || true)"
decide code "$code_files"
decide docs "$(any "${DOCS[@]}")"

# windows: PlatformTests' whole modules (their directories, from build.sbt), the listed sources, and the named suites
windows_files="$(printf '%s\n' "$files" | ruby -e '
  files = $stdin.read.split("\n").reject(&:empty?)
  sbt = File.exist?("build.sbt") ? File.read("build.sbt") : ""
  dirs = sbt.scan(/^lazy val (\w+)\s*=\s*\(?project in file\("([^"]+)"\)/).to_h.transform_values { |d| d.squeeze("/") }
  pt = File.exist?("project/PlatformTests.scala") ? File.read("project/PlatformTests.scala") : ""
  whole = pt[/val wholeModules[^=]*=\s*Seq\((.*?)\)/m, 1].to_s.scan(/"(\w+)"/).flatten
  suites = pt[/val suites[^=]*=\s*Seq\((.*)\)/m, 1].to_s.scan(/"([\w.*]+\.[\w*]+)"/).flatten
  listed = File.exist?("scripts/platform-sensitive-sources.txt") ?
    File.readlines("scripts/platform-sensitive-sources.txt").map(&:strip).reject { |l| l.empty? || l.start_with?("#") }
      .map { |l| l.split.first } : []
  prefixes = whole.map { |m| dirs[m] }.compact.map { |d| "#{d}/" } + ["scripts/platform-sensitive-sources.txt"]
  suite_re = suites.map { |s| Regexp.new("\\A" + Regexp.escape(s).gsub("\\*", "[^.]*") + "\\z") }
  files.each do |f|
    fqcn = f[%r{/src/test/(?:scala|java)/(.+)\.(?:scala|java)\z}, 1]&.tr("/", ".")
    hit = prefixes.any? { |p| f.start_with?(p) } ||
          listed.any? { |l| l.end_with?("/") ? f.start_with?(l) : f == l } ||
          (fqcn && suite_re.any? { |r| r.match?(fqcn) })
    puts f if hit
  end
')"
decide windows "$windows_files"
decide integration "$(any "${INTEGRATION[@]}")"
decide consumers "$(any "${CONSUMERS[@]}")"
if [ "${CI_EVENT:-}" = merge_group ]; then
  say workspace false "a merge group: the queued pull request has already run the tier"
else
  "$HERE/workspace-tier-changes.sh" "$BASE" "$HEAD" | sed 's/^changed=/workspace=/'
fi
decide performance "$(any "${PERFORMANCE[@]}")"
select_modules "$full_why" "$code_files"
