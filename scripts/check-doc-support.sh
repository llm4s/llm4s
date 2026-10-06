#!/usr/bin/env bash
# Fails when the documented support matrix says something the build does not do.
#
# The Scala 2.13 thread (#874, #888, #1095) started because CLAUDE.md and the docs claimed cross-building,
# `sbt +test` over several versions and a `modules/crossTest/` directory, and the build had none of them.
# #1134 corrected the prose; this keeps it corrected. It checks the handful of documented facts that can
# be compared with the build, not the prose around them:
#
#   1. Scala  - every `Scala 3.x.y` in the docs equals `scala3` in project/Dependencies.scala, every
#               `Scala 3.x` is that value's major.minor, and build.sbt's scalaVersion is that value.
#   2. JDK    - every `JDK N` (also `Java N`, `OpenJDK N`, `Temurin N`, `Corretto N`) in the docs is a JDK
#               that .github/workflows/ci.yml runs; a floor (`JDK N+`, `JDK N or newer`) is fine when N is
#               at or below the newest JDK CI runs.
#   3. Module - every module in CLAUDE.md's repository-structure block exists on disk, and every module
#               build.sbt defines is named there (itself or a parent directory).
#   4. sbt    - every `sbt ...` command quoted in the docs is a build alias, a task the build defines,
#               an sbt built-in, a command of a plugin project/plugins.sbt loads, or such a task scoped
#               to a project the build defines (`core/test`). A task build.sbt sets only inside some
#               projects' definitions is valid only scoped to those (`core/publishedArtifactsCheck` is
#               not: the root alone sets it). A backslash-continued command is read as one line; one
#               the shell cannot parse fails.
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

# ---------------------------------------------------------------- the build's side of the facts
deps = read("project/Dependencies.scala")
m = re.search(r'val\s+scala3\s*=\s*"([^"]+)"', deps)
if not m:
    fail("project/Dependencies.scala", 1, "no `val scala3 = \"x.y.z\"`; cannot establish the Scala version")
    SCALA = None
else:
    SCALA = m.group(1)
build = read("build.sbt")
if SCALA and not re.search(r"scalaVersion\s*:=\s*scala3\b", build):
    fail("build.sbt", 1, "scalaVersion is not `scala3`, so the documented Scala version is not the build's")

ci = read(".github/workflows/ci.yml")
ci_jdks = set()
for jm in re.finditer(r"java-version:\s*(\S+)", ci):
    value = jm.group(1).strip("'\"")
    if value.isdigit():
        ci_jdks.add(int(value))
    elif "matrix.java" in value:
        for mm in re.finditer(r"\bjava:\s*\[([^\]]*)\]", ci):
            ci_jdks.update(int(x) for x in re.findall(r"\d+", mm.group(1)))
if not ci_jdks:
    fail(".github/workflows/ci.yml", 1, "no java-version found; cannot establish the JDK CI runs")

# ---------------------------------------------------------------- 1. Scala
canonical_has_scala = False
SCALA_SHORT = ".".join(SCALA.split(".")[:2]) if SCALA else None
for p in FILES:
    text = p.read_text(encoding="utf-8")
    for sm in re.finditer(r"Scala (?:3 only \()?(3\.\d+(?:\.\d+)?)", text):
        found = sm.group(1)
        if exempt(line_text_at(text, sm.start())):
            continue
        full = found.count(".") == 2
        if full and p.as_posix() == "docs/reference/v1-scope.md" and found == SCALA:
            canonical_has_scala = True
        if SCALA and full and found != SCALA:
            fail(p.as_posix(), line_of(text, sm.start()),
                 f"documents Scala {found}, but the build is Scala {SCALA}")
        elif SCALA and not full and found != SCALA_SHORT:
            fail(p.as_posix(), line_of(text, sm.start()),
                 f"documents Scala {found}, but the build is Scala {SCALA}")
if SCALA and not canonical_has_scala:
    fail("docs/reference/v1-scope.md", 1, f"does not state Scala {SCALA}, which the build uses")

