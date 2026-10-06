#!/usr/bin/env bash
# Tests for scripts/check-doc-support.sh: each kind of stale claim fails with a message naming it, and
# each true claim passes. No sbt is needed: every case runs the check against a small fixture repository
# - Markdown docs, a CI workflow, module directories - and a fixture build model shaped like the one
# `sbt "dumpBuildModel <file>"` writes, with one thing changed. The fixture's versions are its own (Scala
# 3.7.1, JDK 21 and 25), so a version bump of the real build does not touch this test.
#
# Usage: scripts/test-check-doc-support.sh [MODEL]
#   MODEL  a model of the real build (`sbt "dumpBuildModel target/build-model.json"`); when given, the real
#          repository is checked against it first.
#
# Exit code 0 = every case behaved. 1 = a case did not.
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

# ---- the fixture build model: projects, keys, configurations, commands and aliases, as sbt reports them
cat > "$WORK/fixture.py" <<'PY'
import json, sys

COMPILE = {"id": "Compile", "name": "compile", "extends": []}
RUNTIME = {"id": "Runtime", "name": "runtime", "extends": ["compile"]}
TEST = {"id": "Test", "name": "test", "extends": ["runtime"]}
DOCKER = {"id": "Docker", "name": "docker", "extends": []}
UNIVERSAL = {"id": "Universal", "name": "universal", "extends": []}
JMH = {"id": "Jmh", "name": "jmh", "extends": ["test"]}

# What sbt and the build's plugins define in every project (a small, representative part of it).
ZERO_KEYS = ["clean", "publish", "publishLocal", "publishM2", "publishSigned", "update", "version", "name",
             "scalaVersion", "scalafmtAll", "scalafmtCheckAll", "scalafmtSbtCheck", "scalafixAll", "coverageReport",
             "coverageAggregate", "dependencyUpdates", "mimaReportBinaryIssues", "aggregate"]
CONFIG_KEYS = ["compile", "doc", "run", "runMain", "console", "package", "scalafix", "scalafmt", "scalafmtCheck",
               "scalacOptions", "javacOptions", "sources"]
TEST_KEYS = ["test", "testOnly", "testQuick", "testOptions"]


def project(pid, base, aggregate=(), configs=(), extra=(), no_aggregate=()):
    keys = [["", "", k] for k in ZERO_KEYS]
    for c in ("compile", "test"):
        keys += [[c, "", k] for k in CONFIG_KEYS]
        keys += [[c, "doc", "scalacOptions"], [c, "compile", "scalacOptions"]]
    keys += [["test", "", k] for k in TEST_KEYS]
    keys += [list(k) for k in extra]
    return {
        "id": pid, "base": base, "aggregate": list(aggregate), "plugins": [],
        "configurations": [COMPILE, RUNTIME, TEST] + list(configs),
        "keys": sorted(keys), "noAggregate": sorted(no_aggregate),
        "scalaVersion": "3.7.1", "crossScalaVersions": ["3.7.1"],
        "scalacOptions": {"compile": ["-feature", "-source:3.3"], "test": ["-feature", "-source:3.3"]},
        "javacOptions": {"compile": [], "test": []},
    }


ROOT_TASKS = ["publishedArtifactsCheck", "stabilityTierCheck", "coveragePolicyCheck", "frozenDependencyCheck"]
DOCKER_KEYS = [["docker", "", "publishLocal"], ["docker", "", "stage"], ["", "", "stage"], ["", "", "daemonUser"],
               ["universal", "", "packageBin"]]


