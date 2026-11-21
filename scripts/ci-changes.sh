#!/usr/bin/env bash
# Decides which of CI's change-gated jobs a pull request needs, from the paths it changes.
#
# Prints one `<name>=true|false` line per decision on stdout, for $GITHUB_OUTPUT, and why on stderr:
#
#   workspace    the @Workspace tier (scripts/workspace-tier-changes.sh, which has its own, narrower rules)
#   performance  the `Performance tests` job: `sbt testPerformance` runs only core's and agent's tagged tests
#                (`performanceTestProjects` in build.sbt), so it runs when core, agent or media (core's one in-build
#                dependency) changes, or a build file does
#
# A build file (BUILD below: build.sbt, project/, ci.yml, the composite actions, sbt's options, the scripts every sbt
# job runs) sets every decision but `workspace` to true. Renames are not folded: a moved file counts as a change to
# its old path too.
#
# Usage: scripts/ci-changes.sh BASE HEAD      (CI passes HEAD^1 HEAD on the PR's merge commit)
# Needs git and ruby (the workspace decision).
set -euo pipefail

BASE="${1:?usage: $0 BASE HEAD}"
HEAD="${2:?usage: $0 BASE HEAD}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Paths whose change sets every decision. Extended regular expressions, matched against repo-relative paths.
BUILD=(
  '^build\.sbt$'
  '^project/'
  '^\.github/workflows/ci\.yml$'
  '^\.github/actions/'
  '^\.sbtopts$'
  '^\.jvmopts$'
  '^scripts/ci-changes\.sh$'
  '^scripts/retry-on-transient-network\.sh$'
)
PERFORMANCE=(
  '^modules/media/'
  '^modules/core/'
  '^modules/agent/'
)

files="$(git diff --name-only --no-renames "$BASE" "$HEAD")"
any() { # any LIST...: the changed files matching any of the patterns
  printf '%s\n' "$files" | grep -E "$(IFS='|'; echo "$*")" || true
}

build="$(any "${BUILD[@]}")"
# decide NAME PATTERN...: NAME is true when a build file or a file matching a pattern changed
decide() {
  local name="$1"; shift
  local hit
  if [ -n "$build" ]; then
    echo "$name: a build file changed: $(printf '%s' "$build" | head -1)" >&2
    echo "$name=true"
    return
  fi
  hit="$(any "$@")"
  if [ -n "$hit" ]; then
    echo "$name: the change touches $(printf '%s' "$hit" | head -1)$([ "$(printf '%s\n' "$hit" | wc -l)" -gt 1 ] && echo ' and more')" >&2
    echo "$name=true"
  else
    echo "$name: nothing it covers changed" >&2
    echo "$name=false"
  fi
}

"$HERE/workspace-tier-changes.sh" "$BASE" "$HEAD" | sed 's/^changed=/workspace=/'
decide performance "${PERFORMANCE[@]}"
