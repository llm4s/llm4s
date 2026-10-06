#!/usr/bin/env bash
# Fails when the documented support matrix says something the build does not do (#967).
#
# The Scala 2.13 thread (#874, #888, #1095) started because CLAUDE.md and the docs claimed cross-building,
# `sbt +test` over several versions and a `modules/crossTest/` directory, and the build had none of them.
# #1134 corrected the prose; this keeps it corrected. It catches stale claims; it does not validate prose.
#
# The build's side comes from sbt itself: `sbt "dumpBuildModel <file>"` (project/BuildModel.scala) writes
# the loaded build as JSON - projects with base directories, aggregates, configurations and defined keys,
# the ThisBuild/Global keys, keys whose `aggregate` is false, commands, aliases and Scala versions.
#
#   1. Scala  - every project has the same `scalaVersion`. A `Scala N[.N[.N]]` in the docs must agree with it
#               as far as it goes, unless the same clause says it is not supported (`Scala 2.13 support is
#               deferred`, `no Scala 2.13 artifact`). `scalaVersion := "..."` in a snippet must equal it, an
#               `llm4s-*_<binary>` artifact must be the build's binary version, and `crossScalaVersions` or
#               `sbt +task` may appear only if some project cross-builds. docs/reference/v1-scope.md must
#               state the version.
#   2. JDK    - the JDKs CI runs are the literal `java-version:` values and inline `java: [..]` matrix lists
#               in .github/workflows/ci.yml. Every `JDK N` / `Java N` in the docs must be one of them, and a
#               floor (`JDK N+`, `JDK N or newer/later`, `requires JDK N`) must be the oldest of them.
#   3. Module - every module in CLAUDE.md's repository-structure block is a directory, and every project
#               base directory under modules/ is named there (itself or through a parent directory).
#   4. sbt    - every documented `sbt` command line, and every alias body, is replayed against the model:
#               from the root project, `project X` switching the project later commands run in. Each
#               command must be an alias (whose body is replayed in place), an sbt or plugin command, or a
#               `[project/][Config/][task/]key` that resolves in that project - through the configurations
#               a configuration extends, ThisBuild and Global, or the projects it aggregates when the key's
#               `aggregate` is not false. `++<version> cmd` checks the version and replays `cmd`.
#               A command line is `sbt`, `sbtn` or `./sbt` at the start of a line of a code block or of an
#               inline code span, after `$ `, after `&&`, `||`, `;` or `|` outside quotes, or as a one-line
#               YAML `run: sbt ...` value; a backslash-continued line is joined first. Arguments are split
#               with shlex, then at sbt's own `;`.
#
# Not checked, on purpose (rephrase, or mark the line `doc-support: ignore`):
#   - sbt invoked through a wrapper (`sudo`, `env`, `time`, `VAR=x sbt`), inside `$(...)` or backticks, or in
#     a YAML block scalar (`run: |`); sbt shell prompts (`sbt:llm4s> test`);
#   - arguments: `set` and `eval` expressions, `inspect`/`last`/`export` targets, test and main class names,
#     input-task arguments, `sbt new` templates;
#   - version grammar: lists (`Scala 3.7.1 and 2.13`), ranges and ceilings (`JDK 21-25`, `JDK 25 or older`),
#     comparisons (`at least JDK 21`, `JDK >= 21`), patch versions (`JDK 21.0.2`), and a denial in another
#     clause or sentence (`Scala 2.13, which is not supported`);
#   - the JVM release target in scalacOptions/javacOptions, and workflows other than ci.yml.
#
# Usage: scripts/check-doc-support.sh [--model FILE] [REPO_ROOT]
#   --model FILE   a model written by `sbt "dumpBuildModel FILE"` (also $DOC_SUPPORT_MODEL). Without one the
#                  script runs sbt in REPO_ROOT to write it, which needs a JDK and sbt.
#   REPO_ROOT      defaults to this script's repository.
#
# Release notes, migration guides and design documents name old versions on purpose and are not read.
# Elsewhere a line is exempt when it carries `doc-support: ignore` or says it describes the past
# (`previously`, `formerly`, `no longer`, `used to`, `legacy`, `was`, `were`).
# Exit 0 = the matrix is true. Non-zero = file:line and the claim, one per line.
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
UNDOCUMENTED_MODULE_PREFIXES = ("modules/relocations/",)   # coordinate-forwarding stubs
IGNORE_MARKER = "doc-support: ignore"
HISTORICAL = re.compile(r"\b(?:previously|formerly|no longer|used to|legacy|was|were)\b", re.I)
MODEL_NAME = "the build model"
errors = []


