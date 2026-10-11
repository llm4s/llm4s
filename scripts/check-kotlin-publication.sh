#!/usr/bin/env bash
# Checks the Maven publication of org.llm4s:llm4s-kotlin-api that the Gradle build in modules/kotlin-api wrote to a file
# repository (`gradle publishMavenPublicationToStagingRepository`, build/staging-deploy), before anything is uploaded
# (#1494). CI runs it on every pull request on an unsigned dry run; the release workflow runs it on the signed
# publication with REQUIRE_SIGNATURES=1 before scripts/publish-kotlin-api.sh uploads it.
#
#   1. Files: the POM, the jar, the sources jar (with the Kotlin sources) and the javadoc jar (with Dokka's HTML), as
#      Maven Central requires; with REQUIRE_SIGNATURES=1, a `.asc` signature beside each of them.
#   2. POM: the coordinates and version, a name and a description, and the project metadata - url, licences,
#      organization, scm, developers, version scheme - equal to the POM sbt wrote for llm4s-java-api_3, so the Kotlin
#      artifact cannot drift from the rest of the release. It depends on llm4s-java-api_3 at the same version, at
#      compile scope (its signatures expose the facade's types), and on no snapshot unless it is one.
#   3. A throwaway Gradle project imports llm4s-bom, declares llm4s-kotlin-api with NO version, and compiles Kotlin code
#      that uses AgentKt, AgentStreamItem, a JAgentResult and a Flow: the BOM supplies the version, and the facade and
#      kotlinx-coroutines reach the consumer's compile classpath through the POM.
#
# Usage: scripts/check-kotlin-publication.sh <version> <staging-repo> [maven-repo]
#   <version>       the version the publication was written at (CI: 0.1.0-SNAPSHOT; a release: the tag without its v)
#   <staging-repo>  the file repository Gradle published to (modules/kotlin-api/build/staging-deploy)
#   [maven-repo]    the local Maven repository sbt published the rest of the library to (`sbt publishM2`);
#                   default ~/.m2/repository
# Needs python3 and gradle (and network access for the consumer's Kotlin plugin and kotlinx-coroutines).
# SKIP_CONSUMER=1 skips step 3.
set -euo pipefail

VERSION="${1:?usage: check-kotlin-publication.sh <version> <staging-repo> [maven-repo]}"
STAGING="$(cd "${2:?usage: check-kotlin-publication.sh <version> <staging-repo> [maven-repo]}" && pwd)"
REPO="$(cd "${3:-$HOME/.m2/repository}" && pwd)"
ARTIFACT=llm4s-kotlin-api
DIR="$STAGING/org/llm4s/$ARTIFACT/$VERSION"
REFERENCE_POM="$REPO/org/llm4s/llm4s-java-api_3/$VERSION/llm4s-java-api_3-$VERSION.pom"

fail=0
note() { printf '%s\n' "$*"; }
problem() { note "FAIL  $*"; fail=1; }

[ -d "$DIR" ] || { note "FAIL  no publication at $DIR: did the Gradle build publish $ARTIFACT $VERSION there?"; exit 1; }
[ -f "$REFERENCE_POM" ] || { note "FAIL  no llm4s-java-api_3 $VERSION POM in $REPO: run sbt publishM2 first"; exit 1; }

# A snapshot published to a file repository carries a timestamp in its file names (llm4s-kotlin-api-0.1.0-20261011.120000-1.jar),
# so the files are found by their suffix; a release has exactly one of each.
one() { # <suffix>: the single file in $DIR whose name ends with it, not a checksum or a signature
  local matches
  matches="$(find "$DIR" -maxdepth 1 -type f -name "$ARTIFACT-*$1" | sort)"
  if [ -z "$matches" ]; then return 1; fi
  printf '%s\n' "$matches" | tail -1
}

# ---- 1. files -------------------------------------------------------------------------------------------------------
POM="$(one .pom)" || { note "FAIL  no POM in $DIR"; exit 1; }
for kind in "jar:.jar" "sources jar:-sources.jar" "javadoc jar:-javadoc.jar"; do
  label="${kind%%:*}"; suffix="${kind#*:}"
  if [ "$suffix" = .jar ]; then
    file="$(find "$DIR" -maxdepth 1 -type f -name "$ARTIFACT-*.jar" ! -name '*-sources.jar' ! -name '*-javadoc.jar' | sort | tail -1)"
  else
    file="$(one "$suffix" || true)"
  fi
  if [ -z "$file" ]; then problem "no $label"; continue; fi
  note "ok    $label: $(basename "$file")"
  # read the listing whole: `unzip | grep -q` can fail under pipefail when grep stops reading early
  entries="$(unzip -Z1 "$file")"
  case "$label" in
    "sources jar") grep -qx 'org/llm4s/kotlin/AgentKt.kt' <<<"$entries" || problem "the sources jar has no AgentKt.kt" ;;
    "javadoc jar") grep -qx 'index.html' <<<"$entries" || problem "the javadoc jar has no index.html (Dokka's output)" ;;
  esac
done

if [ "${REQUIRE_SIGNATURES:-0}" = 1 ]; then
  unsigned=0
  while IFS= read -r f; do
    [ -f "$f.asc" ] || { problem "not signed: $(basename "$f")"; unsigned=1; }
  done < <(find "$DIR" -maxdepth 1 -type f \( -name '*.pom' -o -name '*.jar' -o -name '*.module' \))
  [ "$unsigned" = 1 ] || note "ok    every POM, jar and module file has a .asc signature"
fi

