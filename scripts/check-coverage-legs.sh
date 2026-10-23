#!/usr/bin/env bash
# Fails when a module with a coverage floor would not have that floor enforced by CI.
#
# CI's coverage runs in parallel legs (the `coverage-leg` job's matrix in .github/workflows/ci.yml): each leg runs
# `<module>/test <module>/coverageReport` for the modules it lists, and `<module>/coverageReport` is what enforces the
# module's floor (`coverageFloor(n)` in build.sbt). A module in no leg is never measured, and nothing else says so;
# that is how a carved-out module used to drop out of coverage when someone forgot to add it to the job. This checks,
# against the build sbt actually loaded (the build model, project/BuildModel.scala) rather than build.sbt's text:
#
#   - every module with a coverage floor is in exactly one leg, or in EXEMPT below with the reason;
#   - every module in a leg has a coverage floor, and no leg name or module is listed twice;
#   - every module in a leg has exactly one Codecov upload step in that job, gated on its leg
#     (`if: contains(matrix.modules, '<module>')`), uploading `<base>/target/scala-<version>/scoverage-report/scoverage.xml`
#     under the codecov.yml flag whose path is `<base>/src/main/scala`, and no upload step names a module in no leg;
#     so each flag is uploaded once, from the leg that owns the module;
#   - the `integration-tests` job (#2349) is the one deliberate second uploader: the modules in its test step's
#     COVERAGE_MODULES env (the explicit allow-list) are instrumented there, its test step switches their floors off
#     (`coverageFailOnMinimum := false`, since those reports measure only the integration suites), and each has
#     exactly one upload step in that job, of its own report under its own flag. Any other upload there fails.
#
# No network, no sbt when given a model. Needs ruby (on GitHub's runners).
#
# Usage: scripts/check-coverage-legs.sh [--model FILE] [--workflow FILE] [--codecov FILE]
#   --model     the build model (default target/build-model.json; write it with `sbt "dumpBuildModel FILE"`)
#   --workflow  the workflow holding the `coverage-leg` job (default .github/workflows/ci.yml)
#   --codecov   the Codecov configuration (default codecov.yml)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODEL="$REPO_ROOT/target/build-model.json"
WORKFLOW="$REPO_ROOT/.github/workflows/ci.yml"
CODECOV="$REPO_ROOT/codecov.yml"
while [ $# -gt 0 ]; do
  case "$1" in
    --model) MODEL="$2"; shift 2 ;;
    --workflow) WORKFLOW="$2"; shift 2 ;;
    --codecov) CODECOV="$2"; shift 2 ;;
    -h|--help) sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
command -v ruby >/dev/null || { echo "ruby is needed to run this check" >&2; exit 2; }
for f in "$MODEL" "$WORKFLOW" "$CODECOV"; do
  [ -f "$f" ] || { echo "No such file: $f" >&2; exit 2; }
done

ruby -ryaml -rjson - "$MODEL" "$WORKFLOW" "$CODECOV" <<'RUBY'
model_file, workflow_file, codecov_file = ARGV

# Modules whose floor no leg enforces, each with the reason. Every entry needs one; adding a module here takes the
# same deliberate decision as `coverageDisabled` in build.sbt. These two were outside the single coverage job too.
EXEMPT = {
  'benchmarks'          => 'JMH benchmarks; benchmarks.yml runs them, and codecov.yml ignores modules/benchmarks',
  'knowledgegraphNeo4j' => 'not in the coverage job before the legs either (docs/reference/test-coverage.md); ' \
                           'its store is exercised against Neo4j by the modules/it Docker tier, which uploads its ' \
                           'coverage from the integration-tests job without enforcing the floor',
}
JOB = 'coverage-leg'
IT_JOB = 'integration-tests'
IT_LIST = 'COVERAGE_MODULES'

errors = []
model = JSON.parse(File.read(model_file))
projects = model.fetch('projects').to_h { |p| [p.fetch('id'), p] }
abort "The build model has no coverageFloor field; rewrite it with this build's dumpBuildModel" \
  unless projects.values.all? { |p| p.key?('coverageFloor') }
floors = projects.select { |_, p| p['coverageFloor'] }.keys.sort