def fail(path, line, message):
    errors.append(f"{path}:{line}: {message}")


def exempt(line_text):
    return IGNORE_MARKER in line_text or HISTORICAL.search(line_text) is not None


def line_of(text, index):
    return text.count("\n", 0, index) + 1


def line_at(text, index):
    start = text.rfind("\n", 0, index) + 1
    end = text.find("\n", index)
    return text[start:len(text) if end < 0 else end], start


def read(rel):
    p = pathlib.Path(rel)
    return p.read_text(encoding="utf-8") if p.exists() else ""


FILES = [p for p in [pathlib.Path("CLAUDE.md"), pathlib.Path("README.md")] + sorted(pathlib.Path("docs").rglob("*.md"))
         if p.exists() and p.as_posix() not in SKIP_FILES and not p.as_posix().startswith(SKIP_DIRS)]
TEXTS = {p: p.read_text(encoding="utf-8") for p in FILES}

# ---------------------------------------------------------------- the build, as sbt loaded it
try:
    model = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
except (OSError, ValueError) as e:
    print(f"Cannot read the build model {sys.argv[1]}: {e}", file=sys.stderr)
    sys.exit(2)


class Project:
    def __init__(self, raw):
        self.id, self.base = raw["id"], raw["base"]
        self.aggregate = list(raw.get("aggregate", []))
        self.config_by_id = {c["id"]: c["name"] for c in raw.get("configurations", [])}
        self.extends = {c["name"]: list(c.get("extends", [])) for c in raw.get("configurations", [])}
        self.keys = {}                                   # key -> {(config name, task axis)}
        for config, task, key in raw.get("keys", []):
            self.keys.setdefault(key, set()).add((config, task))
        self.no_aggregate = set(raw.get("noAggregate", []))
        self.scala = raw.get("scalaVersion", "")
        self.cross = list(raw.get("crossScalaVersions", []))

    def config_name(self, ident):
        """The configuration `ident` names here (`Test` or, old syntax, `test`), or None."""
        if ident in self.config_by_id:
            return self.config_by_id[ident]
        return next((n for n in self.extends if n.lower() == ident.lower()), None)

    def config_closure(self, name):
        seen, todo = set(), [name]
        while todo:
            cur = todo.pop()
            if cur not in seen:
                seen.add(cur)
                todo.extend(self.extends.get(cur, ()))
        return seen


PROJECTS = {raw["id"]: Project(raw) for raw in model["projects"]}
ROOT = model["root"]
SHARED_KEYS = {}                                         # ThisBuild and Global: every project delegates to them
for config, task, key in model.get("buildKeys", []) + model.get("globalKeys", []):
    SHARED_KEYS.setdefault(key, set()).add((config, task))
COMMANDS = set(model.get("commands", []))
ALIASES = {a["name"]: a["body"] for a in model.get("aliases", [])}
ALL_KEYS = set(SHARED_KEYS).union(*(p.keys for p in PROJECTS.values()))
ALL_CONFIG_IDS = set().union(*(p.config_by_id for p in PROJECTS.values()))
SCOPE_AXES = {"ThisBuild", "Global", "Zero"}

scala_versions = sorted({p.scala for p in PROJECTS.values() if p.scala})
SCALA = scala_versions[0] if len(scala_versions) == 1 else None
if SCALA is None:
    fail(MODEL_NAME, 1, f"projects must share one scalaVersion to be documented, found: {', '.join(scala_versions) or 'none'}")
CROSS_BUILDS = any(set(p.cross) - {p.scala} for p in PROJECTS.values())

