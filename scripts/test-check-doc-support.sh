#!/usr/bin/env bash
# Tests for scripts/check-doc-support.sh: each kind of stale claim fails with a message naming it, and each
# true claim passes. No sbt is needed: every case runs the check against a small fixture repository - Markdown
# docs, a CI workflow, module directories - and a fixture build model shaped like the one
# `sbt "dumpBuildModel <file>"` writes, with one thing changed. The fixture's versions are its own (Scala
# 3.7.1, JDK 21 and 25), so a version bump of the real build does not touch this test.
#
# Usage: scripts/test-check-doc-support.sh [MODEL]
#   MODEL  a model of the real build; when given, the real repository is checked against it first.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="$REPO_ROOT/scripts/check-doc-support.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if [ $# -gt 0 ]; then
  echo "== the real repository"
  "$CHECK" --model "$1" "$REPO_ROOT" >/dev/null || { echo "FAIL: the check fails on the repository itself:"; "$CHECK" --model "$1" "$REPO_ROOT"; exit 1; }
  echo "ok   [repository passes against the real build model]"
fi

# ---- the fixture build model
cat > "$WORK/fixture.py" <<'PY'
import json, sys

COMPILE = {"id": "Compile", "name": "compile", "extends": []}
RUNTIME = {"id": "Runtime", "name": "runtime", "extends": ["compile"]}
TEST = {"id": "Test", "name": "test", "extends": ["runtime"]}
DOCKER = {"id": "Docker", "name": "docker", "extends": []}

ZERO_KEYS = ["clean", "publishLocal", "update", "version", "scalaVersion", "scalafmtAll", "coverageReport",
             "coverageAggregate", "aggregate", "coverageEnabled"]
CONFIG_KEYS = ["compile", "doc", "run", "runMain", "console", "scalacOptions"]
TEST_KEYS = ["test", "testOnly", "testOptions"]


def project(pid, base, aggregate=(), configs=(), extra=(), no_aggregate=()):
    keys = [["", "", k] for k in ZERO_KEYS]
    keys += [[c, "", k] for c in ("compile", "test") for k in CONFIG_KEYS]
    keys += [["test", "", k] for k in TEST_KEYS] + [list(k) for k in extra]
    return {"id": pid, "base": base, "aggregate": list(aggregate),
            "configurations": [COMPILE, RUNTIME, TEST] + list(configs), "keys": sorted(keys),
            "noAggregate": sorted(no_aggregate), "scalaVersion": "3.7.1", "crossScalaVersions": ["3.7.1"]}


ROOT_TASKS = ["publishedArtifactsCheck", "stabilityTierCheck"]
DOCKER_KEYS = [["docker", "", "publishLocal"], ["docker", "", "stage"]]


def base_model():
    return {
        "format": "1", "root": "llm4s",
        "projects": [
            project("llm4s", ".", aggregate=["core", "it", "deployService", "relocationCore"],
                    extra=[["", "", k] for k in ROOT_TASKS], no_aggregate=ROOT_TASKS + ["coverageAggregate"]),
            project("core", "modules/core"),
            project("it", "modules/it", extra=[["", "", "itTierCheck"]]),
            project("deployService", "modules/deploy-service", configs=[DOCKER], extra=DOCKER_KEYS),
            project("relocationCore", "modules/relocations/core"),
        ],
        "buildKeys": [["", "", "scalaVersion"], ["", "", "coverageEnabled"]],
        "globalKeys": [["", "", "onLoad"]],
        "commands": [";", "~", "about", "alias", "eval", "help", "inspect", "new", "project", "projects", "reload", "set"],
        "aliases": [
            {"name": "buildAll", "body": ";clean;compile;test"},
            {"name": "coverage", "body": ";set ThisBuild / coverageEnabled := true"},
            {"name": "cov", "body": ";clean;coverage;test;coverageAggregate;coverageReport"},
            {"name": "testIntegration", "body": ';set it / Test / testOptions += Tests.Argument("-n", "Docker"); it/test'},
        ],
    }


def proj(m, pid):
    return next(p for p in m["projects"] if p["id"] == pid)


if __name__ == "__main__":
    path = sys.argv[1]
    if len(sys.argv) > 2:
        m = json.load(open(path))
        exec(sys.argv[2])
    else:
        m = base_model()
    json.dump(m, open(path, "w"), indent=1)
PY