wf = YAML.load_file(workflow_file)
job = (wf['jobs'] || {})[JOB]
abort "#{workflow_file} has no job '#{JOB}'" unless job
include_rows = Array(job.dig('strategy', 'matrix', 'include'))
abort "#{JOB} has no strategy.matrix.include legs" if include_rows.empty?

legs = {}
include_rows.each do |row|
  leg = row['leg'].to_s
  mods = Array(row['modules']).map(&:to_s)
  errors << "a leg has no name: #{row.inspect}" if leg.empty?
  errors << "leg '#{leg}' is listed twice" if legs.key?(leg)
  errors << "leg '#{leg}' has no modules" if mods.empty?
  mods.group_by(&:itself).transform_values(&:size).each { |m, n| errors << "leg '#{leg}' lists #{m} #{n} times" if n > 1 }
  legs[leg] = mods
end
owner = {} # module => the legs listing it
legs.each { |leg, mods| mods.uniq.each { |m| (owner[m] ||= []) << leg } }
legs_of = ->(m) { owner.fetch(m, []) }

floors.each do |m|
  if EXEMPT.key?(m)
    errors << "#{m} is exempt (#{EXEMPT[m]}) but also in leg(s) #{legs_of[m].join(', ')}: drop one" unless legs_of[m].empty?
  elsif legs_of[m].empty?
    errors << "#{m} has a coverage floor (#{projects[m]['coverageFloor']}%) but is in no coverage leg: " \
              "add it to a leg of #{JOB} in #{File.basename(workflow_file)}, with an upload step for its flag"
  elsif legs_of[m].size > 1
    errors << "#{m} is in more than one coverage leg: #{legs_of[m].join(', ')}"
  end
end
EXEMPT.each_key do |m|
  errors << "#{m} is in EXEMPT but has no coverage floor in the build (or no longer exists): remove it" \
    unless floors.include?(m)
end
owner.each_key do |m|
  if !projects.key?(m)
    errors << "#{m}, in leg #{owner[m].join(', ')}, is not a project of this build"
  elsif !projects[m]['coverageFloor']
    errors << "#{m}, in leg #{owner[m].join(', ')}, has no coverage floor, so its coverageReport would enforce nothing"
  end
end

codecov = YAML.load_file(codecov_file) || {}
flag_paths = (codecov['flags'] || {}).to_h { |name, f| [name, Array((f || {})['paths'])] }

report_of = ->(p) { "#{p['base']}/target/scala-#{p['scalaVersion']}/scoverage-report/scoverage.xml" }
# An upload step for module m (project p) must upload m's report under the one flag whose path is m's sources.
check_upload = lambda do |s, m, p|
  w = s['with'] || {}
  want = report_of.(p)
  errors << "upload step '#{s['name']}' for #{m} uploads #{w['files'].inspect}, expected #{want}" if w['files'] != want
  flag = w['flags'].to_s
  if flag.empty? || flag.include?(',')
    errors << "upload step '#{s['name']}' for #{m} must carry exactly one flag, has #{w['flags'].inspect}"
  elsif !flag_paths.key?(flag)
    errors << "upload step '#{s['name']}' for #{m} uses flag '#{flag}', which #{File.basename(codecov_file)} does not define"
  elsif flag_paths[flag] != ["#{p['base']}/src/main/scala"]
    errors << "flag '#{flag}' (uploaded for #{m}) has paths #{flag_paths[flag].inspect} in " \
              "#{File.basename(codecov_file)}, expected [\"#{p['base']}/src/main/scala\"]"
  end
end

