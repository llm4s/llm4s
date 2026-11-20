#!/usr/bin/env bash
# The key, restore keys and paths of the sbt dependency cache. Its one caller is .github/actions/restore-sbt-deps,
# which every CI job and .github/workflows/dependency-cache.yml (the cache's only writer) restore through, so the key
# a job looks for and the key the writer saves under are computed by the same code.
#
# Usage: scripts/sbt-deps-cache-key.sh ci|save     (run from the repository root; writes to $GITHUB_OUTPUT, or stdout)
#
# Key: sbt-deps-v1-<OS>-<YYYY-MM>-<hash>. The hash covers what decides which artifacts get downloaded:
#   - the build files (tracked *.sbt, project/build.properties, project/**/*.scala and *.sbt), except the portable
#     spike's own build, which resolveAll never loads;
#   - .scalafmt.conf (the scalafmt version the checks fetch);
#   - the -Dllm4s.*Version overrides the Spring jobs in ci.yml pass, not the rest of ci.yml, which changes too often;
#   - this script, the restore action and dependency-cache.yml, so a change to how the cache is built rebuilds it.
# The month: the writer restores only this month's entries, so its first run each month starts empty and drops what
# the build no longer uses. Each save is otherwise the union of everything ever restored, and grows without limit.
#
# Restore keys:
#   save: only this month's prefix.
#   ci:   this month's, then any month's (early in a month), then setup-java's own `setup-java-<OS>-<arch>-sbt-` entries,
#         which matched until the writer first ran. That last one restores only because the paths below are, string
#         for string and in order, the ones setup-java passes (src/cache.ts at v6.1.0): the cache version is a hash of
#         the path list, and a different list misses every entry.
set -euo pipefail

mode="${1:-}"
case "$mode" in ci|save) ;; *) echo "usage: $0 ci|save" >&2; exit 2;; esac

os="${RUNNER_OS:-$(uname -s)}"
arch="$(printf '%s' "${RUNNER_ARCH:-X64}" | tr '[:upper:]' '[:lower:]')"
month="$(date -u +%Y-%m)"

# os.homedir() and path.join, as setup-java builds them: backslashes on Windows.
if [ "$os" = "Windows" ]; then
  home="${USERPROFILE:?USERPROFILE is not set}"
  s="\\"
  coursier="${home}${s}AppData${s}Local${s}Coursier${s}Cache"
elif [ "$os" = "macOS" ]; then
  home="$HOME"; s='/'; coursier="$home/Library/Caches/Coursier"
else
  home="$HOME"; s='/'; coursier="$home/.cache/coursier"
fi

hash="$(
  {
    # Without :(glob) magic a pathspec's * also matches /, so '*project/*.scala' is every .scala under any project/.
    git ls-files -z -- '*.sbt' '*project/build.properties' '*project/*.scala' \
      ':(exclude)docs/design/portable-spike/**' \
      .scalafmt.conf scripts/sbt-deps-cache-key.sh .github/actions/restore-sbt-deps \
      .github/workflows/dependency-cache.yml |
      sort -zu |
      while IFS= read -r -d '' f; do printf '== %s\n' "$f"; tr -d '\r' < "$f"; done
    printf '== spring\n'
    grep -oE -- '-Dllm4s\.[A-Za-z0-9]+=[^ ]+' .github/workflows/ci.yml | sort -u
  } | sha256sum | cut -c1-64
)"

prefix="sbt-deps-v1-${os}"
key="${prefix}-${month}-${hash}"
if [ "$mode" = "save" ]; then
  restore_keys="${prefix}-${month}-"
else
  restore_keys="${prefix}-${month}-
${prefix}-
setup-java-${os}-${arch}-sbt-"
fi

out="${GITHUB_OUTPUT:-/dev/stdout}"
{
  echo "key=$key"
  echo 'restore-keys<<EOF'
  echo "$restore_keys"
  echo 'EOF'
  echo 'paths<<EOF'
  echo "${home}${s}.ivy2${s}cache"
  echo "${home}${s}.sbt"
  echo "$coursier"
  echo "!${home}${s}.sbt${s}*.lock"
  echo "!${home}${s}**${s}ivydata-*.properties"
  echo 'EOF'
} >> "$out"
