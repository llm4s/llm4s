#!/usr/bin/env bash
# Tests for scripts/verify-release.sh, with no network and no sbt.
#
# `curl` is replaced by a fake that serves a fixture Maven repository from disk: a URL under MAVEN_CENTRAL_URL answers
# 200 and the file when the file exists, 404 otherwise. The list of coordinates is passed with `--list`, as the script
# accepts it, so sbt is not needed either.
#
# The cases cover each kind of line `sbt listPublishedArtifacts` prints: `artifact` (POM, jar and the pointer javadoc
# jar), `gradle` (the Gradle-built llm4s-kotlin-api: POM, jar, javadoc jar, and a POM that depends on llm4s-java-api_3 at the release's version, #1494),
# `bom` (a POM with <dependencyManagement>) and `stub` (a POM with <relocation>); for the sbt-published ones, the .asc,
# .md5 and .sha1 beside each file, and no checksum of a signature or CycloneDX SBOM. The pointer and "nothing more"
# rules hold from 0.6.1; 0.6.0 and earlier, uploaded before them, are checked without them.
#
# `--attestations` (#2305) is tested against a fake `gh` that accepts `gh attestation verify <target> -R llm4s/llm4s
# --signer-workflow llm4s/llm4s/.github/workflows/release.yml` for a target named in a fixture list and refuses any
# other target, repository or signer.
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

# The fake gh: attested targets are listed, one per line (a file's base name, or an oci:// reference), in $FAKE_ATTESTED.
cat > "$WORK/bin/gh" <<'FAKE'
#!/usr/bin/env bash
[ "$1 $2" = "attestation verify" ] || { echo "unexpected: gh $*" >&2; exit 2; }
target="$3"; shift 3
[ "$*" = "-R llm4s/llm4s --signer-workflow llm4s/llm4s/.github/workflows/release.yml" ] ||
  { echo "unexpected flags: $*" >&2; exit 2; }
case "$target" in oci://*) name="$target" ;; *) [ -s "$target" ] || { echo "empty file" >&2; exit 1; }; name="$(basename "$target")" ;; esac
grep -qxF -- "$name" "$FAKE_ATTESTED" && exit 0
echo "Error: no attestations found for subject" >&2
exit 1
FAKE
chmod +x "$WORK/bin/gh"

VERSION=0.6.1   # the first release uploaded under the current rules (#2667); 0.6.0 is tested at the end

# pointer_jar <jar> <README.md text>: a javadoc jar holding a README.md, as project/JavadocPointer.scala writes one
pointer_jar() {
  local d; d="$(mktemp -d "$WORK/jar.XXXX")"
  printf '%s\n' "$2" > "$d/README.md"
  (cd "$d" && zip -q "$1" README.md)
}