def base_model():
    return {
        "format": "1",
        "root": "llm4s",
        "projects": [
            project("llm4s", ".", aggregate=["core", "it", "workspaceRunner", "deployService", "benchmarks",
                                             "relocationCore"],
                    extra=[["", "", k] for k in ROOT_TASKS],
                    no_aggregate=ROOT_TASKS + ["coverageAggregate"]),
            project("core", "modules/core"),
            project("it", "modules/it", extra=[["", "", "itTierCheck"]]),
            project("workspaceRunner", "modules/workspace/workspaceRunner", configs=[DOCKER, UNIVERSAL], extra=DOCKER_KEYS),
            project("deployService", "modules/deploy-service", configs=[DOCKER, UNIVERSAL], extra=DOCKER_KEYS),
            project("benchmarks", "modules/benchmarks", configs=[JMH], extra=[["jmh", "", "run"], ["jmh", "", "compile"]]),
            project("relocationCore", "modules/relocations/core"),
        ],
        "buildKeys": [["", "", "scalaVersion"], ["", "", "organization"], ["", "", "dockerBaseImage"]],
        "globalKeys": [["", "", "onLoad"], ["", "", "concurrentRestrictions"]],
        "commands": [";", "~", "about", "alias", "ci-release", "eval", "exit", "help", "inspect", "last", "new",
                     "plugins", "project", "projects", "reload", "session", "set", "settings", "shell", "tasks"],
        "aliases": [
            {"name": "buildAll", "body": ";clean;compile;test"},
            {"name": "testAll", "body": ";test"},
            {"name": "coverage", "body": ";set ThisBuild / coverageEnabled := true"},
            {"name": "coverageOff", "body": ";set ThisBuild / coverageEnabled := false"},
            {"name": "cov", "body": ";clean;coverage;test;coverageAggregate;coverageReport;coverageOff"},
            {"name": "testIntegration",
             "body": ';set it / Test / test / testOptions := Seq(Tests.Argument(TestFrameworks.ScalaTest, "-n", '
                     '"org.llm4s.it.tags.Docker")); it/test'},
        ],
    }


def proj(m, pid):
    return next(p for p in m["projects"] if p["id"] == pid)


def drop_key(m, key):
    for p in m["projects"]:
        p["keys"] = [k for k in p["keys"] if k[2] != key]


def drop_config(m, name):
    for p in m["projects"]:
        p["configurations"] = [c for c in p["configurations"] if c["name"] != name]
        p["keys"] = [k for k in p["keys"] if k[0] != name]


def set_options(m, kind, opts):
    for p in m["projects"]:
        p[kind]["compile"] = list(opts)


if __name__ == "__main__":
    path = sys.argv[1]
    if len(sys.argv) > 2:
        m = json.load(open(path))
        exec(sys.argv[2])
    else:
        m = base_model()
    json.dump(m, open(path, "w"), indent=1)
PY

# ---- the fixture repository: the documents the check reads, CI, and module directories
TEMPLATE="$WORK/.template"
mkdir -p "$TEMPLATE/.github/workflows" "$TEMPLATE/docs/reference" "$TEMPLATE/docs/getting-started"
for m in core it workspace/workspaceRunner deploy-service benchmarks relocations/core; do
  mkdir -p "$TEMPLATE/modules/$m/src"
done
python3 "$WORK/fixture.py" "$TEMPLATE/build-model.json"
cat > "$TEMPLATE/CLAUDE.md" <<'MD'
# Fixture

Scala 3 only (3.7.1).

## Repository Structure

```
llm4s/
├── modules/
│   ├── core/                  # Core library
│   ├── it/                    # Integration tests
│   ├── workspace/             # Containerized execution
│   ├── deploy-service/        # Deployment service
│   └── benchmarks/            # JMH benchmarks
├── docs/
└── build.sbt
```

## Common Commands

```bash
sbt buildAll           # Clean, compile, test
sbt test
sbt testIntegration
sbt it/itTierCheck
sbt publishedArtifactsCheck stabilityTierCheck
sbt "core/testOnly org.llm4s.Foo"
```
MD
cat > "$TEMPLATE/README.md" <<'MD'
# LLM4S

Built for Scala 3.7.1 on JDK 21+. Run `sbt compile` and then `sbt "benchmarks/Jmh/run -rf json"`.
MD
cat > "$TEMPLATE/docs/reference/v1-scope.md" <<'MD'
## Scala and JDK support

1.0 targets **Scala 3 only (3.7.1)**. Scala 2.13 support is deferred to post-1.0. JDK 21 and 25 are used in CI.
MD
cat > "$TEMPLATE/docs/getting-started/installation.md" <<'MD'
# Installation

