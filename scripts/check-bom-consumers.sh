#!/usr/bin/env bash
# Builds a Gradle project and a Maven project that import org.llm4s:llm4s-bom and declare llm4s-core_3 and
# llm4s-ollama_3 with NO version, against a library already published to a local Maven repository
# (`sbt publishM2`). Each must compile a class that uses llm4s-core and resolve both modules to the BOM's
# version. A Gradle control project with the same two dependencies and no BOM must fail to resolve them, so a
# pass means the versions came from the BOM and from nowhere else (#1462).
#
# Usage: scripts/check-bom-consumers.sh <version> [maven-repo-dir]
#   <version>         the version the library was published at (CI: 0.1.0-SNAPSHOT)
#   [maven-repo-dir]  the local Maven repository to read; default ~/.m2/repository
# Maven is optional locally (the Maven project is skipped with a note when `mvn` is not on the PATH);
# set REQUIRE_MAVEN=1 to make a missing `mvn` a failure, as CI does.
set -euo pipefail

VERSION="${1:?usage: check-bom-consumers.sh <version> [maven-repo-dir]}"
REPO="${2:-$HOME/.m2/repository}"
REPO="$(cd "$REPO" && pwd)"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fail=0
note() { printf '%s\n' "$*"; }

write_java_source() {
  mkdir -p "$1/src/main/java/bomcheck"
  cat >"$1/src/main/java/bomcheck/App.java" <<'JAVA'
package bomcheck;

import org.llm4s.llmconnect.LLMConnect;

public class App {
    public static void main(String[] args) {
        System.out.println(LLMConnect.class.getName());
    }
}
JAVA
}

gradle_project() { # <dir> <with-bom: yes|no>
  local dir="$1" platform=""
  mkdir -p "$dir"
  if [ "$2" = yes ]; then platform="    implementation(platform(\"org.llm4s:llm4s-bom:$VERSION\"))"; fi
  echo 'rootProject.name = "bom-consumer"' >"$dir/settings.gradle.kts"
  cat >"$dir/build.gradle.kts" <<EOF
plugins { java }

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
$platform
    implementation("org.llm4s:llm4s-core_3")
    implementation("org.llm4s:llm4s-ollama_3")
}
EOF
  write_java_source "$dir"
}

# ---- Gradle, with the BOM: compiles, and both modules resolve to the BOM's version
gradle_project "$WORK/gradle" yes
if (cd "$WORK/gradle" && gradle -q --no-daemon -Dmaven.repo.local="$REPO" compileJava >"$WORK/gradle.log" 2>&1); then
  deps="$(cd "$WORK/gradle" && gradle -q --no-daemon -Dmaven.repo.local="$REPO" dependencies --configuration compileClasspath 2>&1)"
  for m in llm4s-core_3 llm4s-ollama_3; do
    if printf '%s\n' "$deps" | grep -Eq "org\.llm4s:$m -> $VERSION( |$)"; then
      note "ok    gradle: $m -> $VERSION (from the BOM)"
    else
      note "FAIL  gradle: $m did not resolve to $VERSION through the BOM"
      printf '%s\n' "$deps" | grep "org.llm4s:$m" || true
      fail=1
    fi
  done
  note "ok    gradle: a class using llm4s-core compiles"
else
  note "FAIL  gradle: the BOM consumer does not build"
  tail -30 "$WORK/gradle.log"
  fail=1
fi

# ---- Gradle control, without the BOM: the versionless dependencies must not resolve
gradle_project "$WORK/gradle-nobom" no
if (cd "$WORK/gradle-nobom" && gradle -q --no-daemon -Dmaven.repo.local="$REPO" compileJava >"$WORK/nobom.log" 2>&1); then
  note "FAIL  gradle control: built without the BOM, so the check above does not prove the BOM supplies the versions"
  fail=1
else
  note "ok    gradle control: without the BOM the versionless dependencies do not resolve"
fi

# ---- Maven, with the BOM imported in dependencyManagement
if command -v mvn >/dev/null 2>&1; then
  mkdir -p "$WORK/maven"
  cat >"$WORK/maven/pom.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>bomcheck</groupId>
  <artifactId>bom-consumer</artifactId>
  <version>1</version>
  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.llm4s</groupId>
        <artifactId>llm4s-bom</artifactId>
        <version>$VERSION</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.llm4s</groupId>
      <artifactId>llm4s-core_3</artifactId>
    </dependency>
    <dependency>
      <groupId>org.llm4s</groupId>
      <artifactId>llm4s-ollama_3</artifactId>
    </dependency>
  </dependencies>
</project>
EOF
  write_java_source "$WORK/maven"
  if (cd "$WORK/maven" && mvn -B -ntp -q -Dmaven.repo.local="$REPO" compile dependency:list -DoutputFile="$WORK/mvn-deps.txt" >"$WORK/maven.log" 2>&1); then
    for m in llm4s-core_3 llm4s-ollama_3; do
      if grep -Eq "org\.llm4s:$m:jar:$VERSION:compile" "$WORK/mvn-deps.txt"; then
        note "ok    maven: $m -> $VERSION (from the BOM)"
      else
        note "FAIL  maven: $m did not resolve to $VERSION through the BOM"
        fail=1
      fi
    done
    note "ok    maven: a class using llm4s-core compiles"
  else
    note "FAIL  maven: the BOM consumer does not build"
    tail -30 "$WORK/maven.log"
    fail=1
  fi
elif [ "${REQUIRE_MAVEN:-0}" = 1 ]; then
  note "FAIL  maven: mvn is not on the PATH and REQUIRE_MAVEN=1"
  fail=1
else
  note "skip  maven: mvn is not on the PATH (set REQUIRE_MAVEN=1 to make this a failure)"
fi

if [ "$fail" -ne 0 ]; then
  echo "The llm4s-bom consumer check failed."
  exit 1
fi
echo "llm4s-bom: Gradle and Maven consumers resolve llm4s-core_3 and llm4s-ollama_3 at $VERSION with no versions of their own."
