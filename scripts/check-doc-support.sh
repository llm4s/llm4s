#!/usr/bin/env bash
# Fails when the documented support matrix says something the build does not do.
#
# The Scala 2.13 thread (#874, #888, #1095) started because CLAUDE.md and the docs claimed cross-building,
# `sbt +test` over several versions and a `modules/crossTest/` directory, and the build had none of them.
# #1134 corrected the prose; this keeps it corrected.
#
# The build's side of every comparison comes from sbt itself, never from reading build.sbt's text:
# `sbt "dumpBuildModel <file>"` (project/BuildModel.scala) writes the loaded build as JSON - every project
# with its base directory, aggregates, configurations and defined keys (configuration- and task-scoped
# ones included, so `Docker` exists only where DockerPlugin is enabled), the `ThisBuild` and `Global`
# keys, the keys whose `aggregate` is false, the commands sbt and its plugins define, each alias with its
# body, each project's Scala versions, and its resolved scalacOptions and javacOptions.
#
#   1. Scala  - every project has one `scalaVersion`, and every concrete Scala version in the docs
#               (`Scala 3.x.y`, `Scala 3.x`, `Scala 3`, and `Scala 2.13` just the same) agrees with it as
#               far as it goes. So do `scalaVersion := "..."` and `scala3-library_3` pins in snippets, and
#               `_<binary>` artifact suffixes. Naming another version is fine only to say it is not
#               supported: in the same clause a negation before it (`no Scala 2.13 artifact`) or a deferral
#               or denial after it (`Scala 2.13 support is deferred`, `... is not supported`). Unless a
#               project sets other `crossScalaVersions`, `crossScalaVersions` and `sbt +task` are claims too.
#   2. JDK    - every `JDK N` (also `Java N`, `Java SE N`, `Java 1.N`, `JRE N`, `OpenJDK N`, `Temurin N`,
#               `Corretto N`, `Zulu N`) is a JDK .github/workflows/ci.yml runs, and so is each JDK of a list
#               or range. The floor is the JVM release target the build compiles for (`-release N`,
#               `-release:N`, `--release N`, `--release=N`, `-java-output-version N`, `-target ...` in the
#               resolved scalacOptions or javacOptions), else the oldest JDK CI runs; a target must also be
#               a JDK CI runs. A floor (`JDK N+`, `JDK N or newer`, `at least JDK N`, `JDK >= N`) or a
#               range's start must be that floor, and a ceiling (`JDK N or older`) is the floor or wrong.
#   3. Module - every module in CLAUDE.md's repository-structure block exists on disk, and every project
#               base directory under modules/ is named there (itself or a parent directory).
#   4. sbt    - every `sbt ...` command quoted in the docs, and every alias body, is replayed the way sbt
#               runs it: commands in order from the root project, `project X` switching the project the
#               commands after it run in. Each command must be an alias, an sbt or plugin command, or a key
#               that resolves - through sbt's delegation to the configurations a configuration extends,
#               `ThisBuild` and `Global` - in the project it is scoped to (or the current one), or, when
#               that project aggregates others and the key's `aggregate` is not false, in one of them. A
#               configuration must be one the project has. A backslash-continued command is read as one
#               line, a command after `&&`, `||` or `|` is read too, and one the shell cannot parse fails.
#
# Usage: scripts/check-doc-support.sh [--model FILE] [REPO_ROOT]
#   --model FILE   a model written by `sbt "dumpBuildModel FILE"` (also $DOC_SUPPORT_MODEL). Without one the
#                  script runs sbt in REPO_ROOT to write it, which needs a JDK and sbt (about 20 s).
#   REPO_ROOT      defaults to this script's repository.
#
# Release notes, migration guides and design documents name old versions and commands on purpose and are
# not checked. Elsewhere, a line is exempt from checks 1, 2 and 4 when it says it describes the past
# (`previously`, `formerly`, `no longer`, `dropped`, `used to`, `upgrading from`, `legacy`, `was`, `were`,
# `prior to`, `before v1.2`, a dated entry such as `2025-06-01`), or carries the marker
# `doc-support: ignore` (in an HTML comment in prose, or `# doc-support: ignore` in a code block). A JDK
# ceiling (`JDK N or older`) states what is supported now and is checked even on such a line; only the
# marker exempts it. Exit code 0 = the matrix is true. Non-zero = file:line and the claim, one per line.
set -euo pipefail

MODEL="${DOC_SUPPORT_MODEL:-}"
REPO_ROOT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --model) MODEL="${2:?--model needs a file}"; shift 2 ;;
    --model=*) MODEL="${1#--model=}"; shift ;;
    -h|--help) sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d'; exit 0 ;;
    *) REPO_ROOT="$1"; shift ;;
  esac