# ---------------------------------------------------------------- 2. JDK
JDK_RE = re.compile(
    r"\b(?:OpenJDK|JDK|Java|Temurin|Corretto)[ -]?(\d{1,2})(?!\d)(?![A-Za-z])(\+| or (?:newer|later|above))?")
for p in FILES:
    text = p.read_text(encoding="utf-8")
    for jm in JDK_RE.finditer(text):
        if not ci_jdks or exempt(line_text_at(text, jm.start())):
            continue
        n = int(jm.group(1))
        runs = ", ".join(str(j) for j in sorted(ci_jdks))
        if jm.group(2):
            if n > max(ci_jdks):
                fail(p.as_posix(), line_of(text, jm.start()),
                     f"requires JDK {n} or newer, but CI only runs JDK {runs}")
        elif n not in ci_jdks:
            fail(p.as_posix(), line_of(text, jm.start()),
                 f"documents JDK {n}, but CI runs JDK {runs}")

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
aliases = set(re.findall(r'addCommandAlias\(\s*"([^"]+)"', build))
keys = set()
scala_sources = [pathlib.Path(q).read_text(encoding="utf-8") for q in sorted(pathlib.Path("project").glob("*.scala"))]
for src in [build] + scala_sources:
    keys.update(re.findall(r"(\w+)\s*(?::\s*[\w\[\]]+\s*)?=\s*(?:taskKey|settingKey|inputKey)\b", src))
projects = set(re.findall(r"lazy val (\w+)\s*=\s*\(?\s*project\b", build))

# The plugins project/plugins.sbt loads (commented-out lines do not count), and what they add.
plugins_sbt = "\n".join(re.sub(r"//.*$", "", ln) for ln in read("project/plugins.sbt").splitlines())
loaded_plugins = set(re.findall(r'addSbtPlugin\(\s*"[^"]+"\s*%+\s*"([^"]+)"', plugins_sbt))
if re.search(r"\baddDependencyTreePlugin\b", plugins_sbt):
    loaded_plugins.add("addDependencyTreePlugin")
plugin_commands = set().union(*(PLUGIN_COMMANDS.get(pl, set()) for pl in loaded_plugins))
scope_words = SCOPE_WORDS.union(*(PLUGIN_CONFIGS.get(pl, set()) for pl in loaded_plugins))
unloaded_plugin_of = {c: pl for pl, cs in PLUGIN_COMMANDS.items() if pl not in loaded_plugins for c in cs}
unloaded_plugin_of.update({c: pl for pl, cs in PLUGIN_CONFIGS.items() if pl not in loaded_plugins for c in cs})


def project_blocks(src):
    """{project: (start, end)} - a project's definition runs from its `lazy val` to the next line that
    starts in column 0 with anything but `.` or `)` (the next definition, alias or comment)."""
    out = {}
    for mm in re.finditer(r"^lazy val (\w+)\s*=\s*\(?\s*project\b", src, re.M):
        nm = re.compile(r"^[^\s.)]", re.M).search(src, src.index("\n", mm.start()) + 1)
        out[mm.group(1)] = (mm.start(), nm.start() if nm else len(src))
    return out


# Which projects a build-defined key is set in. A key set inside one or more project definitions, and
# nowhere else, exists only there: `core/publishedArtifactsCheck` is not a task when only the root sets it.
# A key set anywhere else - shared settings, `ThisBuild /`, a helper in project/*.scala - or never set in
# build.sbt is taken to be available everywhere; static parsing cannot follow it further.
blocks = project_blocks(build)
key_projects = {}
for key in keys:
    owners = set()
    unrestricted = any(re.search(r"\b" + key + r"\s*:=", src) for src in scala_sources)
    for am in re.finditer(r"(ThisBuild\s*/\s*)?\b" + key + r"\s*(?:/\s*\w+\s*)?(?::=|\+=|\+\+=)", build):
        owner = next((name for name, (b, e) in blocks.items() if b <= am.start() < e), None)
        if owner is None or am.group(1):
            unrestricted = True
        else:
            owners.add(owner)
    if owners and not unrestricted:
        key_projects[key] = owners