You need JDK 21 or newer and Scala 3.7.1. Build the image with `sbt workspaceRunner/docker:publishLocal`.
MD
cat > "$TEMPLATE/.github/workflows/ci.yml" <<'YML'
jobs:
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

fresh_copy() {
  local dir="$WORK/$1"
  cp -R "$TEMPLATE" "$dir"
  echo "$dir"
}

discard() {
  case "$1" in
    "$WORK"/?*) rm -rf -- "$1" ;;
    *) echo "SETUP: refusing to delete '$1', which is not under $WORK"; exit 2 ;;
  esac
}

# edit_model DIR PYTHON: change the copy's build model; `m` is the model, fixture.py's helpers are in scope.
edit_model() {
  (cd "$WORK" && python3 -c "import sys; sys.argv = ['fixture.py', sys.argv[1], sys.argv[2]]; exec(open('fixture.py').read())" "$1/build-model.json" "$2")
}

# say DIR FILE TEXT: append a paragraph to a document of the copy.
say() { printf '\n%s\n' "$3" >> "$1/$2"; }

# run_sbt_doc DIR COMMAND: quote `COMMAND` as a shell line in a code block of CLAUDE.md.
run_sbt_doc() { printf '\n```bash\n%s\n```\n' "$2" >> "$1/CLAUDE.md"; }

run_check() { "$CHECK" --model "$1/build-model.json" "$1" 2>&1; }

expect_fail() {
  local name="$1" dir="$2" needle="$3" out status=0
  out="$(run_check "$dir")" || status=$?
  if [ "$status" -eq 0 ]; then
    echo "FAIL [$name]: the check passed on a repository with a stale claim"; exit 1
  fi
  if ! grep -qF -- "$needle" <<<"$out"; then
    echo "FAIL [$name]: the check failed but did not mention '$needle'. Output:"; echo "$out"; exit 1
  fi
  discard "$dir"
  echo "ok   [$name]"
}

expect_pass() {
  local name="$1" dir="$2" out status=0
  out="$(run_check "$dir")" || status=$?
  if [ "$status" -ne 0 ]; then
    echo "FAIL [$name]: the check rejected a true claim. Output:"; echo "$out"; exit 1
  fi
  discard "$dir"
  echo "ok   [$name]"
}

echo "== the fixture"
expect_pass "the fixture repository and model agree" "$(fresh_copy fixture)"

d="$(fresh_copy no-model)"
rm "$d/build-model.json"
expect_fail "a missing model is an error, not a pass" "$d" "No build model"

echo "== Scala version"
d="$(fresh_copy scala)"; say "$d" "$INSTALL" "LLM4S is built with Scala 3.7.2."
expect_fail "stale Scala version in a getting-started page" "$d" "documents Scala 3.7.2"

d="$(fresh_copy scala-build)"; edit_model "$d" 'for p in m["projects"]: p["scalaVersion"] = "3.8.0"; p["crossScalaVersions"] = ["3.8.0"]'
expect_fail "build moved on, docs did not" "$d" "but the build is Scala 3.8.0"

d="$(fresh_copy scala-bump)"; edit_model "$d" 'for p in m["projects"]: p["scalaVersion"] = "3.8.0"; p["crossScalaVersions"] = ["3.8.0"]'
for f in CLAUDE.md README.md docs/reference/v1-scope.md "$INSTALL"; do sed -i.bak 's/3\.7\.1/3.8.0/g' "$d/$f" && rm "$d/$f.bak"; done
expect_pass "docs and build bumped together" "$d"

d="$(fresh_copy scala-projects-differ)"; edit_model "$d" 'proj(m, "core")["scalaVersion"] = "3.3.5"'
expect_fail "projects that build with different Scala versions" "$d" "projects build with different Scala versions"

d="$(fresh_copy scala-short)"; say "$d" "$INSTALL" "LLM4S targets Scala 3.8."
expect_fail "a major.minor claim that is not the build's" "$d" "documents Scala 3.8"

d="$(fresh_copy scala-short-ok)"; say "$d" "$INSTALL" "LLM4S targets Scala 3.7."
expect_pass "a major.minor claim that is the build's" "$d"

