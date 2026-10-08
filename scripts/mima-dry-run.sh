#!/usr/bin/env bash
# Release readiness: a MiMa dry run (#1281). Proves, before 0.5.0 exists, that "set mimaBaselineVersion and let
# MiMa check the frozen modules" will work, by checking the build against a copy of itself.
#
# MiMa compares a module with the artifact of the same name in a previous release, so it can check nothing until
# 0.5.0 is published (`mimaBaselineVersion` is `None` in build.sbt). This script does the 0.5.0 steps early, against
# a throwaway version and a temporary repository, in a scratch clone of HEAD, and checks that they work:
#
#   1. Clone HEAD into a temp directory (the working tree you run this from is not touched; uncommitted changes are
#      not included), set `mimaBaselineVersion` to a throwaway version there, and `sbt publish` every module to a
#      temporary Maven repository inside that directory. Nothing goes to ~/.ivy2, ~/.m2 or Maven Central.
#   2. For each frozen module (the `mimaFrozen("llm4s-...")` call sites in build.sbt) check that `mimaPreviousArtifacts`
#      names the published artifact, that MiMa resolved it (`mimaFindBinaryIssues` lists `llm4s-<name>_3` with its
#      problem lists), and that it found no problems; then run `mimaReportBinaryIssues` for the whole build.
#      Printing what MiMa compared is the point: with a silent empty baseline MiMa passes and checks nothing.
#   3. Negative control (skip with --no-negative-control): publish a second baseline of the core module that holds
#      two extra classes, one @Stable and one @Experimental, remove them from the sources, and check that MiMa now
#      fails, naming the @Stable class and not the @Experimental one. This shows the check can fail, and that a type
#      the code marks @Experimental is outside the freeze without a hand-written ProblemFilters.exclude.
#
# It proves the wiring: coordinates, the Scala 3 `_3` suffix, resolution, and the exclusion. It does not prove that
# any API is compatible with anything: there is no 0.5.0 baseline yet. It is a manual script, not a CI job: it
# publishes the whole build, which is the heavy part.
#
# Usage: scripts/mima-dry-run.sh [--no-negative-control] [--keep] [--list-frozen] [REPO_ROOT]
#   --no-negative-control  skip step 3
#   --list-frozen          print the frozen modules (project id, artifact, directory) read from build.sbt, and stop
#   --keep                 keep the temp directory (it holds the clone, the repository and the sbt logs)
#   REPO_ROOT              the repository to check (default: the one this script is in)
# Environment: SBT  the sbt launcher to run (default: sbt). Run it on the JDK CI uses (21).
#
# Stopping it: use Ctrl-C, which signals the whole process group, sbt included. A plain `kill <pid of this script>`
# is deferred by bash until the running sbt command finishes (a couple of minutes), and only then removes the
# temp directory; it leaves sbt running meanwhile. Nothing outside the temp directory is touched either way.
#
# Exit status: 0 the dry run held; 1 a check failed; 2 bad usage; 3 the script no longer matches the build.
set -euo pipefail

DRY_VERSION="0.0.0-mima-dryrun"
PROBE_VERSION="0.0.0-mima-probe"
EXCLUDED_ANNOTATION="org.llm4s.annotation.Experimental"
SBT_CMD="${SBT:-sbt}"
NEGATIVE=1
KEEP=0
LIST_ONLY=0
ROOT_ARG=""

usage() {
  sed -n '/^# Usage:/,/^# Exit status/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
  case "$1" in
    --no-negative-control) NEGATIVE=0 ;;
    --keep) KEEP=1 ;;
    --list-frozen) LIST_ONLY=1 ;;
    -h | --help) usage; exit 0 ;;
    -*) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
    *) ROOT_ARG="$1" ;;
  esac
  shift
done

ROOT="$(cd "${ROOT_ARG:-$(dirname "${BASH_SOURCE[0]}")/..}" && pwd)"
START="$(date +%s)"
FAILURES=0

fail() { echo "FAIL: $*"; FAILURES=$((FAILURES + 1)); }
die() { echo "error: $*" >&2; exit "${2:-3}"; }

