#!/usr/bin/env python3
"""Generates llms.txt, llms-full.txt and llm4s-rules.md from the docs pages (#2287).

  generate-llms-txt.py --version 0.5.1 --out docs                 # build the files
  generate-llms-txt.py --check docs                               # check built files (the deploy gate)

The files are built from the pages, never kept by hand: the getting-started pages, the user guide (which holds the
Java and Kotlin guides), the cookbook and docs/ai-assistants.md, in navigation order. Design documents, RFCs, talks
and events are not listed.

llms-full.txt starts each page with `<!-- llms-page: <path> | compile-checked -->` when the page is in
`DocSnippets.pages` (project/DocSnippets.scala: its Scala blocks are compiled against the release) and with
`<!-- llms-page: <path> | not-compile-checked -->` when it is not; in that case every Scala block is preceded by the
line `(not compile-checked)`. `--check` fails when a file is missing or empty, when llms.txt links a page that does
not exist, and when a Scala block of an unchecked page lacks the marker. Standard library only.
"""
import argparse
import os
import re
import sys
from pathlib import Path

SITE = "https://llm4s.org"
LENIENT = False  # --lenient: an older release may lack a page that is listed now
MARKER = "(not compile-checked)"
RULES_START, RULES_END = "<!-- rules:start -->", "<!-- rules:end -->"
PAGE_RE = re.compile(r"^<!-- llms-page: (\S+) \| (compile-checked|not-compile-checked) -->$")
FENCE_RE = re.compile(r"^(\s*)```(\S*)")

# (heading, directory or file of docs/, ...): the pages of each part of llms.txt.
SECTIONS = [
    ("Getting started", ["getting-started", "ai-assistants.md"]),
    ("User guide (including Java and Kotlin)", ["guide"]),
    ("Cookbook", ["examples/cookbook.md", "examples/cookbook"]),
]


def fail(msg):
    print(f"error: {msg}", file=sys.stderr)
    sys.exit(1)


def front_matter(text):
    """The front matter as a dict of strings, and the body after it."""
    if not text.startswith("---"):
        return {}, text
    end = text.find("\n---", 3)
    if end < 0:
        return {}, text
    meta = {}
    for line in text[3:end].splitlines():
        m = re.match(r"^([A-Za-z_]+)\s*:\s*(.*?)\s*$", line)
        if m:
            meta[m.group(1)] = m.group(2).strip().strip('"').strip("'")
    return meta, text[end + 4:].lstrip("\n")


def checked_pages(snippets_file):
    """The page paths in `DocSnippets.pages`, relative to docs/."""
    if not snippets_file.is_file():
        return set()
    src = snippets_file.read_text(encoding="utf-8")
    m = re.search(r"val pages: Seq\[String\] = Seq\((.*?)\)", src, re.S)
    if not m:
        fail(f"{snippets_file} has no `val pages: Seq[String] = Seq(...)`")
    return set(re.findall(r'"([^"]+\.md)"', m.group(1)))


def data_values(docs, version):
    """The site.data.project values a page may print; the release under build replaces the stored ones."""
    values = {}
    project = docs / "_data" / "project.yml"
    if project.is_file():
        for line in project.read_text(encoding="utf-8").splitlines():
            m = re.match(r'^([a-z_]+):\s*"?([^"#]*?)"?\s*(#.*)?$', line)
            if m and m.group(2):
                values[m.group(1)] = m.group(2)
    values["version"] = version
    values["latest_release"] = version
    return values


def provider_counts(docs):
    """The chat and embedding provider counts docs/guide/comparison.md reads from capabilities.yml."""
    chat = emb = 0
    caps = docs / "_data" / "capabilities.yml"
    if caps.is_file():
        for line in caps.read_text(encoding="utf-8").splitlines():
            chat += bool(re.match(r"^\s+chat:\s*true\s*$", line))
            emb += bool(re.match(r"^\s+embeddings:\s*true\s*$", line))
    return chat, emb