d="$(fresh_copy scala-history)"; say "$d" "$INSTALL" "Releases before 0.5 were built with Scala 3.3.5, which is no longer supported."
expect_pass "an old Scala version named as history" "$d"

d="$(fresh_copy scala-ignore)"; say "$d" "$INSTALL" "<!-- doc-support: ignore --> Scala 3.3.5 is the LTS line."
expect_pass "an opted-out line" "$d"

d="$(fresh_copy scala-other-major)"; say "$d" "$INSTALL" "LLM4S supports Scala 2.13."
expect_fail "a claim of another Scala major version" "$d" "documents Scala 2.13"

d="$(fresh_copy scala-other-major-bare)"; say "$d" "$INSTALL" "Works with Scala 2 projects with no extra setup."
expect_fail "a bare other major version, a negation elsewhere in the clause" "$d" "documents Scala 2"

d="$(fresh_copy scala-other-major-but)"; say "$d" "$INSTALL" "LLM4S supports Scala 2.13 but not Scala 2.12."
expect_fail "a support claim next to a denial in another clause" "$d" "documents Scala 2.13"

d="$(fresh_copy scala-list)"; say "$d" "$INSTALL" "Built with Scala 3.7.1 and 2.13."
expect_fail "every version of a list is a claim" "$d" "documents Scala 2.13"

d="$(fresh_copy scala-deferred)"; say "$d" "$INSTALL" "Scala 2.13 support is deferred to post-1.0. There is no Scala 2.13 artifact, Scala 2 is not supported, and you should not expect Scala 2.12."
expect_pass "statements that another Scala version is not supported" "$d"

d="$(fresh_copy scala-suffix)"; say "$d" "$INSTALL" 'Add `"org.llm4s" % "llm4s-core_2.13"` to your build.'
expect_fail "an artifact suffix for another Scala version" "$d" "_2.13"

d="$(fresh_copy scala-version-setting)"; say "$d" "$INSTALL" "$(printf '```scala\nscalaVersion := "3.7.2"\n```')"
expect_fail "a scalaVersion setting in a snippet" "$d" "pins Scala 3.7.2"

d="$(fresh_copy scala-cross)"; run_sbt_doc "$d" "sbt +test"
expect_fail "a cross-build command when the build cross-builds nothing" "$d" "cross-builds"

d="$(fresh_copy scala-cross-ok)"; run_sbt_doc "$d" "sbt +test"
edit_model "$d" 'proj(m, "core")["crossScalaVersions"] = ["3.7.1", "3.3.5"]'
expect_pass "a cross-build command when a project sets crossScalaVersions" "$d"

d="$(fresh_copy scala-switch)"; run_sbt_doc "$d" "sbt ++2.13.16 test"
expect_fail "switching to a Scala version the build does not use" "$d" "switches to Scala 2.13.16"

echo "== JDK version"
d="$(fresh_copy jdk)"; say "$d" "$INSTALL" "JDK 29 is used in CI."
expect_fail "docs claim a JDK CI does not run" "$d" "documents JDK 29"

d="$(fresh_copy jdk-ci)"; sed -i.bak 's/java: \[21, 25\]/java: [17]/' "$d/.github/workflows/ci.yml"
expect_fail "CI moved to another JDK, docs did not" "$d" "but CI runs JDK 17"

d="$(fresh_copy jdk-java-word)"; say "$d" "$INSTALL" "You need Java 29 to build."
expect_fail "'Java N' is read like 'JDK N'" "$d" "JDK 29"

d="$(fresh_copy jdk-openjdk-word)"; say "$d" "$INSTALL" "We test on OpenJDK 29."
expect_fail "'OpenJDK N' is read like 'JDK N'" "$d" "JDK 29"

d="$(fresh_copy jdk-floor-ok)"; say "$d" "$INSTALL" "Any JDK 21+ can run the compiled jars; you need JDK 21 or newer, at least Java 21."
expect_pass "a floor that is the oldest JDK CI runs" "$d"

d="$(fresh_copy jdk-floor-too-low)"; say "$d" "$INSTALL" "LLM4S runs on JDK 8 or newer."
expect_fail "a floor below every JDK CI runs" "$d" "gives JDK 8 as the minimum"