# ---------------------------------------------------------------- 1. Scala
CLAUSE_BREAK = re.compile(r"\.(?!\d)|[;,|()—–:!?]| - |\bbut\b|\bwhile\b|\bwhereas\b", re.I)
DENIAL = re.compile(r"\b(?:not|no|never|nor|without|cannot|can't|don't|doesn't|isn't|aren't|won't|deferred"
                    r"|unsupported|planned|post-1\.0|after 1\.0)\b", re.I)


def denied(text, start, end):
    """The clause around [start, end) says the version is not supported."""
    line, ls = line_at(text, start)
    left = max((m.end() for m in CLAUSE_BREAK.finditer(line, 0, start - ls)), default=0)
    right = next((m.start() for m in CLAUSE_BREAK.finditer(line, end - ls)), len(line))
    return DENIAL.search(line[left:right]) is not None


SCALA_RE = re.compile(r"(?<![\w./-])Scala[ -]v?(\d+(?:\.(?:\d+|x)){0,2})(?![\d\w]|\.\d)", re.I)
PARTS = SCALA.split(".") if SCALA else []
BINARY = PARTS[0] if PARTS and int(PARTS[0]) >= 3 else ".".join(PARTS[:2])
for p, text in TEXTS.items() if SCALA else ():
    rel = p.as_posix()
    for m in SCALA_RE.finditer(text):
        if exempt(line_at(text, m.start())[0]):
            continue
        if re.match(r"\s*`?scala-library\b", text[m.end():]):
            continue                                     # `the Scala 2.13 scala-library` Scala 3 runs on
        parts = m.group(1).split(".")
        agrees = all(x == "x" or (i < len(PARTS) and x == PARTS[i]) for i, x in enumerate(parts))
        if not agrees and not denied(text, m.start(), m.end()):
            fail(rel, line_of(text, m.start()), f"documents Scala {m.group(1)}, but the build is Scala {SCALA}")
    for m in re.finditer(r'\bscalaVersion\s*:=\s*"([^"]+)"', text):
        if m.group(1) != SCALA and not exempt(line_at(text, m.start())[0]):
            fail(rel, line_of(text, m.start()), f"pins Scala {m.group(1)}, but the build is Scala {SCALA}")
    for m in re.finditer(r"\bllm4s[\w-]*_(\d+(?:\.\d+)?)(?![\w.])", text):
        if m.group(1) != BINARY and not exempt(line_at(text, m.start())[0]) and not denied(text, m.start(), m.end()):
            fail(rel, line_of(text, m.start()), f"documents a `_{m.group(1)}` artifact, but the build publishes `_{BINARY}`")
    if not CROSS_BUILDS:
        for m in re.finditer(r"\bcrossScalaVersions\b", text):
            if not exempt(line_at(text, m.start())[0]) and not denied(text, m.start(), m.end()):
                fail(rel, line_of(text, m.start()), "documents `crossScalaVersions`, but the build cross-builds nothing")
if SCALA and SCALA not in read("docs/reference/v1-scope.md"):
    fail("docs/reference/v1-scope.md", 1, f"does not state Scala {SCALA}, which the build uses")

# ---------------------------------------------------------------- 2. JDK
CI_WORKFLOW = ".github/workflows/ci.yml"
ci = read(CI_WORKFLOW)
ci_jdks = {int(v) for v in re.findall(r"""^\s*java-version:\s*['"]?(\d+)['"]?\s*(?:#.*)?$""", ci, re.M)}
for listed in re.findall(r"^\s*java:\s*\[([^\]]*)\]", ci, re.M):
    ci_jdks |= {int(v) for v in re.findall(r"\d+", listed)}
if not ci_jdks:
    fail(CI_WORKFLOW, 1, "no literal java-version or `java: [..]` matrix found; cannot establish the JDK CI runs")
FLOOR = min(ci_jdks) if ci_jdks else None
RUNS = ", ".join(str(j) for j in sorted(ci_jdks))
JDK_RE = re.compile(r"(?:(?P<req>\brequires?\s+)|(?<![\w./-]))(?:OpenJDK|JDK|JRE|Java)[ -]?(?P<n>\d{1,2})(?![\d\w]|\.\d)"
                    r"(?P<floor>\+|\s+or\s+(?:newer|later|above|higher))?", re.I)
