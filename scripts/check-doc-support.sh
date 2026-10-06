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
#               or denial after it (`Scala 2.13 support is deferred`, `... is planned`, `... is not
#               supported`, `Scala 2 projects cannot ...`). Unless a project sets other
#               `crossScalaVersions`, `crossScalaVersions` and `sbt +task` are claims too.
#   2. JDK    - every `JDK N` (also `JDKs N`, `Java N`, `Java SE N`, `Java 1.N`, `JRE N`, `OpenJDK N`,
#               `Temurin N`, `Corretto N`, `Zulu N`, with or without `LTS`) is a JDK .github/workflows/ci.yml
#               runs, and so is each JDK of a list (`JDK 21 and 25`) and every release of a range (`JDK 21-25`,
#               `21 through 25`, `between JDK 21 and 25`). The floor is the JVM release target the build
#               compiles for (`-release N`, `-release:N`, `--release N`, `--release=N`,
#               `-java-output-version N`, `-target ...` in the resolved scalacOptions or javacOptions), else
#               the oldest JDK CI runs; a target must also be a JDK CI runs. A floor (`JDK N+`, `JDK N or
#               newer/later/above/higher`, `JDK N and up`, `from JDK N onwards`, `at least JDK N`, `minimum
#               JDK: N`, `JDK >= N`, `JDK > N-1`, `newer than JDK N-1`, `no older than JDK N`) or a range's
#               start must be that floor. A ceiling (`JDK N or older/earlier/below/lower`, `up to JDK N`, `at
#               most JDK N`, `JDK <= N`, `older than JDK N+1`, `no newer than JDK N`) claims every older JDK
#               too, so it always reaches below the floor and always fails. A JDK may be named to say it is
#               not supported, in the same clause: a negation before it or a denial after it, as for Scala
#               (`does not support JDK 20 or older`, `JDK 17 is not supported`). Such a statement is not
#               checked, except that a negated ceiling must stay below the floor (`JDK 21 or older is not
#               supported` fails when the floor is 21).
#   3. Module - every module in CLAUDE.md's repository-structure block exists on disk, and every project
#               base directory under modules/ is named there (itself or a parent directory).
#   4. sbt    - every `sbt ...` command quoted in the docs, and every alias body, is replayed the way sbt
#               runs it: commands in order from the root project, `project X` switching the project the
#               commands after it run in. Each command must be an alias, an sbt or plugin command, or a key
#               that resolves - through sbt's delegation to the configurations a configuration extends,
#               `ThisBuild` and `Global` - in the project it is scoped to (or the current one), or, when
#               that project aggregates others and the key's `aggregate` is not false, in one of them. A
#               configuration must be one the project has. An sbt command is found wherever `sbt`, `sbtn`
#               or `./sbt` is the command word of a simple command: at the start of a line, after a shell
#               separator outside quotes (`;`, `&&`, `||`, `|`, `&`, `(`, `$(`, a backtick - so a quoted
#               `echo "a; sbt x"` is no command), inside a `$(...)` or backtick substitution even within double
#               quotes (`v="$(sbt x)"`; not within single quotes), after `VAR=x` assignments, `env`, `time`,
#               `exec`, `sudo`, `nohup`, shell keywords (`if`, `then`, `do`) or Dockerfile `RUN`, and as the
#               value of a YAML command entry (`run: sbt test`, `- run: ...`, `command: ...`, a `- sbt test` list item, and
#               the script of a `run: |` / `run: >` block scalar). Any other `key: value` line is data.
#               Redirections (`2>&1`, `> log`) are not arguments. A backslash-continued command is read as
#               one line, and one the shell cannot parse fails.
#               An alias replays its body inline, in the project current when it runs (aliases inside it
#               too, an alias that runs itself fails), and a `project X` in the body stays in effect after
#               it; every alias body is also replayed from the root on its own, quoted or not. `set` and
#               `set every` must name, left of `:=` / `+=` / `++=` / `-=` / `--=` / `~=`, a key that
#               resolves where the expression scopes it: `[ThisBuild|Global|Zero|project /][Config /]
#               [task /]key` or `key in (project, Config, task)`, in the current project without
#               aggregation; at ThisBuild, Global or with `every` some scope must define it. A left side
#               in neither form fails. `inspect [tree|uses|definitions|actual] KEY`, `last KEY` and
#               `export KEY` must name a key that resolves like a task does.
#
#               Not checked, on purpose - the build model cannot answer them, or only by running code:
#                 - Scala expressions: the right side of `set`, and all of `eval`;
#                 - test and main class names: `testOnly` / `testQuick` / `runMain` arguments, `run` and
#                   `Jmh/run` arguments, and the arguments of any other input task;
#                 - `sbt new` templates, `help` / `about` / `settings` / `tasks` arguments, `alias` definitions,
#                   `++` / `+` beyond the Scala version they name, and arguments given after an alias;
#                 - commands that are not on an `sbt` / `sbtn` / `./sbt` command line: sbt shell prompts
#                   (`sbt:llm4s> test`), task names in prose, and words starting with `$` or `<` (placeholders).
#               A `#` starts a comment only where bash would read one: at the start of a word, outside quotes.
#
# The Scala and JDK prose checks share one claim grammar (`version_claim_re`), matched without regard to case:
# a subject word, a version, and an optional range, list (`,`, `, and`, `, or`, `and`, `or`, `&`, `/`), floor
# or ceiling. They are pattern matches, not a parser. Out of scope, so mark the line
# `doc-support: ignore` or rephrase: versions with a patch or update number (`JDK 21.0.2`, `Java 8u392`);
# versions without a number (`the latest LTS`, `the current JDK`); `since` / `starting with` (a feature's
# start or a floor?); a denial in a separate clause (`Scala 2.13, which is not supported`) or implied rather
# than stated (`Scala 2.13 users must upgrade`); past tense other than the history words below
# (`supported Scala 2.13 until 0.4`); and a negation that does not deny support (`you do not need JDK 25`
# reads as a denial and is not checked).
#
# Usage: scripts/check-doc-support.sh [--model FILE] [REPO_ROOT]
#   --model FILE   a model written by `sbt "dumpBuildModel FILE"` (also $DOC_SUPPORT_MODEL). Without one the
#                  script runs sbt in REPO_ROOT to write it, which needs a JDK and sbt (about 20 s).
#   REPO_ROOT      defaults to this script's repository.
#
# Release notes, migration guides and design documents name old versions and commands on purpose and are
# not checked. Elsewhere, a line is exempt from checks 1, 2 and 4 when it says it describes the past
# (`previously`, `formerly`, `no longer`, `dropped`, `used to`, `upgrading from`, `legacy`, `was`, `were`,
# `ran`, `prior to`, `before v1.2`, a dated entry such as `2025-06-01`), or carries the marker
# `doc-support: ignore` (in an HTML comment in prose, or `# doc-support: ignore` in a code block). A JDK
# ceiling (`JDK N or older`), denied or not, states what is supported now and is checked even on such a
# line; only the marker exempts it. Exit code 0 = the matrix is true. Non-zero = file:line and the claim,
# one per line.
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
    r"\b(?:previously|formerly|no longer|dropped|used to|upgrading from|legacy|was|were|ran|prior to"
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