d="$(fresh_copy jdk-floor-plus-too-low)"; say "$d" "$INSTALL" "Requires Java 17+."
expect_fail "a 'Java N+' floor below the oldest JDK CI runs" "$d" "gives JDK 17 as the minimum"

d="$(fresh_copy jdk-floor-at-least)"; say "$d" "$INSTALL" "You need at least Java 1.8 to run it."
expect_fail "'at least Java 1.8' is a floor of JDK 8" "$d" "gives JDK 8 as the minimum"

d="$(fresh_copy jdk-range)"; say "$d" "$INSTALL" "Tested on JDK 17-25."
expect_fail "a range starting below the oldest JDK CI runs" "$d" "gives JDK 17 as the minimum"

d="$(fresh_copy jdk-through)"; say "$d" "$INSTALL" "Supports Java versions 11 through 25; minimum JDK: 17."
expect_fail "'Java versions N through M' and 'minimum JDK: N'" "$d" "gives JDK 17 as the minimum"

d="$(fresh_copy jdk-list)"; say "$d" "$INSTALL" "Tested on JDK 25 and 29."
expect_fail "every JDK of a list must be one CI runs" "$d" "documents JDK 29"

d="$(fresh_copy jdk-ceiling)"; say "$d" "$INSTALL" "Works on JDK 29 or earlier."
expect_fail "a ceiling claims every older JDK" "$d" "supports JDK 29 or older"

# Round 4: `older` is how a ceiling is phrased; it no longer marks the line as history.
d="$(fresh_copy jdk-ceiling-older)"; say "$d" "$INSTALL" "Requires JDK 25 or older."
expect_fail "'JDK N or older' is a ceiling, not history" "$d" "supports JDK 25 or older"

d="$(fresh_copy jdk-ceiling-on-history-line)"; say "$d" "$INSTALL" "Requires JDK 25 or older; it previously ran on JDK 17."
expect_fail "a ceiling is checked even on a line that also names history" "$d" "supports JDK 25 or older"

d="$(fresh_copy jdk-ceiling-floor)"; say "$d" "$INSTALL" "Compiled class files load on JDK 21 or later, never JDK 21 or older releases that predate it."
expect_pass "a ceiling at the floor itself" "$d"

d="$(fresh_copy jdk-floor-too-high)"; say "$d" "$INSTALL" "Requires JDK 29 or newer."
expect_fail "a floor above every JDK CI runs" "$d" "JDK 29"

d="$(fresh_copy jdk-history)"; say "$d" "$INSTALL" "The build previously ran on JDK 17, and 0.3 was tested on Java 11."
expect_pass "an old JDK named as history" "$d"

d="$(fresh_copy jdk-history-dated)"; say "$d" "$INSTALL" "- 2025-03-01: CI moved off JDK 17."
expect_pass "a dated entry is history" "$d"

echo "== JDK release target, from the options sbt resolved"
d="$(fresh_copy jdk-release-javac)"; edit_model "$d" 'set_options(m, "javacOptions", ["--release", "8"])'
expect_fail "javac --release N" "$d" "compiles for JDK 8"

d="$(fresh_copy jdk-release-javac-equals)"; edit_model "$d" 'set_options(m, "javacOptions", ["--release=8"])'
expect_fail "javac --release=N (round 4)" "$d" "compiles for JDK 8"

d="$(fresh_copy jdk-release-scalac-colon)"; edit_model "$d" 'set_options(m, "scalacOptions", ["-feature", "-release:17"])'
expect_fail "scalac -release:N" "$d" "compiles for JDK 17"

d="$(fresh_copy jdk-release-scalac-output)"; edit_model "$d" 'set_options(m, "scalacOptions", ["-java-output-version", "17"])'
expect_fail "scalac -java-output-version N" "$d" "compiles for JDK 17"

d="$(fresh_copy jdk-release-target-jvm)"; edit_model "$d" 'set_options(m, "scalacOptions", ["-target:jvm-1.8"])'
expect_fail "scalac -target:jvm-1.8" "$d" "compiles for JDK 8"

