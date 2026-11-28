#!/usr/bin/env bash
# Decides which of CI's change-gated jobs a pull request or merge group needs, and which modules' tests, from the paths
# it changes.
#
# Prints one `<name>=<value>` line per output on stdout, for $GITHUB_OUTPUT, and why on stderr (the `Changes` log):
#
#   code         the Code Coverage gate: anything but the files NONCODE matches (docs/, the top-level markdown, other
#                workflows, scripts/ that no test job runs, ...)
#   docs         `ScalaDoc`, and, when `code` is false, `Docs tests` (the suites tagged DocsSpec, which read docs/):
#                docs/, a top-level markdown file, or a script the ScalaDoc job runs
#   integration  `Integration Tests`: modules/it, deploy/, config/, or a module the @Docker suites reach (INTEGRATION)
#   workspace    the @Workspace tier (scripts/workspace-tier-changes.sh, which has its own, narrower rules); always
#                false in a merge group, where the queued pull request has already run it
#   windows      `Test (Windows)`: the change touches a module that runs whole on Windows (PlatformTests.wholeModules),
#                a source scripts/platform-sensitive-sources.txt lists, or a suite PlatformTests.suites names, and those
#                files reach the tests of a project the lists hold; windows_modules names those projects
#   consumers    the consumers job: an affected project (`reached`, below) in CONSUMER_ROOTS, the modules it builds
#                against the published library, or a file it reads outside them (CONSUMER_PATHS)
#   spring       `Spring Boot and Spring AI (latest)`: an affected project in SPRING_ROOTS, the modules it tests
#   performance  `Performance tests`: an affected project in `performanceTestProjects` (build.sbt), the modules with
#                tagged tests; performance_modules names them
#   other        `Test (other modules)`: an affected project in ci.yml's TEST_MODULES (the modules no coverage leg
#                runs); test_modules names them
#   modules      the projects whose tests the change reaches (#3185), space-separated, by the rule below
#   legs         the coverage legs' matrix (#3186): .github/coverage-legs.json's rows, each cut to its modules in
#                `modules`, and a row left with none dropped, as compact JSON (`[]` when no leg has work)
#   full         true when every module is selected (a build file, the `ci:full` label, an unmapped path, --all)
#   windows_modules, performance_modules, test_modules
#                what the job runs when its decision is true: a space-separated list, or empty for everything the job
#                knows (`sbt testPlatform`, `sbt testPerformance`, all of TEST_MODULES), which is what a full
#                selection and every non-PR event (no decision is printed, so every job runs) get
#
# `reached` is `modules` before leg completeness (below), the projects whose tests run changed code or read a changed
# file: the jobs other than the legs test those, so a module added only to keep a leg's report whole selects no job.
# A full selection (any reason) sets every decision but `workspace` to true, so what the graph cannot place runs
# everything, as it does in the legs.
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
# project and not in OUTSIDE_SBT selects everything, as does a build file (BUILD) and the `ci:full` label (CI_FULL).
# docs/ is not code: the suites that read it (tagged DocsSpec) run in `Docs tests` whenever docs changed and the
# selection is not full (ci.yml), so a docs edit beside a rag change does not select core's whole test suite.
#
# Build files (#3189). A file in FINGERPRINTED (build.sbt, project/Dependencies.scala, the graph itself) is judged by
# what it does to the loaded build, not by its text: base's committed graph is compared with head's, which Quick
# Checks proves is what head's build loads. A project whose dependencies, borrowed sources or `fp.compile`
# fingerprint changed counts as a changed compile node, one whose test dependencies or `fp.test` changed as a changed
# test node (project/CiGraph.scala lists what each fingerprint hashes), so a floor bump selects that module's tests, a
# `dependsOn` its dependents, and a comment nothing. A changed `global` hash (the command aliases), a project that
# moved or a base graph without fingerprints selects everything. A file in PROJECT_FILES (a project/*.scala that one
# job or project uses) selects what it names. What no hash sees - the body of a task or command defined in build.sbt
# or Dependencies.scala, and a source generator's code - is not selected; Quick Checks runs the build's own checks on
# every pull request, and push, nightly and release runs run everything.
#
# Any other build file (BUILD: the rest of project/, ci.yml, the composite actions, sbt's and the formatters'
# options, the scripts every sbt job runs, the leg lists) sets every decision but `workspace` to true, and the
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
  '^scripts/merge-group-labels\.sh$'
  '^scripts/merge-queue-tested\.sh$'
  '^scripts/retry-on-transient-network\.sh$'
)
# Build files judged by the graph diff (above), not as BUILD.
FINGERPRINTED=(
  '^build\.sbt$'
  '^project/Dependencies\.scala$'
  '^\.github/ci-graph\.json$'
)
# project/*.scala files one job or project uses, not as BUILD: `<regex> <target>...`, a target being a project's node
# (`samples:test`, `deployService:compile`), a job (`job:windows` runs every Windows entry, `job:consumers`,
# `job:integration`), or `-`: only Quick Checks, which runs on every pull request, uses it.
PROJECT_FILES=(
  '^project/PlatformTests\.scala$ job:windows'
  '^project/ItTiers\.scala$ it:test job:integration'
  '^project/DocSnippets\.scala$ samples:test'
  '^project/Relocation\.scala$ relocationCore:compile relocationWorkspaceClient:compile relocationWorkspaceShared:compile relocationTraceOpentelemetry:compile relocationKnowledgegraphNeo4j:compile'
  '^project/DeployServiceDocker\.scala$ deployService:compile job:integration'
  '^project/(PomDescriptions|PomMetadata|PublishedArtifacts|CentralBundle|Bom|JavadocPointer)\.scala$ job:consumers'
  '^project/(StabilityTiers|LicencePolicy|FrozenDependencies|CompatibilityMatrix|BuildModel|SbomPrune)\.scala$ -'
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
# the doctor and the REPL against the published library: a change it can see is one an affected project of
# CONSUMER_ROOTS reaches (their dependencies, by the graph), or one of the files outside the sbt build it reads.
CONSUMER_ROOTS='javaApi javaRag reactor springBootStarter springAi bom doctor repl'
CONSUMER_PATHS=(
  '^modules/(kotlin-api|quarkus)/'
  '^modules/samples/(gradle-java|maven-java|quarkus-java|micronaut-java|helidon-java)/'
  '^examples/repl/'
  '^docs/guide/(quarkus|micronaut|helidon|spring-boot|spring-ai)\.md$'
  '^docs/getting-started/maven\.md$'
  '^docs/reference/doctor\.md$'
  '^scripts/check-(kotlin-publication|bom-consumers|doctor-launch|repl-scripts|quarkus-guide-snippets|framework-guide-snippets)\.sh$'
)
# The Spring job tests the starter against the latest Spring Boot 3.x and 4.x, and llm4s-spring-ai against the latest
# Spring AI: those projects' own tests.
SPRING_ROOTS='springBootStarter springBootStarterBoot4 springAi'
# PlatformTests projects whose named suites test another project's code, which the graph cannot see: `<project>
# <the project they test>`. Windows runs such a project's entries only when the change reaches the tested project, or
# touches a file of the project itself (a listed suite, its own sources).
# `it` depends on nearly every module, so the graph alone would compile its tests on Windows for almost any change,
# while its two listed suites (the vision clients' file encoding) test image.
WINDOWS_VIA='it:image'

re() { (IFS='|'; echo "$*"); }

# select_modules FULL_WHY CODE_FILES [ALL_FILES]: prints modules=, legs= and full=, and why on stderr; given ALL_FILES
# (a pull request or merge group, not --all) also the module-based decisions (windows, consumers, spring, performance,
# other) and their module lists
select_modules() {
  FULL_WHY="$1" CODE_FILES="$2" ALL_FILES="${3:-}" DECIDE="$([ $# -ge 3 ] && echo 1 || true)" \
  OUTSIDE_RE="$(re "${OUTSIDE_SBT[@]}")" READS_LIST="$(printf '%s\n' "${READS[@]}")" \
  ROOT="$ROOT" CONSUMER_ROOTS="$CONSUMER_ROOTS" CONSUMER_RE="$(re "${CONSUMER_PATHS[@]}")" SPRING_ROOTS="$SPRING_ROOTS" \
  WINDOWS_VIA="$WINDOWS_VIA" EXTRA_NODES="${EXTRA_NODES:-}" FORCED_JOBS="${FORCED_JOBS:-}" \
  ruby -rjson -ryaml - "$GRAPH" "$LEGS" <<'RUBY'
graph_file, legs_file = ARGV
graph = JSON.parse(File.read(graph_file))
projects = graph.fetch("projects")
legs = JSON.parse(File.read(legs_file))
files = ENV["CODE_FILES"].to_s.split("\n").reject(&:empty?)
all_files = ENV["ALL_FILES"].to_s.split("\n").reject(&:empty?)
decide = !ENV["DECIDE"].to_s.empty?
full_why = ENV["FULL_WHY"].to_s
outside = ENV["OUTSIDE_RE"].to_s.empty? ? nil : Regexp.new(ENV["OUTSIDE_RE"])
reads = ENV["READS_LIST"].to_s.split("\n").reject(&:empty?).map { |l| r, p = l.split(" ", 2); [Regexp.new(r), p.strip] }
# the nodes the build files changed (`<node> <base|-> <why>` lines) and the jobs they need, from the bash part
extra = ENV["EXTRA_NODES"].to_s.split("\n").reject(&:empty?).to_h { |l| n, _, w = l.split(" ", 3); [n, "build: #{w}"] }
forced_jobs = ENV["FORCED_JOBS"].to_s.split

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

dependents = Hash.new { |h, k| h[k] = [] }
deps_of.each { |n, ds| ds.each { |d| dependents[d] << n } }

# trace FILES [NODES]: [the projects whose tests FILES and the changed NODES (node => why) reach (project => why),
# nil], or [nil, why] when a file is in no project
trace = lambda do |fs, extra_nodes = {}|
  changed = extra_nodes.select { |n, _| projects.key?(n.split(":").first) } # node => the first changed file in it
  direct = {}  # project => why its tests are selected without the graph
  fs.each do |f|
    b, id = bases.find { |base, _| f.start_with?(base) }
    nodes = borrowers.fetch(f, []).dup
    nodes << "#{id}:#{f[b.size..].start_with?('src/test/') ? 'test' : 'compile'}" if id
    reads.each { |r, p| direct[p] ||= "its suites read #{f}" if r.match?(f) }
    if nodes.empty?
      next if outside&.match?(f)
      next if reads.any? { |r, _| r.match?(f) }
      return [nil, "#{f} is in no project of .github/ci-graph.json"]
    end
    nodes.each { |n| changed[n] ||= f }
  end
  # walk back from the changed nodes; toward[n] is the next node on a shortest path from n to a changed one
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
  sel = {}
  projects.each_key do |id|
    node = "#{id}:test"
    if toward.key?(node)
      path = [node]
      path << toward[path.last] while toward[path.last]
      sel[id] = "#{path.join(' > ')} (#{changed[path.last]})"
    elsif direct.key?(id)
      sel[id] = direct[id]
    end
  end
  [sel, nil]
end

reached = {} # project => why: the projects whose tests run changed code or read a changed file
if full_why.empty?
  sel, why = trace.(files, extra)
  if why then full_why = why else reached = sel end
end
selected = {} # project => why: `reached`, and what leg completeness adds
if !full_why.empty?
  projects.each_key { |id| selected[id] = full_why }
  reached = selected.dup
else
  selected = reached.dup
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

# The module-based decisions (#3187): each job's projects that `reached` holds, or everything when the selection is full.
if decide
  root = ENV.fetch("ROOT")
  full = !full_why.empty?
  say = ->(name, value, why) { warn "#{name}: #{why}"; puts "#{name}=#{value}" }
  # a name that is not a project could never be reached: scripts/test-ci-changes.sh checks every list here against the
  # committed graph, so a renamed project fails there; a smaller graph (the tests' own) just drops it
  known = lambda do |what, ids|
    missing = ids.reject { |m| projects.key?(m) }
    warn "note: #{what} names #{missing.join(', ')}, not in the project graph" unless missing.empty?
    ids - missing
  end
  # decide_on NAME LIST_NAME IDS [EXTRA_FILES]: NAME is true when the selection is full, or a project of IDS is
  # reached, or EXTRA_FILES (files outside the sbt build the job reads) is not empty; LIST_NAME, when given, is the
  # reached projects of IDS (empty, which the job reads as all of them, when the selection is full)
  decide_on = lambda do |name, list_name, ids, extra_files = []|
    hit = ids.select { |m| reached.key?(m) }
    if full || forced_jobs.include?(name)
      say.(name, true, full ? full_why : "a project file it uses changed")
      puts "#{list_name}=" if list_name
    elsif !hit.empty?
      say.(name, true, "the change reaches #{hit.join(' ')}: #{hit.map { |m| "#{m}: #{reached[m]}" }.first}")
      puts "#{list_name}=#{hit.join(' ')}" if list_name
    elsif !extra_files.empty?
      say.(name, true, "the change touches #{extra_files.first} (#{extra_files.size} file(s))")
    else
      say.(name, false, "the change reaches none of #{ids.join(' ')}")
    end
  end

  # windows: the files of PlatformTests' whole modules, the sources platform-sensitive-sources.txt lists, and the suites
  # PlatformTests names; Windows runs the listed projects those files reach (not every listed project the whole change
  # reaches: a change elsewhere that is not platform-sensitive is pure JVM logic, which ubuntu tests)
  pt = File.read(File.join(root, "project/PlatformTests.scala"))
  whole = pt[/val wholeModules[^=]*=\s*Seq\((.*?)\)/m, 1].to_s.scan(/"(\w+)"/).flatten
  suites_src = pt[/val suites[^=]*=\s*Seq\((.*)\)/m, 1].to_s
  suite_mods = suites_src.scan(/"(\w+)"\s*->/).flatten
  suite_names = suites_src.scan(/"([\w.*]+\.[\w*]+)"/).flatten
  abort "ci-changes.sh: cannot read wholeModules and suites from project/PlatformTests.scala" if whole.empty? || suite_mods.empty?
  platform = known.("project/PlatformTests.scala", (whole + suite_mods).uniq.sort)
  src_list = File.join(root, "scripts/platform-sensitive-sources.txt")
  listed = File.exist?(src_list) ?
    File.readlines(src_list).map(&:strip).reject { |l| l.empty? || l.start_with?("#") }.map { |l| l.split.first } : []
  prefixes = whole.select { |m| projects.key?(m) }.map { |m| "#{projects[m]['base']}/" }
  suite_re = suite_names.map { |s| Regexp.new("\\A" + Regexp.escape(s).gsub("\\*", "[^.]*") + "\\z") }
  wfiles = files.select do |f|
    fqcn = f[%r{/src/test/(?:scala|java)/(.+)\.(?:scala|java)\z}, 1]&.tr("/", ".")
    prefixes.any? { |p| f.start_with?(p) } ||
      listed.any? { |l| l.end_with?("/") ? f.start_with?(l) : f == l } ||
      (fqcn && suite_re.any? { |r| r.match?(fqcn) })
  end
  # and the build settings of a listed project (its own nodes, from the build files)
  wextra = extra.select { |n, _| platform.include?(n.split(":").first) }
  if full || forced_jobs.include?("windows")
    say.("windows", true, full ? full_why : "a project file it runs changed (project/PlatformTests.scala)")
    puts "windows_modules="
  elsif wfiles.empty? && wextra.empty?
    say.("windows", false, "nothing it covers changed")
  else
    wsel, why = trace.(wfiles, wextra)
    wfiles = wfiles + wextra.values
    via = ENV["WINDOWS_VIA"].to_s.split.to_h { |e| e.split(":", 2) }
    # the project's own file, or its own build settings, changed
    own = lambda do |m|
      wextra.keys.any? { |n| n.split(":").first == m } ||
        wfiles.any? { |f| bases.find { |b, _| f.start_with?(b) }&.last == m }
    end
    hit = why ? [] : platform.select { |m| wsel.key?(m) && (!via.key?(m) || wsel.key?(via[m]) || own.(m)) }
    if why
      say.("windows", true, why)
      puts "windows_modules="
    elsif hit.empty?
      say.("windows", false, "#{wfiles.first} reaches no project project/PlatformTests.scala lists")
    else
      say.("windows", true, "the change touches #{wfiles.first} (#{wfiles.size} file(s)), which reaches #{hit.join(' ')}")
      puts "windows_modules=#{hit.join(' ')}"
    end
  end

  consumer_re = ENV["CONSUMER_RE"].to_s.empty? ? nil : Regexp.new(ENV["CONSUMER_RE"])
  decide_on.("consumers", nil, known.("CONSUMER_ROOTS", ENV["CONSUMER_ROOTS"].split),
             consumer_re ? all_files.grep(consumer_re) : [])
  decide_on.("spring", nil, known.("SPRING_ROOTS", ENV["SPRING_ROOTS"].split))
  perf = File.read(File.join(root, "build.sbt"))[/lazy val performanceTestProjects\s*=\s*Seq\(([^)]*)\)/, 1]
  abort "ci-changes.sh: cannot read performanceTestProjects from build.sbt" unless perf
  decide_on.("performance", "performance_modules", known.("performanceTestProjects", perf.scan(/"(\w+)"/).flatten))
  others = YAML.load_file(File.join(root, ".github/workflows/ci.yml")).dig("jobs", "test", "env", "TEST_MODULES").to_s.split
  abort "ci-changes.sh: cannot read the test job's TEST_MODULES from ci.yml" if others.empty?
  decide_on.("other", "test_modules", known.("TEST_MODULES", others))
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

fingerprinted="$(any "${FINGERPRINTED[@]}")"
mapped="" # the PROJECT_FILES entries' targets, one per line: `<target> <file>`
handled="$fingerprinted"
while IFS= read -r f; do
  [ -n "$f" ] || continue
  for e in "${PROJECT_FILES[@]}"; do
    if [[ "$f" =~ ${e%% *} ]]; then
      for t in ${e#* }; do mapped+="$t $f"$'\n'; done
      handled+=$'\n'"$f"
      break
    fi
  done
done <<< "$files"
handled="$(printf '%s\n' "$handled" | grep -v '^$' || true)"
build="$(any "${BUILD[@]}" | { if [ -n "$handled" ]; then grep -vxF "$handled"; else cat; fi; } || true)"
full_why=""
if [ -n "$build" ]; then
  full_why="a build file changed ($(printf '%s\n' "$build" | head -1))"
elif [ -n "${CI_FULL:-}" ]; then
  full_why="$CI_FULL"
fi

# The build files' own selection: the nodes they change (`<node> <why>` lines, EXTRA_NODES), the jobs they need
extra_nodes=""
forced_jobs=""
if [ -z "$full_why" ] && [ -n "$fingerprinted" ]; then
  base_graph="$(git show "$BASE:.github/ci-graph.json" 2>/dev/null || true)"
  diff_out="$(BASE_GRAPH="${CI_BASE_GRAPH:+$(cat "$CI_BASE_GRAPH")}" BASE_GRAPH_GIT="$base_graph" ruby -rjson - "$GRAPH" <<'RUBY'
head = JSON.parse(File.read(ARGV[0]))
src = ENV["BASE_GRAPH"].to_s.empty? ? ENV["BASE_GRAPH_GIT"].to_s : ENV["BASE_GRAPH"]
full = ->(why) { puts "full #{why}"; exit 0 }
full.("the base has no project graph") if src.strip.empty?
base = JSON.parse(src)
bp, hp = base.fetch("projects", {}), head.fetch("projects", {})
full.("the base's project graph has no fingerprints") if base["global"].nil? || bp.values.any? { |p| p["fp"].nil? }
full.("head's project graph has no fingerprints") if head["global"].nil? || hp.values.any? { |p| p["fp"].nil? }
full.("the command aliases changed (the graph's global hash)") if base["global"] != head["global"]
side = ->(c) { c.to_s.strip == "test" ? "test" : "compile" }
# edges SIDE P: the project's dependency edges that start at its SIDE node, as `dep:from->to`
edges = lambda do |s, p|
  Array(p["deps"]).flat_map { |d, conf| conf.to_s.split(";").map { |part| f, t = part.split("->", 2); [d, side.(f), (t || "compile").strip] } }
    .select { |_, f, _| f == s }.map { |d, _, t| "#{d}->#{t}" }.sort
end
# the changed nodes, printed only once every project has passed the checks that select everything
nodes = []
hp.sort.each do |id, h|
  b = bp[id]
  if b.nil?
    nodes << "#{id}:compile #{h['base']} a new project"
    next
  end
  full.("#{id} moved from #{b['base']} to #{h['base']}") if b["base"] != h["base"]
  %w[compile test].each do |s|
    why = []
    why << "its #{s} dependencies changed" if edges.(s, b) != edges.(s, h)
    why << "its #{s} borrowed sources changed" if
      Array(b["borrowed"]).select { |c, _| side.(c) == s }.sort != Array(h["borrowed"]).select { |c, _| side.(c) == s }.sort
    why << "its #{s} settings changed (fp.#{s})" if b["fp"][s] != h["fp"][s]
    nodes << "#{id}:#{s} #{h['base']} #{why.join(', ')}" unless why.empty?
  end
end
puts nodes
RUBY
)"
  if full_line="$(printf '%s\n' "$diff_out" | grep -m1 '^full ')"; then
    full_why="${full_line#full }"
  else
    extra_nodes="$diff_out"
  fi
fi
while IFS=' ' read -r t f; do
  [ -n "${t:-}" ] || continue
  case "$t" in
    -) echo "build: $f is used only by Quick Checks" >&2 ;;
    job:*) forced_jobs+=" ${t#job:}"; echo "build: $f needs ${t#job:}" >&2 ;;
    *) extra_nodes+=$'\n'"$t - $f changed" ;;
  esac
