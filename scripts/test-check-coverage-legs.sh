#!/usr/bin/env bash
# Tests for scripts/check-coverage-legs.sh: the repository's own workflow passes, and each way a coverage floor can
# stop being enforced (or a flag be uploaded twice) fails with a message naming it. Each case copies the build model,
# ci.yml and codecov.yml into a scratch directory, changes one thing with ruby, and runs the check there. The modules
# changed are picked from the real files (the first leg's first module, ...), so a new module or a re-balanced leg
# does not touch this test.
#
# No network, no sbt. Usage: scripts/test-check-coverage-legs.sh [build-model.json]   (default target/build-model.json)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="$REPO_ROOT/scripts/check-coverage-legs.sh"
MODEL="${1:-$REPO_ROOT/target/build-model.json}"
[ -f "$MODEL" ] || { echo "No build model at $MODEL; write it with sbt \"dumpBuildModel $MODEL\"" >&2; exit 2; }
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PASSED=0
FAILED=0

fresh() {
  rm -rf "$WORK/case"
  mkdir -p "$WORK/case"
  cp "$MODEL" "$WORK/case/model.json"
  cp "$REPO_ROOT/.github/workflows/ci.yml" "$WORK/case/ci.yml"
  cp "$REPO_ROOT/codecov.yml" "$WORK/case/codecov.yml"
}

# mutate <<'RB' ... RB : run Ruby with `wf` (ci.yml), `job` (its coverage-leg job), `legs` (its matrix rows),
# `uploads` (the leg job's upload steps), `it_job` (the integration-tests job), `it_run` (its step with
# COVERAGE_MODULES), `it_uploads` (its upload steps), `model` and `codecov` loaded; each is written back afterwards.
mutate() {
  ruby -ryaml -rjson - "$WORK/case" "$(cat)" <<'RUBY'
dir, code = ARGV
wf = YAML.load_file("#{dir}/ci.yml")
job = wf['jobs']['coverage-leg']
legs = job['strategy']['matrix']['include']
model = JSON.parse(File.read("#{dir}/model.json"))
codecov = YAML.load_file("#{dir}/codecov.yml")
uploads = job['steps'].select { |s| s['uses'].to_s.start_with?('codecov/') }
it_job = wf['jobs']['integration-tests']
it_run = it_job['steps'].find { |s| (s['env'] || {}).key?('COVERAGE_MODULES') }
it_uploads = it_job['steps'].select { |s| s['uses'].to_s.start_with?('codecov/') }
eval(code) # rubocop:disable Security/Eval -- the cases below are this file's own code
File.write("#{dir}/ci.yml", wf.to_yaml)
File.write("#{dir}/model.json", JSON.generate(model))
File.write("#{dir}/codecov.yml", codecov.to_yaml)
RUBY
}

run_check() {
  "$CHECK" --model "$WORK/case/model.json" --workflow "$WORK/case/ci.yml" --codecov "$WORK/case/codecov.yml" > "$WORK/out" 2>&1
}

expect_pass() {
  if run_check; then
    PASSED=$((PASSED + 1)); echo "ok   $1"
  else
    FAILED=$((FAILED + 1)); echo "FAIL $1 (expected to pass)"; sed 's/^/       /' "$WORK/out"
  fi
}

# expect_fail NAME PATTERN : the check must fail and its output must match PATTERN (grep -E).
expect_fail() {
  if run_check; then
    FAILED=$((FAILED + 1)); echo "FAIL $1 (expected to fail)"; sed 's/^/       /' "$WORK/out"
  elif grep -qE "$2" "$WORK/out"; then
    PASSED=$((PASSED + 1)); echo "ok   $1"
  else
    FAILED=$((FAILED + 1)); echo "FAIL $1 (failed, but without /$2/)"; sed 's/^/       /' "$WORK/out"
  fi
}

fresh
expect_pass "the repository's coverage legs pass"

fresh
mutate <<'RB'
legs[0]['modules'].shift
RB
expect_fail "a module dropped from its leg fails" "has a coverage floor .* but is in no coverage leg"

fresh
mutate <<'RB'
legs[1]['modules'] << legs[0]['modules'].first
RB
expect_fail "a module in two legs fails" "is in more than one coverage leg"

fresh
mutate <<'RB'
model['projects'] << { 'id' => 'brandNewModule', 'base' => 'modules/brand-new', 'scalaVersion' => '3.7.1', 'coverageFloor' => 80 }
RB
expect_fail "a new module with a floor and no leg fails" "brandNewModule has a coverage floor \\(80%\\) but is in no coverage leg"

fresh
mutate <<'RB'
model['projects'] << { 'id' => 'brandNewModule', 'base' => 'modules/brand-new', 'scalaVersion' => '3.7.1', 'coverageFloor' => nil }
RB
expect_pass "a new module without a floor needs no leg"

fresh
mutate <<'RB'
legs[0]['modules'] << model['projects'].find { |p| p['coverageFloor'].nil? }['id']
RB
expect_fail "a module without a floor in a leg fails" "has no coverage floor, so its coverageReport would enforce nothing"

fresh
mutate <<'RB'
legs[0]['modules'] << 'benchmarks'
RB
expect_fail "an exempt module in a leg fails" "benchmarks is exempt .* but also in leg"

fresh
mutate <<'RB'
model['projects'].find { |p| p['id'] == 'benchmarks' }['coverageFloor'] = nil
RB
expect_fail "an exemption for a module with no floor fails" "benchmarks is in EXEMPT but has no coverage floor"