# A fixture repository with every kind of coordinate present and correct.
fixture() { # <dir>
  local r="$1/org/llm4s"
  mkdir -p "$r/llm4s-core_3/$VERSION" "$r/llm4s-kotlin-api/$VERSION" "$r/llm4s-bom/$VERSION" "$r/core_3/$VERSION"
  echo '<project><artifactId>llm4s-core_3</artifactId></project>' > "$r/llm4s-core_3/$VERSION/llm4s-core_3-$VERSION.pom"
  echo jar > "$r/llm4s-core_3/$VERSION/llm4s-core_3-$VERSION.jar"
  echo sources > "$r/llm4s-core_3/$VERSION/llm4s-core_3-$VERSION-sources.jar"
  pointer_jar "$r/llm4s-core_3/$VERSION/llm4s-core_3-$VERSION-javadoc.jar" 'See https://llm4s.org/scaladoc/'
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
  echo dokka > "$r/llm4s-kotlin-api/$VERSION/llm4s-kotlin-api-$VERSION-javadoc.jar"
  echo '<project><dependencyManagement></dependencyManagement></project>' > "$r/llm4s-bom/$VERSION/llm4s-bom-$VERSION.pom"
  echo '<project><distributionManagement><relocation></relocation></distributionManagement></project>' > "$r/core_3/$VERSION/core_3-$VERSION.pom"
  # the .asc, .md5 and .sha1 Central requires beside every file of an sbt-published coordinate
  local f c
  for f in "$r"/llm4s-core_3/$VERSION/* "$r"/llm4s-bom/$VERSION/* "$r"/core_3/$VERSION/*; do
    for c in asc md5 sha1; do echo x > "$f.$c"; done
  done
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

# ---- --attestations ---------------------------------------------------------------------------------------------------
# run_attest <repo-dir> <attested-list>: as run, with --attestations
run_attest() {
  OUT="$(PATH="$WORK/bin:$PATH" FAKE_REPO="$1" FAKE_BASE="$BASE" MAVEN_CENTRAL_URL="$BASE" FAKE_ATTESTED="$2" \
    bash "$SCRIPT" "v$VERSION" --list "$WORK/list.txt" --attestations 2>&1)"
  RC=$?
}
printf '%s\n' "llm4s-core_3-$VERSION.jar" "llm4s-kotlin-api-$VERSION.jar" "llm4s-bom-$VERSION.pom" "core_3-$VERSION.pom" \
  "oci://ghcr.io/llm4s/workspace-runner:$VERSION" > "$WORK/attested.txt"

run_attest "$WORK/good" "$WORK/attested.txt"
expect "every artifact and the image attested" 0 "All 5 coordinates resolve at $VERSION, each with build provenance"
expect "the image is checked" 0 "ok       image    ghcr.io/llm4s/workspace-runner:$VERSION"

grep -vxF "llm4s-core_3-$VERSION.jar" "$WORK/attested.txt" > "$WORK/nocore.txt"
run_attest "$WORK/good" "$WORK/nocore.txt"
expect "a jar with no attestation is reported" 1 "no build provenance for llm4s-core_3-$VERSION.jar"

grep -vxF "llm4s-bom-$VERSION.pom" "$WORK/attested.txt" > "$WORK/nobom.txt"
run_attest "$WORK/good" "$WORK/nobom.txt"
expect "a POM-only artifact is checked by its POM" 1 "no build provenance for llm4s-bom-$VERSION.pom"

grep -v '^oci://' "$WORK/attested.txt" > "$WORK/noimage.txt"
run_attest "$WORK/good" "$WORK/noimage.txt"
expect "an image with no attestation is reported" 1 "MISSING  image    ghcr.io/llm4s/workspace-runner:$VERSION"

run_attest "$WORK/corejar" "$WORK/attested.txt"
expect "a missing jar is reported as missing, not as unattested" 1 "jar HTTP 404"

run good "$WORK/good"
if grep -q image <<<"$OUT"; then bad "without --attestations neither gh nor the image is used" "$OUT"
else ok "without --attestations neither gh nor the image is used"; fi

# ---- the javadoc jars --------------------------------------------------------------------------------------------------
CORE_JAVADOC="org/llm4s/llm4s-core_3/$VERSION/llm4s-core_3-$VERSION-javadoc.jar"

cp -R "$WORK/good" "$WORK/nodoc"
rm "$WORK/nodoc/$CORE_JAVADOC"
run nodoc "$WORK/nodoc"
expect "a Scala artifact without its javadoc jar is missing" 1 "javadoc jar HTTP 404"

cp -R "$WORK/good" "$WORK/bigdoc"
rm "$WORK/bigdoc/$CORE_JAVADOC"
pointer_jar "$WORK/bigdoc/$CORE_JAVADOC" 'See https://llm4s.org/scaladoc/'
head -c 60000 /dev/zero >> "$WORK/bigdoc/$CORE_JAVADOC"
run bigdoc "$WORK/bigdoc"
expect "a javadoc jar holding a module's Scaladoc again is refused" 1 "not the pointer jar"

cp -R "$WORK/good" "$WORK/nopointer"
rm "$WORK/nopointer/$CORE_JAVADOC"
pointer_jar "$WORK/nopointer/$CORE_JAVADOC" 'some other text'
run nopointer "$WORK/nopointer"
expect "a javadoc jar that does not point to the site's Scaladoc is refused" 1 "no README.md pointing to"

cp -R "$WORK/good" "$WORK/kotlinnodoc"
rm "$WORK/kotlinnodoc/org/llm4s/llm4s-kotlin-api/$VERSION/llm4s-kotlin-api-$VERSION-javadoc.jar"
run kotlinnodoc "$WORK/kotlinnodoc"
expect "a Kotlin artifact without its javadoc jar is missing" 1 "MISSING  gradle   llm4s-kotlin-api"

# ---- signatures and checksums, and nothing more --------------------------------------------------------------------------
cp -R "$WORK/good" "$WORK/noasc"
rm "$WORK/noasc/org/llm4s/llm4s-core_3/$VERSION/llm4s-core_3-$VERSION-sources.jar.asc"
run noasc "$WORK/noasc"
expect "a file without its signature is missing" 1 "llm4s-core_3-$VERSION-sources.jar.asc HTTP 404"

cp -R "$WORK/good" "$WORK/stubsha"
rm "$WORK/stubsha/org/llm4s/core_3/$VERSION/core_3-$VERSION.pom.sha1"
run stubsha "$WORK/stubsha"
expect "a stub's POM without its SHA-1 is missing" 1 "core_3-$VERSION.pom.sha1 HTTP 404"

cp -R "$WORK/good" "$WORK/ascsum"
echo x > "$WORK/ascsum/org/llm4s/llm4s-core_3/$VERSION/llm4s-core_3-$VERSION.jar.asc.md5"
run ascsum "$WORK/ascsum"
expect "a checksum of a signature is refused" 1 "llm4s-core_3-$VERSION.jar.asc.md5 was uploaded"

cp -R "$WORK/good" "$WORK/sbom"
echo '{}' > "$WORK/sbom/org/llm4s/llm4s-bom/$VERSION/llm4s-bom-$VERSION-cyclonedx.json"
run sbom "$WORK/sbom"
expect "an SBOM on Central is refused" 1 "llm4s-bom-$VERSION-cyclonedx.json was uploaded"

# ---- 0.6.0 and earlier predate the pointer javadoc jar and the slimmer upload ---------------------------------------------
NEW_VERSION="$VERSION"
VERSION=0.6.0
CORE_JAVADOC="org/llm4s/llm4s-core_3/$VERSION/llm4s-core_3-$VERSION-javadoc.jar"
fixture "$WORK/old"
rm "$WORK/old/$CORE_JAVADOC"
echo scaladoc > "$WORK/old/$CORE_JAVADOC"
head -c 60000 /dev/zero >> "$WORK/old/$CORE_JAVADOC"
echo x > "$WORK/old/org/llm4s/llm4s-core_3/$VERSION/llm4s-core_3-$VERSION.jar.asc.md5"
echo x > "$WORK/old/org/llm4s/core_3/$VERSION/core_3-$VERSION.pom.asc.md5"
echo '{}' > "$WORK/old/org/llm4s/llm4s-core_3/$VERSION/llm4s-core_3-$VERSION-cyclonedx.json"
run old "$WORK/old"
expect "0.6.0's full Scaladoc javadoc jar, signature checksums and SBOMs are accepted" 0 "All 4 coordinates resolve at 0.6.0."

cp -R "$WORK/old" "$WORK/oldnodoc"
rm "$WORK/oldnodoc/$CORE_JAVADOC"
run oldnodoc "$WORK/oldnodoc"
expect "0.6.0 still needs a javadoc jar" 1 "javadoc jar HTTP 404"

cp -R "$WORK/old" "$WORK/oldnoasc"
rm "$WORK/oldnoasc/org/llm4s/llm4s-core_3/$VERSION/llm4s-core_3-$VERSION.pom.asc"
run oldnoasc "$WORK/oldnoasc"
expect "0.6.0 still needs every signature" 1 "llm4s-core_3-$VERSION.pom.asc HTTP 404"

# A pre-release of 0.6.1 is uploaded under the current rules.
VERSION=0.6.1-RC1
fixture "$WORK/rc"
echo x > "$WORK/rc/org/llm4s/llm4s-core_3/$VERSION/llm4s-core_3-$VERSION.jar.asc.md5"
run rc "$WORK/rc"
expect "0.6.1-RC1 is held to the current rules" 1 "llm4s-core_3-$VERSION.jar.asc.md5 was uploaded"
VERSION="$NEW_VERSION"

# ---- the list sbt prints feeds the gradle lines through ---------------------------------------------------------------
if grep -qF "'^(artifact|gradle|bom|stub) '" "$SCRIPT"; then
  ok "the list read from sbt keeps the gradle lines"
else
  bad "the list read from sbt keeps the gradle lines" "verify-release.sh filters sbt's output without 'gradle'"
fi

echo
echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
