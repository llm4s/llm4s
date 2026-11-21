#!/usr/bin/env bash
# Fails when a test that reads the repository's docs/ is not tagged DocsSpec, or is in a module `sbt testDocs` skips.
#
# A pull request that changes only docs/ (and other non-code files, scripts/ci-changes.sh) skips the test and coverage
# jobs, and runs, besides Quick Checks and ScalaDoc, only the suites that read docs/: they test a guide's own code
# blocks, its links or its tables against the code, so a docs-only change can break them. They are found by their tag,
# the class annotation `org.llm4s.tags.DocsSpec`, and `sbt testDocs` runs the tag in the modules that have it
# (`docsTestProjects` in build.sbt). A suite that reads docs/ without the tag would not run on such a pull request, so
# this checks, against the build sbt actually loaded (the build model, project/BuildModel.scala):
#
#   - every test source that reads docs/ is a tagged suite, or a helper that only tagged suites use. A source reads docs/
#     when a code line (not a comment) has a string literal naming a path under docs/ that exists in this repository
#     (`"docs/guide/x.md"`, `s"docs/tutorials/$page"`, `"docs/_data"`); a test's own fixture paths (`"docs/a.txt"`) do
#     not exist, so they do not count. A source that declares no suite (`class <Name>Spec|Suite`) is a helper, and
#     every source in the same module that names one of its top-level types or members reads docs/ in turn;
#   - every module with a tagged suite is one `sbt testDocs` runs, and every module it runs has one.
#
# No network, no sbt when given a model. Needs ruby (on GitHub's runners).
#
# Usage: scripts/check-docs-tests.sh [--model FILE] [--root DIR] [--list]
#   --model  the build model (default target/build-model.json; write it with `sbt "dumpBuildModel FILE"`)
#   --root   the repository whose modules/ and docs/ are read (default this one)
#   --list   print the suites that read docs/, one per line as <project> <file>, and exit
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODEL="$REPO_ROOT/target/build-model.json"
ROOT="$REPO_ROOT"
LIST=false
while [ $# -gt 0 ]; do
  case "$1" in
    --model) MODEL="$2"; shift 2 ;;
    --root) ROOT="$2"; shift 2 ;;
    --list) LIST=true; shift ;;
    -h|--help) sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
command -v ruby >/dev/null || { echo "ruby is needed to run this check" >&2; exit 2; }
[ -f "$MODEL" ] || { echo "No such file: $MODEL" >&2; exit 2; }

ruby -rjson - "$MODEL" "$ROOT" "$LIST" <<'RUBY'
model_file, root, list_only = ARGV
TAG = 'org.llm4s.tags.DocsSpec'
ALIAS = 'testDocs'
COMMENT = %r{\A\s*(//|/?\*)}
LITERAL = /(?:\bs)?"docs\/([^"\/$]+)/ # the first path segment under docs/
TAGGED = /@(?:org\.llm4s\.tags\.)?DocsSpec\b/
SUITE = /^\s*(?:@[\w.]+(?:\([^)]*\))?\s+)*(?:final\s+)?class\s+(\w+(?:Spec|Suite))\b/

model = JSON.parse(File.read(model_file))
bases = model.fetch('projects').to_h { |p| [p.fetch('id'), p.fetch('base')] }
owner = lambda do |f|
  bases.select { |_, b| b != '.' && f.start_with?("#{b}/") }.max_by { |_, b| b.size }&.first
end
code_lines = ->(text) { text.each_line.reject { |l| l.match?(COMMENT) } }
reads_docs = lambda do |text|
  code_lines.(text).any? { |l| l.scan(LITERAL).flatten.any? { |seg| File.exist?(File.join(root, 'docs', seg)) } }
end

sources = {} # file => text, test sources of the build's projects
bases.each do |id, base|
  next if base == '.'
  Dir.glob(File.join(root, base, 'src', 'test', '**', '*.{scala,java}')).each do |f|
    rel = f.delete_prefix("#{root}/")
    sources[rel] = File.read(f) if owner.(rel) == id
  end
end

readers = sources.select { |_, t| reads_docs.(t) }.keys
# Helpers: a reader that declares no suite passes the reading on to the sources of its module that name it.
loop do
  added = []
  readers.reject { |f| sources[f].match?(SUITE) }.each do |h|
    names = sources[h].scan(/^\s*(?:private\[\w+\]\s+|final\s+|case\s+|sealed\s+)*(?:object|trait|class)\s+(\w+)/).flatten
    names -= %w[apply unapply main]
    next if names.empty?
    word = /\b(?:#{names.map { |n| Regexp.escape(n) }.join('|')})\b/
    sources.each do |f, t|
      next if readers.include?(f) || added.include?(f) || owner.(f) != owner.(h)
      added << f if code_lines.(t).any? { |l| l.match?(word) }
    end
  end
  break if added.empty?
  readers.concat(added)
end
suites = readers.select { |f| sources[f].match?(SUITE) }.sort

if list_only == 'true'
  suites.each { |f| puts "#{owner.(f)} #{f}" }
  exit 0
end

errors = []
body = (model['aliases'] || []).find { |a| a['name'] == ALIAS }&.fetch('body')
abort "The build model has no `#{ALIAS}` alias" unless body
run = body.scan(%r{(?:\A|;)\s*([A-Za-z0-9_]+)/testOnly\s+--\s+-n\s+#{Regexp.escape(TAG)}\b}).flatten
errors << "`#{ALIAS}` (#{body}) runs no `<project>/testOnly -- -n #{TAG}`" if run.empty?
run.each { |p| errors << "`#{ALIAS}` runs #{p}, which is not a project of this build" unless bases.key?(p) }

suites.reject { |f| sources[f].match?(TAGGED) }.each do |f|
  errors << "#{f} reads docs/ but is not tagged: annotate its suite class @DocsSpec (org.llm4s.tags.DocsSpec)"
end
tagged = sources.select { |_, t| t.match?(TAGGED) && t.match?(SUITE) }.keys
tagged.group_by { |f| owner.(f) }.each do |p, fs|
  next if run.include?(p)
  errors << "#{fs.first} is tagged DocsSpec, but `sbt #{ALIAS}` does not run #{p}: add it to `docsTestProjects` in build.sbt"
end
run.each do |p|
  next if !bases.key?(p) || tagged.any? { |f| owner.(f) == p }
  errors << "`#{ALIAS}` runs #{p}, which has no suite tagged DocsSpec: drop it from `docsTestProjects`"
end

if errors.empty?
  puts "OK: the #{suites.size} suites that read docs/ are tagged DocsSpec (#{tagged.size} tagged in all), in the " \
       "#{run.size} modules `sbt #{ALIAS}` runs"
else
  errors.each { |e| puts "FAIL: #{e}" }
  exit 1
end
RUBY
