#!/usr/bin/env bash
# Tests for scripts/dependency-footprint.sh against a fake `cs`, with no network: the jar count, the total size and
# the "Brings" column of each row, the version passed through to every coordinate, the default version read from
# docs/_data/project.yml, and a failed resolution failing the script.
#
# Usage: scripts/test-dependency-footprint.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$REPO_ROOT/scripts/dependency-footprint.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FAILED=0

fail() {
  echo "FAIL: $1" >&2
  FAILED=1
}

# jars: core's two (1 MiB each), the Anthropic SDK and OkHttp (512 KiB each)
mkdir -p "$WORK/jars"
make_jar() { head -c "$2" /dev/zero >"$WORK/jars/$1"; }
make_jar scala3-library_3-3.7.1.jar 1048576
make_jar llm4s-core_3-9.9.9.jar 1048576
make_jar anthropic-java-core-2.65.0.jar 524288
make_jar okhttp-4.12.0.jar 524288

# The fake cs: records its arguments, and for `fetch` prints core's jars, plus the Anthropic ones when the anthropic
# module is asked for. FAKE_CS_FAIL makes it fail.
cat >"$WORK/cs" <<EOF
#!/usr/bin/env bash
echo "\$*" >>"$WORK/calls"
[ -z "\${FAKE_CS_FAIL:-}" ] || exit 1
[ "\$1" = fetch ] || exit 2
echo "$WORK/jars/scala3-library_3-3.7.1.jar"
echo "$WORK/jars/llm4s-core_3-9.9.9.jar"
case "\$*" in
  *llm4s-anthropic_3*) echo "$WORK/jars/anthropic-java-core-2.65.0.jar"; echo "$WORK/jars/okhttp-4.12.0.jar" ;;
esac
EOF
chmod +x "$WORK/cs"

out="$(CS="$WORK/cs" "$SCRIPT" 9.9.9)"

echo "$out" | grep -qF '| Dependencies (llm4s 9.9.9) | Jars | Size (MB) | Brings |' || fail "header names the version"
echo "$out" | grep -qF '| llm4s-core | 2 | 2.0 | - |' || fail "core row: 2 jars, 2.0 MB, brings nothing"
echo "$out" | grep -qF '| llm4s-core + llm4s-anthropic | 4 | 3.0 | OkHttp, Anthropic SDK |' ||
  fail "anthropic row: 4 jars, 3.0 MB, OkHttp and the Anthropic SDK"
[ "$(echo "$out" | grep -c '^| llm4s-')" -eq 8 ] || fail "one row per measured set (8)"
grep -qF 'fetch org.llm4s:llm4s-core_3:9.9.9 org.llm4s:llm4s-anthropic_3:9.9.9' "$WORK/calls" ||
  fail "a set is resolved together, every coordinate at the version"
grep -qF 'fetch org.llm4s:llm4s-java-api_3:9.9.9' "$WORK/calls" || fail "llm4s-java-api is measured"

# the default version is latest_release from docs/_data/project.yml
latest="$(sed -n 's/^latest_release: *"\{0,1\}\([^"]*\)"\{0,1\} *$/\1/p' "$REPO_ROOT/docs/_data/project.yml")"
default_out="$(CS="$WORK/cs" "$SCRIPT")"
echo "$default_out" | grep -qF "| Dependencies (llm4s $latest) |" || fail "default version is latest_release ($latest)"

if FAKE_CS_FAIL=1 CS="$WORK/cs" "$SCRIPT" 9.9.9 >/dev/null 2>"$WORK/err"; then
  fail "a failed resolution fails the script"
fi
grep -qF 'cs fetch failed for:' "$WORK/err" || fail "a failed resolution names the coordinates"

if CS="$WORK/cs" "$SCRIPT" 1 2 >/dev/null 2>&1; then fail "two arguments are a usage error"; fi

if [ "$FAILED" -ne 0 ]; then exit 1; fi
echo "dependency-footprint.sh: all checks passed"