def render(body, values, counts, path):
    """The page's Markdown with the Liquid the site would have resolved: only what the pages use, nothing else."""
    body = re.sub(r"\{%-?\s*comment\s*-?%\}.*?\{%-?\s*endcomment\s*-?%\}", "", body, flags=re.S)
    body = re.sub(r"\{%-?\s*assign[^\n]*endfor\s*-?%\}", "", body)

    def unless(m):
        return "" if m.group(1) in values["latest_release"] else m.group(2)

    body = re.sub(
        r'\{%-?\s*unless site\.data\.project\.latest_release contains "([^"]*)"\s*-?%\}(.*?)\{%-?\s*endunless\s*-?%\}',
        unless, body, flags=re.S)
    body = re.sub(r"\{\{\s*site\.data\.project\.([a-z_]+)\s*\}\}", lambda m: values.get(m.group(1), m.group(0)), body)
    body = body.replace("{{ llm4s_chat_providers }}", str(counts[0]))
    body = body.replace("{{ llm4s_embedding_providers }}", str(counts[1]))
    if "{%" in body:
        fail(f"docs/{path} uses Liquid this generator does not resolve: {re.search(r'{%.*', body).group(0)[:80]}")
    # Jekyll/just-the-docs only: attribute lists, the TOC and the "Table of contents" heading.
    body = re.sub(r"(?m)^\{:[^}]*\}\s*\n", "", body)
    body = re.sub(r"[ \t]*\{:[^}]*\}[ \t]*$", "", body, flags=re.M)
    body = re.sub(r"(?ms)^#+ Table of contents\s*\n+1\. TOC\s*\n+", "", body)
    return re.sub(r"\n{3,}", "\n\n", body).strip() + "\n"


def mark_unchecked(body):
    """Puts the marker before every Scala block."""
    out, in_fence = [], False
    for line in body.splitlines():
        m = FENCE_RE.match(line)
        if m and not in_fence:
            in_fence = True
            if m.group(2) == "scala":
                out += [m.group(1) + MARKER, ""]
        elif m and in_fence and line.strip() == "```":
            in_fence = False
        out.append(line)
    return "\n".join(out) + "\n"


def page_url(rel, meta):
    if meta.get("permalink"):
        return SITE + meta["permalink"]
    p = rel[:-3]
    return SITE + "/" + (p[: -len("index")] if p.endswith("index") else p)