# ---------------------------------------------------------------- the version-claim grammar
# Scala and JDK prose is read with one grammar, matched without regard to case (`JDK 21`, `jdk 21`,
# `SUPPORTS JAVA 17`), only the subject word and the shape of a version differing:
#
#   claim   := [prefix] SUBJECT [joiner] VERSION [`LTS`] [`only`] [`(` VERSION `)`] [range] [list] [floor | ceiling]
#   prefix  := `at least` | `minimum [of]` | `min.`                      (a floor)
#            | `up to [and including]` | `at most` | `maximum [of]` | `max.`   (a ceiling)
#            | `between` | [`no` | `not`] (`older` | `newer` | ...) `than` [`a` | `an` | `the`]
#   joiner  := `>=` | `>` | `<=` | `<` | [`no` | `not`] (`older` | `newer` | ...) `than` | `between` | `:` | ` ` | `-`
#   range   := (`-` | `–` | `to` | `through` | `thru`) VERSION
#   list    := (SEP [SUBJECT] VERSION)*  where SEP is `,` | `, and` | `, or` | `, &` | `and` | `or` | `&` | `/`
#   floor   := `+` | [`,`] (`or` | `and`) [`any` | `a`] (`newer` | `later` | `above` | `up` | ...) | `minimum` | `onwards`
#   ceiling := [`,`] (`or` | `and`) [`any` | `an`] (`older` | `earlier` | `below` | `lower`)
#
# A subject is a word of its own - not after a letter, digit, `.`, `/` or `-`, nor followed by a letter - and a
# version is not followed by a letter or another version part, so `JavaScript 5`, `java.util`,
# `java-version: 21`, `jdk17compat`, `scala-library`, a file name (`Foo.scala:42-68`, `Foo.java:42`), a path
# (`src/main/scala-3`) and an image tag (`eclipse-temurin:21`) are not claims, while `JDK21` is.
_CMP = r"(?:(?:no|not)\s+)?(?:older|earlier|lower|newer|later|higher|greater)\s+than"
_LIST_SEP = r"(?:\s*,\s*(?:(?:and|or|&)\s+)?|\s*[/&]\s*|\s+(?:and|or)\s+)"