for p, text in TEXTS.items() if ci_jdks else ():
    for m in JDK_RE.finditer(text):
        if exempt(line_at(text, m.start())[0]) or denied(text, m.start(), m.end()):
            continue
        n, line = int(m.group("n")), line_of(text, m.start())
        if (m.group("req") or m.group("floor")) and n != FLOOR:
            fail(p.as_posix(), line, f"gives JDK {n} as the minimum, but the oldest JDK CI runs is {FLOOR}")
        elif n not in ci_jdks:
            fail(p.as_posix(), line, f"documents JDK {n}, but CI runs JDK {RUNS}")

# ---------------------------------------------------------------- 3. modules
claude = read("CLAUDE.md")
documented = []                                          # (path, line)
block = re.search(r"## Repository Structure\s*```[^\n]*\n(.*?)```", claude, re.S)
if not block:
    fail("CLAUDE.md", 1, "no repository-structure block; cannot compare modules with the build")
else:
    base_line, stack = line_of(claude, block.start(1)), {}
    for offset, raw in enumerate(block.group(1).splitlines()):
        em = re.match(r"^((?:│   |    )*)(?:├── |└── )([^\s#/]+)/?", raw)
        if not em:
            continue
        depth = len(em.group(1)) // 4
        stack = {d: v for d, v in stack.items() if d < depth}
        stack[depth] = em.group(2)
        parts = [stack[d] for d in sorted(stack)]
        if parts[0] == "modules" and len(parts) > 1:
            documented.append(("/".join(parts), base_line + offset))
for path, line in documented:
    if not pathlib.Path(path).is_dir():
        fail("CLAUDE.md", line, f"names {path}/, which is not a directory")


def has_content(path):
    """Anything but build output: sbt makes `target/` for a project with no sources (the `docs` aggregate)."""
    p = pathlib.Path(path)
    return p.is_dir() and any(c.name not in {"target", ".bloop", ".bsp", ".metals"} for c in p.iterdir())


doc_paths = {path for path, _ in documented}
for proj in sorted(PROJECTS.values(), key=lambda q: q.base):
    mod = proj.base
    if not mod.startswith("modules/") or mod.startswith(UNDOCUMENTED_MODULE_PREFIXES) or not has_content(mod):
        continue
    if not any(mod == d or mod.startswith(d + "/") for d in doc_paths):
        fail(MODEL_NAME, 1, f"project `{proj.id}` is {mod}, which CLAUDE.md's repository-structure block does not name")

# ---------------------------------------------------------------- 4. sbt commands, replayed against the model
def sbt_split(body):
    """`body` cut at each `;` outside sbt's double quotes, each command as words; `core / Test / compile` is one."""
    parts, start, quoted, i = [], 0, False, 0
    while i < len(body):
        if quoted and body[i] == "\\":
            i += 1
        elif body[i] == '"':
            quoted = not quoted
        elif body[i] == ";" and not quoted:
            parts.append(body[start:i])
            start = i + 1
        i += 1
    parts.append(body[start:])
    return [re.sub(r"\s*/\s*(?=\S)", "/", c).split() for c in parts if c.split()]


def reached(start, key):
    """`start` and what it aggregates, transitively, except beneath a project where `key` does not aggregate."""
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
    configs = None if config is None else proj.config_closure(config) | {""}
    tasks = {"", task} if task else {""}
    return any((configs is None or c in configs) and t in tasks
               for c, t in proj.keys.get(key, set()) | SHARED_KEYS.get(key, set()))


