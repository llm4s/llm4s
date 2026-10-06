#!/usr/bin/env bash
# Fails when the documented support matrix says something the build does not do.
#
# The Scala 2.13 thread (#874, #888, #1095) started because CLAUDE.md and the docs claimed cross-building,
# `sbt +test` over several versions and a `modules/crossTest/` directory, and the build had none of them.
# #1134 corrected the prose; this keeps it corrected. It checks the handful of documented facts that can
# be compared with the build, not the prose around them:
#
#   1. Scala  - every concrete Scala version in the docs (`Scala 3.x.y`, `Scala 3.x`, `Scala 3`, and
#               `Scala 2.13` just the same) agrees with `scala3` in project/Dependencies.scala as far as it
#               goes, and build.sbt's scalaVersion is that value. So do `scalaVersion := "..."` and the
#               `scala3-library_3` pins in snippets, and `_<binary>` artifact suffixes. Naming another
#               version is fine only to say it is not supported: in the same clause a negation before it
#               (`no Scala 2.13 artifact`, `do not ... Scala 2.13`) or a deferral or denial after it
#               (`Scala 2.13 support is deferred`, `... is not supported`). The build cross-builds nothing,
#               so `crossScalaVersions` and `sbt +task` are claims too.
#   2. JDK    - every `JDK N` (also `Java N`, `Java SE N`, `Java 1.N`, `JRE N`, `OpenJDK N`, `Temurin N`,
#               `Corretto N`, `Zulu N`) is a JDK .github/workflows/ci.yml runs, and so is each JDK of a list
#               or range (`JDK 21 and 25`, `JDK 21-25`). A floor (`JDK N+`, `JDK N or newer`, `at least
#               JDK N`, `JDK >= N`) or a range's start must be the minimum runtime: the oldest JDK CI runs,
#               which a release target in the build (`-release`, `--release`, `-java-output-version`,
#               `-target`), if any, must equal. A ceiling (`JDK N or older`) is the floor itself or wrong.
#   3. Module - every module in CLAUDE.md's repository-structure block exists on disk, and every module
#               build.sbt defines is named there (itself or a parent directory).
#   4. sbt    - every `sbt ...` command quoted in the docs is a build alias, a task the build both declares
#               and sets (a `taskKey` with no `:=` is not a task), an sbt built-in, a command of a plugin
#               project/plugins.sbt loads, or such a task scoped to a project the build defines
#               (`core/test`). A task build.sbt sets only inside some projects' definitions is valid in
#               those projects and in the projects that aggregate them - scoped, or unscoped from the root
#               (`core/publishedArtifactsCheck` is not: the root alone sets it). `project X` must name a
#               project, and each alias's literal body must itself be valid. A backslash-continued command
#               is read as one line, a command after `&&`, `||` or `|` is read too, and one the shell
#               cannot parse fails.
#
# Commented-out code in build.sbt, project/*.scala and project/plugins.sbt does not count as build.
#
# Usage: scripts/check-doc-support.sh [REPO_ROOT]    (the root defaults to this script's repository)
# Release notes, migration guides and design documents name old versions and commands on purpose and
# are not checked. Elsewhere, a line is exempt from checks 1, 2 and 4 when it says it is history
# (`previously`, `formerly`, `no longer`, `dropped`, `until`, `legacy`, `older`, `used to`, `upgrading from`)
# or carries the marker `doc-support: ignore` (in an HTML comment in prose, or `# doc-support: ignore` in a
# code block). Exit code 0 = the matrix is true. Non-zero = file:line and the claim, one per line.
set -euo pipefail

REPO_ROOT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$REPO_ROOT"

python3 - <<'PYEOF'
import pathlib
import re
import shlex
import sys

SKIP_DIRS = ("docs/migrations/", "docs/design/", "docs/_data/", "docs/_site/", "docs/superpowers/")
SKIP_FILES = {"docs/reference/migration.md", "docs/reference/release.md", "CHANGELOG.md"}

# build.sbt projects that exist only to forward an old coordinate; they are not modules to document.
UNDOCUMENTED_MODULE_PREFIXES = ("modules/relocations/",)