def version_claim_re(subject, version, joiner):
    """The claim grammar above for one SUBJECT and VERSION; `joiner` is the plain separator between them."""
    return re.compile(
        r"(?:(?P<pre>\b(?:at\s+least|minimum(?:\s+of)?|min\.?)\s+)"
        r"|(?P<precap>\b(?:up\s+to(?:\s+and\s+including)?|at\s+most|maximum(?:\s+of)?|max\.?)\s+)"
        r"|(?P<between>\bbetween\s+)"
        rf"|(?P<cmp>\b{_CMP}\s+(?:(?:a|an|the)\s+)?))?"
        rf"(?P<subject>{subject})"
        rf"(?:\s*(?P<op>>=|≥|<=|≤|>|<)\s*|\s+(?P<cmp2>{_CMP})\s+|\s+(?P<between2>between)\s+|\s*:\s*|{joiner})"
        rf"(?P<n>{version})"
        r"(?:\s*\(?LTS\)?(?![a-z]))?"
        r"(?:[ -]only\b)?"
        rf"(?:\s*\((?P<paren>{version})\))?"
        rf"(?:\s*(?:-|–|\bto\b|\bthrough\b|\bthru\b)\s*(?P<hi>{version}))?"
        rf"(?P<more>(?:{_LIST_SEP}(?:{subject}{joiner})?{version}(?!\s*[-–]\s*\d))*)"
        r"(?P<floor>\+|,?\s+(?:or|and)\s+(?:any\s+|a\s+)?(?:newer|later|above|higher|greater|up|beyond)"
        r"|\s+(?:minimum|onwards?))?"
        r"(?P<ceil>,?\s+(?:or|and)\s+(?:any\s+|an\s+)?(?:older|earlier|below|lower))?",
        re.I)


# A JDK: `21`, `1.8`; a Scala version: `3`, `3.7`, `3.7.1`, `3.x`, `v3.7.1`. Neither may run on into a longer
# version or a word (`JDK 21.0.2`, `Scala 3rd`).
_JV = r"(?:1\.)?\d{1,2}(?!\d)(?!\.\d)(?![a-z])"
_SV = r"v?\d+(?:\.(?:\d+|x)){0,2}(?!\d)(?!\.\d)(?![a-z])"
_JDK_WORD = r"(?<![\w./-])(?:OpenJDK|JDK|JRE|Java(?:\s+SE)?|Temurin|Corretto|Zulu)s?(?![a-z])(?:\s+(?:versions?|releases?)\b)?"
_SCALA_WORD = r"(?<![\w./-])Scala(?![a-z])(?:\s+versions?\b)?"
JDK_RE = version_claim_re(_JDK_WORD, _JV, r"[ -]?")
SCALA_RE = version_claim_re(_SCALA_WORD, _SV, r"[ -]")


def listed_versions(m, version):
    """The versions a claim names after its first, other than a range's end: a parenthesised one and the list."""
    return ([m.group("paren")] if m.group("paren") else []) + re.findall(version, m.group("more") or "", re.I)


def claimed_versions(m, version):
    """Every version a claim names: the first, a range's end, and the listed ones."""
    return [m.group("n")] + ([m.group("hi")] if m.group("hi") else []) + listed_versions(m, version)