done <<< "$mapped"
extra_nodes="$(printf '%s\n' "$extra_nodes" | grep -v '^$' || true)"
[ -z "$extra_nodes" ] || printf 'build: %s\n' "$extra_nodes" >&2
forced() { [[ " $forced_jobs " == *" $1 "* ]]; }
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
# integration: its paths, a project in them whose build settings changed (its base directory), or a project file it uses
integration_files="$(any "${INTEGRATION[@]}")"
build_dirs="$(printf '%s\n' "$extra_nodes" | awk '$2 != "-" && $2 != "" { print $2 "/" }' |
  grep -E "$(re "${INTEGRATION[@]}")" || true)"
if forced integration; then build_dirs+=$'\n'"$(printf '%s\n' "$mapped" | awk '$1 == "job:integration" { print $2 }')"; fi
decide integration "$(printf '%s\n' "$integration_files" "$build_dirs" | grep -v '^$' || true)"
if [ "${CI_EVENT:-}" = merge_group ]; then
  say workspace false "a merge group: the queued pull request has already run the tier"
else
  "$HERE/workspace-tier-changes.sh" "$BASE" "$HEAD" | sed 's/^changed=/workspace=/'
fi
# windows, consumers, spring, performance and other, by the project graph; then modules, legs and full. The build
# files handled above are not traced as files (build.sbt is in no project): their nodes are EXTRA_NODES.
EXTRA_NODES="$extra_nodes" FORCED_JOBS="$forced_jobs" select_modules "$full_why" \
  "$(printf '%s\n' "$code_files" | { if [ -n "$handled" ]; then grep -vxF "$handled"; else cat; fi; } || true)" "$files"
