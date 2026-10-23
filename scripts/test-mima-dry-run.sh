#!/usr/bin/env bash
# Tests for scripts/mima-dry-run.sh, with no sbt and no network. Each case runs the script against a small fixture
# git repository (a build.sbt with three projects, two of them frozen) and a fake `sbt` that answers the way the real
# one does, with one thing wrong: an empty baseline, an artifact that was not published, MiMa comparing nothing,
# MiMa finding problems in the build's own copy, a negative control that cannot fail, an @Experimental type that is
# not excluded, artifacts left in ~/.m2, a failing publish. Every one of them must fail the script with a message
# that names the problem, and the good case must pass.
#
# Per-module baselines (#2355) run against fixture variants whose `mimaBaselineOverrides` gives llm4s-agent a
# baseline of its own (`Some`) or none (`None`): each must pass, comparing every module with its own baseline, and
# a build that ignores the override, or an override the script cannot read or that names no frozen module, must not.
#
# The script's own run against the real build.sbt is checked at the end: it must find the frozen modules by reading
# the `mimaFrozen("llm4s-...")` call sites, the same number a plain grep finds.
#
# Usage: scripts/test-mima-dry-run.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$REPO_ROOT/scripts/mima-dry-run.sh"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/test-mima-dry-run.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
FAILED=0
PASSED=0

pass() { PASSED=$((PASSED + 1)); echo "ok   $1"; }
fail() { FAILED=$((FAILED + 1)); echo "FAIL $1"; }

# ---- the fixture repository: its own build.sbt, committed
FIXTURE="$WORK/fixture"
mkdir -p "$FIXTURE/modules/core" "$FIXTURE/modules/agent" "$FIXTURE/modules/samples"
cat > "$FIXTURE/build.sbt" <<'EOF'
val mimaBaselineVersion: Option[String] = None

val mimaBaselineOverrides: Map[String, Option[String]] = Map.empty

def mimaFrozen(module: String) = Seq()

lazy val core = (project in file("modules/core"))
  .settings(name := "llm4s-core", mimaFrozen("llm4s-core"))

lazy val agent = (project in file("modules/agent"))
  .dependsOn(core)
  .settings(
    name := "llm4s-agent",
    mimaFrozen("llm4s-agent")
  )

lazy val samples = (project in file("modules/samples"))
  .settings(name := "llm4s-samples")
EOF
git -C "$FIXTURE" init -q
git -C "$FIXTURE" -c user.name=test -c user.email=test@example.com add -A
git -C "$FIXTURE" -c user.name=test -c user.email=test@example.com commit -q -m fixture