done
REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
if [ -n "$MODEL" ]; then
  MODEL="$(cd "$(dirname "$MODEL")" && pwd)/$(basename "$MODEL")"
fi
cd "$REPO_ROOT"

if [ -z "$MODEL" ]; then
  MODEL="$REPO_ROOT/target/build-model.json"
  mkdir -p "$REPO_ROOT/target"
  echo "Writing the build model with sbt (pass --model FILE to reuse one)..." >&2
  if ! sbt_out="$(sbt -batch "dumpBuildModel $MODEL" 2>&1)"; then
    echo "$sbt_out" >&2
    echo "sbt could not write the build model; see above." >&2
    exit 2
  fi
fi
[ -f "$MODEL" ] || { echo "No build model at $MODEL" >&2; exit 2; }

python3 - "$MODEL" <<'PYEOF'
import json
import pathlib
import re
import shlex
import sys

SKIP_DIRS = ("docs/migrations/", "docs/design/", "docs/_data/", "docs/_site/", "docs/superpowers/")
SKIP_FILES = {"docs/reference/migration.md", "docs/reference/release.md", "CHANGELOG.md"}

# build.sbt projects that exist only to forward an old coordinate; they are not modules to document.
UNDOCUMENTED_MODULE_PREFIXES = ("modules/relocations/",)

errors = []

IGNORE_MARKER = "doc-support: ignore"
# Words that say a sentence is about the past, so it may name an old version. Deliberately specific:
# a comparative such as `older` or `earlier` is how a support ceiling is phrased, not a sign of history.
HISTORICAL = re.compile(
    r"\b(?:previously|formerly|no longer|dropped|used to|upgrading from|legacy|was|were|prior to"
    r"|before\s+v?\d+\.\d+|(?:19|20)\d\d-\d\d(?:-\d\d)?)\b", re.I)


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
TEXTS = {p: p.read_text(encoding="utf-8") for p in FILES}

# ---------------------------------------------------------------- the build, as sbt loaded it
MODEL_PATH = sys.argv[1]
try:
    model = json.loads(pathlib.Path(MODEL_PATH).read_text(encoding="utf-8"))
except (OSError, ValueError) as e:
    print(f"Cannot read the build model {MODEL_PATH}: {e}", file=sys.stderr)
    sys.exit(2)
MODEL_NAME = "the build model"


class Project:
    def __init__(self, raw):
        self.id = raw["id"]
        self.base = raw["base"]
        self.aggregate = list(raw.get("aggregate", []))
        self.config_names = {c["name"] for c in raw.get("configurations", [])}
        self.config_by_id = {c["id"]: c["name"] for c in raw.get("configurations", [])}
        self.extends = {c["name"]: list(c.get("extends", [])) for c in raw.get("configurations", [])}
        self.keys = {}                       # key -> {(config name, task axis)}
        for config, task, key in raw.get("keys", []):
            self.keys.setdefault(key, set()).add((config, task))
        self.no_aggregate = set(raw.get("noAggregate", []))
        self.scala = raw.get("scalaVersion", "")
        self.cross = list(raw.get("crossScalaVersions", []))
        self.options = [(f"{kind}/{config}", opts)
                        for kind in ("scalacOptions", "javacOptions")
                        for config, opts in sorted(raw.get(kind, {}).items())]

    def config_name(self, ident):
        """The configuration `ident` names here: an identifier (`Test`, slash syntax) or, in the old
        `config:key` syntax, a name, matched without regard to case as sbt does. None if it has none."""
        if ident in self.config_by_id:
            return self.config_by_id[ident]
        return next((n for n in self.config_names if n.lower() == ident.lower()), None)

    def config_closure(self, name):
        """`name` and every configuration it extends: the configurations sbt delegates it to."""
        seen, todo = set(), [name]
        while todo:
            cur = todo.pop()
            if cur not in seen:
                seen.add(cur)
                todo.extend(self.extends.get(cur, ()))
        return seen


PROJECTS = {raw["id"]: Project(raw) for raw in model["projects"]}
ROOT = model["root"]
SHARED_KEYS = {}                             # ThisBuild and Global: every project delegates to them
for config, task, key in model.get("buildKeys", []) + model.get("globalKeys", []):
    SHARED_KEYS.setdefault(key, set()).add((config, task))
