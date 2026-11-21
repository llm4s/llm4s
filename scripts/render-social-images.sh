#!/usr/bin/env bash
# Renders the llm4s.org share cards (#2316): one 1200x630 PNG per line of docs/image_assets/social/cards.tsv, from
# docs/image_assets/social/template.svg, into that directory. The PNGs are committed; run this after changing the
# template or a card's text (for 1.0, say), then check the alt text in docs/_config.yml still matches.
#
# Needs python3 and one renderer: rsvg-convert (librsvg) if it is on the PATH, else headless Chrome or Chromium
# (set CHROME to its binary if it is not found). Text is set in Inter if it is installed, else Helvetica or Arial.
#
# Usage: scripts/render-social-images.sh [CARD_NAME...]   (default: every card)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIR="$REPO_ROOT/docs/image_assets/social"
LOGO="$REPO_ROOT/docs/image_assets/llm4s_logo.png"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

find_chrome() {
  if [ -n "${CHROME:-}" ]; then echo "$CHROME"; return; fi
  local c
  for c in google-chrome google-chrome-stable chromium chromium-browser \
           "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
           "/Applications/Chromium.app/Contents/MacOS/Chromium"; do
    if command -v "$c" >/dev/null 2>&1 || [ -x "$c" ]; then echo "$c"; return; fi
  done
}

if command -v rsvg-convert >/dev/null 2>&1; then
  RENDERER=rsvg
else
  CHROME_BIN="$(find_chrome)"
  if [ -z "$CHROME_BIN" ]; then
    echo "No renderer: install librsvg (rsvg-convert) or Chrome/Chromium, or set CHROME." >&2
    exit 1
  fi
  RENDERER=chrome
fi

# One filled-in SVG per selected card, written to $WORK/<name>.svg, with the logo inlined as a data URI.
python3 - "$DIR/template.svg" "$DIR/cards.tsv" "$LOGO" "$WORK" "$@" <<'PYEOF'
import base64, pathlib, sys
from xml.sax.saxutils import escape

template_path, cards_path, logo_path, work, *wanted = sys.argv[1:]
template = pathlib.Path(template_path).read_text(encoding="utf-8")
logo = "data:image/png;base64," + base64.b64encode(pathlib.Path(logo_path).read_bytes()).decode("ascii")
cards = {}
for n, line in enumerate(pathlib.Path(cards_path).read_text(encoding="utf-8").splitlines(), 1):
    if not line.strip() or line.startswith("#"):
        continue
    fields = line.split("\t")
    if len(fields) != 4 or not all(f.strip() for f in fields):
        sys.exit(f"cards.tsv line {n}: expected 4 tab-separated non-empty fields, got {len(fields)}")
    cards[fields[0]] = fields[1:]
unknown = [w for w in wanted if w not in cards]
if unknown:
    sys.exit("unknown card(s): " + ", ".join(unknown) + "; cards.tsv has " + ", ".join(cards))
for name in wanted or cards:
    heading, line1, line2 = (escape(f) for f in cards[name])
    svg = (template.replace("@@HEADING@@", heading).replace("@@LINE1@@", line1).replace("@@LINE2@@", line2)
           .replace('"../llm4s_logo.png"', '"' + logo + '"'))
    pathlib.Path(work, name + ".svg").write_text(svg, encoding="utf-8")
PYEOF

for svg in "$WORK"/*.svg; do
  name="$(basename "$svg" .svg)"
  out="$DIR/$name.png"
  if [ "$RENDERER" = rsvg ]; then
    rsvg-convert --width 1200 --height 630 --format png --output "$out" "$svg"
  else
    "$CHROME_BIN" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
      --window-size=1200,630 --screenshot="$out" "file://$svg" >/dev/null 2>&1
  fi
  [ -s "$out" ] || { echo "rendering $name failed" >&2; exit 1; }
  echo "wrote ${out#"$REPO_ROOT"/}"
done