d="$(fresh_copy jdk-release-one-project)"; edit_model "$d" 'proj(m, "it")["javacOptions"]["test"] = ["--release", "11"]'
expect_fail "a release target in one project's Test options" "$d" "compiles for JDK 11 (it javacOptions/test)"

d="$(fresh_copy jdk-release-at-floor)"; edit_model "$d" 'set_options(m, "javacOptions", ["--release", "21"]); set_options(m, "scalacOptions", ["-release", "21"])'
expect_pass "a release target that is the oldest JDK CI runs" "$d"

echo "== modules"
d="$(fresh_copy module-missing)"; rm -r "$d/modules/core"
expect_fail "documented module has no directory" "$d" "names modules/core/"

d="$(fresh_copy module-invented)"
python3 - "$d/CLAUDE.md" <<'PY'
import sys
path = sys.argv[1]
text = open(path, encoding="utf-8").read()
line = "│   ├── core/                  # Core library"
open(path, "w", encoding="utf-8").write(text.replace(line, line + "\n│   ├── crossTest/             # Cross-version tests", 1))
PY
expect_fail "documented module that never existed" "$d" "modules/crossTest"

d="$(fresh_copy module-undocumented)"; mkdir -p "$d/modules/secret-module/src"
edit_model "$d" 'm["projects"].append(project("secretModule", "modules/secret-module"))'
expect_fail "build project the docs never name" "$d" "project \`secretModule\` is modules/secret-module"

d="$(fresh_copy module-build-output-only)"; mkdir -p "$d/modules/aggregate-only/target"
edit_model "$d" 'm["projects"].append(project("aggregateOnly", "modules/aggregate-only"))'
expect_pass "project with only build output is not a module" "$d"

echo "== sbt commands quoted in the docs"
d="$(fresh_copy cmd-bare)"; run_sbt_doc "$d" "sbt crossTestAll"
expect_fail "unknown sbt task" "$d" "\`crossTestAll\` is not an alias, a command, nor a task or setting the build defines"

d="$(fresh_copy cmd-project)"; say "$d" README.md 'Run `sbt "nonexistentProject/test"` to check it.'
expect_fail "unknown sbt project" "$d" "names project \`nonexistentProject\`"

d="$(fresh_copy cmd-scoped-task)"; say "$d" README.md 'Run `sbt "core/definitelyNotATask"` to check it.'
expect_fail "unknown task in a project the build defines" "$d" "definitelyNotATask"

d="$(fresh_copy cmd-scoped-config-task)"; run_sbt_doc "$d" "sbt core/Test/definitelyNotATask"
expect_fail "unknown task after a project and configuration" "$d" "definitelyNotATask"

d="$(fresh_copy cmd-unknown-config)"; run_sbt_doc "$d" "sbt core/Nonexistent/compile"
expect_fail "unknown configuration" "$d" "configuration \`Nonexistent\` is not defined in \`core\`"

d="$(fresh_copy cmd-scoped-ok)"; run_sbt_doc "$d" 'sbt core/Test/compile core/test:compile "core / Test / testOnly org.Foo" core/Compile/doc/scalacOptions doc/scalacOptions ThisBuild/scalaVersion show core/version "~compile" core/dockerBaseImage'
expect_pass "keys resolved with configuration and task axes, ThisBuild, show and ~" "$d"

d="$(fresh_copy cmd-config-delegation)"; run_sbt_doc "$d" "sbt core/Test/version benchmarks/Jmh/testOnly"
expect_pass "a key delegates to the configurations a configuration extends, then to none" "$d"

d="$(fresh_copy cmd-continued)"; run_sbt_doc "$d" "$(printf 'sbt -Dllm4s.x=y \\\n    -Dllm4s.z=w \\\n    "definitelyNotATask"')"
expect_fail "an unknown task on a continuation line" "$d" "definitelyNotATask"

d="$(fresh_copy cmd-continued-ok)"; run_sbt_doc "$d" "$(printf 'sbt -Dllm4s.x=y \\\n    "core/run"')"
expect_pass "a known task on a continuation line" "$d"

d="$(fresh_copy cmd-malformed-quotes)"; run_sbt_doc "$d" 'sbt "core/test'
expect_fail "a command the shell cannot parse" "$d" "cannot be parsed"