# ---- 2. the POM -----------------------------------------------------------------------------------------------------
if python3 - "$POM" "$REFERENCE_POM" "$VERSION" "$ARTIFACT" <<'PY'; then :; else fail=1; fi
import sys
import xml.etree.ElementTree as ET

pom_path, ref_path, version, artifact = sys.argv[1:5]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
pom, ref = ET.parse(pom_path).getroot(), ET.parse(ref_path).getroot()
ok = True

def say(good, message):
    global ok
    print(("ok    " if good else "FAIL  ") + message)
    ok = ok and good

def text(root, path):
    e = root.find(path, NS)
    return (e.text or "").strip() if e is not None else ""

def canon(e):
    """An element as nested (tag, text, sorted children), so child order and whitespace do not matter."""
    if e is None:
        return None
    return (e.tag, (e.text or "").strip(), tuple(sorted(canon(c) for c in e)))

say(text(pom, "m:groupId") == "org.llm4s", f"groupId is org.llm4s ({text(pom, 'm:groupId')!r})")
say(text(pom, "m:artifactId") == artifact, f"artifactId is {artifact} ({text(pom, 'm:artifactId')!r})")
say(text(pom, "m:version") == version, f"version is {version} ({text(pom, 'm:version')!r})")
say(text(pom, "m:name") != "", "the POM has a name")
say(text(pom, "m:description") != "", "the POM has a description")
for field in ["url", "licenses", "organization", "scm", "developers", "properties/m:info.versionScheme"]:
    mine, theirs = canon(pom.find("m:" + field, NS)), canon(ref.find("m:" + field, NS))
    say(theirs is not None and mine == theirs, f"<{field.replace('/m:', '/')}> matches the sbt-published llm4s-java-api_3 POM")

deps = {}
for d in pom.findall("m:dependencies/m:dependency", NS):
    deps[(text(d, "m:groupId"), text(d, "m:artifactId"))] = (text(d, "m:version"), text(d, "m:scope") or "compile")
facade = deps.get(("org.llm4s", "llm4s-java-api_3"))
say(facade == (version, "compile"), f"depends on llm4s-java-api_3 {version} at compile scope ({facade})")
coroutines = [v for (g, a), v in deps.items() if g == "org.jetbrains.kotlinx" and a.startswith("kotlinx-coroutines-core")]
say(len(coroutines) == 1 and coroutines[0][1] == "compile", f"depends on kotlinx-coroutines-core at compile scope ({coroutines})")
if not version.endswith("-SNAPSHOT"):
    snapshots = [f"{g}:{a}:{v}" for (g, a), (v, _) in deps.items() if v.endswith("-SNAPSHOT")]
    say(not snapshots, f"a release depends on no snapshot ({snapshots})")
sys.exit(0 if ok else 1)
PY

# ---- 3. a consumer that takes the version from the BOM ----------------------------------------------------------------
if [ "${SKIP_CONSUMER:-0}" = 1 ]; then
  note "skip  the consumer project (SKIP_CONSUMER=1)"
else
  WORK="$(mktemp -d)"
  trap 'rm -rf "$WORK"' EXIT
  mkdir -p "$WORK/src/main/kotlin/consumer"
  echo 'rootProject.name = "kotlin-api-consumer"' > "$WORK/settings.gradle.kts"
  cat > "$WORK/build.gradle.kts" <<EOF
plugins { kotlin("jvm") version "2.0.21" }

repositories {
    // only these: the publication under test, the library sbt published, and Central for third-party dependencies
    maven { url = uri("file://$STAGING") }
    maven { url = uri("file://$REPO") }
    mavenCentral()
}

dependencies {
    implementation(platform("org.llm4s:llm4s-bom:$VERSION"))
    implementation("org.llm4s:$ARTIFACT")
}

EOF
  cat > "$WORK/src/main/kotlin/consumer/Consumer.kt" <<'KT'
package consumer

import kotlinx.coroutines.flow.Flow
import org.llm4s.javaapi.JAgentResult
import org.llm4s.kotlin.AgentKt
import org.llm4s.kotlin.AgentStreamItem
import org.llm4s.kotlin.LLMClientKt
import org.llm4s.kotlin.LLMException

suspend fun ask(client: LLMClientKt, question: String): String = client.complete(question)

suspend fun turn(agent: AgentKt, query: String): JAgentResult = agent.run(query)

fun events(agent: AgentKt, threadId: String, query: String): Flow<AgentStreamItem> = agent.stream(threadId, query)

fun describe(e: LLMException): String = e.message.orEmpty()
KT
  if (cd "$WORK" && gradle -q --no-daemon compileKotlin >"$WORK/build.log" 2>&1); then
    note "ok    a project that imports llm4s-bom compiles Kotlin code against $ARTIFACT, the facade and Flow"
    deps="$(cd "$WORK" && gradle -q --no-daemon dependencies --configuration compileClasspath 2>&1)"
    if grep -Eq "org\.llm4s:$ARTIFACT -> $VERSION( |$)" <<<"$deps"; then
      note "ok    $ARTIFACT resolves to $VERSION through the BOM"
    else
      problem "$ARTIFACT did not resolve to $VERSION through the BOM"
      printf '%s\n' "$deps" | grep "org.llm4s" || true
    fi
  else
    problem "the consumer project does not build"
    tail -30 "$WORK/build.log"
  fi
fi

if [ "$fail" -ne 0 ]; then
  note "The $ARTIFACT $VERSION publication in $STAGING is not fit to upload."
  exit 1
fi
note "The $ARTIFACT $VERSION publication in $STAGING is complete and consistent with the sbt release."