# What `sbt <word>` may be without the build defining it: sbt itself, valid in any project.
SBT_BUILTINS = {
    "clean", "compile", "test", "testOnly", "testQuick", "run", "runMain", "console", "consoleQuick",
    "package", "publish", "publishLocal", "publishM2", "doc", "update", "reload",
    "projects", "project", "tasks", "settings", "show", "inspect", "set", "new", "exit", "help",
    "version", "name", "scalaVersion", "evicted", "dependencyTree",
}
SCOPE_WORDS = {"ThisBuild", "Global", "Test", "Compile", "IntegrationTest", "Runtime"}

# Commands and configurations a plugin adds, keyed by the plugin's artifact: they are accepted only while
# project/plugins.sbt declares that plugin, so removing a plugin makes the docs that still quote it fail.
# `addDependencyTreePlugin` is sbt's own switch for the full dependency-graph plugin.
PLUGIN_COMMANDS = {
    "sbt-scalafmt": {"scalafmt", "scalafmtAll", "scalafmtCheck", "scalafmtCheckAll", "scalafmtSbt",
                     "scalafmtSbtCheck", "scalafmtOnly"},
    "sbt-scalafix": {"scalafix", "scalafixAll"},
    "sbt-scoverage": {"coverage", "coverageOff", "coverageReport", "coverageAggregate"},
    "sbt-dependency-updates": {"dependencyUpdates"},
    "sbt-mima-plugin": {"mimaReportBinaryIssues"},
    "sbt-native-packager": {"stage"},
    "sbt-pgp": {"publishSigned"},
    "sbt-ci-release": {"ci-release", "sonatypeBundleRelease"},
    "sbt-assembly": {"assembly"},
    "sbt-license-report": {"dumpLicenseReport"},
    "addDependencyTreePlugin": {"dependencyBrowseTree", "dependencyBrowseGraph", "dependencyDot"},
}
PLUGIN_CONFIGS = {
    "sbt-native-packager": {"Docker", "Universal"},
    "sbt-jmh": {"Jmh"},
}

errors = []
sbt_unknown = []

IGNORE_MARKER = "doc-support: ignore"
HISTORICAL = re.compile(
    r"\b(previously|formerly|no longer|dropped|until|legacy|older|used to|upgrading from)\b", re.I)


def exempt(line_text):
    """A line that opts out, or says it is describing the past: it names an old version on purpose."""
    return IGNORE_MARKER in line_text or HISTORICAL.search(line_text) is not None


def line_text_at(text, index):
    start = text.rfind("\n", 0, index) + 1
    end = text.find("\n", index)
    return text[start:len(text) if end < 0 else end]


def fail(path, line, message):
    errors.append(f"{path}:{line}: {message}")


def read(rel):
    p = pathlib.Path(rel)
    return p.read_text(encoding="utf-8") if p.exists() else ""


def strip_comments(src):
    """Scala/sbt source with `//` and `/* */` comments blanked (newlines kept), string literals left
    alone: a commented-out alias, task, project or plugin is not part of the build."""
    out, i, n = [], 0, len(src)
    while i < n:
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(src[i:j]); i = j
        elif src[i] == '"':
            j = i + 1
            while j < n and src[j] not in '"\n':
                j += 2 if src[j] == "\\" else 1
            out.append(src[i:j + 1]); i = j + 1
        elif src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("\n" * src.count("\n", i, j)); i = j
        else:
            out.append(src[i]); i += 1
    return "".join(out)


def line_of(text, index):
    return text.count("\n", 0, index) + 1


def doc_files():
    out = [pathlib.Path("CLAUDE.md"), pathlib.Path("README.md")]
    out += sorted(pathlib.Path("docs").rglob("*.md")) if pathlib.Path("docs").exists() else []
    keep = []
    for p in out:
        rel = p.as_posix()
        if rel in SKIP_FILES or rel.startswith(SKIP_DIRS) or not p.exists():
            continue
        keep.append(p)
    return keep


FILES = doc_files()
TEXTS = {p: p.read_text(encoding="utf-8") for p in FILES}

# ---------------------------------------------------------------- the build's side of the facts
deps = strip_comments(read("project/Dependencies.scala"))
m = re.search(r'val\s+scala3\s*=\s*"([^"]+)"', deps)
if not m:
    fail("project/Dependencies.scala", 1, "no `val scala3 = \"x.y.z\"`; cannot establish the Scala version")
    SCALA = None
else:
    SCALA = m.group(1)
build = strip_comments(read("build.sbt"))
scala_sources = [strip_comments(pathlib.Path(q).read_text(encoding="utf-8"))
                 for q in sorted(pathlib.Path("project").glob("*.scala"))]