COMMANDS = set(model.get("commands", []))
ALIASES = {a["name"]: a["body"] for a in model.get("aliases", [])}
ALL_KEYS = set(SHARED_KEYS).union(*(p.keys for p in PROJECTS.values()))
ALL_CONFIG_IDS = set().union(*(p.config_by_id for p in PROJECTS.values()))
ALL_CONFIG_NAMES = set().union(*(p.config_names for p in PROJECTS.values()))
SCOPE_AXES = {"ThisBuild", "Global", "Zero"}

# ---------------------------------------------------------------- Scala, from the model
scala_versions = sorted({p.scala for p in PROJECTS.values() if p.scala})
if not scala_versions:
    fail(MODEL_NAME, 1, "no project has a scalaVersion; cannot establish the Scala version")
    SCALA = None
elif len(scala_versions) > 1:
    by_version = {v: sorted(p.id for p in PROJECTS.values() if p.scala == v) for v in scala_versions}
    fail(MODEL_NAME, 1, "projects build with different Scala versions, which docs stating one cannot match: "
         + "; ".join(f"{v} ({', '.join(ids[:4])}{', ...' if len(ids) > 4 else ''})" for v, ids in by_version.items()))
    SCALA = None
else:
    SCALA = scala_versions[0]
CROSS_BUILDS = any(set(p.cross) - {p.scala} for p in PROJECTS.values())

ci = read(".github/workflows/ci.yml")
ci_jdks = set()
for jm in re.finditer(r"^[^#\n]*java-version:\s*([^#\n]*?)\s*(?:#.*)?$", ci, re.M):
    value = jm.group(1).strip("'\"")
    if value.isdigit():
        ci_jdks.add(int(value))
    elif "matrix.java" in value:
        for mm in re.finditer(r"^[^#\n]*\bjava:\s*\[([^\]]*)\]", ci, re.M):
            ci_jdks.update(int(x) for x in re.findall(r"\d+", mm.group(1)))
if not ci_jdks:
    fail(".github/workflows/ci.yml", 1, "no java-version found; cannot establish the JDK CI runs")

# The JVM release target, from the options sbt resolved for each project: `-release 17`, `-release:17`,
# `--release 17`, `--release=17`, `-java-output-version 17`, `-target 17`, `-target:jvm-1.8`, `-Xtarget:8`.
RELEASE_FLAGS = {"-release", "--release", "-java-output-version", "--java-output-version",
                 "-target", "--target", "-Xtarget", "-Xunchecked-java-output-version"}


def release_targets(opts):
    out, i = [], 0
    while i < len(opts):
        opt, value = opts[i], None
        if opt in RELEASE_FLAGS and i + 1 < len(opts):
            value = opts[i + 1]
            i += 1
        else:
            flag, sep, rest = opt.partition("=") if "=" in opt else opt.partition(":")
            if sep and flag in RELEASE_FLAGS:
                value = rest
        i += 1
        if value is not None:
            mm = re.fullmatch(r"(?:jvm-)?(?:1\.)?(\d+)", value.strip())
            if mm:
                out.append(int(mm.group(1)))
    return out


targets = {}                                  # target -> [where]
for p in PROJECTS.values():
    for where, opts in p.options:
        for t in release_targets(opts):
            targets.setdefault(t, []).append(f"{p.id} {where}")
for t, where in sorted(targets.items()):
    if ci_jdks and t != min(ci_jdks):
        fail(MODEL_NAME, 1, f"compiles for JDK {t} ({', '.join(where[:3])}{', ...' if len(where) > 3 else ''}), "
                            f"but the oldest JDK CI runs is {min(ci_jdks)}: the supported floor must be one CI exercises")
JDK_FLOOR = min(targets) if targets else (min(ci_jdks) if ci_jdks else None)
FLOOR_SOURCE = "the build's release target" if targets else "the oldest JDK CI runs"

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
    r"(?P<ceil>\s+(?:or|and)\s+(?:older|earlier|below|lower)|\s*(?:<=|≤))?")


def jdk_number(token):
    token = token.strip()
    return int(token[2:]) if token.startswith("1.") else int(token)


