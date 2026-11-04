#!/usr/bin/env bash
# Runs the published doctor (llm4s-doctor, #2283) the ways docs/reference/doctor.md tells a user to, against a library
# already published to a local Maven repository (`sbt publishM2`), from empty directories outside this checkout:
#
#   - `cs launch` with llm4s-doctor and llm4s-ollama only, and --config: the report passes, and with a config that
#     names `openai` the "Provider module" step fails listing `ollama` as the only registered provider, which proves the
#     doctor checks the classpath it is launched on, with no --classpath and no llm4s source on it;
#   - a Maven project (llm4s-ollama, and llm4s-doctor in test scope) running `exec:java`, reading its own
#     src/main/resources/application.conf;
#   - a Gradle project running the documented `JavaExec` task, the same way.
#
# Usage: scripts/check-doctor-launch.sh <version> [maven-repo-dir]
#   <version>         the version the library was published at (CI: 0.1.0-SNAPSHOT)
#   [maven-repo-dir]  the local Maven repository to read; default ~/.m2/repository
# `cs` is downloaded when it is not on the PATH. Maven and Gradle are skipped with a note when they are not on the
# PATH; set REQUIRE_MAVEN=1 / REQUIRE_GRADLE=1 to make a missing one a failure, as CI does.
set -euo pipefail

VERSION="${1:?usage: check-doctor-launch.sh <version> [maven-repo-dir]}"
REPO="${2:-$HOME/.m2/repository}"
REPO="$(cd "$REPO" && pwd)"
CS_VERSION="v2.1.24"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fail=0
note() { printf '%s\n' "$*"; }

OLLAMA_CONF='llm4s.providers {
  provider = "local"
  local { provider = "ollama", model = "llama3", baseUrl = "http://localhost:11434" }
}'
OPENAI_CONF='llm4s.providers {
  provider = "main"
  main { provider = "openai", model = "gpt-4o-mini", apiKey = "sk-doctor-launch-check-0123456789" }
}'

# expect <label> <log> <wanted exit> <actual exit> <pattern>...: the run ended with the wanted exit code and its
# output has every pattern (extended regular expressions).
expect() {
  local label="$1" log="$2" want="$3" got="$4" ok=1
  shift 4
  if [ "$got" != "$want" ]; then
    note "FAIL  $label: exit code $got, expected $want"
    ok=0
  fi
  for pattern in "$@"; do
    if ! grep -Eq -- "$pattern" "$log"; then
      note "FAIL  $label: the output has no line matching: $pattern"
      ok=0
    fi
  done
  if [ "$ok" = 1 ]; then
    note "ok    $label"
  else
    tail -40 "$log"
    fail=1
  fi
}

# ---- Coursier: no project, just the doctor and one provider module
CS="$(command -v cs || true)"
if [ -z "$CS" ]; then
  case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) asset="cs-x86_64-pc-linux.gz" ;;
    Darwin-arm64) asset="cs-aarch64-apple-darwin.gz" ;;
    Darwin-x86_64) asset="cs-x86_64-apple-darwin.gz" ;;
    *) note "FAIL  cs: not on the PATH, and no download for $(uname -s)-$(uname -m)"; exit 1 ;;
  esac
  curl -fsSL "https://github.com/coursier/coursier/releases/download/$CS_VERSION/$asset" | gzip -d >"$WORK/cs"
  chmod +x "$WORK/cs"
  CS="$WORK/cs"
fi

mkdir -p "$WORK/cs-run"
printf '%s\n' "$OLLAMA_CONF" >"$WORK/cs-run/ollama.conf"
printf '%s\n' "$OPENAI_CONF" >"$WORK/cs-run/openai.conf"
launch() { # <config file> <log>
  (cd "$WORK/cs-run" && "$CS" launch -r "file://$REPO" \
    "org.llm4s:llm4s-doctor_3:$VERSION" "org.llm4s:llm4s-ollama_3:$VERSION" \
    -M org.llm4s.doctor.DoctorCli -- --config "$1") >"$2" 2>&1
}