# ---- the fake sbt. It reads the scratch clone it runs in (build.sbt, dryrun.sbt) like the real one would, and
# behaves according to FAKE_* variables. Every call is appended to $FAKE_CALLS.
mkdir -p "$WORK/bin"
cat > "$WORK/bin/fake-sbt" <<'EOF'
#!/usr/bin/env bash
set -u
while [ $# -gt 0 ] && case "$1" in -*) true ;; *) false ;; esac; do shift; done
version="$(sed -n 's/^ThisBuild \/ version *:= *"\(.*\)"$/\1/p' dryrun.sbt)"
repo="$(sed -n 's|.*file://\([^"]*\)".*|\1|p' dryrun.sbt | head -1)"
shared="$(sed -n 's/^val mimaBaselineVersion: Option\[String\] = Some("\(.*\)")$/\1/p' build.sbt)"
baseline_of() { # artifact -> its baseline version, empty for none; FAKE_IGNORE_OVERRIDES=1 is a build that ignores
  # mimaBaselineOverrides and gives every module the shared baseline
  if [ "${FAKE_IGNORE_OVERRIDES:-}" != "1" ]; then
    grep -q "\"$1\" -> None" build.sbt && return 0
    own="$(sed -n "s/.*\"$1\" -> Some(\"\([^\"]*\)\").*/\1/p" build.sbt)"
    [ -n "$own" ] && { echo "$own"; return 0; }
  fi
  echo "$shared"
}
artifact_of() { # project id -> artifact
  awk -v id="$1" '$0 ~ "^lazy val " id " " {f=1} f && /name := "/ {match($0, /name := "[^"]*"/); print substr($0, RSTART+9, RLENGTH-10); exit}' build.sbt
}
publish_artifact() { # artifact
  [ "${FAKE_SKIP_PUBLISH:-}" = "$1" ] && return 0
  mkdir -p "$repo/org/llm4s/${1}_3/$version"
  : > "$repo/org/llm4s/${1}_3/$version/${1}_3-$version.jar"
}
status=0
for cmd in "$@"; do
  echo "$cmd" >> "$FAKE_CALLS"
  case "$cmd" in
    publish)
      [ "${FAKE_PUBLISH_FAIL:-}" = "1" ] && { echo "[error] simulated publish failure"; exit 1; }
      for a in $(sed -n 's/.*name := "\(llm4s-[a-z-]*\)".*/\1/p' build.sbt); do publish_artifact "$a"; done ;;
    */publish)
      publish_artifact "$(artifact_of "${cmd%/publish}")" ;;
    "show "*/mimaPreviousArtifacts)
      id="${cmd#show }"; id="${id%/mimaPreviousArtifacts}"
      baseline="$(baseline_of "$(artifact_of "$id")")"
      if [ "${FAKE_EMPTY_BASELINE:-}" = "1" ] || [ -z "$baseline" ]; then echo "[info] Set()"
      else echo "[info] Set(org.llm4s:$(artifact_of "$id"):$baseline)"; fi ;;
    "show "*/mimaFindBinaryIssues)
      id="${cmd#show }"; id="${id%/mimaFindBinaryIssues}"
      art="$(artifact_of "$id")"
      baseline="$(baseline_of "$art")"
      [ "${FAKE_NO_MAP:-}" = "$id" ] && continue
      [ -z "$baseline" ] && { echo "[info] Map()"; continue; }
      if [ "$baseline" = "0.0.0-mima-probe" ]; then
        case "${FAKE_NEG:-stable}" in
          none) echo "[info] Map(org.llm4s:${art}_3:$baseline -> (List(),List()))" ;;
          both) echo "[info] Map(org.llm4s:${art}_3:$baseline -> (List(MissingClassProblem(class MimaDryRunExperimentalProbe), MissingClassProblem(class MimaDryRunStableProbe)),List()))" ;;
          *) echo "[info] Map(org.llm4s:${art}_3:$baseline -> (List(MissingClassProblem(class MimaDryRunStableProbe)),List()))" ;;
        esac
      elif [ "${FAKE_PROBLEM:-}" = "$id" ]; then
        echo "[info] Map(org.llm4s:${art}_3:$baseline -> (List(MissingMethodProblem(method foo())),List()))"
      else
        echo "[info] Map(org.llm4s:${art}_3:$baseline -> (List(),List()))"
      fi ;;
    "show "*/mimaExcludeAnnotations)
      echo "[info] List(org.llm4s.annotation.Experimental)" ;;
    mimaReportBinaryIssues)
      [ "${FAKE_REPORT_FAIL:-}" = "1" ] && { echo "[error] simulated MiMa failure"; status=1; } ;;
    */mimaReportBinaryIssues)
      if [ "$(baseline_of "$(artifact_of "${cmd%/mimaReportBinaryIssues}")")" = "0.0.0-mima-probe" ] && [ "${FAKE_NEG:-stable}" != "none" ]; then
        echo "[error] Failed binary compatibility check! Found problems (simulated)"; status=1
      fi ;;
  esac
  [ "$status" -ne 0 ] && exit "$status"
done
exit 0
EOF
chmod +x "$WORK/bin/fake-sbt"