if SCALA and not re.search(r"scalaVersion\s*:=\s*scala3\b", build):
    fail("build.sbt", 1, "scalaVersion is not `scala3`, so the documented Scala version is not the build's")
CROSS_BUILDS = any(re.search(r"\bcrossScalaVersions\s*(?::=|\+=|\+\+=)", s) for s in [build] + scala_sources)

ci = read(".github/workflows/ci.yml")
ci_jdks = set()
for jm in re.finditer(r"^[^#\n]*java-version:\s*(\S+)", ci, re.M):
    value = jm.group(1).strip("'\"")
    if value.isdigit():
        ci_jdks.add(int(value))
    elif "matrix.java" in value:
        for mm in re.finditer(r"^[^#\n]*\bjava:\s*\[([^\]]*)\]", ci, re.M):
            ci_jdks.update(int(x) for x in re.findall(r"\d+", mm.group(1)))
if not ci_jdks:
    fail(".github/workflows/ci.yml", 1, "no java-version found; cannot establish the JDK CI runs")

# The minimum supported runtime is the oldest JDK CI runs. A release target compiled into the class files
# is a second statement of it, and must agree: a target CI never runs is a floor nothing tests.
JDK_FLOOR = min(ci_jdks) if ci_jdks else None
RELEASE_RE = re.compile(
    r'"(?:-release|--release|-java-output-version|-target)"\s*,\s*"(?:jvm-)?(?:1\.)?(\d+)"'
    r'|"(?:-release|-target|-java-output-version):(?:jvm-)?(?:1\.)?(\d+)"')
release_targets = {int(a or b) for s in [build] + scala_sources for a, b in RELEASE_RE.findall(s)}
for target in sorted(release_targets):
    if JDK_FLOOR is not None and target != JDK_FLOOR:
        fail("build.sbt", 1, f"compiles for JDK {target}, but the oldest JDK CI runs is {JDK_FLOOR}: "
                             f"the supported floor must be one CI exercises")

# ---------------------------------------------------------------- 1. Scala
# A version other than the build's may be named only to say it is not supported, in the same clause:
# a negation before it (`no Scala 2.13 artifact`, `do not rewrite ... to Scala 2.13`), or a deferral or
# denial after it (`Scala 2.13 support is deferred`, `Scala 2 is not supported`).
CLAUSE_BREAK = re.compile(r"(?<!\d)\.(?!\d)|\.(?=\s|$)|[;,|()—–:!?]| - |\bbut\b|\bwhile\b|\bwhereas\b", re.I)
NEG_BEFORE = re.compile(r"\b(?:not|no|never|deferred|without|nor|cannot|can't|don't|doesn't|isn't|aren't|won't)\b", re.I)
NEG_AFTER = re.compile(
    r"\b(?:deferred|unsupported|(?:is|are|was|were|will be)\s+not|isn't|aren't|wasn't|won't|never|"
    r"not\s+(?:supported|published|available|built)|post-1\.0|after 1\.0)\b", re.I)


def clause_around(line, start, end):
    """The clause of `line` containing [start, end): split at sentence and clause punctuation."""
    left = max((mm.end() for mm in CLAUSE_BREAK.finditer(line, 0, start)), default=0)
    right = next((mm.start() for mm in CLAUSE_BREAK.finditer(line, end)), len(line))
    return line[left:start], line[end:right]


def denied(text, start, end):
    ls = text.rfind("\n", 0, start) + 1
    line = line_text_at(text, start)
    before, after = clause_around(line, start - ls, end - ls)
    return NEG_BEFORE.search(before) is not None or NEG_AFTER.search(after) is not None


SCALA_PARTS = SCALA.split(".") if SCALA else []
SCALA_BINARY = (SCALA_PARTS[0] if SCALA_PARTS and int(SCALA_PARTS[0]) >= 3 else ".".join(SCALA_PARTS[:2]))


def scala_agrees(parts):
    """`parts` (major[, minor[, patch]]; `x` is a wildcard) names the build's version as far as it goes."""
    return all(p == "x" or (i < len(SCALA_PARTS) and p == SCALA_PARTS[i]) for i, p in enumerate(parts))