def check_key(word, current):
    """None if `[project/][Config/][task/]key` (or old `config:key`) runs from `current`; otherwise why not."""
    project, explicit, segments = current, False, word.split("/")
    if len(segments) > 1 and (segments[0] in PROJECTS or segments[0] in SCOPE_AXES):
        project, explicit, segments = segments[0], True, segments[1:]
    elif len(segments) > 1 and segments[0] not in ALL_CONFIG_IDS and segments[0] not in ALL_KEYS:
        return f"names project `{segments[0]}`, which the build does not define"
    config = None
    if len(segments) == 1 and re.fullmatch(r"[\w-]+:[\w-]+", segments[0]):
        config, key = segments[0].split(":")
        segments = [key]
    if len(segments) > 1 and segments[0] in ALL_CONFIG_IDS:
        config, segments = segments[0], segments[1:]
    if len(segments) > 2:
        return "has more scope axes than sbt's project/Config/task/key"
    task, key = segments if len(segments) == 2 else (None, segments[0])
    if key not in ALL_KEYS:
        return f"`{key}` is not an alias, a command, nor a task or setting the build defines"
    if task is not None and task not in ALL_KEYS:
        return f"`{task}` is not a task or setting the build defines"
    if project in SCOPE_AXES:
        return None if key in SHARED_KEYS else f"`{key}` is not defined in {project}"
    targets = reached(project, key)
    with_config = [q for q in targets if config is None or PROJECTS[q].config_name(config)]
    if not with_config:
        return f"configuration `{config}` is not defined in `{project}`" + (" or what it aggregates" if len(targets) > 1 else "")
    for q in with_config:
        pr = PROJECTS[q]
        if defined_in(pr, pr.config_name(config) if config else None, task, key):
            return None
    scope = "/".join(x for x in (config, task, key) if x)
    owners = sorted(q.id for q in PROJECTS.values() if key in q.keys)
    where = f"; it is defined in {', '.join(owners[:6])}{', ...' if len(owners) > 6 else ''}" if owners else ""
    which = f"`{project}`" if explicit else f"the current project `{project}`"
    return f"`{scope}` is not defined in {which}" + (" or what it aggregates" if len(targets) > 1 else "") + where


def replay(commands, current, report, expanding=()):
    """Run `commands` through the model as sbt would from project `current`; report each that would not run.
    Returns the project current afterwards (a `project X`, also inside an alias, stays in effect)."""
    for words in commands:
        head = " ".join(words)
        if words[0].startswith("++"):
            rest = ([words[0][2:]] if words[0][2:] else []) + words[1:]
            version = rest[0].rstrip("!") if rest else ""
            if not re.fullmatch(r"\d+(?:\.(?:\d+|x|\*))*", version):
                report(f"`{head}` names no Scala version")
                continue
            if SCALA and not re.match(re.escape(version).replace(r"\.x", r"\.\d+").replace(r"\.\*", r"\.\d+") + r"(\.|$)", SCALA):
                report(f"`{head}` switches to Scala {version}, but the build is Scala {SCALA}")
            words = rest[1:]
        elif words[0].startswith("+"):
            if not CROSS_BUILDS:
                report(f"`{head}` cross-builds, but no project sets other crossScalaVersions")
            words = ([words[0][1:]] if words[0][1:] else []) + words[1:]
        while words and words[0] in {"~", "show"}:
            words = words[1:]
        if words and words[0].startswith("~"):
            words = [words[0][1:]] + words[1:]
        if not words or not words[0] or not re.match(r"[\w{]", words[0]):
            continue
        first = words[0]
        if first == "project":
            target = words[1] if len(words) > 1 else None
            if target in PROJECTS:
                current = target
            elif target == "/":
                current = ROOT
            elif target is not None:
                report(f"`{head}` names project `{target}`, which the build does not define")
        elif first in ALIASES:
            if first in expanding:
                report(f"alias `{first}` runs itself ({' -> '.join(expanding + (first,))})")
                continue
            # From the root the standalone alias check below reports the body's problems once.
            inner = ((lambda problem: None) if current == ROOT
                     else (lambda problem, a=first, c=current: report(f"alias `{a}` run in `{c}`: {problem}")))
            current = replay(sbt_split(ALIASES[first]), current, inner, expanding + (first,))
        elif first not in COMMANDS:
            problem = check_key(first, current)
            if problem:
                report(problem)
    return current


for name, body in sorted(ALIASES.items()):
    replay(sbt_split(body), ROOT, lambda problem, s=f"alias `{name}` (`{body.strip()}`)": fail(MODEL_NAME, 1, f"{s}: {problem}"),
           (name,))