uploads = Array(job['steps']).select { |s| s['uses'].to_s.start_with?('codecov/codecov-action') }
by_module = {} # module => its upload steps
uploads.each do |s|
  label = s['name'] || s['uses']
  m = s['if'].to_s[/\A\s*(?:\$\{\{\s*)?contains\(matrix\.modules,\s*'([^']+)'\)\s*(?:\}\})?\s*\z/, 1]
  if m.nil?
    errors << "upload step '#{label}' is not gated on one module's leg (if: contains(matrix.modules, '<module>')), " \
              "so it would run in every leg"
    next
  end
  (by_module[m] ||= []) << s
end
by_module.each do |m, steps|
  next if owner.key?(m)
  steps.each { |s| errors << "upload step '#{s['name']}' is gated on #{m}, which is in no leg, so it never runs" }
end
owner.each_key do |m|
  steps = by_module.fetch(m, [])
  if steps.empty?
    errors << "#{m} is in a leg but has no Codecov upload step gated on it"
    next
  end
  errors << "#{m} has #{steps.size} Codecov upload steps; it needs exactly one" if steps.size > 1
  p = projects[m] or next
  steps.each { |s| check_upload.(s, m, p) }
end
uploads.map { |s| (s['with'] || {})['flags'] }.compact.group_by(&:itself).transform_values(&:size).each do |flag, n|
  errors << "flag '#{flag}' is uploaded by #{n} steps; each flag is uploaded once" if n > 1
end

# The integration job (#2349): its suites run against real services with coverage on for the modules named in its
# test step's COVERAGE_MODULES, and each of those uploads its report under the flag its leg uploads too. That second
# upload of a flag is allowed for exactly these modules, the explicit list; any other upload in the job fails.
it_job = (wf['jobs'] || {})[IT_JOB]
abort "#{workflow_file} has no job '#{IT_JOB}'" unless it_job
it_steps = Array(it_job['steps'])
it_runs = it_steps.select { |s| (s['env'] || {}).key?(IT_LIST) }
it_uploads = it_steps.select { |s| s['uses'].to_s.start_with?('codecov/codecov-action') }
measured = []
if it_runs.size != 1
  errors << "#{IT_JOB} needs exactly one step with env #{IT_LIST} (the modules its suites measure), has #{it_runs.size}" \
    unless it_runs.empty? && it_uploads.empty?
else
  run_step = it_runs.first
  measured = run_step['env'][IT_LIST].to_s.split
  errors << "#{IT_JOB}'s #{IT_LIST} is empty: drop it, or name the modules its suites measure" if measured.empty?
  measured.group_by(&:itself).transform_values(&:size).each do |m, n|
    errors << "#{IT_JOB}'s #{IT_LIST} lists #{m} #{n} times" if n > 1
  end
  unless run_step['run'].to_s.include?('coverageFailOnMinimum := false')
    errors << "#{IT_JOB}'s test step does not set coverageFailOnMinimum := false for #{IT_LIST}: its reports measure " \
              "only the integration suites, so a module's floor must not be judged on them (its leg enforces it)"
  end
  measured.uniq.each do |m|
    if !projects.key?(m)
      errors << "#{m}, in #{IT_JOB}'s #{IT_LIST}, is not a project of this build"
    elsif !projects[m]['coverageFloor']
      errors << "#{m}, in #{IT_JOB}'s #{IT_LIST}, has no coverage floor (coverage is disabled), so it is not measured"
    end
  end
end
it_by_module = {} # module => its upload steps in the integration job
it_uploads.each do |s|
  files = (s['with'] || {})['files']
  m = measured.uniq.find { |id| projects.key?(id) && files == report_of.(projects[id]) }
  if m.nil?
    errors << "upload step '#{s['name'] || s['uses']}' in #{IT_JOB} uploads #{files.inspect}, which is not the report " \
              "of a module in its #{IT_LIST}"
  else
    (it_by_module[m] ||= []) << s
  end
end
measured.uniq.select { |m| projects.key?(m) && projects[m]['coverageFloor'] }.each do |m|
  steps = it_by_module.fetch(m, [])
  if steps.size != 1
    errors << "#{m} is in #{IT_JOB}'s #{IT_LIST} but has #{steps.size} Codecov upload steps there; it needs exactly one"
  end
  steps.each { |s| check_upload.(s, m, projects[m]) }
end

if errors.empty?
  puts "Coverage legs: #{legs.map { |l, ms| "#{l} (#{ms.size})" }.join(', ')}"
  puts "OK: each of the #{floors.size - EXEMPT.size} modules with a coverage floor is in exactly one leg with " \
       "one upload step; exempt: #{EXEMPT.keys.join(', ')}"
  puts "Also uploaded by #{IT_JOB}: #{measured.empty? ? 'none' : measured.join(', ')}"
else
  errors.each { |e| puts "FAIL: #{e}" }
  exit 1
end
RUBY