# `Scala 3`, `Scala 3.7`, `Scala 3.7.1`, `Scala 3.x`, `Scala-3-only`, `Scala 3 only (3.7.1)`, `scala-3`.
# A list (`Scala 3.7.1 and 2.13`, `Scala versions 2.13 and 3`) is a claim about each of its versions.
SCALA_RE = re.compile(r"\b[Ss]cala(?:\s+versions?)?[ -]v?(?:(\d+)(?:[ -]only)? \()?(\d+(?:\.(?:\d+|x)){0,2})(?![\d])"
                      r"(?P<more>(?:\s*(?:,|/|\band\b|\bor\b)\s*(?:[Ss]cala\s+)?\d(?:\.(?:\d+|x)){0,2}(?![\w.]\w))*)")
SUFFIX_RE = re.compile(r"`_(\d+(?:\.\d+)?)`|\bllm4s[\w-]*_(\d+(?:\.\d+)?)(?![\w.])")
LIBRARY_PIN_RE = re.compile(r"scala3-library_3`?\s+(?:to|at)\s+`?(\d+\.\d+\.\d+)")
canonical_has_scala = False
for p in FILES:
    text = TEXTS[p]
    rel = p.as_posix()
    if not SCALA:
        break
    for sm in SCALA_RE.finditer(text):
        if exempt(line_text_at(text, sm.start())):
            continue
        claims = [sm.group(2).split(".")]
        if sm.group(1):
            claims.append([sm.group(1)])
        claims += [v.split(".") for v in re.findall(r"\d(?:\.(?:\d+|x)){0,2}", sm.group("more") or "")]
        if rel == "docs/reference/v1-scope.md" and sm.group(2) == SCALA:
            canonical_has_scala = True
        # `the Scala 2.13 \`scala-library\``: the standard library artifact Scala 3 runs on, not a target.
        if re.match(r"\s*`?scala-library\b", text[sm.end():]) and claims[0][:2] == ["2", "13"]:
            continue
        for parts in claims:
            if not scala_agrees(parts) and not denied(text, sm.start(), sm.end()):
                fail(rel, line_of(text, sm.start()),
                     f"documents Scala {'.'.join(parts)}, but the build is Scala {SCALA}")
    for xm in SUFFIX_RE.finditer(text):
        suffix = xm.group(1) or xm.group(2)
        if suffix != SCALA_BINARY and not exempt(line_text_at(text, xm.start())) \
                and not denied(text, xm.start(), xm.end()):
            fail(rel, line_of(text, xm.start()),
                 f"documents a `_{suffix}` artifact, but the build publishes only `_{SCALA_BINARY}` (Scala {SCALA})")
    pins = [(vm.start(), vm.group(1)) for vm in re.finditer(r'\bscalaVersion\s*:=\s*"([^"]+)"', text)]
    pins += [(vm.start(1), vm.group(1)) for vm in LIBRARY_PIN_RE.finditer(text)]
    for fm in re.finditer(r"```[^\n]*\n(.*?)```", text, re.S):
        if "scala3-library" in fm.group(1):
            pins += [(fm.start(1) + vm.start(), vm.group(1))
                     for vm in re.finditer(r'useVersion\(\s*"([^"]+)"', fm.group(1))]
    for at, version in pins:
        if version != SCALA and not exempt(line_text_at(text, at)):
            fail(rel, line_of(text, at), f"pins Scala {version}, but the build is Scala {SCALA}")
    if not CROSS_BUILDS:
        for cm in re.finditer(r"\bcrossScalaVersions\b", text):
            if not exempt(line_text_at(text, cm.start())) and not denied(text, cm.start(), cm.end()):
                fail(rel, line_of(text, cm.start()),
                     "documents `crossScalaVersions`, but the build cross-builds nothing")
if SCALA and not canonical_has_scala:
    fail("docs/reference/v1-scope.md", 1, f"does not state Scala {SCALA}, which the build uses")

# ---------------------------------------------------------------- 2. JDK
_JV = r"(?:1\.)?\d{1,2}(?!\d)(?!\.\d)(?![A-Za-z])"
JDK_RE = re.compile(
    r"(?P<pre>\b(?:at least|minimum(?: of)?|min\.?)\s+)?"
    r"\b(?:OpenJDK|JDK|JRE|Java(?:\s+SE)?|Temurin|Corretto|Zulu)(?:\s+versions?)?(?:\s*(?P<ge>>=|≥)\s*|\s*:\s*|[ -]?)"
    rf"(?P<n>{_JV})"
    rf"(?:\s*(?:-|–|\bto\b|\bthrough\b)\s*(?P<hi>{_JV}))?"
    rf"(?P<more>(?:\s*(?:,|/|\band\b|\bor\b)\s*(?:JDK\s*)?{_JV}(?!\s*(?:-|–)\s*\d))*)"
    r"(?P<floor>\+|\s+(?:or|and)\s+(?:newer|later|above|higher|greater|up|beyond)|\s+(?:minimum|or\s+any\s+later))?"
    r"(?P<ceil>\s+(?:or|and)\s+(?:older|earlier|below|lower))?")