def command_heads(argument_string):
    """The sbt command words quoted after `sbt`: flags are dropped, `;` separates commands.
    None when the shell could not parse the line (unbalanced quotes): a reader pasting it gets an error."""
    try:
        tokens = shlex.split(argument_string)
    except ValueError:
        return None
    heads = []
    for index, token in enumerate(tokens):
        if token.startswith("-") or token.startswith("$") or token.startswith("<"):
            continue
        if token == "new" or token.startswith("new "):
            heads.append("new")          # `sbt new <template>`: what follows is the template, not a command
            break
        for command in token.split(";"):
            words = command.split()
            if words:
                heads.append(words[0])
    return heads


def known_task(word):
    return word in keys or word in SBT_BUILTINS or word in plugin_commands


def unknown(path, line, head, word, what):
    sbt_unknown.append(word)
    if word in unloaded_plugin_of:
        fail(path, line, f"`sbt {head}`: `{word}` comes from {unloaded_plugin_of[word]}, "
                         f"which project/plugins.sbt does not load")
    else:
        fail(path, line, f"`sbt {head}`: `{word}` is not {what}")


def check_config(path, line, head, config):
    """A `config:task` prefix (the old slash-free syntax) is matched without regard to case."""
    if config.lower() in {w.lower() for w in scope_words}:
        return True
    canonical = {w.lower(): w for w in unloaded_plugin_of}.get(config.lower(), config)
    unknown(path, line, head, canonical, "a configuration the build or its plugins define")
    return False


def check_head(path, line, head):
    word = head.lstrip("+~")          # `+test`, `++3.7.1` cross-build and `~test` triggered execution
    if word == "" or word[0].isdigit() or re.fullmatch(r"[^\w]+", word):
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
        if scope in projects and word in key_projects and scope not in key_projects[word]:
            where = ", ".join(sorted(key_projects[word]))
            fail(path, line, f"`sbt {head}`: `{word}` is set only in project {where}, not in `{scope}`")
        return
    if word in aliases or known_task(word):
        return
    unknown(path, line, head, word, "an alias or task the build defines, a loaded plugin's, nor an sbt built-in")


def check_invocation(path, line, argument_string):
    heads = command_heads(argument_string)
    if heads is None:
        fail(path, line, f"`sbt {argument_string}` cannot be parsed by the shell (unbalanced quotes?)")
        return
    for head in heads:
        check_head(path, line, head)


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
INLINE = re.compile(r"`(sbt\s[^`\n]+)`")
for p in FILES:
    text = p.read_text(encoding="utf-8")
    for fm in FENCE.finditer(text):
        body_line = line_of(text, fm.start(1))
        for offset, raw in logical_lines(fm.group(1)):
            sm = re.match(r"^\s*(?:[$>]\s*)?sbt\s+(.*?)\s*$", raw)
            if sm and not raw.lstrip().startswith("#") and IGNORE_MARKER not in raw and not HISTORICAL.search(raw):
                arg = re.sub(r"\s+#.*$", "", sm.group(1))
                check_invocation(p.as_posix(), body_line + offset, arg)
    # Inline code outside fences: blank the fenced spans first so a block is not read twice.
    outside = FENCE.sub(lambda mm: "\n" * mm.group(0).count("\n"), text)
    for im in INLINE.finditer(outside):
        if exempt(line_text_at(outside, im.start())):
            continue
        check_invocation(p.as_posix(), line_of(outside, im.start()), im.group(1)[len("sbt"):].strip())

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
print(f"Support matrix verified: Scala {SCALA}, JDK {', '.join(str(j) for j in sorted(ci_jdks))}, "
      f"{len(doc_paths)} documented modules, {len(aliases)} aliases, {len(keys)} tasks and "
      f"{len(loaded_plugins)} plugins known.")
PYEOF