def description(meta, body):
    if meta.get("description"):
        return meta["description"]
    para = []
    for line in body.splitlines():
        s = line.strip()
        if not s or s.startswith(("#", "{:", "|", "```", "-", "*", "<", ">")) or s[0].isdigit():
            if para:
                break
            continue
        para.append(s)
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", " ".join(para)).replace("`", "")
    return (text[:197] + "...") if len(text) > 200 else text


class Page:
    def __init__(self, docs, path):
        self.rel = path.relative_to(docs).as_posix()
        self.meta, self.body = front_matter(path.read_text(encoding="utf-8"))
        self.title = self.meta.get("title") or self.rel
        self.nav = self.order(self.meta)

    @staticmethod
    def order(meta):
        try:
            return float(meta.get("nav_order", "999"))
        except ValueError:
            return 999.0


def section_pages(docs, entries):
    """The pages of one section in navigation order: a directory's pages by `nav_order`, each folder after its index."""
    pages = []
    for e in entries:
        p = docs / e
        files = [p] if p.is_file() else sorted(p.rglob("*.md")) if p.is_dir() else []
        if not files and LENIENT:
            continue
        if not files:
            fail(f"docs/{e} has no pages: a section of llms.txt would be empty")
        pages += [Page(docs, f) for f in files if not str(f.relative_to(docs)).startswith("_")]
    # A page without front matter is not a page of the site (Jekyll copies it as a file), so it is not listed.
    pages = [p for p in pages if p.meta and p.meta.get("nav_exclude") != "true"]
    by_rel = {p.rel: p for p in pages}

    def parent(p):
        d = Path(p.rel).parent
        if p.rel.endswith("index.md"):
            d = d.parent
        if d.as_posix() == ".":
            return None
        for cand in ((d / "index.md").as_posix(), (d.parent / (d.name + ".md")).as_posix()):
            if cand in by_rel and cand != p.rel:
                return by_rel[cand]
        return None

    children = {}
    for p in pages:
        children.setdefault(parent(p) and parent(p).rel, []).append(p)

    def walk(key):
        for p in sorted(children.get(key, []), key=lambda q: (q.nav, q.title)):
            yield p
            yield from walk(p.rel)

    return list(walk(None))


def build(docs, snippets, version, out, prefix=""):
    docs = Path(docs)
    if not docs.is_dir():
        fail(f"{docs} is not a directory")
    checked = checked_pages(Path(snippets))
    values, counts = data_values(docs, version), provider_counts(docs)

    assistants = docs / "ai-assistants.md"
    text = assistants.read_text(encoding="utf-8") if assistants.is_file() else ""
    has_rules = RULES_START in text and RULES_END in text
    index = [
        "# LLM4S",
        "",
        "> LLM4S (Large Language Models for Scala) is a type-safe Scala 3 framework for LLM-powered applications: "
        "multi-provider chat and embeddings, tools, agents, RAG and observability, with Java and Kotlin APIs.",
        "",
        f"Version: {version}",
        "",
        f"Full text of every page below: {SITE}{prefix}/llms-full.txt."
        + (f" Rules for code written with a coding assistant: {SITE}{prefix}/llm4s-rules.md ({SITE}/ai-assistants)."
           if has_rules else ""),
        "",
    ]
    full = [
        f"# LLM4S {version} documentation", "",
        f"> The getting-started pages, the user guide, the cookbook and the Java and Kotlin guides of {SITE}, "
        f"release {version}, in navigation order.",
        "",
        f"A Scala block under a page marked `not-compile-checked` is followed by `{MARKER}`: other blocks are "
        "compiled against the release.",
        "",
    ]
    for heading, entries in SECTIONS:
        index += [f"## {heading}", ""]
        for p in section_pages(docs, entries):
            body = render(p.body, values, counts, p.rel)
            url = page_url(p.rel, p.meta)
            desc = description(p.meta, body)
            index.append(f"- [{p.title}]({url})" + (f": {desc}" if desc else ""))
            is_checked = p.rel in checked
            full += ["---", "", f"<!-- llms-page: {p.rel} | {'compile-checked' if is_checked else 'not-compile-checked'} -->",
                     f"Source: {url}", "", body if is_checked else mark_unchecked(body)]
        index.append("")

    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "llms.txt").write_text("\n".join(index).rstrip() + "\n", encoding="utf-8")
    (out / "llms-full.txt").write_text("\n".join(full).rstrip() + "\n", encoding="utf-8")

    if has_rules:
        block = text.split(RULES_START, 1)[1].split(RULES_END, 1)[0].strip()
        block = render(block, values, counts, "ai-assistants.md")
        (out / "llm4s-rules.md").write_text(f"<!-- llm4s rules for release {version}: {SITE}/ai-assistants -->\n\n{block}",
                                            encoding="utf-8")
    print(f"wrote llms.txt, llms-full.txt and llm4s-rules.md for {version} to {out}")


def check(out, docs, snippets):
    out, docs = Path(out), Path(docs)
    errors = []
    for name in ("llms.txt", "llms-full.txt"):
        f = out / name
        if not f.is_file() or f.stat().st_size == 0:
            errors.append(f"{f} is missing or empty")
    if errors:
        return errors
    urls = set()
    for f in docs.rglob("*.md"):
        meta, _ = front_matter(f.read_text(encoding="utf-8"))
        if meta:
            urls.add(page_url(f.relative_to(docs).as_posix(), meta).rstrip("/"))
    for url in re.findall(r"\]\((https://llm4s\.org[^)]*)\)", (out / "llms.txt").read_text(encoding="utf-8")):
        if url.rstrip("/") not in urls:
            errors.append(f"llms.txt links {url}, which is not a page of the docs")
    checked, state, prev, pages = checked_pages(Path(snippets)), None, "", 0
    in_fence = False
    for n, line in enumerate((out / "llms-full.txt").read_text(encoding="utf-8").splitlines(), 1):
        m = PAGE_RE.match(line)
        if m:
            pages += 1
            state, prev, in_fence = m.group(2), "", False
            if (m.group(2) == "compile-checked") != (m.group(1) in checked):
                errors.append(f"llms-full.txt:{n}: {m.group(1)} is marked {m.group(2)}, but DocSnippets.pages "
                              f"{'lists' if m.group(1) in checked else 'does not list'} it")
            continue
        f = FENCE_RE.match(line)
        if f and not in_fence:
            in_fence = True
            if f.group(2) == "scala" and state == "not-compile-checked" and prev != MARKER:
                errors.append(f"llms-full.txt:{n}: a Scala block of an unchecked page lacks `{MARKER}`")
        elif f and in_fence and line.strip() == "```":
            in_fence = False
        if line.strip():
            prev = line.strip()
    if pages == 0:
        errors.append("llms-full.txt has no pages")
    return errors


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version")
    ap.add_argument("--out")
    ap.add_argument("--path-prefix", default="", help="site path the files are published under, e.g. /llms/0.5")
    ap.add_argument("--docs", default="docs")
    ap.add_argument("--snippets", default="project/DocSnippets.scala")
    ap.add_argument("--lenient", action="store_true", help="skip a listed page or folder that does not exist")
    ap.add_argument("--check", metavar="DIR")
    a = ap.parse_args()
    global LENIENT
    LENIENT = a.lenient
    if a.check:
        errs = check(a.check, a.docs, a.snippets)
        for e in errs:
            print(f"error: {e}", file=sys.stderr)
        sys.exit(1 if errs else 0)
    if not a.version or not a.out:
        fail("--version and --out are required")
    build(a.docs, a.snippets, a.version, a.out, a.path_prefix)


if __name__ == "__main__":
    main()