def jdk_number(token):
    token = token.strip()
    return int(token[2:]) if token.startswith("1.") else int(token)


runs = ", ".join(str(j) for j in sorted(ci_jdks))
for p in FILES:
    text = TEXTS[p]
    rel = p.as_posix()
    for jm in JDK_RE.finditer(text):
        if not ci_jdks or exempt(line_text_at(text, jm.start())):
            continue
        line = line_of(text, jm.start())
        n = jdk_number(jm.group("n"))
        listed = [jdk_number(x) for x in re.findall(_JV, jm.group("more") or "")]
        hi = jdk_number(jm.group("hi")) if jm.group("hi") else None
        is_floor = bool(jm.group("pre") or jm.group("ge") or jm.group("floor"))
        if jm.group("ceil"):
            if n != JDK_FLOOR:
                fail(rel, line, f"supports JDK {n} or older, but the oldest JDK CI runs is {JDK_FLOOR}")
            continue
        if is_floor or hi is not None:
            if n != JDK_FLOOR:
                fail(rel, line, f"gives JDK {n} as the minimum, but the minimum supported runtime "
                                f"(the oldest JDK CI runs) is JDK {JDK_FLOOR}")
            if is_floor and n > max(ci_jdks):
                fail(rel, line, f"requires JDK {n} or newer, but CI only runs JDK {runs}")
        elif n not in ci_jdks:
            fail(rel, line, f"documents JDK {n}, but CI runs JDK {runs}")
        for other in ([hi] if hi is not None else []) + listed:
            if other not in ci_jdks:
                fail(rel, line, f"documents JDK {other}, but CI runs JDK {runs}")

# ---------------------------------------------------------------- 3. modules
claude = read("CLAUDE.md")
documented = []  # (path, line)
block = re.search(r"## Repository Structure\s*```[^\n]*\n(.*?)```", claude, re.S)
if not block:
    fail("CLAUDE.md", 1, "no repository-structure block; cannot compare modules with the build")
else:
    base_line = line_of(claude, block.start(1))
    stack = {}
    for offset, raw in enumerate(block.group(1).splitlines()):
        em = re.match(r"^((?:│   |    )*)(?:├── |└── )([^\s#/]+)/?", raw)
        if not em:
            continue
        depth = len(em.group(1)) // 4
        stack[depth] = em.group(2)
        for deeper in [d for d in stack if d > depth]:
            del stack[deeper]
        parts = [stack[d] for d in sorted(stack)]
        if parts[0] != "modules" or len(parts) == 1:
            continue
        documented.append(("/".join(parts), base_line + offset))

for path, line in documented:
    if not pathlib.Path(path).is_dir():
        fail("CLAUDE.md", line, f"names {path}/, which is not a directory")


def has_content(path):
    """A directory with anything but build output in it: sbt makes `target/` for a project with no sources."""
    p = pathlib.Path(path)
    return p.is_dir() and any(c.name not in {"target", ".bloop", ".bsp", ".metals"} for c in p.iterdir())


doc_paths = {path for path, _ in documented}
build_modules = sorted({mm.replace("//", "/") for mm in re.findall(r'file\("(modules/[^"]+)"\)', build)})
for mod in build_modules:
    if mod.startswith(UNDOCUMENTED_MODULE_PREFIXES) or not has_content(mod):
        continue                         # relocation stubs and the aggregate `docs` project have no module to name
    covered = any(mod == d or mod.startswith(d + "/") for d in doc_paths)
    if not covered:
        fail("build.sbt", 1, f"defines {mod}, which CLAUDE.md's repository-structure block does not name")

# ---------------------------------------------------------------- 4. sbt commands
STRING_LIT = r'(?:"""(.*?)"""|"((?:[^"\\\n]|\\.)*)")'
alias_defs = [(am.group(1), am.group(2) if am.group(2) is not None else am.group(3))
              for am in re.finditer(r'addCommandAlias\(\s*"([^"]+)"\s*,\s*(?:' + STRING_LIT + r'\s*\))?', build, re.S)]
