#!/usr/bin/env bash
# Fails when the documented support matrix says something the build does not do.
#
# The Scala 2.13 thread (#874, #888, #1095) started because CLAUDE.md and the docs claimed cross-building,
# `sbt +test` over several versions and a `modules/crossTest/` directory, and the build had none of them.
# #1134 corrected the prose; this keeps it corrected. It checks the handful of documented facts that can
# be compared with the build, not the prose around them:
#
#   1. Scala  - every `Scala 3.x.y` in the docs equals `scala3` in project/Dependencies.scala, and
#               build.sbt's scalaVersion is that value.
#   2. JDK    - every `JDK N` in the docs is a JDK that .github/workflows/ci.yml runs.
#   3. Module - every module in CLAUDE.md's repository-structure block exists on disk, and every module
#               build.sbt defines is named there (itself or a parent directory).
#   4. sbt    - every `sbt ...` command quoted in the docs is a build alias, a task the build defines,
#               an sbt built-in, or a task in a project the build defines.
#
# Usage: scripts/check-doc-support.sh [REPO_ROOT]    (the root defaults to this script's repository)
# Release notes, migration guides and design documents name old versions and commands on purpose and
# are not checked. Exit code 0 = the matrix is true. Non-zero = file:line and the claim, one per line.
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

# What `sbt <word>` may be without the build defining it: sbt itself and the plugins the build loads.
SBT_BUILTINS = {
    "clean", "compile", "test", "testOnly", "testQuick", "run", "runMain", "console", "consoleQuick",
    "package", "publish", "publishLocal", "publishM2", "publishSigned", "doc", "update", "reload",
    "projects", "project", "tasks", "settings", "show", "inspect", "set", "new", "exit", "help",
    "scalafmt", "scalafmtAll", "scalafmtCheck", "scalafmtCheckAll", "scalafmtSbt", "scalafmtSbtCheck",
    "scalafix", "scalafixAll", "coverage", "coverageOff", "coverageReport", "coverageAggregate",
    "dependencyTree", "dependencyUpdates", "dependencyBrowseTree", "mimaReportBinaryIssues",
    "version", "name", "scalaVersion", "stage", "assembly", "evicted", "dumpLicenseReport", "ci-release", "sonatypeBundleRelease",
}
SCOPE_WORDS = {"ThisBuild", "Global", "Test", "Compile", "Docker", "IntegrationTest", "Runtime"}

errors = []


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
for p in FILES:
    text = p.read_text(encoding="utf-8")
    for sm in re.finditer(r"Scala (?:3 only \()?(3\.\d+\.\d+)", text):
        found = sm.group(1)
        if p.as_posix() == "docs/reference/v1-scope.md" and found == SCALA:
            canonical_has_scala = True
        if SCALA and found != SCALA:
            fail(p.as_posix(), line_of(text, sm.start()),
                 f"documents Scala {found}, but the build is Scala {SCALA}")
if SCALA and not canonical_has_scala:
    fail("docs/reference/v1-scope.md", 1, f"does not state Scala {SCALA}, which the build uses")

# ---------------------------------------------------------------- 2. JDK
for p in FILES:
    text = p.read_text(encoding="utf-8")
    for jm in re.finditer(r"\bJDK ?(\d{2})(\+?)", text):
        n = int(jm.group(1))
        if ci_jdks and n not in ci_jdks:
            fail(p.as_posix(), line_of(text, jm.start()),
                 f"documents JDK {n}{jm.group(2)}, but CI runs JDK {', '.join(str(j) for j in sorted(ci_jdks))}")

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
for src in [build] + [pathlib.Path(q).read_text(encoding="utf-8") for q in sorted(pathlib.Path("project").glob("*.scala"))]:
    keys.update(re.findall(r"(\w+)\s*(?::\s*[\w\[\]]+\s*)?=\s*(?:taskKey|settingKey|inputKey)\b", src))
projects = set(re.findall(r"lazy val (\w+)\s*=\s*\(?\s*project\b", build))


def command_heads(argument_string):
    """The sbt command words quoted after `sbt`: flags are dropped, `;` separates commands."""
    try:
        tokens = shlex.split(argument_string)
    except ValueError:
        return []
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


def check_head(path, line, head):
    word = head.lstrip("+~")          # `+test`, `++3.7.1` cross-build and `~test` triggered execution
    if word == "" or word[0].isdigit() or re.fullmatch(r"[^\w]+", word):
        return
    if re.fullmatch(r"[\w.-]+:[\w-]+", word):   # config:task such as docker:publishLocal
        word = word.split(":", 1)[1]
    if "/" in word:
        scope = word.split("/", 1)[0]
        if scope not in projects and scope not in SCOPE_WORDS:
            fail(path, line, f"`sbt {head}` names project `{scope}`, which build.sbt does not define")
        return
    if word in aliases or word in keys or word in SBT_BUILTINS:
        return
    fail(path, line, f"`sbt {head}`: `{word}` is not an alias or task the build defines, nor an sbt built-in")


FENCE = re.compile(r"```[^\n]*\n(.*?)```", re.S)
INLINE = re.compile(r"`(sbt\s[^`\n]+)`")
for p in FILES:
    text = p.read_text(encoding="utf-8")
    for fm in FENCE.finditer(text):
        body_line = line_of(text, fm.start(1))
        for offset, raw in enumerate(fm.group(1).splitlines()):
            sm = re.match(r"^\s*(?:[$>]\s*)?sbt\s+(.*?)\s*\\?$", raw)
            if sm and not raw.lstrip().startswith("#"):
                arg = re.sub(r"\s+#.*$", "", sm.group(1))
                for head in command_heads(arg):
                    check_head(p.as_posix(), body_line + offset, head)
    # Inline code outside fences: blank the fenced spans first so a block is not read twice.
    outside = FENCE.sub(lambda mm: "\n" * mm.group(0).count("\n"), text)
    for im in INLINE.finditer(outside):
        for head in command_heads(im.group(1)[len("sbt"):].strip()):
            check_head(p.as_posix(), line_of(outside, im.start()), head)

if errors:
    print("The documented support matrix does not match the build:", file=sys.stderr)
    for e in errors:
        print("  " + e, file=sys.stderr)
    print(f"{len(errors)} stale claim(s). Fix the docs, or the build if the docs are right.", file=sys.stderr)
    sys.exit(1)
print(f"Support matrix verified: Scala {SCALA}, JDK {', '.join(str(j) for j in sorted(ci_jdks))}, "
      f"{len(doc_paths)} documented modules, {len(aliases)} aliases and {len(keys)} tasks known.")
PYEOF
