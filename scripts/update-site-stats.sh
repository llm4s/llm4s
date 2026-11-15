#!/usr/bin/env bash
# Refreshes the numbers in docs/_data/stats.yml (the home page stats band and the Foundation page) on the day the
# site is built. pages.yml runs it before the Jekyll build, on every deploy and on a daily schedule; the result is
# never committed.
#
# - github: stars, forks, contributors (bots excluded), merged pull requests and releases, from the GitHub API
#   (needs `gh` with a token in GH_TOKEN). If any of them cannot be fetched, all five keep their committed values:
#   a stale number is better than a missing or wrong one.
# - community: the talks and events in docs/_data/events.yml that have started by today, and the weekly Dev Hours
#   held since `dev_hour.first_session` (every Sunday, less `dev_hour.skipped`). These need no network.
#
# Usage: scripts/update-site-stats.sh [repo] [stats-file] [events-file]
set -euo pipefail

REPO="${1:-llm4s/llm4s}"
FILE="${2:-docs/_data/stats.yml}"
EVENTS="${3:-docs/_data/events.yml}"

github_numbers() {
  local repo_json stars forks contributors merged releases n
  repo_json=$(gh api "repos/$REPO") || return 1
  stars=$(jq -r .stargazers_count <<<"$repo_json")
  forks=$(jq -r .forks_count <<<"$repo_json")
  # Bots (dependabot[bot], github-actions[bot]) are not contributors.
  contributors=$(gh api --paginate "repos/$REPO/contributors?per_page=100" --jq '.[] | select(.type != "Bot") | .login' | wc -l | tr -d ' ') || return 1
  merged=$(gh api "search/issues?q=repo:$REPO+is:pr+is:merged&per_page=1" --jq .total_count) || return 1
  releases=$(gh api --paginate "repos/$REPO/releases?per_page=100" --jq '.[] | select(.draft | not) | .tag_name' | wc -l | tr -d ' ') || return 1
  for n in "$stars" "$forks" "$contributors" "$merged" "$releases"; do
    [[ "$n" =~ ^[0-9]+$ ]] && [ "$n" -gt 0 ] || return 1
  done
  echo "$stars $forks $contributors $merged $releases"
}

if GITHUB_NUMBERS=$(github_numbers); then
  export GITHUB_NUMBERS
else
  echo "::warning::update-site-stats: could not read the GitHub numbers for $REPO; keeping the committed ones in $FILE."
  export GITHUB_NUMBERS=""
fi

python3 - "$FILE" "$EVENTS" <<'EOF'
import datetime, os, re, sys

path, events_path = sys.argv[1], sys.argv[2]
text = open(path, encoding="utf-8").read()
today = datetime.datetime.now(datetime.timezone.utc).date()

def sub(pattern, repl):
    global text
    text, n = re.subn(pattern, repl, text, count=1, flags=re.M)
    if n != 1:
        sys.exit(f"update-site-stats: no match for {pattern!r} in {path}")

def set_value(label, value):
    sub(rf'(- value: )"[^"]*"(\n\s+label: {re.escape(label)}$)', rf'\g<1>"{value:,}"\g<2>')

numbers = os.environ.get("GITHUB_NUMBERS", "").split()
if numbers:
    for label, value in zip(["GitHub stars", "Forks", "Contributors", "Merged pull requests", "Releases"], numbers):
        set_value(label, int(value))
    as_of = f"{today.day} {today:%b %Y}"
    sub(r'^as_of: ".*"$', f'as_of: "{as_of}"')
    sub(r'(^source_note: "GitHub, as of )[^.]*(\.)', rf"\g<1>{as_of}\g<2>")

# Talks and events that have started by today; an announced, upcoming one is not counted yet.
starts = re.findall(r"^- start: (\d{4}-\d{2}-\d{2})\s*$", open(events_path, encoding="utf-8").read(), flags=re.M)
if not starts:
    sys.exit(f"update-site-stats: no `- start:` dates in {events_path}")
set_value("Talks and events", sum(datetime.date.fromisoformat(s) <= today for s in starts))

# Dev Hour is every Sunday at 09:00 London time; the daily build runs before then, so only earlier Sundays count.
first = datetime.date.fromisoformat(re.search(r'^\s+first_session: "?(\d{4}-\d{2}-\d{2})"?\s*$', text, flags=re.M).group(1))
skipped = int(re.search(r"^\s+skipped: (\d+)\s*(?:#.*)?$", text, flags=re.M).group(1))
held = ((today - first).days - 1) // 7 + 1 if today > first else 0
set_value("Dev Hour sessions", max(held - skipped, 0))

open(path, "w", encoding="utf-8").write(text)
EOF

echo "Updated $FILE:"
cat "$FILE"