# ---------------------------------------------------------------- 1. Scala
# A version other than the build's may be named only to say it is not supported, in the same clause:
# a negation before it (`no Scala 2.13 artifact`, `do not rewrite ... to Scala 2.13`), or a deferral or
# denial after it (`Scala 2.13 support is deferred`, `Scala 2 is not supported`).
CLAUSE_BREAK = re.compile(r"(?<!\d)\.(?!\d)|\.(?=\s|$)|[;,|()—–:!?]| - |\bbut\b|\bwhile\b|\bwhereas\b", re.I)
NEG_BEFORE = re.compile(r"\b(?:not|no|never|deferred|without|nor|cannot|can't|don't|doesn't|isn't|aren't|won't)\b", re.I)
NEG_AFTER = re.compile(
    r"\b(?:deferred|unsupported|(?:is|are|was|were|will be)\s+not|isn't|aren't|wasn't|won't|never|"
    r"(?:does|do|will)\s+not|doesn't|don't|cannot|can't|planned|"
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


# `Scala 3`, `Scala 3.7`, `Scala 3.7.1`, `Scala 3.x`, `Scala-3-only`, `Scala 3 only (3.7.1)`, `scala 3`.
# A list (`Scala 3.7.1 and 2.13`, `Scala versions 2.13, 3, and 3.7`) or range (`Scala 3.3-3.7`) is a claim
# about each of its versions, and so is a floor or ceiling (`Scala 3.3+`): each must be the build's version.
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
        versions = [v.lower().lstrip("v") for v in claimed_versions(sm, _SV)]
        claims = [v.split(".") for v in versions]
        if rel == "docs/reference/v1-scope.md" and SCALA in versions:
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
# One mention of a JDK, with what is said about it: a floor (`JDK 21+`, `at least Java 21`, `JDK >= 21`,
# `JDK 21 or newer`, `newer than JDK 20`, `no older than JDK 21`), a ceiling (`JDK 25 or older`, `up to JDK 25`,
# `at most JDK 25`, `JDK <= 25`, `older than JDK 26`, `no newer than JDK 25`), a range (`JDK 21-25`,
# `JDK 21 through 25`, `between JDK 21 and 25`) or a JDK and a list (`JDK 21 and 25`, `JDKs 21, 25`,
# `JDK 21, 25, and 29`, `jdk 21`). JDK_RE is the claim grammar above with the JDK subject words.
# A JDK named only to say it is not supported, in the same clause: a negation before it (`does not support
# JDK 20 or older`, `no JDK 17 build`) or a denial after it (`JDK 20 or older is not supported`).
JDK_NEG_BEFORE = re.compile(
    r"\b(?:not|no|never|nor|cannot|can't|don't|doesn't|isn't|aren't|won't|unsupported)\b", re.I)


def jdk_number(token):
    token = token.strip()
    return int(token[2:]) if token.startswith("1.") else int(token)


def jdk_claim(jm):
    """(kind, numbers) for one mention: ("floor", [n, listed...]), ("ceiling", [n]) - every JDK up to and
    including n -, ("range", [lo, hi]) or ("points", [n, ...]). A strict comparison moves by one: `newer
    than JDK 20` is a floor of 21, `older than JDK 22` a ceiling of 21; `no older than JDK 21` is a floor of 21."""
    n = jdk_number(jm.group("n"))
    listed = [jdk_number(x) for x in listed_versions(jm, _JV)]
    words = (jm.group("cmp") or jm.group("cmp2") or "").lower().split()
    if words:
        negated = words[0] in {"no", "not"}
        newer = (words[1] if negated else words[0]) in {"newer", "later", "higher", "greater"}
        if newer:
            return ("ceiling", [n]) if negated else ("floor", [n + 1])
        return ("floor", [n]) if negated else ("ceiling", [n - 1])
    op = jm.group("op")
    if jm.group("pre") or op in {">=", "≥"} or jm.group("floor"):
        return "floor", [n] + listed          # `JDK 21, 25 or newer`: the floor, and each JDK listed
    if op == ">":
        return "floor", [n + 1]
    if jm.group("precap") or op in {"<=", "≤"} or jm.group("ceil"):
        return "ceiling", [n]
    if op == "<":
        return "ceiling", [n - 1]
    if jm.group("hi"):
        return "range", sorted([n, jdk_number(jm.group("hi"))])
    if (jm.group("between") or jm.group("between2")) and listed:
        return "range", sorted([n, listed[0]])
    return "points", [n] + listed


def jdk_negated(text, jm):
    ls = text.rfind("\n", 0, jm.start()) + 1
    before, after = clause_around(line_text_at(text, jm.start()), jm.start() - ls, jm.end() - ls)
    return JDK_NEG_BEFORE.search(before) is not None or NEG_AFTER.search(after) is not None


runs = ", ".join(str(j) for j in sorted(ci_jdks))
for p in FILES:
    text = TEXTS[p]
    rel = p.as_posix()
    for jm in JDK_RE.finditer(text):
        line_text = line_text_at(text, jm.start())
        if not ci_jdks or IGNORE_MARKER in line_text:
            continue
        line = line_of(text, jm.start())
        kind, nums = jdk_claim(jm)
        negated = jdk_negated(text, jm)
        # A ceiling says what runs today, whatever else the line says: checked before the history exemption.
        if kind == "ceiling":
            c = nums[0]
            if not negated:
                # `JDK N or older` claims every JDK below N as well, so it always reaches below the floor.
                fail(rel, line, f"supports JDK {c} or older, which claims JDKs below the floor ({FLOOR_SOURCE}), "
                                f"JDK {JDK_FLOOR}; state the floor instead (`JDK {JDK_FLOOR}+`)")
            elif c >= JDK_FLOOR:
                fail(rel, line, f"says JDK {c} and older are unsupported, but the floor ({FLOOR_SOURCE}) "
                                f"is JDK {JDK_FLOOR}")
            continue
        if negated or HISTORICAL.search(line_text):
            continue
        if kind == "floor":
            n = nums[0]
            if n != JDK_FLOOR:
                fail(rel, line, f"gives JDK {n} as the minimum, but the minimum supported runtime "
                                f"({FLOOR_SOURCE}) is JDK {JDK_FLOOR}")
            if n > max(ci_jdks):
                fail(rel, line, f"requires JDK {n} or newer, but CI only runs JDK {runs}")
            for other in nums[1:]:
                if other not in ci_jdks:
                    fail(rel, line, f"documents JDK {other}, but CI runs JDK {runs}")
        elif kind == "range":
            lo, hi = nums
            if lo != JDK_FLOOR:
                fail(rel, line, f"gives JDK {lo} as the minimum, but the minimum supported runtime "
                                f"({FLOOR_SOURCE}) is JDK {JDK_FLOOR}")
            missing = [j for j in range(lo, hi + 1) if j not in ci_jdks]
            if missing:
                fail(rel, line, f"documents JDK {lo} to {hi}, but CI does not run JDK "
                                f"{', '.join(str(j) for j in missing)} (it runs JDK {runs})")
        else:
            for other in nums:
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


def sbt_split(body):
    """`body` cut at each `;` outside sbt's own quotes: sbt's parser takes a double-quoted argument, with
    backslash escapes inside it, so `run "explain a;b"` is one command, not `run "explain a` and `b"`."""
    parts, start, quoted, i = [], 0, False, 0
    while i < len(body):
        ch = body[i]
        if quoted and ch == "\\":
            i += 1                       # an escaped character, `\"` included
        elif ch == '"':
            quoted = not quoted
        elif ch == ";" and not quoted:
            parts.append(body[start:i])
            start = i + 1
        i += 1
    parts.append(body[start:])
    return parts


def body_commands(body):
    """`a; b c` as [[a], [b, c]]; `core / Test / compile` is one word."""
    out = []
    for command in sbt_split(body):
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


def check_key(word, current, aggregate=True):
    """None if `word` runs from `current`; otherwise why not. `aggregate=False` for a command that acts
    on the one project it names (or the current one) and not on what that project aggregates."""
    parsed = parse_key(word, current)
    if isinstance(parsed, str):
        return parsed
    return resolve_key(*parsed, aggregate=aggregate)


def resolve_key(project, config, task, key, explicit, aggregate=True):
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
    targets = reached(project, key) if aggregate else [project]
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


# ---- `set`: the scoped key left of the operator must exist where the expression puts it
SET_OPERATORS = ("++=", "--=", ":=", "+=", "-=", "~=")
# A Scala identifier naming a project, configuration or key: `core`, `Test`, `Keys.fork` (qualified),
# `` `it` `` (backquoted), `LocalProject("it")`.
SET_IDENT = r'(?:`[^`]+`|LocalProject\(\s*"[^"]+"\s*\)|[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)'
SET_IN_AXIS = rf"(?:{SET_IDENT}|\(\s*{SET_IDENT}(?:\s*,\s*{SET_IDENT}){{0,2}}\s*\))"


def set_ident(token):
    token = token.strip()
    lp = re.fullmatch(r'LocalProject\(\s*"([^"]+)"\s*\)', token)
    if lp:
        return lp.group(1)
    if token.startswith("`"):
        return token.strip("`")
    return token.rsplit(".", 1)[-1]


def set_lhs(expression):
    """The text before the first top-level `:=`, `+=`, `++=`, `-=`, `--=` or `~=` of a setting expression,
    or None when it has none."""
    depth, quote, i = 0, None, 0
    while i < len(expression):
        ch = expression[i]
        if quote:
            if ch == "\\":
                i += 1
            elif ch == quote:
                quote = None
        elif ch in "\"'":
            quote = ch
        elif ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif depth == 0:
            for op in SET_OPERATORS:
                if expression.startswith(op, i):
                    return expression[:i]
        i += 1
    return None


def classify_axis(axis):
    if axis in SCOPE_AXES or axis in PROJECTS:
        return "project"
    if axis in ALL_CONFIG_IDS:
        return "config"
    if axis in ALL_KEYS:
        return "task"
    return None


def parse_set_scope(lhs, current):
    """`[ThisBuild|Global|Zero|project /][Config /][task /]key` (slash syntax) or `key in X`, `key in (X, Y)`
    (the old `in` syntax), as (project, config, task, key, explicit); an error string for a name that is
    none of those; None when `lhs` is in neither form."""
    lhs = lhs.strip()
    in_form = re.fullmatch(rf"({SET_IDENT})((?:\s+in\s+{SET_IN_AXIS})+)", lhs)
    if in_form:
        key = set_ident(in_form.group(1))
        axes = [set_ident(a) for a in re.findall(SET_IDENT, in_form.group(2)) if a != "in"]
        slots = {}
        for axis in axes:
            slot = classify_axis(axis)
            if slot is None:
                return f"`{axis}` is not a project, configuration or key the build defines"
            if slot in slots:
                return f"`{lhs}` names the {slot} axis twice"
            slots[slot] = axis
        project = slots.get("project")
        return (project or current), slots.get("config"), slots.get("task"), key, project is not None
    parts = lhs.split("/")
    if len(parts) > 4 or not all(re.fullmatch(SET_IDENT, part.strip()) for part in parts):
        return None
    parts = [set_ident(part) for part in parts]
    key, axes = parts[-1], parts[:-1]
    project, explicit, config, task = current, False, None, None
    if axes and (axes[0] in SCOPE_AXES or axes[0] in PROJECTS):
        project, explicit, axes = axes[0], True, axes[1:]
    if axes and (axes[0] in ALL_CONFIG_IDS or axes[0] == "Zero"):
        config, axes = (None if axes[0] == "Zero" else axes[0]), axes[1:]
    if axes and (axes[0] in ALL_KEYS or axes[0] == "Zero"):
        task, axes = (None if axes[0] == "Zero" else axes[0]), axes[1:]
    if axes:
        return f"`{axes[0]}` is not a project, configuration or key the build defines"
    return project, config, task, key, explicit


def check_set(words, current):
    """None if `set [every] <setting>` names a key the build defines where it puts it; otherwise why not.
    A setting applies to the one project it is scoped to (or the current one), so aggregation does not
    count, while ThisBuild, Global and `every` need only some scope of the build to define the key."""
    every = bool(words) and words[0] == "every"
    expression = " ".join(words[1:] if every else words)
    shown = f"`set every {expression}`" if every else f"`set {expression}`"
    lhs = set_lhs(expression)
    if lhs is None:
        return f"{shown}: no `:=`, `+=`, `++=`, `-=`, `--=` or `~=`, so it cannot be checked as a setting"
    parsed = parse_set_scope(lhs, current)
    if parsed is None:
        return (f"{shown}: cannot read `{lhs.strip()}` as `[project /][Config /][task /]key` or `key in (...)`;"
                f" write it in one of those forms, or mark the line `doc-support: ignore`")
    if isinstance(parsed, str):
        return f"{shown}: {parsed}"
    project, config, task, key, explicit = parsed
    if key not in ALL_KEYS:
        return f"{shown}: `{key}` is not a task or setting the build defines"
    if task is not None and task not in ALL_KEYS:
        return f"{shown}: `{task}` is not a task or setting the build defines"
    if every or project in SCOPE_AXES:
        return None
    problem = resolve_key(project, config, task, key, explicit, aggregate=False)
    return f"{shown}: {problem}" if problem else None


INSPECT_MODES = {"tree", "uses", "definitions", "actual"}


def replay(commands, current, report, expanding=(), report_cycle=None):
    """Run `commands` through the model as sbt would, from project `current`, calling `report(problem)` for
    each command that would not run; returns the project current after them. An alias runs its body here,
    in the current project, and a `project X` in that body stays in effect after it, as in sbt. An alias
    that runs itself, directly or not, goes to `report_cycle` (the outermost reporter), never silenced."""
    report_cycle = report_cycle or report
    for words in commands:
        head = " ".join(words)
        first = words[0]
        if first.startswith("++"):
            version = first[2:] or (words[1] if len(words) > 1 else "")
            if SCALA and version and version[0].isdigit() and version.rstrip("!") != SCALA:
                report(f"`{head}` switches to Scala {version.rstrip('!')}, but the build is Scala {SCALA}")
            continue
        if first.startswith("+"):
            if not CROSS_BUILDS:
                report(f"`{head}` cross-builds, but no project sets other crossScalaVersions")
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
                    report(f"`{head}` names project `{target}`, which the build does not define")
            continue
        if first in ALIASES:
            if first in expanding:
                report_cycle(f"alias `{first}` runs itself ({' -> '.join(expanding + (first,))})")
                continue
            # From the root the body replays exactly as the standalone alias check below does, so its problems
            # are reported once, there; from any other project they are reported here, naming that project.
            inner = ((lambda problem: None) if current == ROOT
                     else (lambda problem, name=first, cur=current: report(f"alias `{name}` run in `{cur}`: {problem}")))
            current = replay(body_commands(ALIASES[first]), current, inner, expanding + (first,), report_cycle)
            continue
        if first == "set" and first in COMMANDS:
            problem = check_set(words[1:], current)
            if problem:
                report(problem)
            continue
        if first in {"inspect", "last", "export"} and first in COMMANDS:
            # `inspect [tree|uses|definitions|actual] key` reads one scope, `last key` and `export key` the
            # key's runs: each names a key, which must resolve as it would if run.
            rest = [w for w in words[1:] if not w.startswith("-") and not (first == "inspect" and w in INSPECT_MODES)]
            problem = check_key(rest[0], current, aggregate=first != "inspect") if rest else None
            if problem:
                report(f"`{head}`: {problem}")
            continue
        if first in COMMANDS:
            continue
        problem = check_key(first, current)
        if problem:
            report(problem)
    return current


def check_invocation(path, line, argument_string):
    commands = split_commands(argument_string)
    if commands is None:
        fail(path, line, f"`sbt {argument_string}` cannot be parsed by the shell (unbalanced quotes?)")
        return
    replay(commands, ROOT, lambda problem: fail(path, line, f"`sbt {argument_string}`: {problem}"))


# An alias is a command line too: each command of its body (as sbt holds it, whatever Scala computed it)
# must run from the root, so an alias whose task was removed fails even when no document quotes it.
for name, body in sorted(ALIASES.items()):
    replay(body_commands(body), ROOT,
           lambda problem, shown=f"alias `{name}` (`{body.strip()}`)": fail(MODEL_NAME, 1, f"{shown}: {problem}"),
           (name,))


def strip_comment(line):
    """`line` without its shell comment: a `#` that starts a word outside quotes, as bash reads it. A `#`
    inside quotes (`sbt "run explain #123"`), escaped, or within a word (`a#b`) is kept."""
    quote, i = None, 0
    while i < len(line):
        ch = line[i]
        if quote == "'":
            if ch == "'":
                quote = None
        elif quote == '"':
            if ch == "\\":
                i += 1
            elif ch == '"':
                quote = None
        elif ch == "\\":
            i += 1
        elif ch in "'\"":
            quote = ch
        elif ch == "#" and (i == 0 or line[i - 1] in " \t;&|()<>"):
            return line[:i]
        i += 1
    return line


def shell_segments(line):
    """`line` cut into simple commands, as bash reads them, outside quotes: at `;`, `&&`, `||`, `|`, `|&`,
    a background `&` (not the `&` of a redirection such as `2>&1` or `&>`), `(`, `)`, `$(` and backticks.
    Each piece is returned as written, quotes kept, so a quoted `;` (`echo "a; sbt x"`) stays in its word.
    A command substitution (`$(...)` or a backtick pair) runs its own commands even inside double quotes
    (`v="$(sbt x)"`), so it is entered there too, with quoting starting afresh inside it and the double
    quotes resuming after it; nested substitutions and subshells nest. Single quotes hide everything."""
    out, start, quote, i = [], 0, None, 0
    frames = []                       # (closer, quote to resume): one per open `$(`, `(` or backtick

    def cut(at, width):
        out.append(line[start:at])
        return at + width

    while i < len(line):
        ch = line[i]
        if quote == "'":
            if ch == "'":
                quote = None
        elif ch == "\\":
            i += 1
        elif line.startswith("$(", i):
            frames.append((")", quote))
            quote = None
            i = start = cut(i, 2)
            continue
        elif ch == "`" and quote is None and frames and frames[-1][0] == "`":
            quote = frames.pop()[1]
            i = start = cut(i, 1)
            continue
        elif ch == "`":
            frames.append(("`", quote))
            quote = None
            i = start = cut(i, 1)
            continue
        elif quote == '"':
            if ch == '"':
                quote = None
        elif ch in "'\"":
            quote = ch
        elif line.startswith(("&&", "||", "|&"), i):
            i = start = cut(i, 2)
            continue
        elif ch == "&" and (i > 0 and line[i - 1] in "<>" or line.startswith("&>", i)):
            pass                                         # `2>&1`, `>&2`, `&>file`: a redirection
        elif ch == "(":
            frames.append((")", None))
            i = start = cut(i, 1)
            continue
        elif ch == ")":
            if frames and frames[-1][0] == ")":
                quote = frames.pop()[1]
            i = start = cut(i, 1)
            continue
        elif ch in ";|&":
            i = start = cut(i, 1)
            continue
        i += 1
    out.append(line[start:])
    return out


def shell_words(segment):
    """(raw word, end offset) for each word of `segment`, split at whitespace outside quotes."""
    words, quote, i, begin = [], None, 0, None
    while i < len(segment):
        ch = segment[i]
        if quote is None and ch in " \t":
            if begin is not None:
                words.append((segment[begin:i], i)); begin = None
            i += 1
            continue
        if begin is None:
            begin = i
        if quote == "'":
            if ch == "'":
                quote = None
        elif quote == '"':
            if ch == "\\":
                i += 1
            elif ch == '"':
                quote = None
        elif ch == "\\":
            i += 1
        elif ch in "'\"":
            quote = ch
        i += 1
    if begin is not None:
        words.append((segment[begin:], len(segment)))
    return words


SBT_LAUNCHERS = {"sbt", "sbtn", "./sbt", "./sbtn"}
# Words that run the command after them: shell keywords, a prompt marker, and Dockerfile's RUN.
# `VAR=value` assignments are skipped wherever they lead.
COMMAND_PREFIXES = {"if", "then", "else", "elif", "while", "until", "do", "!", "{", "$", ">", "RUN"}
# Wrappers that run the command after their options, as (short options that take a value, long options that
# take a value, positional arguments before the command). A wrapper not listed here is not guessed at: its
# command word is not found, so nothing is checked rather than the wrong word.
WRAPPERS = {
    "sudo": ("ughpCDRTU", {"--user", "--group", "--host", "--prompt", "--close-from", "--chdir", "--chroot",
                           "--role", "--type", "--command-timeout", "--other-user"}, 0),
    "env": ("uCS", {"--unset", "--chdir", "--split-string"}, 0),
    "nice": ("n", {"--adjustment"}, 0),
    "nohup": ("", set(), 0),
    "time": ("fo", {"--format", "--output"}, 0),
    "exec": ("a", set(), 0),
    "command": ("", set(), 0),
    "timeout": ("ks", {"--kill-after", "--signal"}, 1),    # timeout [OPTION]... DURATION COMMAND
}
ASSIGNMENT = re.compile(r"[A-Za-z_]\w*=")
REDIRECTION = re.compile(r"^(?:\d+|&)?(?:>>?|<<?|>&|<&|>\|)(.*)$")


def skip_wrapper(words, i, wrapper):
    """The index of the first word after `wrapper`'s options and positional arguments, from words[i]. An option
    and its value are consumed together: `-u ci`, `-uci`, `-Eu ci`, `--user ci`, `--user=ci`; `--` ends them."""
    short, long_opts, positional = WRAPPERS[wrapper]
    while i < len(words):
        word = words[i][0]
        if word == "--":
            i += 1
            break
        if word.startswith("--"):
            i += 2 if "=" not in word and word in long_opts else 1
        elif word.startswith("-") and len(word) > 1:
            i += 1
            for k, ch in enumerate(word[1:], 1):
                if ch in short:
                    if k == len(word) - 1:
                        i += 1           # `-u ci`: the value is the next word; in `-uci` it is attached
                    break
        else:
            break
    return min(i + positional, len(words))


def sbt_arguments(segment):
    """The argument string of `segment` when its command word is an sbt launcher, else None. `sbt` must be the
    command word itself - `addSbtPlugin(...)`, `sbt-plugin`, `which sbt` or a path containing sbt are not."""
    words = shell_words(segment)
    i = 0
    while i < len(words):
        word = words[i][0]
        if ASSIGNMENT.match(word) or word in COMMAND_PREFIXES:
            i += 1
        elif word in WRAPPERS:
            i = skip_wrapper(words, i + 1, word)
        else:
            break
    if i >= len(words) or words[i][0] not in SBT_LAUNCHERS:
        return None
    # The arguments as written (quotes kept, for shlex), less redirections: `2>&1`, `> log`, `>>log`, `<in`.
    args, rest = [], iter(w for w, _ in words[i + 1:])
    for word in rest:
        r = REDIRECTION.match(word)
        if r:
            if not r.group(1):
                next(rest, None)                 # `> log`: the target is the next word
            continue
        args.append(word)
    return " ".join(args) or None


# A YAML entry whose value is a shell command: `run: ...`, `- run: ...`, `command: ...`, `script: ...`.
YAML_COMMAND_KEYS = r"(?:run|command|cmd|script|entrypoint|shell_command)"
YAML_COMMAND = re.compile(r"^\s*(?:-\s+)?" + YAML_COMMAND_KEYS + r"\s*:\s+(?=\S)")
YAML_BLOCK = re.compile(r"^(\s*)(?:-\s+)?" + YAML_COMMAND_KEYS + r"\s*:\s*([|>])[-+0-9]*\s*(?:#.*)?$")
YAML_DASH = re.compile(r"^\s*-\s+(?=\S)")
# Any other `key: value` line is data, not a command (`- name: Unit tests (sbt fast)`, a Scala parameter).
YAML_OTHER_KEY = re.compile(r"^\s*(?:-\s+)?[A-Za-z_][\w.-]*\s*:(?:\s|$)")


def yaml_unquote(value):
    """A YAML scalar's text: `"sbt \\"x\\""` and `'sbt ''x'''` unquoted, a plain scalar as it is."""
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] == '"':
        return re.sub(r'\\(.)', r'\1', value[1:-1])
    if len(value) >= 2 and value[0] == value[-1] == "'":
        return value[1:-1].replace("''", "'")
    return value


