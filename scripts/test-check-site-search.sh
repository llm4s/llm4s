#!/usr/bin/env bash
# Tests for scripts/add-scaladoc-to-search.py and scripts/check-site-search.cjs (#2291), on a fake built site searched
# with the lunr release just-the-docs ships (2.3.9, from npm).
set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADD="$REPO_ROOT/scripts/add-scaladoc-to-search.py"
CHK="$REPO_ROOT/scripts/check-site-search.cjs"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fails=0
expect() { # name expected-exit actual-exit
  if [ "$2" = "$3" ]; then echo "ok: $1"; else echo "FAIL: $1 (exit $3, wanted $2)"; fails=$((fails + 1)); fi
}

npm install --silent --no-save --no-audit --no-fund --prefix "$WORK/npm" lunr@2.3.9 >/dev/null ||
  { echo "FAIL: could not install lunr from npm"; exit 1; }
LUNR="$WORK/npm/node_modules/lunr/lunr.js"

site() { # dir: a built site, its search index holding two guide sections, and the Scaladoc beside it
  mkdir -p "$1/assets/js/vendor" "$1/scaladoc/scripts"
  cp "$LUNR" "$1/assets/js/vendor/lunr.min.js"
  # The lines of just-the-docs's rendered search script the checker reads, as docs/_config.yml renders them.
  cat > "$1/assets/js/just-the-docs.js" <<'EOF'
      lunr.tokenizer.separator = /[\s/]+/
      var index = lunr(function(){
        this.ref('id');
        this.field('title', { boost: 200 });
        this.field('content', { boost: 2 });
        this.field('relUrl');
EOF
  cat > "$1/assets/js/search-data.json" <<'EOF'
{"0":{"doc":"Streaming","title":"Streaming","content":"Stream tokens as they arrive with an LLMClient. ","url":"/llm4s/guide/streaming","relUrl":"/guide/streaming"},
 "1":{"doc":"Agents","title":"Running an agent","content":"An Agent is built once and run by thread. The LLMClient answers. ","url":"/llm4s/guide/agents#run","relUrl":"/guide/agents#run"}}
EOF
  cat > "$1/scaladoc/scripts/searchData.js" <<'EOF'
pages = [{"l":"index.html#","e":false,"i":"","n":"llm4s-docs","t":"llm4s-docs","d":"","k":"static","x":""},
{"l":"org/llm4s/llmconnect.html#","e":false,"i":"","n":"org.llm4s.llmconnect","t":"org.llm4s.llmconnect","d":"","k":"package","x":""},
{"l":"org/llm4s/llmconnect/LLMClient.html#","e":false,"i":"","n":"LLMClient","t":"LLMClient","d":"org.llm4s.llmconnect","k":"trait","x":""},
{"l":"org/llm4s/llmconnect/LLMClient$.html#","e":false,"i":"","n":"LLMClient","t":"LLMClient","d":"org.llm4s.llmconnect","k":"object","x":""},
{"l":"org/llm4s/llmconnect/LLMClient.html#complete-0","e":false,"i":"","n":"complete","t":"complete","d":"org.llm4s.llmconnect.LLMClient","k":"def","x":""},
{"l":"org/llm4s/config/Llm4sConfig$.html#","e":false,"i":"","n":"Llm4sConfig","t":"Llm4sConfig","d":"org.llm4s.config","k":"object","x":""},
{"l":"org/llm4s/types.html#Result-0","e":false,"i":"","n":"Result","t":"Result","d":"org.llm4s.types","k":"type","x":""}];
EOF
  printf 'LLMClient\t/scaladoc/org/llm4s/llmconnect/LLMClient.html\nLlm4sConfig\t/scaladoc/org/llm4s/config/Llm4sConfig$.html\nstream tokens\t/guide/streaming.html\n' > "$1/queries.tsv"
}
entries() { python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(" ".join(sorted(e["relUrl"] for e in d.values())))' "$1/assets/js/search-data.json"; }

site "$WORK/a"
node "$CHK" "$WORK/a" "$WORK/a/queries.tsv" >/dev/null 2>&1; expect "type names miss before the Scaladoc is added" 1 $?
python3 "$ADD" "$WORK/a" >/dev/null; expect "add the Scaladoc" 0 $?
got="$(entries "$WORK/a")"
want="/guide/agents#run /guide/streaming /scaladoc/org/llm4s/config/Llm4sConfig\$.html /scaladoc/org/llm4s/llmconnect/LLMClient.html /scaladoc/org/llm4s/types.html#Result-0"
[ "$got" = "$want" ]; expect "adds types, not packages, members or a companion object" 0 $?
python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert all(e["url"]=="/llm4s"+e["relUrl"] for e in d.values())' \
  "$WORK/a/assets/js/search-data.json"; expect "API urls carry the site's base URL" 0 $?
python3 "$ADD" "$WORK/a" >/dev/null && [ "$(entries "$WORK/a")" = "$want" ]; expect "a second run changes nothing" 0 $?
out="$(node "$CHK" "$WORK/a" "$WORK/a/queries.tsv")"; expect "every query finds its page" 0 $?
case "$out" in *'"LLMClient" -> /scaladoc/org/llm4s/llmconnect/LLMClient.html (#1)'*) expect "the type's API page ranks first" 0 0 ;; *) expect "the type's API page ranks first ($out)" 0 1 ;; esac

printf 'tokens\t/guide/agents\n' > "$WORK/a/miss.tsv"
node "$CHK" "$WORK/a" "$WORK/a/miss.tsv" >/dev/null 2>&1; expect "an expected page outside the top 3 fails" 1 $?
printf 'LLMClient /guide/agents\n' > "$WORK/a/bad.tsv"
node "$CHK" "$WORK/a" "$WORK/a/bad.tsv" >/dev/null 2>&1; expect "a line without a tab fails" 1 $?
printf '# only a comment\n' > "$WORK/a/empty.tsv"
node "$CHK" "$WORK/a" "$WORK/a/empty.tsv" >/dev/null 2>&1; expect "no queries fail" 1 $?

site "$WORK/b"; rm "$WORK/b/scaladoc/scripts/searchData.js"
python3 "$ADD" "$WORK/b" >/dev/null 2>&1; expect "a site without the Scaladoc fails" 1 $?
site "$WORK/c"; printf 'var x = 1;' > "$WORK/c/scaladoc/scripts/searchData.js"
python3 "$ADD" "$WORK/c" >/dev/null 2>&1; expect "an unreadable Scaladoc index fails" 1 $?
site "$WORK/d"; sed -i.bak '/tokenizer.separator/d' "$WORK/d/assets/js/just-the-docs.js"
node "$CHK" "$WORK/d" "$WORK/d/queries.tsv" >/dev/null 2>&1; expect "a changed just-the-docs search script fails" 1 $?

# The committed list: twenty queries, each a query, a tab and a site path.
n="$(grep -vcE '^(#|$)' "$REPO_ROOT/scripts/search-queries.tsv")"
[ "$n" = 20 ]; expect "search-queries.tsv lists 20 queries ($n)" 0 $?
! grep -vE '^(#|$)' "$REPO_ROOT/scripts/search-queries.tsv" | grep -vqE $'^[^\t]+\t/[^\t ]*$'; expect "every query line is <query><TAB>/path" 0 $?

[ "$fails" = 0 ] || { echo "$fails failed"; exit 1; }
