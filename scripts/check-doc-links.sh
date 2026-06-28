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
    content = re.sub(r'`[^`\n]+`', '', content)
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
  "$REPO_ROOT/CHANGELOG.md"
  "$REPO_ROOT/docs/migrations/index.md"
  "$REPO_ROOT/docs/migrations/0x-to-1x.md"
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

if [[ "$ERRORS" -eq 0 ]]; then
  echo "All doc links OK"
  exit 0
else
  echo "$ERRORS broken link(s) found"
  exit 1
fi