d="$(fresh_copy cmd-malformed-quotes-inline)"; say "$d" README.md 'Run `sbt "testOnly org.llm4s.Foo` to check it.'
expect_fail "an inline command the shell cannot parse" "$d" "cannot be parsed"

d="$(fresh_copy cmd-ignore)"; run_sbt_doc "$d" "sbt crossTestAll   # doc-support: ignore"
expect_pass "an opted-out sbt line" "$d"

d="$(fresh_copy cmd-after-and)"; run_sbt_doc "$d" "cd modules/core && sbt crossTestAll"
expect_fail "a command after && in a code block" "$d" "crossTestAll"

d="$(fresh_copy cmd-after-and-inline)"; say "$d" README.md 'Or `cd x && sbt otherCrossTest`.'
expect_fail "a command after && in inline code" "$d" "otherCrossTest"

d="$(fresh_copy cmd-thin-client)"; run_sbt_doc "$d" "$(printf './sbt crossTestAll\nsbtn otherCrossTest')"
expect_fail "the ./sbt launcher and sbtn are read like sbt" "$d" "otherCrossTest"

d="$(fresh_copy cmd-commands)"; run_sbt_doc "$d" 'sbt reload projects "set core / Test / fork := true" ci-release "inspect core/compile" new scala/scala3.g8'
expect_pass "sbt and plugin commands from the model" "$d"

echo "== sbt keys exist only where the build defines them"
d="$(fresh_copy cmd-root-task-in-module)"; run_sbt_doc "$d" "sbt core/publishedArtifactsCheck"
expect_fail "a root-only task scoped to another project" "$d" "\`publishedArtifactsCheck\` is not defined in \`core\`; it is defined in llm4s"

d="$(fresh_copy cmd-module-task-elsewhere)"; say "$d" README.md 'Run `sbt "core/itTierCheck"` to check it.'
expect_fail "a task one module defines, scoped to another" "$d" "\`itTierCheck\` is not defined in \`core\`; it is defined in it"

d="$(fresh_copy cmd-task-where-defined)"; run_sbt_doc "$d" "sbt publishedArtifactsCheck it/itTierCheck itTierCheck llm4s/stabilityTierCheck core/test"
expect_pass "tasks run where the build defines them, or in what the root aggregates" "$d"

d="$(fresh_copy cmd-unscoped-not-aggregated)"; run_sbt_doc "$d" "sbt lonerCheck"
edit_model "$d" 'm["projects"].append(project("loner", "modules/loner", extra=[["", "", "lonerCheck"]]))'
expect_fail "an unscoped task defined only in a project the root does not aggregate" "$d" "\`lonerCheck\` is not defined in the current project \`llm4s\` nor any project it aggregates; it is defined in loner"

d="$(fresh_copy cmd-unscoped-aggregated)"; run_sbt_doc "$d" "sbt itTierCheck"
expect_pass "an unscoped task the root reaches by aggregation" "$d"

d="$(fresh_copy cmd-aggregate-false)"; run_sbt_doc "$d" "sbt itTierCheck"
edit_model "$d" 'proj(m, "llm4s")["noAggregate"].append("itTierCheck")'
expect_fail "a key whose aggregate is false in the root does not reach its aggregates" "$d" "\`itTierCheck\` is not defined in the current project \`llm4s\`"

d="$(fresh_copy cmd-key-removed)"; run_sbt_doc "$d" "sbt it/itTierCheck"
edit_model "$d" 'drop_key(m, "itTierCheck")'
expect_fail "a task the build no longer defines (declared but never set)" "$d" "\`itTierCheck\` is not an alias"

echo "== project switches persist through the command sequence (round 4)"
d="$(fresh_copy cmd-switch-root-task)"; run_sbt_doc "$d" 'sbt "project core" publishedArtifactsCheck'
expect_fail "'project core' then a root-only task" "$d" "\`publishedArtifactsCheck\` is not defined in the current project \`core\`; it is defined in llm4s"

d="$(fresh_copy cmd-switch-semicolon)"; say "$d" README.md 'Run `sbt "project core; publishedArtifactsCheck"`.'
expect_fail "'project core; task' in one argument" "$d" "not defined in the current project \`core\`"

