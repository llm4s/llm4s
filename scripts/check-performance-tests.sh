#!/usr/bin/env bash
# Fails when a test tagged PerformanceTest is in a module that `sbt testPerformance` does not run.
#
# The performance tier (tests tagged `org.llm4s.tags.PerformanceTest`, `LinearTime.assertLinear` and other timing
# ratios) is left out of `sbt test` and the coverage legs, and `sbt testPerformance` runs it. The alias runs only the
# modules that have such tests (`performanceTestProjects` in build.sbt), not `testOnly` across the whole build, which
# compiled every module's tests to run tests in two of them. So a module whose tests start using the tag must join that
# list, or its tests run nowhere: this checks, against the build sbt actually loaded (the build model,
# project/BuildModel.scala), that every test source using the tag (a code line naming `PerformanceTest`, not a comment
# or an import; or the tag's name as a string) is under the base directory of a project the alias runs, and that every
# project the alias runs exists and has such a test.
#
# No network, no sbt when given a model. Needs ruby (on GitHub's runners).
#
# Usage: scripts/check-performance-tests.sh [--model FILE] [--root DIR]
#   --model  the build model (default target/build-model.json; write it with `sbt "dumpBuildModel FILE"`)
#   --root   the repository whose modules/ is searched (default this one)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODEL="$REPO_ROOT/target/build-model.json"
ROOT="$REPO_ROOT"
while [ $# -gt 0 ]; do
  case "$1" in
    --model) MODEL="$2"; shift 2 ;;
    --root) ROOT="$2"; shift 2 ;;
    -h|--help) sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
command -v ruby >/dev/null || { echo "ruby is needed to run this check" >&2; exit 2; }
[ -f "$MODEL" ] || { echo "No such file: $MODEL" >&2; exit 2; }

ruby -rjson -rfind - "$MODEL" "$ROOT" <<'RUBY'
model_file, root = ARGV
TAG = 'org.llm4s.tags.PerformanceTest'
ALIAS = 'testPerformance'
# A use of the tag: a code line (not a comment, not an import) naming `PerformanceTest`, or the tag's name as a string.
COMMENT = %r{\A\s*(//|/?\*|import\b)}
uses_tag = lambda do |text|
  text.include?(%("#{TAG}")) || text.each_line.any? { |l| !l.match?(COMMENT) && l.match?(/\bPerformanceTest\b/) }
end

model = JSON.parse(File.read(model_file))
bases = model.fetch('projects').to_h { |p| [p.fetch('id'), p.fetch('base')] }
body = (model['aliases'] || []).find { |a| a['name'] == ALIAS }&.fetch('body')
abort "The build model has no `#{ALIAS}` alias" unless body
run = body.scan(%r{(?:\A|;)\s*([A-Za-z0-9_]+)/testOnly\s+--\s+-n\s+#{Regexp.escape(TAG)}\b}).flatten
errors = []
errors << "`#{ALIAS}` (#{body}) runs no `<project>/testOnly -- -n #{TAG}`" if run.empty?
run.each { |p| errors << "`#{ALIAS}` runs #{p}, which is not a project of this build" unless bases.key?(p) }

files = []
Dir.glob(File.join(root, 'modules', '**', 'src', 'test', '**', '*.{scala,java}')).sort.each do |f|
  next if f.include?('/target/')
  files << f.delete_prefix("#{root}/") if uses_tag.call(File.read(f))
end
# A file belongs to the project with the longest base that contains it.
owner = lambda do |f|
  bases.select { |_, b| b != '.' && f.start_with?("#{b}/") }.max_by { |_, b| b.size }&.first
end
by_project = files.group_by { |f| owner.call(f) }
by_project.each do |p, fs|
  next if run.include?(p)
  fs.each do |f|
    errors << "#{f} is tagged PerformanceTest, but `sbt #{ALIAS}` does not run #{p || 'its module'}: add it to " \
              '`performanceTestProjects` in build.sbt'
  end
end
run.each do |p|
  next unless bases.key?(p)
  errors << "`#{ALIAS}` runs #{p}, which has no PerformanceTest: drop it from `performanceTestProjects`" \
    unless by_project.key?(p)
end

if errors.empty?
  puts "OK: the #{files.size} test files tagged PerformanceTest are all in #{run.join(', ')}, which `sbt #{ALIAS}` runs"
else
  errors.each { |e| puts "FAIL: #{e}" }
  exit 1
end
RUBY