# ---- the frozen set, from build.sbt: "<project id> <artifact name> <base directory>" per line
frozen_set() {
  awk '
    /^lazy val [A-Za-z0-9_]+ *= *\(project/ {
      id = $3; dir = ""
      if (match($0, /file\("[^"]*"\)/)) dir = substr($0, RSTART + 6, RLENGTH - 8)
    }
    /mimaFrozen\("[^"]*"\)/ {
      match($0, /mimaFrozen\("[^"]*"\)/)
      print id, substr($0, RSTART + 12, RLENGTH - 14), dir
    }
  ' "$ROOT/build.sbt"
}

FROZEN="$(frozen_set)"
[ -n "$FROZEN" ] || die "no mimaFrozen(\"llm4s-...\") call sites in $ROOT/build.sbt: the build changed, update this script"
echo "$FROZEN" | while read -r id artifact dir; do
  [ -n "$id" ] && [ -n "$artifact" ] && [ -n "$dir" ] || { echo "error: could not read the project for mimaFrozen(\"$artifact\") in build.sbt" >&2; exit 3; }
done || exit 3
if [ "$LIST_ONLY" = "1" ]; then echo "$FROZEN"; exit 0; fi

# ---- scratch space, cleaned up on exit
WORK="$(mktemp -d "${TMPDIR:-/tmp}/mima-dry-run.XXXXXX")"
cleanup() {
  if [ "$KEEP" = "1" ]; then echo "kept: $WORK"; else rm -rf "$WORK"; fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM
TREE="$WORK/tree"
REPO="$WORK/repo"
mkdir -p "$REPO"

git -C "$ROOT" rev-parse --verify HEAD >/dev/null 2>&1 || die "$ROOT is not a git repository with a commit"
HEAD_SHA="$(git -C "$ROOT" rev-parse --short HEAD)"
echo "== MiMa dry run on $HEAD_SHA (committed HEAD; uncommitted changes are not included)"
git clone -q --no-hardlinks "$ROOT" "$TREE"

# ---- the two edits a release makes, done in the scratch clone
set_baseline() { # VERSION: the line a maintainer edits at the 0.5.0 cut
  grep -q '^val mimaBaselineVersion: Option\[String\] = ' "$TREE/build.sbt" \
    || die "build.sbt has no 'val mimaBaselineVersion: Option[String] = ...' line: the build changed, update this script"
  sed "s|^val mimaBaselineVersion: Option\[String\] = .*\$|val mimaBaselineVersion: Option[String] = Some(\"$1\")|" "$TREE/build.sbt" > "$TREE/build.sbt.new"
  mv "$TREE/build.sbt.new" "$TREE/build.sbt"
  grep -q "^val mimaBaselineVersion: Option\[String\] = Some(\"$1\")\$" "$TREE/build.sbt" || die "could not set mimaBaselineVersion in the scratch build.sbt"
}
write_dryrun_sbt() { # VERSION: publish to the temp repository, resolve from it
  cat > "$TREE/dryrun.sbt" <<EOF
ThisBuild / version   := "$1"
ThisBuild / publishTo := Some("dryrun".at("file://$REPO"))
ThisBuild / resolvers += "dryrun".at("file://$REPO")
EOF
}

# run_sbt LOG ARGS...: sbt in the scratch clone; the output goes to LOG; the status is returned
run_sbt() {
  local log="$WORK/$1.log"; shift
  local status=0
  (cd "$TREE" && "$SBT_CMD" -batch -Dsbt.color=false "$@") < /dev/null > "$log" 2>&1 || status=$?
  return "$status"
}
show_tail() { echo "---- last lines of $1.log"; tail -n 25 "$WORK/$1.log" | sed 's/^/     /'; }
jar_of() { echo "$REPO/org/llm4s/${1}_3/$2/${1}_3-$2.jar"; } # artifact version

# ---- 1. publish everything the release would, at the throwaway version
set_baseline "$DRY_VERSION"
write_dryrun_sbt "$DRY_VERSION"
echo "== 1. publishing the build at $DRY_VERSION to a temporary repository"
if ! run_sbt publish publish; then show_tail publish; die "sbt publish failed in the scratch clone" 1; fi
echo "$FROZEN" | while read -r id artifact dir; do
  [ -f "$(jar_of "$artifact" "$DRY_VERSION")" ] || { echo "FAIL: $artifact was not published ($(jar_of "$artifact" "$DRY_VERSION") is missing)"; exit 1; }
done || FAILURES=$((FAILURES + 1))

# ---- 2. MiMa against it, for every frozen module
echo "== 2. MiMa against the published copy"
SHOW_ARGS=()
while read -r id artifact dir; do
  SHOW_ARGS+=("show $id/mimaPreviousArtifacts" "show $id/mimaFindBinaryIssues")
done <<EOF
$FROZEN
EOF
if ! run_sbt mima-show "${SHOW_ARGS[@]}"; then show_tail mima-show; die "sbt could not show MiMa's baseline and problems" 1; fi
printf '%-26s %-52s %s\n' MODULE "BASELINE MiMa RESOLVED" PROBLEMS
while read -r id artifact dir; do
  coord="org.llm4s:${artifact}_3:$DRY_VERSION"
  if ! grep -F -q "Set(org.llm4s:$artifact:$DRY_VERSION)" "$WORK/mima-show.log"; then
    fail "$artifact: mimaPreviousArtifacts does not name org.llm4s:$artifact:$DRY_VERSION (an empty baseline would pass and check nothing)"
    continue
  fi
  if grep -F -q "Map($coord -> (List(),List()))" "$WORK/mima-show.log"; then
    printf '%-26s %-52s %s\n' "$artifact" "$coord" "0 backward, 0 forward"
  elif grep -F "Map($coord -> " "$WORK/mima-show.log" > "$WORK/problem-line.txt"; then
    fail "$artifact: MiMa reported problems against its own published copy: $(cut -c1-300 "$WORK/problem-line.txt")"
  else
    fail "$artifact: MiMa compared nothing: mimaFindBinaryIssues has no entry for $coord"
  fi
done <<EOF
$FROZEN
EOF
if run_sbt mima-report mimaReportBinaryIssues; then
  echo "mimaReportBinaryIssues (whole build): exit 0"
else
  show_tail mima-report; fail "mimaReportBinaryIssues failed against the build's own published copy"
fi

# ---- 3. negative control: the check can fail, and @Experimental is outside the freeze
if [ "$NEGATIVE" = "1" ]; then
  echo "== 3. negative control: a baseline with two extra classes that the code then lacks"
  NEG="$(echo "$FROZEN" | awk '$2 == "llm4s-core" {print $1, $2, $3}')"
  if [ -z "$NEG" ]; then
    die "llm4s-core is not a frozen module in build.sbt: the negative control targets it, update this script"
  fi
  read -r NEG_ID NEG_ARTIFACT NEG_DIR <<EOF
$NEG
EOF
  PROBE_DIR="$TREE/$NEG_DIR/src/main/scala/org/llm4s/mimadryrun"
  mkdir -p "$PROBE_DIR"
  cat > "$PROBE_DIR/Probes.scala" <<EOF
package org.llm4s.mimadryrun

@org.llm4s.annotation.Stable
final class MimaDryRunStableProbe { def value: Int = 1 }

@org.llm4s.annotation.Experimental
final class MimaDryRunExperimentalProbe { def value: Int = 2 }
EOF
  set_baseline "$PROBE_VERSION"
  write_dryrun_sbt "$PROBE_VERSION"
  if ! run_sbt neg-publish "$NEG_ID/publish"; then show_tail neg-publish; die "could not publish the probe baseline" 1; fi
  [ -f "$(jar_of "$NEG_ARTIFACT" "$PROBE_VERSION")" ] || fail "the probe baseline of $NEG_ARTIFACT was not published"
  rm -f "$PROBE_DIR/Probes.scala"; rmdir "$PROBE_DIR"
  NEG_STATUS=0
  run_sbt neg-mima "show $NEG_ID/mimaExcludeAnnotations" "show $NEG_ID/mimaFindBinaryIssues" "$NEG_ID/mimaReportBinaryIssues" || NEG_STATUS=$?
  if [ "$NEG_STATUS" = "0" ]; then
    fail "negative control: MiMa passed although a published class is gone: the check cannot fail"
  elif ! grep -q "MimaDryRunStableProbe" "$WORK/neg-mima.log"; then
    show_tail neg-mima; fail "negative control: MiMa failed, but not by naming the removed @Stable class"
  else
    echo "MiMa failed as it must: the removed @Stable class is reported"
    if grep -q "MimaDryRunExperimentalProbe" "$WORK/neg-mima.log"; then
      fail "@Experimental is not excluded: MiMa reports the removed @Experimental class, so every @Experimental change in a frozen module would need a hand-written ProblemFilters.exclude (build.sbt: mimaFrozen should add mimaExcludeAnnotations += \"$EXCLUDED_ANNOTATION\")"
    else
      echo "the removed @Experimental class is not reported: @Experimental is outside the freeze"
    fi
  fi
fi

# ---- nothing may have leaked into the developer's real repositories
LEAKED=""
for dir in "$HOME/.ivy2/local/org.llm4s" "$HOME/.m2/repository/org/llm4s"; do
  if [ -d "$dir" ]; then
    FOUND="$(find "$dir" -maxdepth 3 \( -name "$DRY_VERSION" -o -name "$PROBE_VERSION" \) 2>/dev/null || true)"
    [ -z "$FOUND" ] || LEAKED="$LEAKED $dir"
  fi
done
[ -z "$LEAKED" ] || fail "the dry run left artifacts in:$LEAKED"

ELAPSED=$(( $(date +%s) - START ))
if [ "$FAILURES" -eq 0 ]; then
  echo "== the MiMa dry run held ($(echo "$FROZEN" | wc -l | tr -d ' ') frozen modules, ${ELAPSED}s)"
  exit 0
fi
echo "== the MiMa dry run FAILED: $FAILURES problem(s) (${ELAPSED}s)"
exit 1