d="$(fresh_copy cmd-switch-ok)"; run_sbt_doc "$d" 'sbt "project it" itTierCheck test "project llm4s" publishedArtifactsCheck'
expect_pass "tasks after a switch to the project that defines them, and back" "$d"

d="$(fresh_copy cmd-switch-unknown)"; run_sbt_doc "$d" 'sbt "project nonexistentProject" test'
expect_fail "'project X' names a project the build does not define" "$d" "names project \`nonexistentProject\`"

echo "== plugin configurations exist only in projects that enable the plugin (round 4)"
d="$(fresh_copy cmd-docker-in-core)"; run_sbt_doc "$d" "sbt core/docker:publishLocal"
expect_fail "core/docker:publishLocal" "$d" "configuration \`docker\` is not defined in \`core\` (only in deployService, workspaceRunner)"

d="$(fresh_copy cmd-jmh-in-core)"; run_sbt_doc "$d" "sbt core/Jmh/run"
expect_fail "core/Jmh/run" "$d" "configuration \`Jmh\` is not defined in \`core\` (only in benchmarks)"

d="$(fresh_copy cmd-docker-after-switch)"; run_sbt_doc "$d" 'sbt "project core" Docker/publishLocal'
expect_fail "Docker/publishLocal after switching to core" "$d" "configuration \`Docker\` is not defined in \`core\`"

d="$(fresh_copy cmd-plugin-configs-ok)"; run_sbt_doc "$d" 'sbt workspaceRunner/docker:publishLocal deployService/Docker/publishLocal "benchmarks/Jmh/run -rf json" Docker/publishLocal'
expect_pass "plugin configurations where the plugin is enabled, and from the aggregating root" "$d"

d="$(fresh_copy plugin-removed)"; run_sbt_doc "$d" "sbt dependencyUpdates"
edit_model "$d" 'drop_key(m, "dependencyUpdates")'
expect_fail "a plugin task whose plugin was removed" "$d" "\`dependencyUpdates\` is not an alias"

d="$(fresh_copy plugin-config-removed)"
edit_model "$d" 'drop_config(m, "docker")'
expect_fail "a plugin configuration whose plugin was removed" "$d" "configuration \`docker\` is not defined in \`workspaceRunner\`, nor in any project the build defines"

d="$(fresh_copy plugin-never-loaded)"; run_sbt_doc "$d" "sbt assembly"
expect_fail "a plugin task the build never loaded" "$d" "\`assembly\` is not an alias"

d="$(fresh_copy plugin-command-removed)"; run_sbt_doc "$d" "sbt ci-release"
edit_model "$d" 'm["commands"].remove("ci-release")'
expect_fail "a plugin command whose plugin was removed" "$d" "\`ci-release\` is not an alias"

echo "== aliases"
d="$(fresh_copy cmd-alias-removed)"
edit_model "$d" 'm["aliases"] = [a for a in m["aliases"] if a["name"] != "buildAll"]'
expect_fail "documented alias removed from the build" "$d" "\`buildAll\` is not an alias"

d="$(fresh_copy cmd-alias-body)"
edit_model "$d" 'next(a for a in m["aliases"] if a["name"] == "buildAll")["body"] = ";clean;compile;tset"'
expect_fail "an alias whose body names an unknown task" "$d" "alias \`buildAll\` (\`;clean;compile;tset\`): \`tset\` is not an alias"

d="$(fresh_copy cmd-alias-body-scoped)"
edit_model "$d" 'next(a for a in m["aliases"] if a["name"] == "testIntegration")["body"] = ";set core / fork := true; core/itTierCheck"'
expect_fail "an alias body with a key scoped to the wrong project" "$d" "\`itTierCheck\` is not defined in \`core\`"

d="$(fresh_copy cmd-alias-body-switch)"
edit_model "$d" 'm["aliases"].append({"name": "checkCore", "body": ";project core;publishedArtifactsCheck"})'
expect_fail "an alias body's project switch persists" "$d" "not defined in the current project \`core\`"

echo "all cases behaved"