def shell_segments(line):
    """`line` up to a `#` comment, cut at `;`, `&&`, `||` and `|` outside quotes (`2>&1` is kept whole)."""
    out, start, quote, i = [], 0, None, 0
    while i < len(line):
        ch = line[i]
        if quote:
            if ch == "\\" and quote == '"':
                i += 1
            elif ch == quote:
                quote = None
        elif ch in "'\"":
            quote = ch
        elif ch == "\\":
            i += 1
        elif ch == "#" and (i == 0 or line[i - 1] in " \t"):
            break                                        # a shell comment
        elif ch in ";|" or (ch == "&" and line.startswith("&&", i)):
            out.append(line[start:i])
            i += 2 if line.startswith(("&&", "||"), i) else 1
            start = i
            continue
        i += 1
    out.append(line[start:i])
    return out


YAML_RUN = re.compile(r"^\s*(?:-\s+)?run:\s+(?![|>])(.+)$")
LAUNCHERS = ("sbt", "sbtn", "./sbt", "./sbtn")


def sbt_calls(line):
    """The argument string of each sbt invocation on one line of code."""
    m = YAML_RUN.match(line)
    if m:
        line = m.group(1).strip()
        if len(line) >= 2 and line[0] == line[-1] and line[0] in "'\"":
            line = line[1:-1]
    calls = []
    for segment in shell_segments(line):
        segment = re.sub(r"^\s*\$\s+", "", segment).strip()
        word, _, args = segment.partition(" ")
        if word in LAUNCHERS and args.strip():
            calls.append(args.strip())
    return calls


def check_invocation(path, line, args):
    try:
        tokens = shlex.split(args, comments=True)
    except ValueError:
        fail(path, line, f"`sbt {args}` cannot be parsed by the shell (unbalanced quotes?)")
        return
    commands = []
    for token in tokens:
        if token == "new":                               # `sbt new <template>`: the rest is the template
            break
        if token.startswith(("-", "$", "<")) or re.fullmatch(r"\d*>&?\d*|&>", token):
            continue                                     # launcher flags, placeholders, `2>&1`
        commands.extend(sbt_split(token))
    replay(commands, ROOT, lambda problem: fail(path, line, f"`sbt {args}`: {problem}"))


def continued_lines(body):
    """(offset, line) for each line of a code block, a backslash-continued line joined with the next."""
    out, pending = [], None
    for offset, raw in enumerate(body.splitlines()):
        start, text = pending if pending else (offset, "")
        if raw.rstrip().endswith("\\"):
            pending = (start, text + raw.rstrip()[:-1] + " ")
        else:
            out.append((start, text + raw))
            pending = None
    return out + ([pending] if pending else [])


FENCE = re.compile(r"```[^\n]*\n(.*?)```", re.S)
INLINE = re.compile(r"`([^`\n]*\bsbtn?\s[^`\n]+)`")
for p, text in TEXTS.items():
    for fm in FENCE.finditer(text):
        first_line = line_of(text, fm.start(1))
        for offset, raw in continued_lines(fm.group(1)):
            if raw.lstrip().startswith("#") or exempt(raw):
                continue
            for args in sbt_calls(raw):
                check_invocation(p.as_posix(), first_line + offset, args)
    outside = FENCE.sub(lambda mm: "\n" * mm.group(0).count("\n"), text)    # fenced lines are read above
    for im in INLINE.finditer(outside):
        if not exempt(line_at(outside, im.start())[0]):
            for args in sbt_calls(im.group(1)):
                check_invocation(p.as_posix(), line_of(outside, im.start()), args)

if errors:
    print("The documented support matrix does not match the build:", file=sys.stderr)
    for e in errors:
        print("  " + e, file=sys.stderr)
    print(f"{len(errors)} stale claim(s). Fix the docs, or the build if the docs are right. A model older than the "
          f"build is regenerated by running this script without --model. A line that describes the past may be "
          f"marked `doc-support: ignore`.", file=sys.stderr)
    sys.exit(1)
print(f"Support matrix verified: Scala {SCALA}, JDK {RUNS} (floor {FLOOR}), {len(doc_paths)} documented modules, "
      f"{len(PROJECTS)} projects, {len(ALIASES)} aliases, {len(COMMANDS)} commands and {len(ALL_KEYS)} keys known.")
PYEOF