aliases = {name for name, _ in alias_defs}
declared = set()
for src in [build] + scala_sources:
    declared.update(re.findall(r"(\w+)\s*(?::\s*[\w\[\]]+\s*)?=\s*(?:taskKey|settingKey|inputKey)\b", src))
projects = set(re.findall(r"lazy val (\w+)\s*=\s*\(?\s*project\b", build))


def assignments(key, src):
    """Where `src` gives `key` a value: `key := ...`, `Scope / key += ...`. `key / aggregate := false` sets
    `aggregate`, not `key`, so it does not count: the key must be the last segment before the operator."""
    return list(re.finditer(r"(ThisBuild\s*/\s*)?\b" + re.escape(key)
                            + r"\s*(?::=|\+=|\+\+=|~=)", src))


# A declared key nothing assigns is not a task: sbt reports it as an undefined reference.
keys = {key for key in declared if any(assignments(key, src) for src in [build] + scala_sources)}
unassigned = declared - keys

# The plugins project/plugins.sbt loads (commented-out lines do not count), and what they add.
plugins_sbt = strip_comments(read("project/plugins.sbt"))
loaded_plugins = set(re.findall(r'addSbtPlugin\(\s*"[^"]+"\s*%+\s*"([^"]+)"', plugins_sbt))
if re.search(r"\baddDependencyTreePlugin\b", plugins_sbt):
    loaded_plugins.add("addDependencyTreePlugin")
plugin_commands = set().union(*(PLUGIN_COMMANDS.get(pl, set()) for pl in loaded_plugins))
scope_words = SCOPE_WORDS.union(*(PLUGIN_CONFIGS.get(pl, set()) for pl in loaded_plugins))
unloaded_plugin_of = {c: pl for pl, cs in PLUGIN_COMMANDS.items() if pl not in loaded_plugins for c in cs}
unloaded_plugin_of.update({c: pl for pl, cs in PLUGIN_CONFIGS.items() if pl not in loaded_plugins for c in cs})


def project_blocks(src):
    """{project: (start, end)} - a project's definition runs from its `lazy val` to the next line that
    starts in column 0 with anything but `.` or `)` (the next definition or alias; comments are gone)."""
    out = {}
    for mm in re.finditer(r"^lazy val (\w+)\s*=\s*\(?\s*project\b", src, re.M):
        nm = re.compile(r"^[^\s.)]", re.M).search(src, src.index("\n", mm.start()) + 1)
        out[mm.group(1)] = (mm.start(), nm.start() if nm else len(src))
    return out


blocks = project_blocks(build)
root_project = next((mm.group(1) for mm in re.finditer(r'lazy val (\w+)\s*=\s*\(?\s*project\s+in\s+file\("\."\)', build)), None)

# Which projects aggregate which: `sbt task` from the root, or `sbt p/task`, also runs in p's aggregates.
aggregates = {}
for name, (b, e) in blocks.items():
    agg = set()
    for am in re.finditer(r"\.aggregate\(([^)]*)\)", build[b:e], re.S):
        agg.update(w for w in re.findall(r"\b(\w+)\b", am.group(1)) if w in projects)
    aggregates[name] = agg


def reaches(project):
    """`project` and every project it aggregates, transitively."""
    seen, todo = set(), [project]
    while todo:
        cur = todo.pop()
        if cur not in seen:
            seen.add(cur)
            todo.extend(aggregates.get(cur, ()))
    return seen


# Which projects a build-defined key is set in. A key set inside one or more project definitions, and
# nowhere else, exists only there: `core/publishedArtifactsCheck` is not a task when only the root sets it.
# A key set anywhere else - shared settings, `ThisBuild /`, a helper in project/*.scala - is taken to be
# available everywhere; static parsing cannot follow it further.
key_projects = {}
for key in keys:
    owners = set()
    unrestricted = any(assignments(key, src) for src in scala_sources)
    for am in assignments(key, build):
        owner = next((name for name, (b, e) in blocks.items() if b <= am.start() < e), None)
        if owner is None or am.group(1):
            unrestricted = True
        else:
            owners.add(owner)
    if owners and not unrestricted:
        key_projects[key] = owners