fresh
mutate <<'RB'
legs << { 'leg' => legs[0]['leg'], 'modules' => ['x'] }
RB
expect_fail "a leg name listed twice fails" "is listed twice"

fresh
mutate <<'RB'
m = legs[0]['modules'].first
job['steps'].reject! { |s| s['if'] == "contains(matrix.modules, '#{m}')" }
RB
expect_fail "a module with no upload step fails" "has no Codecov upload step gated on it"

fresh
mutate <<'RB'
m = legs[0]['modules'].first
dup = job['steps'].find { |s| s['if'] == "contains(matrix.modules, '#{m}')" }
job['steps'] << Marshal.load(Marshal.dump(dup))
RB
expect_fail "a module with two upload steps fails" "has 2 Codecov upload steps"

fresh
mutate <<'RB'
uploads.first.delete('if')
RB
expect_fail "an upload step run in every leg fails" "is not gated on one module's leg"

fresh
mutate <<'RB'
uploads.first['if'] = "contains(matrix.modules, 'noSuchModule')"
RB
expect_fail "an upload step gated on a module in no leg fails" "gated on noSuchModule, which is in no leg"

fresh
mutate <<'RB'
uploads.first['with']['files'] = uploads.last['with']['files']
RB
expect_fail "an upload of another module's report fails" "uploads .*, expected "

fresh
mutate <<'RB'
uploads.first['with']['flags'] = uploads.first['with']['flags'] + ',' + uploads.last['with']['flags']
RB
expect_fail "an upload with two flags fails" "must carry exactly one flag"

fresh
mutate <<'RB'
codecov['flags'].delete(uploads.first['with']['flags'])
RB
expect_fail "an upload under a flag codecov.yml lacks fails" "which codecov.yml does not define"

fresh
mutate <<'RB'
codecov['flags'][uploads.first['with']['flags']]['paths'] = ['modules/elsewhere/src/main/scala']
RB
expect_fail "a flag whose path is not the module's fails" "has paths .* expected"

fresh
mutate <<'RB'
job['strategy']['matrix'].delete('include')
RB
expect_fail "a coverage job without legs fails" "has no strategy.matrix.include legs"

# The integration-tests job (#2349): the second uploader of the flags in its COVERAGE_MODULES, and of no other.
fresh
mutate <<'RB'
it_job['steps'].delete(it_run)
it_job['steps'].reject! { |s| it_uploads.include?(s) }
RB
expect_pass "an integration job that measures nothing passes"

fresh
mutate <<'RB'
m = legs.flat_map { |l| l['modules'] }.find { |id| !it_run['env']['COVERAGE_MODULES'].split.include?(id) }
p = model['projects'].find { |x| x['id'] == m }
step = Marshal.load(Marshal.dump(it_uploads.first))
step['name'] = "Upload #{m} integration coverage to Codecov"
step['with']['files'] = "#{p['base']}/target/scala-#{p['scalaVersion']}/scoverage-report/scoverage.xml"
step['with']['flags'] = job['steps'].find { |s| s['if'] == "contains(matrix.modules, '#{m}')" }['with']['flags']
it_job['steps'] << step
it_run['env']['COVERAGE_MODULES'] += " #{m}"
RB
expect_pass "a leg's module added to the integration job with its upload passes"

fresh
mutate <<'RB'
it_run['env']['COVERAGE_MODULES'] = it_run['env']['COVERAGE_MODULES'].split.drop(1).join(' ')
RB
expect_fail "an integration upload of a module not in COVERAGE_MODULES fails" "which is not the report of a module in its COVERAGE_MODULES"

fresh
mutate <<'RB'
it_job['steps'].delete(it_uploads.first)
RB
expect_fail "a module in COVERAGE_MODULES with no upload step fails" "is in integration-tests's COVERAGE_MODULES but has 0 Codecov upload steps"

fresh
mutate <<'RB'
it_job['steps'] << Marshal.load(Marshal.dump(it_uploads.first))
RB
expect_fail "a module in COVERAGE_MODULES with two upload steps fails" "but has 2 Codecov upload steps there"

fresh
mutate <<'RB'
it_uploads.first['with']['flags'] = it_uploads.last['with']['flags']
RB
expect_fail "an integration upload under another module's flag fails" "has paths .* expected"

fresh
mutate <<'RB'
it_run['run'] = it_run['run'].gsub('coverageFailOnMinimum := false', 'coverageEnabled := true')
RB
expect_fail "an integration run that enforces floors fails" "does not set coverageFailOnMinimum := false"

fresh
mutate <<'RB'
it_run['env']['COVERAGE_MODULES'] += ' ' + model['projects'].find { |p| p['coverageFloor'].nil? }['id']
RB
expect_fail "a module without a floor in COVERAGE_MODULES fails" "has no coverage floor \\(coverage is disabled\\)"

fresh
mutate <<'RB'
it_run['env']['COVERAGE_MODULES'] += ' noSuchModule'
RB
expect_fail "an unknown module in COVERAGE_MODULES fails" "noSuchModule, in integration-tests's COVERAGE_MODULES, is not a project"

fresh
mutate <<'RB'
it_run['env']['COVERAGE_MODULES'] += ' ' + it_run['env']['COVERAGE_MODULES'].split.first
RB
expect_fail "a module listed twice in COVERAGE_MODULES fails" "COVERAGE_MODULES lists .* 2 times"

fresh
mutate <<'RB'
it_job['steps'].delete(it_run)
RB
expect_fail "integration uploads without COVERAGE_MODULES fail" "needs exactly one step with env COVERAGE_MODULES"

echo
echo "$PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]