runs = ", ".join(str(j) for j in sorted(ci_jdks))
for p in FILES:
    text = TEXTS[p]
    rel = p.as_posix()
    for jm in JDK_RE.finditer(text):
        line_text = line_text_at(text, jm.start())
        if not ci_jdks or IGNORE_MARKER in line_text:
            continue
        line = line_of(text, jm.start())
        n = jdk_number(jm.group("n"))
        # A ceiling says what runs today, whatever else the line says: checked before the history exemption.
        if jm.group("ceil"):
            if n != JDK_FLOOR:
                fail(rel, line, f"supports JDK {n} or older, but the floor ({FLOOR_SOURCE}) is JDK {JDK_FLOOR}")
            continue
        if HISTORICAL.search(line_text):
            continue
        listed = [jdk_number(x) for x in re.findall(_JV, jm.group("more") or "")]
        hi = jdk_number(jm.group("hi")) if jm.group("hi") else None
        is_floor = bool(jm.group("pre") or jm.group("ge") or jm.group("floor"))
        if is_floor or hi is not None:
            if n != JDK_FLOOR:
                fail(rel, line, f"gives JDK {n} as the minimum, but the minimum supported runtime "
                                f"({FLOOR_SOURCE}) is JDK {JDK_FLOOR}")
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
for proj in sorted(PROJECTS.values(), key=lambda q: q.base):
    mod = proj.base
    if not mod.startswith("modules/") or mod.startswith(UNDOCUMENTED_MODULE_PREFIXES) or not has_content(mod):
        continue                         # the root, relocation stubs and the aggregate `docs` project
    if not any(mod == d or mod.startswith(d + "/") for d in doc_paths):
        fail(MODEL_NAME, 1, f"project `{proj.id}` is {mod}, which CLAUDE.md's repository-structure block does not name")

# ---------------------------------------------------------------- 4. sbt commands, replayed
def split_commands(argument_string):
    """The sbt commands quoted after `sbt`, each a list of words: flags are dropped, each argument is a
    command and `;` separates commands within one. None when the shell cannot parse the line."""
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
        commands.extend(body_commands(token))
    return commands


def body_commands(body):
    """`a; b c` as [[a], [b, c]]; `core / Test / compile` is one word."""
    out = []
    for command in body.split(";"):
        words = command.split()
        if words and words[0] not in {"set", "eval"}:
            words = re.sub(r"\s*/\s*", "/", command).split()
        if words:
            out.append(words)
    return out


def reached(start, key):
    """The projects a key run in `start` runs in: `start`, and what it aggregates, transitively, except
    beneath a project where that key's `aggregate` is false."""
    seen, todo = [], [start]
    while todo:
        cur = todo.pop(0)
        if cur in seen or cur not in PROJECTS:
            continue
        seen.append(cur)
        if key not in PROJECTS[cur].no_aggregate:
            todo.extend(PROJECTS[cur].aggregate)
    return seen


def defined_in(proj, config, task, key):
    """Whether `key` resolves in project `proj` (config: a configuration name or None for any; task: a
    task axis or None), delegating as sbt does: to the configurations `config` extends, then to no
    configuration; from the task axis to none; from the project to ThisBuild and Global."""
    configs = None if config is None else proj.config_closure(config) | {""}
    tasks = {"", task} if task else {""}
    for c, t in proj.keys.get(key, set()) | SHARED_KEYS.get(key, set()):
        if (configs is None or c in configs) and t in tasks:
            return True
    return False


def where_defined(key):
    return sorted(p.id for p in PROJECTS.values() if key in p.keys)


def parse_key(word, current):
    """`[project/][Config/][task/]key` or the old `[project/][config:][task::]key`, as
    (project, config ident or None, task or None, key, project named explicitly), or an error string."""
    explicit = False
    project = current
    segments = word.split("/")
    if len(segments) > 1 and (segments[0] in PROJECTS or segments[0] in SCOPE_AXES):
        project, segments, explicit = segments[0], segments[1:], True
    elif len(segments) > 1 and segments[0] not in ALL_CONFIG_IDS and segments[0] not in ALL_KEYS:
        return f"names project `{segments[0]}`, which the build does not define"
    rest = "/".join(segments)
    config = task = None
    if "::" in rest:
        task, rest = rest.rsplit("::", 1)
    if re.fullmatch(r"[\w.-]+:[\w./:-]+", rest) and "/" not in rest.split(":", 1)[0]:
        config, rest = rest.split(":", 1)
    segments = rest.split("/")
    if len(segments) > 1 and config is None and (segments[0] in ALL_CONFIG_IDS or segments[0] not in ALL_KEYS):
        config, segments = segments[0], segments[1:]
    if len(segments) > 2:
        return "has more scope axes than sbt's project/Config/task/key"
    if len(segments) == 2:
        task, segments = segments[0], segments[1:]
    return project, config, task, segments[0], explicit