# ---- the fixture repository
TEMPLATE="$WORK/.template"
mkdir -p "$TEMPLATE/.github/workflows" "$TEMPLATE/docs/reference" "$TEMPLATE/docs/getting-started"
for m in core it deploy-service relocations/core; do mkdir -p "$TEMPLATE/modules/$m/src"; done
python3 "$WORK/fixture.py" "$TEMPLATE/build-model.json"
cat > "$TEMPLATE/CLAUDE.md" <<'MD'
# Fixture

Scala 3 only (3.7.1). Scala 2.13 support is deferred to post-1.0.

## Repository Structure

```
llm4s/
├── modules/
│   ├── core/                  # Core library
│   ├── it/                    # Integration tests
│   └── deploy-service/        # Deployment service
└── build.sbt
```

## Common Commands

```bash
sbt buildAll           # Clean, compile, test
sbt test it/itTierCheck
sbt testIntegration
sbt publishedArtifactsCheck stabilityTierCheck
sbt "core/testOnly org.llm4s.Foo" 2>&1 | tee log
$ sbt deployService/Docker/publishLocal
cd modules && sbt core/compile; sbtn cov
echo "a; sbt definitelyNotATask"
sbt -Dx=y \
  "core / Test / compile"
```
MD
cat > "$TEMPLATE/README.md" <<'MD'
# LLM4S

Built for Scala 3.7.1 on JDK 21+. Run `sbt compile`, then `sbt "project core" test`.
MD
cat > "$TEMPLATE/docs/reference/v1-scope.md" <<'MD'
1.0 targets **Scala 3 only (3.7.1)**. There is no Scala 2.13 artifact. JDK 21 and 25 are used in CI.
MD
cat > "$TEMPLATE/docs/getting-started/installation.md" <<'MD'
# Installation

You need JDK 21 or newer. The build was previously tested on JDK 17.
MD
cat > "$TEMPLATE/.github/workflows/ci.yml" <<'YML'
jobs:
  quick:
    steps:
      - uses: actions/setup-java@v6
        with:
          java-version: 21
  test:
    strategy:
      matrix:
        java: [21, 25]
    steps:
      - uses: actions/setup-java@v6
        with:
          java-version: ${{ matrix.java }}
YML
INSTALL="docs/getting-started/installation.md"

fresh_copy() { cp -R "$TEMPLATE" "$WORK/$1"; echo "$WORK/$1"; }
discard() { case "$1" in "$WORK"/?*) rm -rf -- "$1" ;; *) echo "SETUP: refusing to delete '$1'"; exit 2 ;; esac; }
# edit_model DIR PYTHON: change the copy's build model; `m` is the model, fixture.py's helpers are in scope.
edit_model() {
  (cd "$WORK" && python3 -c "import sys; sys.argv = ['fixture.py', sys.argv[1], sys.argv[2]]; exec(open('fixture.py').read())" "$1/build-model.json" "$2")
}
say() { printf '\n%s\n' "$3" >> "$1/$2"; }
run_sbt_doc() { printf '\n```bash\n%s\n```\n' "$2" >> "$1/CLAUDE.md"; }
run_check() { "$CHECK" --model "$1/build-model.json" "$1" 2>&1; }

expect_fail() {
  local name="$1" dir="$2" needle="$3" out status=0
  out="$(run_check "$dir")" || status=$?
  if [ "$status" -eq 0 ]; then echo "FAIL [$name]: the check passed on a repository with a stale claim"; exit 1; fi
  if ! grep -qF -- "$needle" <<<"$out"; then
    echo "FAIL [$name]: the check failed but did not mention '$needle'. Output:"; echo "$out"; exit 1
  fi
  discard "$dir"; echo "ok   [$name]"
}

expect_pass() {
  local name="$1" dir="$2" out status=0
  out="$(run_check "$dir")" || status=$?
  if [ "$status" -ne 0 ]; then echo "FAIL [$name]: the check rejected a true claim. Output:"; echo "$out"; exit 1; fi
  discard "$dir"; echo "ok   [$name]"
}

echo "== the fixture"
expect_pass "the fixture repository and model agree" "$(fresh_copy fixture)"
d="$(fresh_copy no-model)"; rm "$d/build-model.json"
expect_fail "a missing model is an error, not a pass" "$d" "No build model"

echo "== Scala"
d="$(fresh_copy scala-stale)"; say "$d" "$INSTALL" "LLM4S is built with Scala 3.7.2."
expect_fail "a stale Scala version" "$d" "documents Scala 3.7.2"

d="$(fresh_copy scala-213)"; say "$d" "$INSTALL" "LLM4S supports Scala 2.13 and 3."
expect_fail "a Scala 2.13 support claim" "$d" "documents Scala 2.13"