# run_case NAME EXPECTED_STATUS EXPECTED_TEXT [ENV=VALUE ...] -- [SCRIPT ARGS]: the output is kept in $WORK/out
run_case() {
  local name="$1" want_status="$2" want_text="$3"; shift 3
  local envs=()
  while [ $# -gt 0 ] && [ "$1" != "--" ]; do envs+=("$1"); shift; done
  [ $# -gt 0 ] && shift
  local tmp="$WORK/tmp-$RANDOM"; mkdir -p "$tmp" "$WORK/home"
  : > "$WORK/calls"
  local status=0
  if [ ${#envs[@]} -gt 0 ]; then
    env "${envs[@]}" SBT="$WORK/bin/fake-sbt" FAKE_CALLS="$WORK/calls" TMPDIR="$tmp" HOME="$WORK/home" "$SCRIPT" "$@" "${CASE_ROOT:-$FIXTURE}" > "$WORK/out" 2>&1 || status=$?
  else
    env SBT="$WORK/bin/fake-sbt" FAKE_CALLS="$WORK/calls" TMPDIR="$tmp" HOME="$WORK/home" "$SCRIPT" "$@" "${CASE_ROOT:-$FIXTURE}" > "$WORK/out" 2>&1 || status=$?
  fi
  LAST_TMP="$tmp"
  if [ "$status" != "$want_status" ]; then
    fail "$name: exit $status, wanted $want_status"; sed 's/^/     | /' "$WORK/out" | tail -n 12; return
  fi
  if ! grep -F -q -- "$want_text" "$WORK/out"; then
    fail "$name: the output does not say: $want_text"; sed 's/^/     | /' "$WORK/out" | tail -n 12; return
  fi
  pass "$name"
}

# ---- the good case, and what it must show
run_case "the dry run holds on a good build" 0 "the MiMa dry run held (2 frozen modules"
grep -F -q "llm4s-core" "$WORK/out" && grep -F -q "llm4s-agent" "$WORK/out" \
  && pass "it reports both frozen modules, read from build.sbt" || fail "it does not report both frozen modules"
grep -F -q "org.llm4s:llm4s-core_3:0.0.0-mima-dryrun" "$WORK/out" \
  && pass "it prints the baseline MiMa resolved, with the _3 suffix" || fail "it does not print the resolved baseline coordinates"
if grep -F -q "llm4s-samples" "$WORK/out"; then fail "it reports a module that is not frozen"; else pass "it does not report a module that is not frozen"; fi
[ -z "$(ls -A "$LAST_TMP")" ] && pass "its temporary directory is removed" || fail "its temporary directory was left behind: $(ls "$LAST_TMP")"
git -C "$FIXTURE" diff --quiet && [ -z "$(git -C "$FIXTURE" status --porcelain)" ] \
  && grep -q '^val mimaBaselineVersion: Option\[String\] = None$' "$FIXTURE/build.sbt" \
  && pass "the repository it was run on is not touched" || fail "the repository it was run on was modified"
grep -q "^publish\$" "$WORK/calls" && ! grep -q "publishLocal" "$WORK/calls" \
  && pass "it publishes with publish, never publishLocal (which writes to ~/.ivy2)" || fail "it did not use plain publish"

# ---- what must fail
run_case "an empty baseline fails instead of passing vacuously" 1 "an empty baseline would pass and check nothing" FAKE_EMPTY_BASELINE=1
run_case "an artifact that was not published fails, naming it" 1 "llm4s-agent was not published" FAKE_SKIP_PUBLISH=llm4s-agent
run_case "MiMa comparing nothing fails, naming the module" 1 "llm4s-agent: MiMa compared nothing" FAKE_NO_MAP=agent
run_case "problems against the build's own copy fail, naming the module" 1 "llm4s-agent: MiMa reported problems against its own published copy" FAKE_PROBLEM=agent
run_case "a failing mimaReportBinaryIssues fails" 1 "mimaReportBinaryIssues failed" FAKE_REPORT_FAIL=1
run_case "a failing publish fails and shows sbt's output" 1 "simulated publish failure" FAKE_PUBLISH_FAIL=1
run_case "a negative control that cannot fail is reported" 1 "the check cannot fail" FAKE_NEG=none
run_case "an @Experimental type that is not excluded is reported" 1 "@Experimental is not excluded" FAKE_NEG=both
run_case "the negative control shows MiMa failing on the @Stable class" 0 "the removed @Stable class is reported"
grep -F -q "@Experimental is outside the freeze" "$WORK/out" \
  && pass "and shows the @Experimental class is not reported" || fail "it does not say @Experimental is outside the freeze"

# ---- options
run_case "--no-negative-control skips step 3" 0 "the MiMa dry run held" -- --no-negative-control
if grep -q "core/publish" "$WORK/calls"; then fail "--no-negative-control still ran the negative control"; else pass "--no-negative-control never publishes the probe baseline"; fi
run_case "an unknown option is refused" 2 "unknown option: --bogus" -- --bogus
run_case "--help prints the usage" 0 "Usage: scripts/mima-dry-run.sh" -- --help
run_case "--keep keeps the scratch directory" 0 "kept: " -- --keep
[ -n "$(ls -A "$LAST_TMP")" ] && pass "and the directory is there" || fail "--keep left nothing"
run_case "--list-frozen prints the modules and stops" 0 "agent llm4s-agent modules/agent" -- --list-frozen
if [ -s "$WORK/calls" ]; then fail "--list-frozen ran sbt"; else pass "--list-frozen does not run sbt"; fi

# ---- failures clean up too
run_case "a failure still removes the temporary directory" 1 "FAILED" FAKE_REPORT_FAIL=1
[ -z "$(ls -A "$LAST_TMP")" ] && pass "after a failure the temporary directory is removed" || fail "a failure left the temporary directory"

# ---- artifacts must not leak into the real repositories
mkdir -p "$WORK/home/.m2/repository/org/llm4s/llm4s-core_3/0.0.0-mima-dryrun"
run_case "artifacts left in ~/.m2 are reported" 1 "the dry run left artifacts in:" FAKE_NEG=stable
rm -rf "$WORK/home/.m2"

# ---- per-module baselines (#2355): fixture variants with an override for llm4s-agent
# variant NAME OVERRIDES: a copy of the fixture whose mimaBaselineOverrides is OVERRIDES (the text after the `=`)
variant() {
  local dir="$WORK/variant-$1"
  cp -R "$FIXTURE" "$dir"
  OVERRIDES="$2" awk '/^val mimaBaselineOverrides/ { print "val mimaBaselineOverrides: Map[String, Option[String]] = " ENVIRON["OVERRIDES"]; next } { print }' \
    "$FIXTURE/build.sbt" > "$dir/build.sbt"
  git -C "$dir" -c user.name=test -c user.email=test@example.com commit -q -am "$1"
  echo "$dir"
}
OWN_ROOT="$(variant own 'Map(
  "llm4s-agent" -> Some("0.9.0") // a later baseline of its own
)')"
NONE_ROOT="$(variant none 'Map("llm4s-agent" -> None)')"

CASE_ROOT="$OWN_ROOT" run_case "a module with a baseline of its own is compared with it" 0 "the MiMa dry run held (2 frozen modules"
grep -F -q "org.llm4s:llm4s-agent_3:0.0.0-mima-dryrun-alt" "$WORK/out" && grep -F -q "org.llm4s:llm4s-core_3:0.0.0-mima-dryrun " "$WORK/out" \
  && pass "and each module with its own baseline: llm4s-agent at the second version, llm4s-core at the shared one" \
  || fail "the modules are not compared with their own baselines"
[ "$(grep -c '^publish$' "$WORK/calls")" = "2" ] \
  && pass "and the build is published at both versions" || fail "the build was not published twice: $(grep -c '^publish$' "$WORK/calls")"
git -C "$OWN_ROOT" diff --quiet && grep -q '"llm4s-agent" -> Some("0.9.0")' "$OWN_ROOT/build.sbt" \
  && pass "and the repository's own override is not touched" || fail "the repository's override was modified"
CASE_ROOT="$OWN_ROOT" run_case "--list-frozen shows each module's baseline" 0 "agent llm4s-agent modules/agent 0.9.0" -- --list-frozen
grep -F -q "core llm4s-core modules/core shared" "$WORK/out" \
  && pass "and a module without an override shares mimaBaselineVersion" || fail "--list-frozen does not say llm4s-core is shared"
CASE_ROOT="$OWN_ROOT" run_case "a build that ignores a module's own baseline fails, naming it" 1 \
  "llm4s-agent: mimaPreviousArtifacts does not name org.llm4s:llm4s-agent:0.0.0-mima-dryrun-alt" FAKE_IGNORE_OVERRIDES=1

CASE_ROOT="$NONE_ROOT" run_case "a module held back with None has no baseline while the others do" 0 \
  "the MiMa dry run held (2 frozen modules, 1 with no baseline"
grep -F -q "none (mimaBaselineOverrides: None)" "$WORK/out" && grep -F -q "org.llm4s:llm4s-core_3:0.0.0-mima-dryrun" "$WORK/out" \
  && pass "and llm4s-agent is reported with no baseline, llm4s-core with the shared one" || fail "the held-back module is not reported"
[ "$(grep -c '^publish$' "$WORK/calls")" = "1" ] \
  && pass "and nothing is published at a second version" || fail "a second publish ran with no module baseline of its own"
CASE_ROOT="$NONE_ROOT" run_case "a build that ignores None and compares the module anyway fails" 1 \
  "llm4s-agent: mimaBaselineOverrides holds it back with None, but mimaPreviousArtifacts names a baseline" FAKE_IGNORE_OVERRIDES=1
CASE_ROOT="$(variant core-none 'Map("llm4s-core" -> None)')" run_case "the negative control refuses a core with no baseline" 3 \
  "llm4s-core has no baseline"
CASE_ROOT="$WORK/variant-core-none" run_case "and --no-negative-control runs without it" 0 "1 with no baseline" -- --no-negative-control

CASE_ROOT="$(variant stranger 'Map("llm4s-samples" -> Some("0.9.0"))')" run_case "an override for a module that is not frozen is exit 3" 3 \
  "mimaBaselineOverrides names llm4s-samples, which is not a frozen module"
CASE_ROOT="$(variant twice 'Map("llm4s-agent" -> None, "llm4s-agent" -> Some("0.9.0"))')" run_case "an override listed twice is exit 3" 3 \
  "mimaBaselineOverrides lists llm4s-agent twice"
CASE_ROOT="$(variant unreadable 'Map("llm4s-agent" -> sys.props.get("agent.baseline"))')" run_case "an override the script cannot read is exit 3" 3 \
  "could not read mimaBaselineOverrides"
CASE_ROOT="$(variant open 'Map(')" run_case "an override map that is never closed is exit 3" 3 "the map is not closed"
NO_MAP="$WORK/variant-no-map"; cp -R "$FIXTURE" "$NO_MAP"
grep -v '^val mimaBaselineOverrides' "$FIXTURE/build.sbt" > "$NO_MAP/build.sbt"
git -C "$NO_MAP" -c user.name=test -c user.email=test@example.com commit -q -am "no overrides"
CASE_ROOT="$NO_MAP" run_case "a build.sbt without mimaBaselineOverrides is exit 3, naming it" 3 "build.sbt has no 'val mimaBaselineOverrides"

# ---- the script no longer matching the build is an error of its own (exit 3), not a pass
BAD="$WORK/bad"; cp -R "$FIXTURE" "$BAD"; mkdir -p "$WORK/tmp-bad"
sed 's/^val mimaBaselineVersion.*$//' "$FIXTURE/build.sbt" > "$BAD/build.sbt"
git -C "$BAD" -c user.name=test -c user.email=test@example.com commit -q -am "no baseline line"
status=0; env SBT="$WORK/bin/fake-sbt" FAKE_CALLS="$WORK/calls" TMPDIR="$WORK/tmp-bad" "$SCRIPT" "$BAD" > "$WORK/out" 2>&1 || status=$?
if [ "$status" = "3" ] && grep -F -q "build.sbt has no 'val mimaBaselineVersion" "$WORK/out"; then pass "a build.sbt without the baseline line is exit 3, naming the line"; else fail "missing baseline line: exit $status"; fi
sed 's/mimaFrozen("[^"]*")/noop/' "$FIXTURE/build.sbt" > "$BAD/build.sbt"
git -C "$BAD" -c user.name=test -c user.email=test@example.com commit -q -am "no mimaFrozen"
status=0; env SBT="$WORK/bin/fake-sbt" FAKE_CALLS="$WORK/calls" TMPDIR="$WORK/tmp-bad" "$SCRIPT" "$BAD" > "$WORK/out" 2>&1 || status=$?
if [ "$status" = "3" ] && grep -F -q "mimaFrozen" "$WORK/out"; then pass "a build.sbt without mimaFrozen call sites is exit 3"; else fail "no mimaFrozen: exit $status"; fi

# ---- the real build.sbt: the frozen set is read from it, not listed twice
REAL_COUNT="$(grep -c 'mimaFrozen("llm4s-' "$REPO_ROOT/build.sbt" || true)"
LISTED="$("$SCRIPT" --list-frozen "$REPO_ROOT" | wc -l | tr -d ' ')"
if [ "$REAL_COUNT" -gt 0 ] && [ "$LISTED" = "$REAL_COUNT" ]; then pass "the real build.sbt: $LISTED frozen modules, as many as mimaFrozen call sites"; else fail "the real build.sbt: script lists $LISTED, grep finds $REAL_COUNT"; fi
if "$SCRIPT" --list-frozen "$REPO_ROOT" | awk '{ if ($1 == "" || $2 !~ /^llm4s-/ || $3 !~ /^modules\//) bad = 1 } END { exit bad }'; then
  pass "every frozen module has a project id, an llm4s-* artifact and a modules/ directory"
else
  fail "a frozen module of the real build has no project id, artifact or directory"
fi

echo
echo "$PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]
