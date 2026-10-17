#!/usr/bin/env bash
# Tests for scripts/verify-release.sh, with no network and no sbt.
#
# `curl` is replaced by a fake that serves a fixture Maven repository from disk: a URL under MAVEN_CENTRAL_URL answers
# 200 and the file when the file exists, 404 otherwise. The list of coordinates is passed with `--list`, as the script
# accepts it, so sbt is not needed either.
#
# The cases cover each kind of line `sbt listPublishedArtifacts` prints: `artifact` (POM and jar), `gradle` (the
# Gradle-built llm4s-kotlin-api: POM, jar, and a POM that depends on llm4s-java-api_3 at the release's version, #1494),
# `bom` (a POM with <dependencyManagement>) and `stub` (a POM with <relocation>).
#
# Usage: scripts/test-verify-release.sh
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="${VERIFY_SCRIPT:-$REPO_ROOT/scripts/verify-release.sh}"   # VERIFY_SCRIPT: run the tests against another copy
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

FAIL=0
PASS=0
ok()  { PASS=$((PASS + 1)); echo "ok   [$1]"; }
bad() { FAIL=$((FAIL + 1)); echo "FAIL [$1]"; [ $# -lt 2 ] || printf '       %s\n' "$2"; }

BASE="https://central.invalid/maven2"

# ---- the fake curl ---------------------------------------------------------------------------------------------------
mkdir -p "$WORK/bin"
cat > "$WORK/bin/curl" <<'FAKE'
#!/usr/bin/env bash
url=""; write_out=""; prev=""
for a in "$@"; do
  case "$prev" in -w) write_out="$a" ;; esac
  case "$a" in http*) url="$a" ;; esac
  prev="$a"
done
file="$FAKE_REPO/${url#"$FAKE_BASE"/}"
if [ -f "$file" ]; then code=200; else code=404; fi
if [ -n "$write_out" ]; then printf '%s' "$code"; exit 0; fi
[ "$code" = 200 ] && cat "$file"
exit 0
FAKE
chmod +x "$WORK/bin/curl"

VERSION=0.5.0

# A fixture repository with every kind of coordinate present and correct.
fixture() { # <dir>
  local r="$1/org/llm4s"
  mkdir -p "$r/llm4s-core_3/$VERSION" "$r/llm4s-kotlin-api/$VERSION" "$r/llm4s-bom/$VERSION" "$r/core_3/$VERSION"
  echo '<project><artifactId>llm4s-core_3</artifactId></project>' > "$r/llm4s-core_3/$VERSION/llm4s-core_3-$VERSION.pom"
  echo jar > "$r/llm4s-core_3/$VERSION/llm4s-core_3-$VERSION.jar"
  cat > "$r/llm4s-kotlin-api/$VERSION/llm4s-kotlin-api-$VERSION.pom" <<POM
<project>
  <artifactId>llm4s-kotlin-api</artifactId>
  <dependencies>
    <dependency>
      <groupId>org.llm4s</groupId>
      <artifactId>llm4s-java-api_3</artifactId>
      <version>$VERSION</version>
      <scope>compile</scope>
    </dependency>
  </dependencies>
</project>
POM
  echo jar > "$r/llm4s-kotlin-api/$VERSION/llm4s-kotlin-api-$VERSION.jar"
  echo '<project><dependencyManagement></dependencyManagement></project>' > "$r/llm4s-bom/$VERSION/llm4s-bom-$VERSION.pom"
  echo '<project><distributionManagement><relocation></relocation></distributionManagement></project>' > "$r/core_3/$VERSION/core_3-$VERSION.pom"
}

printf '%s\n' "artifact llm4s-core_3" "gradle llm4s-kotlin-api" "bom llm4s-bom" "stub core_3" > "$WORK/list.txt"

# run <case-name> <repo-dir>: runs the script, leaving its exit code in RC and its output in OUT
run() {
  OUT="$(PATH="$WORK/bin:$PATH" FAKE_REPO="$2" FAKE_BASE="$BASE" MAVEN_CENTRAL_URL="$BASE" \
    bash "$SCRIPT" "v$VERSION" --list "$WORK/list.txt" 2>&1)"
  RC=$?
}

expect() { # <case> <expected rc> <expected output fragment>
  if [ "$RC" = "$2" ] && grep -qF -- "$3" <<<"$OUT"; then ok "$1"; else bad "$1" "rc=$RC, wanted $2 and '$3' in: $OUT"; fi
}

# ---- every coordinate present -------------------------------------------------------------------------------------
fixture "$WORK/good"
run good "$WORK/good"
expect "all four kinds resolve" 0 "All 4 coordinates resolve at $VERSION."
expect "the gradle line is checked, not skipped" 0 "ok       gradle   llm4s-kotlin-api"

# ---- the Gradle-built artifact ------------------------------------------------------------------------------------
cp -R "$WORK/good" "$WORK/nojar"
rm "$WORK/nojar/org/llm4s/llm4s-kotlin-api/$VERSION/llm4s-kotlin-api-$VERSION.jar"
run nojar "$WORK/nojar"
expect "a Kotlin artifact without its jar is missing" 1 "MISSING  gradle   llm4s-kotlin-api"
expect "and the reason is the jar" 1 "jar HTTP 404"

cp -R "$WORK/good" "$WORK/nopom"
rm -r "$WORK/nopom/org/llm4s/llm4s-kotlin-api"
run nopom "$WORK/nopom"
expect "a Kotlin artifact that was never published is missing" 1 "pom HTTP 404"

cp -R "$WORK/good" "$WORK/skew"
sed -i.bak "s|<version>$VERSION</version>|<version>0.4.9</version>|" \
  "$WORK/skew/org/llm4s/llm4s-kotlin-api/$VERSION/llm4s-kotlin-api-$VERSION.pom"
run skew "$WORK/skew"
expect "a Kotlin artifact built against another release's facade is refused" 1 \
  "pom does not depend on llm4s-java-api_3 $VERSION"

cp -R "$WORK/good" "$WORK/snapshot"
sed -i.bak "s|<version>$VERSION</version>|<version>$VERSION-SNAPSHOT</version>|" \
  "$WORK/snapshot/org/llm4s/llm4s-kotlin-api/$VERSION/llm4s-kotlin-api-$VERSION.pom"
run snapshot "$WORK/snapshot"
expect "a Kotlin artifact that depends on a snapshot of the facade is refused" 1 \
  "pom does not depend on llm4s-java-api_3 $VERSION"

# ---- the other kinds still behave as before --------------------------------------------------------------------------
cp -R "$WORK/good" "$WORK/nostubrel"
echo '<project/>' > "$WORK/nostubrel/org/llm4s/core_3/$VERSION/core_3-$VERSION.pom"
run nostubrel "$WORK/nostubrel"
expect "a stub without <relocation> is missing" 1 "pom has no <relocation>"

cp -R "$WORK/good" "$WORK/corejar"
rm "$WORK/corejar/org/llm4s/llm4s-core_3/$VERSION/llm4s-core_3-$VERSION.jar"
run corejar "$WORK/corejar"
expect "a Scala artifact without its jar is missing" 1 "MISSING  artifact llm4s-core_3"

# ---- the list sbt prints feeds the gradle lines through ---------------------------------------------------------------
if grep -qF "'^(artifact|gradle|bom|stub) '" "$SCRIPT"; then
  ok "the list read from sbt keeps the gradle lines"
else
  bad "the list read from sbt keeps the gradle lines" "verify-release.sh filters sbt's output without 'gradle'"
fi

echo
echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
