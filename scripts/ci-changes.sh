#!/usr/bin/env bash
# Decides which of CI's change-gated jobs a pull request needs, from the paths it changes.
#
# Prints one `<name>=true|false` line per decision on stdout, for $GITHUB_OUTPUT, and why on stderr:
#
#   code         the coverage legs, `Test (other modules)` and the Code Coverage gate: anything but the files NONCODE
#                matches (docs/, the top-level markdown, other workflows, scripts/ that no test job runs, ...)
#   docs         `ScalaDoc`, and, when `code` is false, `Docs tests` (the suites tagged DocsSpec, which read docs/):
#                docs/, a top-level markdown file, or a script the ScalaDoc job runs
#   windows      `Test (Windows)`: a module that runs whole on Windows (PlatformTests.wholeModules), a file
#                scripts/platform-sensitive-sources.txt lists, or a suite PlatformTests.suites names
#   integration  `Integration Tests`: modules/it, deploy/, config/, or a module the @Docker suites reach (INTEGRATION)
#   consumers    the consumers and Spring jobs: the Java, Kotlin, Spring and Quarkus modules, what they depend on, the
#                Gradle and Maven samples, and the scripts and guides those jobs check (CONSUMERS)
#   workspace    the @Workspace tier (scripts/workspace-tier-changes.sh, which has its own, narrower rules)
#   performance  `Performance tests`: `sbt testPerformance` runs only core's and agent's tagged tests
#                (`performanceTestProjects` in build.sbt), so core, agent and media (core's one in-build dependency)
#
# A build file (BUILD: build.sbt, project/, ci.yml, the composite actions, sbt's and the formatters' options, the
# scripts every sbt job runs) sets every decision but `workspace` to true. Renames are not folded: a moved file counts
# as a change to its old path too. The nightly run, and every event but a pull request, runs every job regardless.
#
# Usage: scripts/ci-changes.sh BASE HEAD      (CI passes HEAD^1 HEAD on the PR's merge commit, from the repo root)
# Needs git and ruby.
set -euo pipefail

BASE="${1:?usage: $0 BASE HEAD}"
HEAD="${2:?usage: $0 BASE HEAD}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Extended regular expressions, matched against repo-relative paths.
BUILD=(
  '^build\.sbt$'
  '^project/'
  '^\.github/workflows/ci\.yml$'
  '^\.github/actions/'
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
# The modules the @Docker suites in modules/it import, and what they need: modules/it depends on more (every provider,
# the workspace client, image, speech, mcp, ...), and INTEGRATION_UNREACHED lists those, so that a module `it` starts
# to depend on must be put in one list or the other, and a source file it borrows from another module must be in
# INTEGRATION (scripts/test-ci-changes.sh checks build.sbt against both).
INTEGRATION=(
  '^modules/it/'
  '^deploy/'
  '^config/'
  '^modules/(media|core|rag|vectorstore-elasticsearch|vectorstore-redis|knowledgegraph|knowledgegraph-neo4j)/'
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

files="$(git diff --name-only --no-renames "$BASE" "$HEAD")"
any() { # any PATTERN...: the changed files matching any of the patterns
  printf '%s\n' "$files" | grep -E "$(IFS='|'; echo "$*")" || true
}
count() { [ -z "$1" ] && echo 0 || printf '%s\n' "$1" | wc -l | tr -d ' '; }
say() { # say NAME VALUE WHY
  echo "$1: $3" >&2
  echo "$1=$2"
}

build="$(any "${BUILD[@]}")"
# decide NAME [FILES]: NAME is true when a build file changed, or FILES (those it covers) is not empty
decide() {
  if [ -n "$build" ]; then
    say "$1" true "a build file changed ($(printf '%s\n' "$build" | head -1))"
  elif [ -n "${2:-}" ]; then
    say "$1" true "the change touches $(printf '%s\n' "$2" | head -1) ($(count "$2") file(s))"
  else
    say "$1" false "nothing it covers changed"
  fi
}

code_files="$(printf '%s\n' "$files" | grep -vE "$(IFS='|'; echo "${NONCODE[*]}")" | grep -v '^$' || true)"
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
"$HERE/workspace-tier-changes.sh" "$BASE" "$HEAD" | sed 's/^changed=/workspace=/'
decide performance "$(any "${PERFORMANCE[@]}")"