def check_key(word, current):
    """None if `word` runs from `current`; otherwise why not."""
    parsed = parse_key(word, current)
    if isinstance(parsed, str):
        return parsed
    project, config, task, key, explicit = parsed
    if key not in ALL_KEYS:
        return f"`{key}` is not an alias, a command, nor a task or setting the build defines"
    if task is not None and task not in ALL_KEYS:
        return f"`{task}` is not a task or setting the build defines"
    if project in SCOPE_AXES:
        configs_ok = config is None or config in ALL_CONFIG_IDS or config.lower() in {n.lower() for n in ALL_CONFIG_NAMES}
        tasks = {"", task} if task else {""}
        if configs_ok and any(t in tasks for _, t in SHARED_KEYS.get(key, set())):
            return None
        return f"`{key}` is not defined in {project}"
    targets = reached(project, key)
    has_config = [q for q in targets if config is None or PROJECTS[q].config_name(config) is not None]
    if not has_config:
        owners = sorted(q.id for q in PROJECTS.values() if q.config_name(config) is not None)
        return (f"configuration `{config}` is not defined in `{project}`"
                + (f" or what it aggregates" if len(targets) > 1 else "")
                + (f" (only in {', '.join(owners[:6])}{', ...' if len(owners) > 6 else ''})" if owners
                   else ", nor in any project the build defines"))
    for q in has_config:
        proj = PROJECTS[q]
        if defined_in(proj, None if config is None else proj.config_name(config), task, key):
            return None
    scope = "/".join(x for x in (config, task, key) if x)
    owners = where_defined(key)
    where = f"; it is defined in {', '.join(owners[:6])}{', ...' if len(owners) > 6 else ''}" if owners else ""
    if not explicit and len(targets) > 1:
        return f"`{scope}` is not defined in the current project `{project}` nor any project it aggregates{where}"
    if not explicit:
        return f"`{scope}` is not defined in the current project `{project}`{where}"
    return f"`{scope}` is not defined in `{project}`" + (" or what it aggregates" if len(targets) > 1 else "") + where


def replay(path, line, commands, shown):
    """Run `commands` through the model as sbt would, from the root project."""
    current = ROOT
    for words in commands:
        head = " ".join(words)
        first = words[0]
        if first.startswith("++"):
            version = first[2:] or (words[1] if len(words) > 1 else "")
            if SCALA and version and version[0].isdigit() and version.rstrip("!") != SCALA:
                fail(path, line, f"{shown}: `{head}` switches to Scala {version.rstrip('!')}, "
                                 f"but the build is Scala {SCALA}")
            continue
        if first.startswith("+"):
            if not CROSS_BUILDS:
                fail(path, line, f"{shown}: `{head}` cross-builds, but no project sets other crossScalaVersions")
            words = ([first[1:]] if first[1:] else []) + words[1:]
        while words and words[0] in {"~", "show"}:      # triggered execution and `show` prefix a key
            words = words[1:]
        if words and words[0].startswith("~"):
            words = [words[0][1:]] + words[1:]
        if not words or not words[0] or re.fullmatch(r"[^\w{]+", words[0]):
            continue
        first = words[0]
        if first == "project":
            if len(words) > 1:
                target = words[1]
                if target in PROJECTS:
                    current = target
                elif target == "/":
                    current = ROOT
                elif not target.startswith("{") and target not in {"..", "-"}:
                    fail(path, line, f"{shown}: `{head}` names project `{target}`, which the build does not define")
            continue
        if first in ALIASES or first in COMMANDS:
            continue
        problem = check_key(first, current)
        if problem:
            fail(path, line, f"{shown}: {problem}")


def check_invocation(path, line, argument_string):
    commands = split_commands(argument_string)
    if commands is None:
        fail(path, line, f"`sbt {argument_string}` cannot be parsed by the shell (unbalanced quotes?)")
        return
    replay(path, line, commands, f"`sbt {argument_string}`")


# An alias is a command line too: each command of its body (as sbt holds it, whatever Scala computed it)
# must run from the root, so an alias whose task was removed fails even when no document quotes it.
for name, body in sorted(ALIASES.items()):
    replay(MODEL_NAME, 1, body_commands(body), f"alias `{name}` (`{body.strip()}`)")


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
            if raw.lstrip().startswith("#") or exempt(raw):
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
    print(f"{len(errors)} stale claim(s). Fix the docs, or the build if the docs are right. The build's side "
          f"comes from `sbt \"dumpBuildModel <file>\"`; a model older than the build is regenerated by running "
          f"this script without --model. For a document that describes the past, mark the line "
          f"`doc-support: ignore`.", file=sys.stderr)
    sys.exit(1)
key_count = len(ALL_KEYS)
print(f"Support matrix verified: Scala {SCALA}, JDK {', '.join(str(j) for j in sorted(ci_jdks))} "
      f"(floor {JDK_FLOOR}, {FLOOR_SOURCE}), {len(doc_paths)} documented modules, {len(PROJECTS)} projects, "
      f"{len(ALIASES)} aliases, {len(COMMANDS)} commands and {key_count} keys known.")
PYEOF
