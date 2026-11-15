#!/usr/bin/env bash
# Tests for scripts/check-api-links.sh and scripts/check-scaladoc-crosslinks.sh (#2291), on a fake Scaladoc and docs tree.
set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
API_CHK="$REPO_ROOT/scripts/check-api-links.sh"
XL_CHK="$REPO_ROOT/scripts/check-scaladoc-crosslinks.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fails=0
expect() { # name expected-exit actual-exit
  if [ "$2" = "$3" ]; then echo "ok: $1"; else echo "FAIL: $1 (exit $3, wanted $2)"; fails=$((fails + 1)); fi
}

api() { # dir [revision]: a good generated Scaladoc
  local rev="${2:-main}"
  mkdir -p "$1/org/llm4s/llmconnect" "$1/org/llm4s/config"
  printf '<a href="https://llm4s.org/getting-started/">Getting started</a>' > "$1/index.html"
  printf '<a href="https://github.com/llm4s/llm4s/blob/%s/modules/core/src/main/scala/org/llm4s/llmconnect/LLMClient.scala#L21">source</a>
<a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/Instant.html">Instant</a>
<a href="https://scala-lang.org/api/3.x/scala/Option.html">Option</a>' "$rev" > "$1/org/llm4s/llmconnect/LLMClient.html"
  : > "$1/org/llm4s/config/Llm4sConfig\$.html"
}
docs() { # dir: guides whose api.html includes all resolve
  mkdir -p "$1/guide" "$1/_includes"
  printf 'An include: {%% include api.html t="org.llm4s.config.Llm4sConfig$" %%}\n' > "$1/_includes/api.html"
  printf -- '---\ntitle: G\n---\nUse {%% include api.html t="org.llm4s.llmconnect.LLMClient" %%}, and\n{%% include api.html t="org.llm4s.config.Llm4sConfig$" text="config" %%}.\n' > "$1/guide/g.md"
}

# check-api-links.sh
api "$WORK/api"; docs "$WORK/d1"
out="$("$API_CHK" "$WORK/api" "$WORK/d1")"; expect "resolving includes pass" 0 $?
case "$out" in *"All 2 api.html links"*) expect "counts every include" 0 0 ;; *) expect "counts every include ($out)" 0 1 ;; esac

docs "$WORK/d2"; printf 'A {%% include api.html t="org.llm4s.llmconnect.Moved" %%} type.\n' >> "$WORK/d2/guide/g.md"
"$API_CHK" "$WORK/api" "$WORK/d2" >/dev/null; expect "a type with no page fails" 1 $?

docs "$WORK/d3"; printf 'A {%% include api.html t="org.llm4s.config.Llm4sConfig" %%} object.\n' >> "$WORK/d3/guide/g.md"
out="$("$API_CHK" "$WORK/api" "$WORK/d3")"; expect "an object named without \$ fails" 1 $?
case "$out" in *'use t="org.llm4s.config.Llm4sConfig$"'*) expect "suggests the object's page" 0 0 ;; *) expect "suggests the object's page" 0 1 ;; esac

docs "$WORK/d4"; printf 'A {%% include api.html type="LLMClient" %%} typo.\n' >> "$WORK/d4/guide/g.md"
"$API_CHK" "$WORK/api" "$WORK/d4" >/dev/null; expect "an include without t= fails" 1 $?

docs "$WORK/d5"; printf 'A {%% include api.html t="LLMClient" %%} short name.\n' >> "$WORK/d5/guide/g.md"
"$API_CHK" "$WORK/api" "$WORK/d5" >/dev/null; expect "a name that is not fully qualified fails" 1 $?

mkdir -p "$WORK/d6/guide"; printf 'No includes.\n' > "$WORK/d6/guide/g.md"
"$API_CHK" "$WORK/api" "$WORK/d6" >/dev/null 2>&1; expect "docs with no include fail" 1 $?

"$API_CHK" "$WORK/nothing" "$WORK/d1" >/dev/null 2>&1; expect "a missing Scaladoc fails" 1 $?

# check-scaladoc-crosslinks.sh
"$XL_CHK" "$WORK/api" main >/dev/null; expect "good Scaladoc passes" 0 $?
api "$WORK/rel" v0.6.0
"$XL_CHK" "$WORK/rel" v0.6.0 >/dev/null; expect "release tag passes" 0 $?
out="$("$XL_CHK" "$WORK/rel" main)"; expect "source links at the wrong revision fail" 1 $?
case "$out" in *"no page links its source at main"*) expect "says which revision is missing" 0 0 ;; *) expect "says which revision is missing" 0 1 ;; esac

api "$WORK/mixed"; printf '<a href="https://github.com/llm4s/llm4s/blob/v0.5.0/modules/core/X.scala#L1">s</a><a href="https://github.com/llm4s/llm4s/blob/v0.4.0/docs/x.md">doc comment</a>' > "$WORK/mixed/old.html"
"$XL_CHK" "$WORK/mixed" main >/dev/null; expect "a source link at another revision fails" 1 $?
api "$WORK/comment"; printf '<a href="https://github.com/llm4s/llm4s/blob/v0.4.0/docs/x.md">doc comment</a>' > "$WORK/comment/c.html"
"$XL_CHK" "$WORK/comment" main >/dev/null; expect "a doc comment's link at another revision passes" 0 $?

api "$WORK/nosrc"; sed -i.bak '/github.com/d' "$WORK/nosrc/org/llm4s/llmconnect/LLMClient.html"
"$XL_CHK" "$WORK/nosrc" main >/dev/null; expect "no source links fail" 1 $?

api "$WORK/nojdk"; sed -i.bak '/oracle/d' "$WORK/nojdk/org/llm4s/llmconnect/LLMClient.html"
"$XL_CHK" "$WORK/nojdk" main >/dev/null; expect "no JDK links fail" 1 $?

api "$WORK/noscala"; sed -i.bak '/scala-lang/d' "$WORK/noscala/org/llm4s/llmconnect/LLMClient.html"
"$XL_CHK" "$WORK/noscala" main >/dev/null; expect "no Scala library links fail" 1 $?

api "$WORK/noroot"; printf '<p>API</p>' > "$WORK/noroot/index.html"
"$XL_CHK" "$WORK/noroot" main >/dev/null; expect "a root page without guide links fails" 1 $?

[ "$fails" = 0 ] || { echo "$fails failed"; exit 1; }