d="$(fresh_copy scala-denied)"; say "$d" "$INSTALL" "Scala 2.13 is not supported, and Scala 2 projects cannot depend on it."
expect_pass "a statement that another Scala version is not supported" "$d"

d="$(fresh_copy scala-build-moved)"; edit_model "$d" 'for p in m["projects"]: p["scalaVersion"] = "3.8.0"; p["crossScalaVersions"] = ["3.8.0"]'
expect_fail "the build moved on, the docs did not" "$d" "but the build is Scala 3.8.0"

d="$(fresh_copy scala-mixed)"; edit_model "$d" 'proj(m, "core")["scalaVersion"] = "3.3.5"'
expect_fail "projects with different Scala versions" "$d" "share one scalaVersion"

d="$(fresh_copy scala-pin)"; say "$d" "$INSTALL" "$(printf '```scala\nscalaVersion := "3.7.2"\n```')"
expect_fail "a scalaVersion pin in a snippet" "$d" "pins Scala 3.7.2"

d="$(fresh_copy scala-suffix)"; say "$d" "$INSTALL" 'Add `"org.llm4s" % "llm4s-core_2.13"` to your build.'
expect_fail "an artifact for another Scala binary version" "$d" "_2.13"

d="$(fresh_copy scala-cross)"; say "$d" "$INSTALL" 'Set `crossScalaVersions` to build for both.'
expect_fail "crossScalaVersions when nothing cross-builds" "$d" "cross-builds nothing"

d="$(fresh_copy scala-plus)"; run_sbt_doc "$d" "sbt +test"
expect_fail "sbt +test when nothing cross-builds" "$d" "cross-builds, but no project"

d="$(fresh_copy scala-plus-ok)"; run_sbt_doc "$d" "sbt +test"
edit_model "$d" 'proj(m, "core")["crossScalaVersions"] = ["3.7.1", "3.3.5"]'
expect_pass "sbt +test when a project cross-builds" "$d"

d="$(fresh_copy scala-switch)"; run_sbt_doc "$d" 'sbt ++2.13.16 test'
expect_fail "++ to a Scala version the build does not use" "$d" "switches to Scala 2.13.16"

d="$(fresh_copy scala-switch-cmd)"; run_sbt_doc "$d" 'sbt "++3.7.1 definitelyNotATask"'
expect_fail "the command after ++ is replayed" "$d" "\`definitelyNotATask\` is not an alias"

d="$(fresh_copy scala-canonical)"; printf 'Scala 3 only.\n' > "$d/docs/reference/v1-scope.md"
expect_fail "v1-scope.md must state the version" "$d" "does not state Scala 3.7.1"

d="$(fresh_copy scala-ignore)"; say "$d" "$INSTALL" "<!-- doc-support: ignore --> Scala 3.3.5 is the LTS line."
expect_pass "an opted-out line" "$d"

echo "== JDK"
d="$(fresh_copy jdk-floor)"; say "$d" "$INSTALL" "Requires JDK 17+."
expect_fail "a floor that is not the oldest JDK CI runs" "$d" "gives JDK 17 as the minimum"

d="$(fresh_copy jdk-requires)"; say "$d" "$INSTALL" "LLM4S requires Java 25."
expect_fail "'requires Java N' is a floor" "$d" "gives JDK 25 as the minimum"

d="$(fresh_copy jdk-unknown)"; say "$d" "$INSTALL" "We also test on OpenJDK 29."
expect_fail "a JDK CI does not run" "$d" "documents JDK 29, but CI runs JDK 21, 25"

d="$(fresh_copy jdk-ci-moved)"; sed -i.bak 's/java: \[21, 25\]/java: [17]/; s/java-version: 21/java-version: "17"/' "$d/.github/workflows/ci.yml"
expect_fail "CI moved to another JDK, the docs did not" "$d" "but CI runs JDK 17"

d="$(fresh_copy jdk-no-ci)"; printf 'jobs: {}\n' > "$d/.github/workflows/ci.yml"
expect_fail "no JDK in CI is an error" "$d" "cannot establish the JDK CI runs"

d="$(fresh_copy jdk-ok)"; say "$d" "$INSTALL" "Tested on JDK 25; JDK 17 is not supported."
expect_pass "a JDK CI runs, and a denied one" "$d"

echo "== modules"
d="$(fresh_copy module-missing)"; rm -r "$d/modules/deploy-service"; edit_model "$d" 'm["projects"] = [p for p in m["projects"] if p["id"] != "deployService"]'
expect_fail "a documented module with no directory" "$d" "names modules/deploy-service/, which is not a directory"