def split_commands(argument_string):
    """The sbt commands quoted after `sbt`, each a list of words: flags are dropped, `;` separates
    commands. None when the shell could not parse the line (unbalanced quotes): pasting it is an error."""
    try:
        tokens = shlex.split(argument_string)
    except ValueError:
        return None
    commands = []
    for token in tokens:
        if token.startswith("-") or token.startswith("$") or token.startswith("<"):
            continue
        if token == "new" or token.startswith("new "):
            commands.append(["new"])     # `sbt new <template>`: what follows is the template, not a command
            break
        for command in token.split(";"):
            words = re.sub(r"\s*/\s*", "/", command).split()     # `core / Test / compile` is one key
            if words:
                commands.append(words)
    return commands


def known_task(word):
    return word in keys or word in SBT_BUILTINS or word in plugin_commands


def unknown(path, line, head, word, what):
    sbt_unknown.append(word)
    if word in unloaded_plugin_of:
        fail(path, line, f"`sbt {head}`: `{word}` comes from {unloaded_plugin_of[word]}, "
                         f"which project/plugins.sbt does not load")
    elif word in unassigned:
        fail(path, line, f"`sbt {head}`: `{word}` is declared in the build but never set (`{word} := ...`), "
                         f"so it is not a task")
    else:
        fail(path, line, f"`sbt {head}`: `{word}` is not {what}")


def check_config(path, line, head, config):
    """A `config:task` prefix (the old slash-free syntax) is matched without regard to case."""
    if config.lower() in {w.lower() for w in scope_words}:
        return True
    canonical = {w.lower(): w for w in unloaded_plugin_of}.get(config.lower(), config)
    unknown(path, line, head, canonical, "a configuration the build or its plugins define")
    return False


def check_where_set(path, line, head, word, scope):
    """A task set only in some projects runs where they are reached: in them, or through aggregation."""
    if word not in key_projects or scope is None:
        return
    if not key_projects[word] & reaches(scope):
        where = ", ".join(sorted(key_projects[word]))
        if scope == root_project:
            fail(path, line, f"`sbt {head}`: `{word}` is set only in project {where}, "
                             f"which the root `{scope}` does not aggregate")
        else:
            fail(path, line, f"`sbt {head}`: `{word}` is set only in project {where}, not in `{scope}`")


def check_head(path, line, words):
    head = " ".join(words)
    first = words[0]
    if first.startswith("++"):
        version = first[2:] or (words[1] if len(words) > 1 else "")
        if SCALA and version and version[0].isdigit() and version.rstrip("!") != SCALA:
            fail(path, line, f"`sbt {head}` switches to Scala {version.rstrip('!')}, but the build is Scala {SCALA}")
        return
    if first.startswith("+") and not CROSS_BUILDS:
        fail(path, line, f"`sbt {head}` cross-builds, but the build sets no crossScalaVersions")
    word = first.lstrip("+~")          # `+test` cross-build and `~test` triggered execution
    if word == "" or word[0].isdigit() or re.fullmatch(r"[^\w]+", word):
        return
    if word == "project" and len(words) > 1 and words[1] not in projects and not words[1].startswith("{"):
        fail(path, line, f"`sbt {head}` names project `{words[1]}`, which build.sbt does not define")
        return
    if re.fullmatch(r"[\w.-]+:[\w-]+", word):   # config:task such as docker:publishLocal
        config, word = word.split(":", 1)
        if not check_config(path, line, head, config):
            return
    if "/" in word:
        # project/task, Config/task, project/Config/task: the scope must exist and so must the task.
        segments = word.split("/")
        scope, middle, word = segments[0], segments[1:-1], segments[-1]
        if re.fullmatch(r"[\w.-]+:[\w-]+", word):   # project/config:task such as workspaceRunner/docker:publishLocal
            config, word = word.split(":", 1)
            if not check_config(path, line, head, config):
                return
        if scope not in projects and scope not in scope_words:
            if scope in unloaded_plugin_of:
                unknown(path, line, head, scope, "")
            else:
                fail(path, line, f"`sbt {head}` names project `{scope}`, which build.sbt does not define")
            return
        for axis in middle:
            if axis not in scope_words and not known_task(axis):
                unknown(path, line, head, axis, "a configuration or task the build knows")
                return
        if not known_task(word):
            unknown(path, line, head, word, "a task the build defines, a loaded plugin's, nor an sbt built-in")
            return
        check_where_set(path, line, head, word, scope if scope in projects else root_project)
        return
    if word in aliases:
        return
    if known_task(word):
        check_where_set(path, line, head, word, root_project)
        return
    unknown(path, line, head, word, "an alias or task the build defines, a loaded plugin's, nor an sbt built-in")