def sbt_calls(line):
    """The argument string of every sbt invocation on one shell line. The line may be a YAML command entry
    (`run: sbt test`, `- run: "sbt test"`) or a list item (`- sbt test`); a line keyed by anything else
    (`name: ...`) is data, not a command. It is cut into simple commands at shell separators, outside
    quotes, and a `#` comment is dropped where bash would drop it."""
    line = strip_comment(line)
    m = YAML_COMMAND.match(line)
    if not m and YAML_OTHER_KEY.match(line):
        return []
    m = m or YAML_DASH.match(line)
    if m:
        line = strip_comment(yaml_unquote(line[m.end():]))
    return [args for args in map(sbt_arguments, shell_segments(line)) if args]


def logical_lines(lines):
    """(offset, line) for each shell line of `lines` ((offset, text) pairs), a backslash-continued line
    joined with the lines it continues onto, so `sbt -Dk=v \\` followed by `"run"` is read as one invocation."""
    out, pending, start = [], None, 0
    for offset, raw in lines:
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


def shell_lines(body):
    """(offset, line) for each shell line of a code block. A YAML block scalar under a command key (`run: |`
    or `run: >` and the lines indented under it) is a script: a literal one is read line by line, a folded
    one as its lines joined with spaces. Every other line is read as a shell line."""
    raw = body.splitlines()
    out, i = [], 0
    while i < len(raw):
        m = YAML_BLOCK.match(raw[i])
        if not m:
            out.append((i, raw[i]))
            i += 1
            continue
        indent, style = len(m.group(1)), m.group(2)
        j, content = i + 1, []
        while j < len(raw) and (not raw[j].strip() or len(raw[j]) - len(raw[j].lstrip()) > indent):
            content.append((j, raw[j]))
            j += 1
        while content and not content[-1][1].strip():
            content.pop()
        if style == ">":
            text = " ".join(t.strip() for _, t in content if t.strip())
            if content:
                out.append((content[0][0], text))
        else:
            out.extend(content)
        i = j
    return logical_lines(out)


FENCE = re.compile(r"```[^\n]*\n(.*?)```", re.S)
INLINE = re.compile(r"`([^`\n]*\bsbtn?\s[^`\n]+)`")
for p in FILES:
    text = TEXTS[p]
    for fm in FENCE.finditer(text):
        body_line = line_of(text, fm.start(1))
        for offset, raw in shell_lines(fm.group(1)):
            if raw.lstrip().startswith("#") or exempt(raw):
                continue
            for args in sbt_calls(raw):
                check_invocation(p.as_posix(), body_line + offset, args)
    # Inline code outside fences: blank the fenced spans first so a block is not read twice.
    outside = FENCE.sub(lambda mm: "\n" * mm.group(0).count("\n"), text)
    for im in INLINE.finditer(outside):
        if exempt(line_text_at(outside, im.start())):
            continue
        for args in sbt_calls(im.group(1)):
            check_invocation(p.as_posix(), line_of(outside, im.start()), args)

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