d="$(fresh_copy module-undocumented)"; mkdir -p "$d/modules/extra/src"
edit_model "$d" 'm["projects"].append(project("extra", "modules/extra"))'
expect_fail "a build module the docs never name" "$d" "project \`extra\` is modules/extra"

d="$(fresh_copy module-output-only)"; mkdir -p "$d/modules/docs/target"
edit_model "$d" 'm["projects"].append(project("docs", "modules/docs"))'
expect_pass "a project with only build output is not a module" "$d"

echo "== sbt commands"
d="$(fresh_copy sbt-task)"; run_sbt_doc "$d" "sbt core/definitelyNotATask"
expect_fail "an unknown task" "$d" "\`definitelyNotATask\` is not an alias"

d="$(fresh_copy sbt-project)"; run_sbt_doc "$d" "sbt nowhere/compile"
expect_fail "an unknown project" "$d" "names project \`nowhere\`"

d="$(fresh_copy sbt-config)"; run_sbt_doc "$d" "sbt core/Docker/publishLocal"
expect_fail "a configuration the project does not have" "$d" "configuration \`Docker\` is not defined in \`core\`"

d="$(fresh_copy sbt-no-aggregate)"; run_sbt_doc "$d" "sbt core/publishedArtifactsCheck"
expect_fail "a root-only task scoped to another project" "$d" "\`publishedArtifactsCheck\` is not defined in \`core\`"

d="$(fresh_copy sbt-project-switch)"; run_sbt_doc "$d" 'sbt "project core" stabilityTierCheck'
expect_fail "a project switch persists" "$d" "not defined in the current project \`core\`"

d="$(fresh_copy sbt-alias-removed)"; edit_model "$d" 'm["aliases"] = [a for a in m["aliases"] if a["name"] != "testIntegration"]'
expect_fail "a documented alias removed from the build" "$d" "\`testIntegration\` is not an alias"

d="$(fresh_copy sbt-alias-body)"; edit_model "$d" 'm["aliases"].append({"name": "broken", "body": ";clean;nope"})'
expect_fail "an alias whose body names a missing task" "$d" "alias \`broken\`"

d="$(fresh_copy sbt-alias-cycle)"; edit_model "$d" 'm["aliases"].append({"name": "loop", "body": ";compile;loop"})'
expect_fail "an alias that runs itself" "$d" "runs itself"

d="$(fresh_copy sbt-yaml)"; say "$d" "$INSTALL" "$(printf '```yaml\n      - run: sbt "core/nope"\n```')"
expect_fail "a YAML run: value" "$d" "\`nope\` is not an alias"

d="$(fresh_copy sbt-after-and)"; say "$d" "$INSTALL" 'Run `cd x && ./sbt nope` to start.'
expect_fail "sbt after && in inline code" "$d" "\`nope\` is not an alias"

d="$(fresh_copy sbt-prompt)"; run_sbt_doc "$d" '$ sbt nope'
expect_fail "sbt after a \$ prompt" "$d" "\`nope\` is not an alias"

d="$(fresh_copy sbt-continued)"; run_sbt_doc "$d" "$(printf 'sbt -Dk=v \\\n  nope')"
expect_fail "a backslash-continued sbt line" "$d" "\`nope\` is not an alias"

d="$(fresh_copy sbt-quotes)"; run_sbt_doc "$d" "sbt 'core/compile"
expect_fail "unbalanced quotes" "$d" "cannot be parsed by the shell"

d="$(fresh_copy sbt-semicolon)"; run_sbt_doc "$d" 'sbt "clean; nope"'
expect_fail "sbt's own ; splits commands" "$d" "\`nope\` is not an alias"

d="$(fresh_copy sbt-ok)"
run_sbt_doc "$d" "$(printf '%s\n' 'sbt "set core / Test / fork := true" "inspect tree compile" "help test"' \
  'sbt ~test "show core/version" "new llm4s/llm4s.g8"' 'sbt "runMain org.llm4s.Main --flag" # comment; sbt nope' \
  'which sbt; addSbtPlugin("x" % "sbt-y" % "1")' 'sbt core/test:compile "it / Test / testOptions"' \
  'sbt "project /" publishedArtifactsCheck ThisBuild/scalaVersion')"
expect_pass "set/inspect arguments, ~, show, new, old syntax, comments and non-invocations" "$d"

d="$(fresh_copy sbt-ignore)"; run_sbt_doc "$d" "sbt nope # doc-support: ignore"
expect_pass "an opted-out command" "$d"

echo "All doc-support cases behaved."