def check_invocation(path, line, argument_string):
    commands = split_commands(argument_string)
    if commands is None:
        fail(path, line, f"`sbt {argument_string}` cannot be parsed by the shell (unbalanced quotes?)")
        return
    for words in commands:
        check_head(path, line, words)


# An alias is a command line too: each command of a literal body must be valid, so an alias whose task
# was removed fails here even when no document quotes it. A body computed in Scala is not followed.
for name, body in alias_defs:
    if body is None:
        continue
    for command in body.split(";"):
        words = command.split()
        if words and words[0] != "set":
            check_head("build.sbt", 1, words)


def shell_segments(line):
    """`line` split at `&&`, `||` and `|` outside quotes: each piece is one shell command."""
    out, cur, quote, i = [], [], None, 0
    while i < len(line):
        ch = line[i]
        if quote:
            if ch == quote:
                quote = None
        elif ch in "'\"":
            quote = ch
        elif line.startswith("&&", i) or line.startswith("||", i):
            out.append("".join(cur)); cur = []; i += 2
            continue
        elif ch == "|":
            out.append("".join(cur)); cur = []; i += 1
            continue
        cur.append(ch)
        i += 1
    out.append("".join(cur))
    return out


SBT_CALL = re.compile(r"^\s*(?:[$>]\s*)?\(?\s*(?:[A-Za-z_]\w*=\S*\s+)*(?:\./)?sbtn?\s+(.*?)\s*\)?\s*$")


def logical_lines(body):
    """(offset, line) for each shell line of a code block, a backslash-continued line joined with the
    lines it continues onto, so `sbt -Dk=v \\` followed by `"run"` is read as one invocation."""
    out, pending, start = [], None, 0
    for offset, raw in enumerate(body.splitlines()):
        if pending is None:
            start, pending = offset, ""
        stripped = raw.rstrip()
        if stripped.endswith("\\"):
            pending += stripped[:-1] + " "
            continue
        out.append((start, pending + raw))
        pending = None
    if pending is not None:
        out.append((start, pending))
    return out


FENCE = re.compile(r"```[^\n]*\n(.*?)```", re.S)
INLINE = re.compile(r"`([^`\n]*\bsbtn?\s[^`\n]+)`")
for p in FILES:
    text = TEXTS[p]
    for fm in FENCE.finditer(text):
        body_line = line_of(text, fm.start(1))
        for offset, raw in logical_lines(fm.group(1)):
            if raw.lstrip().startswith("#") or IGNORE_MARKER in raw or HISTORICAL.search(raw):
                continue
            for segment in shell_segments(re.sub(r"\s+#.*$", "", raw)):
                sm = SBT_CALL.match(segment)
                if sm:
                    check_invocation(p.as_posix(), body_line + offset, sm.group(1))
    # Inline code outside fences: blank the fenced spans first so a block is not read twice.
    outside = FENCE.sub(lambda mm: "\n" * mm.group(0).count("\n"), text)
    for im in INLINE.finditer(outside):
        if exempt(line_text_at(outside, im.start())):
            continue
        for segment in shell_segments(im.group(1)):
            sm = SBT_CALL.match(segment)
            if sm:
                check_invocation(p.as_posix(), line_of(outside, im.start()), sm.group(1))

if errors:
    print("The documented support matrix does not match the build:", file=sys.stderr)
    for e in errors:
        print("  " + e, file=sys.stderr)
    print(f"{len(errors)} stale claim(s). Fix the docs, or the build if the docs are right.", file=sys.stderr)
    if sbt_unknown:
        print("For an `sbt` command that is real but unknown to this script, add it to SBT_BUILTINS (an sbt "
              "built-in) or to PLUGIN_COMMANDS under its plugin's artifact (a plugin task) in "
              "scripts/check-doc-support.sh; for a command in a "
              "document that describes the past, mark the line `doc-support: ignore`.", file=sys.stderr)
    sys.exit(1)
print(f"Support matrix verified: Scala {SCALA}, JDK {', '.join(str(j) for j in sorted(ci_jdks))} "
      f"(floor {JDK_FLOOR}), {len(doc_paths)} documented modules, {len(aliases)} aliases, {len(keys)} tasks and "
      f"{len(loaded_plugins)} plugins known.")
PYEOF
