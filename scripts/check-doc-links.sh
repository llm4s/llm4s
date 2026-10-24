#!/usr/bin/env bash
# Validates that all relative Markdown links in CHANGELOG.md, README.md,
# and docs/migrations/ resolve to real files.
# Exit code 0 = all links valid.  Non-zero = broken links found.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ERRORS=0

# Extract relative links from a file, skipping code fences and inline code.
extract_links() {
  python3 - "$1" <<'PYEOF'
import re, sys

def extract_relative_links(path):
    with open(path) as f:
        content = f.read()
    content = re.sub(r'```.*?```', '', content, flags=re.DOTALL)
    # Inline code may wrap onto a second line (`f[A](x, y)(z =>\n  ...)`).
    content = re.sub(r'`[^`\n]+(?:\n[^`\n]+)?`', '', content)
    for m in re.finditer(r'\]\(([^)]+)\)', content):
        link = m.group(1).strip()
        if link.startswith(('http://', 'https://', 'mailto:', '#')):
            continue
        path_part = link.split('#')[0].strip()
        if path_part:
            print(path_part)

extract_relative_links(sys.argv[1])
PYEOF
}

check_links_in_file() {
  local file="$1"
  local dir
  dir="$(dirname "$file")"

  while IFS= read -r path_part; do
    resolved="$(python3 -c "import os,sys; print(os.path.realpath(os.path.join(sys.argv[1], sys.argv[2])))" "$dir" "$path_part" 2>/dev/null || echo "$dir/$path_part")"
    if [[ ! -e "$resolved" ]] && [[ ! -e "${resolved}.md" ]]; then
      echo "BROKEN: $(basename "$file") -> $path_part"
      ERRORS=$((ERRORS + 1))
    fi
  done < <(extract_links "$file")
}

TARGET_FILES=(
  "$REPO_ROOT/README.md"
  "$REPO_ROOT/CONTRIBUTING.md"
  "$REPO_ROOT/CHANGELOG.md"
  "$REPO_ROOT/SECURITY.md"
  "$REPO_ROOT/docs/migrations/index.md"
  "$REPO_ROOT/docs/migrations/0x-to-1x.md"
  "$REPO_ROOT/ADOPTERS.md"
  "$REPO_ROOT/SUPPORT.md"
)

for f in "${TARGET_FILES[@]}"; do
  if [[ -f "$f" ]]; then
    check_links_in_file "$f"
  else
    echo "MISSING file: $f"
    ERRORS=$((ERRORS + 1))
  fi
done

# Validate only the new Changelog & Migration section in README
python3 - "$REPO_ROOT/README.md" <<'PYEOF'
import re, sys, os

path = sys.argv[1]
root = os.path.dirname(path)

with open(path) as f:
    content = f.read()

m = re.search(r'## Changelog & Migration\n(.*?)(?=\n## |\Z)', content, re.DOTALL)
if not m:
    print("WARNING: 'Changelog & Migration' section not found in README")
    sys.exit(0)

section = re.sub(r'`[^`\n]+`', '', m.group(1))
errors = 0
for lm in re.finditer(r'\]\(([^)]+)\)', section):
    link = lm.group(1).strip()
    if link.startswith(('http://', 'https://', 'mailto:', '#')):
        continue
    path_part = link.split('#')[0].strip()
    if not path_part:
        continue
    resolved = os.path.realpath(os.path.join(root, path_part))
    if not os.path.exists(resolved) and not os.path.exists(resolved + '.md'):
        print(f"BROKEN: README.md (Changelog section) -> {path_part}")
        errors += 1

sys.exit(errors)
PYEOF
README_RC=$?
ERRORS=$((ERRORS + README_RC))

# Every link into a README.md anchor - relative (`README.md#x`, `../README.md#x`), to the README on GitHub
# (`github.com/llm4s/llm4s#x`, `.../blob/main/README.md#x`) - must name a heading of README.md (#2322).
python3 - "$REPO_ROOT" <<'PYEOF'
import os, re, subprocess, sys

root = sys.argv[1]

def slug(heading):
    s = re.sub(r'[`*_~]|<[^>]+>', '', heading).strip().lower()
    s = re.sub(r'\[([^\]]*)\]\([^)]*\)', r'\1', s)
    s = re.sub(r'[^\w\- ]', '', s)
    return s.replace(' ', '-')

readme = open(os.path.join(root, "README.md"), encoding="utf-8").read()
readme = re.sub(r'```.*?```', '', readme, flags=re.DOTALL)
anchors, seen = set(), {}
for h in re.findall(r'^#{1,6}\s+(.+?)\s*#*\s*$', readme, flags=re.M):
    base = slug(h)
    n = seen.get(base, 0)
    anchors.add(base if n == 0 else f"{base}-{n}")
    seen[base] = n + 1

files = subprocess.run(["git", "-C", root, "ls-files"], capture_output=True, text=True, check=True).stdout.split()
REMOTE = re.compile(r'github\.com/llm4s/llm4s(?:/blob/main/README\.md)?#([A-Za-z0-9_-]+)')
LOCAL  = re.compile(r'(?<![\w/.-])((?:\.\./)*README\.md)#([A-Za-z0-9_-]+)')
errors = 0
for rel in files:
    if rel.startswith(("docs/_site/", "scripts/check-doc-links.sh")) or not rel.endswith((".md", ".scala", ".java", ".kt", ".yml", ".html", ".sh", ".txt")):
        continue
    try:
        text = open(os.path.join(root, rel), encoding="utf-8").read()
    except (UnicodeDecodeError, FileNotFoundError):
        continue
    hits = [(m.start(), m.group(1)) for m in REMOTE.finditer(text)]
    for m in LOCAL.finditer(text):
        if os.path.normpath(os.path.join(os.path.dirname(rel), m.group(1))) == "README.md":
            hits.append((m.start(), m.group(2)))
    for pos, anchor in hits:
        if anchor.lower() not in anchors:
            print(f"BROKEN: {rel}:{text.count(chr(10), 0, pos) + 1} -> README.md#{anchor} (no such heading)")
            errors += 1
sys.exit(min(errors, 100))
PYEOF
ERRORS=$((ERRORS + $?))

if [[ "$ERRORS" -eq 0 ]]; then
  echo "All doc links OK"
  exit 0
else
  echo "$ERRORS broken link(s) found"
  exit 1
fi