code=0
launch ollama.conf "$WORK/cs-ollama.log" || code=$?
expect "cs launch: a config naming ollama passes" "$WORK/cs-ollama.log" 0 "$code" \
  '^  PASS  Provider module +.ollama. is registered by org\.llm4s\.llmconnect\.provider\.Llm4sOllamaModule \(llm4s-ollama_3-' \
  '^Result: [0-9]+ passed, 0 warnings, 0 failed'

code=0
launch openai.conf "$WORK/cs-openai.log" || code=$?
expect "cs launch: only the launched provider module is registered" "$WORK/cs-openai.log" 2 "$code" \
  '^  FAIL  Provider module' \
  'use a registered provider: ollama$'
if grep -q 'sk-doctor-launch-check' "$WORK/cs-openai.log"; then
  note "FAIL  cs launch: the report shows the configured API key"
  fail=1
fi

# ---- Maven: exec:java on the project's test classpath
if command -v mvn >/dev/null 2>&1; then
  mkdir -p "$WORK/maven/src/main/resources"
  printf '%s\n' "$OLLAMA_CONF" >"$WORK/maven/src/main/resources/application.conf"
  cat >"$WORK/maven/pom.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>doctorcheck</groupId>
  <artifactId>doctor-consumer</artifactId>
  <version>1</version>
  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  <dependencies>
    <dependency>
      <groupId>org.llm4s</groupId>
      <artifactId>llm4s-ollama_3</artifactId>
      <version>$VERSION</version>
    </dependency>
    <dependency>
      <groupId>org.llm4s</groupId>
      <artifactId>llm4s-doctor_3</artifactId>
      <version>$VERSION</version>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
EOF
  code=0
  (cd "$WORK/maven" && mvn -B -ntp -q -Dmaven.repo.local="$REPO" test-compile exec:java \
    -Dexec.mainClass=org.llm4s.doctor.DoctorCli -Dexec.classpathScope=test) >"$WORK/maven.log" 2>&1 || code=$?
  expect "maven exec:java: reads the project's application.conf and passes" "$WORK/maven.log" 0 "$code" \
    '^  PASS  Default provider +.local.' \
    '^  PASS  Provider module +.ollama. is registered'
elif [ "${REQUIRE_MAVEN:-0}" = 1 ]; then
  note "FAIL  maven: mvn is not on the PATH and REQUIRE_MAVEN=1"
  fail=1
else
  note "skip  maven: mvn is not on the PATH (set REQUIRE_MAVEN=1 to make this a failure)"
fi

# ---- Gradle: the documented JavaExec task on the test runtime classpath
if command -v gradle >/dev/null 2>&1; then
  mkdir -p "$WORK/gradle/src/main/resources"
  printf '%s\n' "$OLLAMA_CONF" >"$WORK/gradle/src/main/resources/application.conf"
  echo 'rootProject.name = "doctor-consumer"' >"$WORK/gradle/settings.gradle.kts"
  cat >"$WORK/gradle/build.gradle.kts" <<EOF
plugins { java }

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("org.llm4s:llm4s-ollama_3:$VERSION")
    testRuntimeOnly("org.llm4s:llm4s-doctor_3:$VERSION")
}

tasks.register<JavaExec>("llm4sDoctor") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.llm4s.doctor.DoctorCli")
}
EOF
  code=0
  (cd "$WORK/gradle" && gradle -q --no-daemon -Dmaven.repo.local="$REPO" llm4sDoctor) >"$WORK/gradle.log" 2>&1 ||
    code=$?
  expect "gradle JavaExec: reads the project's application.conf and passes" "$WORK/gradle.log" 0 "$code" \
    '^  PASS  Default provider +.local.' \
    '^  PASS  Provider module +.ollama. is registered'
elif [ "${REQUIRE_GRADLE:-0}" = 1 ]; then
  note "FAIL  gradle: gradle is not on the PATH and REQUIRE_GRADLE=1"
  fail=1
else
  note "skip  gradle: gradle is not on the PATH (set REQUIRE_GRADLE=1 to make this a failure)"
fi

if [ "$fail" -ne 0 ]; then
  echo "The llm4s-doctor launch check failed."
  exit 1
fi
echo "llm4s-doctor $VERSION runs from cs launch, Maven and Gradle projects on their own classpath."
