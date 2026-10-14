#!/usr/bin/env bash
# Tests for scripts/check-platform-tests.sh: the repository passes, and each way platform-sensitive code can drop out
# of the Windows run fails with a message naming it. The cases run the check against a small fixture repository built
# in a scratch directory (a module `whole` that runs whole on Windows, a module `pure` with one named suite), changing
# one thing each.
#
# No network, no sbt. Usage: scripts/test-check-platform-tests.sh [build-model.json]   (default target/build-model.json)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="$REPO_ROOT/scripts/check-platform-tests.sh"
MODEL="${1:-$REPO_ROOT/target/build-model.json}"
[ -f "$MODEL" ] || { echo "No build model at $MODEL; write it with sbt \"dumpBuildModel $MODEL\"" >&2; exit 2; }
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PASSED=0
FAILED=0

# The fixture: `whole` runs whole on Windows; `pure` runs only org.pure.FilesSpec there. pure's Sink.scala writes a
# file and is listed as covered by that suite; Parser.scala is pure logic.
fresh() {
  rm -rf "$WORK/case"
  local r="$WORK/case/repo"
  mkdir -p "$r/modules/whole/src/main/scala" "$r/modules/pure/src/main/scala" "$r/modules/pure/src/test/scala"
  printf 'object Shell { def run() = new ProcessBuilder("ls").start() }\n' > "$r/modules/whole/src/main/scala/Shell.scala"
  printf 'object Sink { def put(p: java.nio.file.Path) = java.nio.file.Files.writeString(p, "x") }\n' \
    > "$r/modules/pure/src/main/scala/Sink.scala"
  printf 'object Parser { def parse(s: String) = s.trim }\n' > "$r/modules/pure/src/main/scala/Parser.scala"
  printf 'package org.pure\nclass FilesSpec\n' > "$r/modules/pure/src/test/scala/FilesSpec.scala"
  cat > "$WORK/case/model.json" <<'JSON'
{"projects":[{"id":"whole","base":"modules/whole"},{"id":"pure","base":"modules/pure"}],
 "platformTests":{"modules":["whole"],"suites":{"pure":["org.pure.FilesSpec"]}}}
JSON
  printf '# comment\nmodules/pure/src/main/scala/Sink.scala  pure:org.pure.FilesSpec\n' > "$WORK/case/sources.txt"
  cat > "$WORK/case/ci.yml" <<'YAML'
jobs:
  test:
    steps:
      - name: Run tests
        run: sbt ${{ runner.os == 'Windows' && 'testPlatform' || 'test' }}
YAML
}

run_check() {
  "$CHECK" --model "$WORK/case/model.json" --sources "$WORK/case/sources.txt" --root "$WORK/case/repo" \
    --workflow "$WORK/case/ci.yml" > "$WORK/out" 2>&1
}

expect_pass() {
  if run_check; then
    PASSED=$((PASSED + 1)); echo "ok   $1"
  else
    FAILED=$((FAILED + 1)); echo "FAIL $1 (expected to pass)"; sed 's/^/       /' "$WORK/out"
  fi
}

# expect_fail NAME PATTERN : the check must fail and its output must match PATTERN (grep -E).
expect_fail() {
  if run_check; then
    FAILED=$((FAILED + 1)); echo "FAIL $1 (expected to fail)"; sed 's/^/       /' "$WORK/out"
  elif grep -qE "$2" "$WORK/out"; then
    PASSED=$((PASSED + 1)); echo "ok   $1"
  else
    FAILED=$((FAILED + 1)); echo "FAIL $1 (failed, but without /$2/)"; sed 's/^/       /' "$WORK/out"
  fi
}

# The repository itself, against the given build model.
if "$CHECK" --model "$MODEL" > "$WORK/out" 2>&1; then
  PASSED=$((PASSED + 1)); echo "ok   the repository passes"
else
  FAILED=$((FAILED + 1)); echo "FAIL the repository passes (expected to pass)"; sed 's/^/       /' "$WORK/out"
fi

fresh
expect_pass "the fixture passes"

fresh
printf 'object Tmp { def make() = java.nio.file.Files.createTempFile("a", "b") }\n' \
  > "$WORK/case/repo/modules/pure/src/main/scala/Tmp.scala"
expect_fail "a new platform-sensitive file outside the whole modules fails" \
  "modules/pure/src/main/scala/Tmp.scala calls a platform-sensitive API \(.*createTemp"

fresh
printf 'object Fx0 { def f(p: java.nio.file.Path) = Files.createDirectory(p) }\n' \
  > "$WORK/case/repo/modules/pure/src/main/scala/Fx0.scala"
expect_fail "Files.createDirectory(p) is platform-sensitive" "Fx0.scala calls a platform-sensitive API \(.*createDirectory"

fresh
printf 'object Fx1 { def f(p: java.nio.file.Path) = Files.createDirectories(p) }\n' \
  > "$WORK/case/repo/modules/pure/src/main/scala/Fx1.scala"
expect_fail "Files.createDirectories(p) is platform-sensitive" "Fx1.scala calls a platform-sensitive API \(.*createDirectories"

fresh
printf 'object Fx2 { def f(p: java.nio.file.Path) = Files.deleteIfExists(p) }\n' \
  > "$WORK/case/repo/modules/pure/src/main/scala/Fx2.scala"
expect_fail "Files.deleteIfExists(p) is platform-sensitive" "Fx2.scala calls a platform-sensitive API \(.*deleteIfExists"

fresh
printf 'object Fx3 { def f(p: java.nio.file.Path) = Files.delete(p) }\n' \
  > "$WORK/case/repo/modules/pure/src/main/scala/Fx3.scala"
expect_fail "Files.delete(p) is platform-sensitive" "Fx3.scala calls a platform-sensitive API \(.*delete"

fresh
printf 'object Fx4 { def f(p: java.nio.file.Path) = Files.createTempFile("a", "b") }\n' \
  > "$WORK/case/repo/modules/pure/src/main/scala/Fx4.scala"
expect_fail "Files.createTempFile("a", "b") is platform-sensitive" "Fx4.scala calls a platform-sensitive API \(.*createTemp"

fresh
printf 'object Fx5 { def f(p: java.nio.file.Path) = Files.createTempDirectory("a") }\n' \
  > "$WORK/case/repo/modules/pure/src/main/scala/Fx5.scala"
expect_fail "Files.createTempDirectory("a") is platform-sensitive" "Fx5.scala calls a platform-sensitive API \(.*createTemp"

fresh
printf 'object Fx6 { def f(p: java.nio.file.Path) = Files.newBufferedWriter(p) }\n' \
  > "$WORK/case/repo/modules/pure/src/main/scala/Fx6.scala"
expect_fail "Files.newBufferedWriter(p) is platform-sensitive" "Fx6.scala calls a platform-sensitive API \(.*newBufferedWriter"

fresh
printf 'object Fx7 { def f(p: java.nio.file.Path) = Files.move(p, p) }\n' \
  > "$WORK/case/repo/modules/pure/src/main/scala/Fx7.scala"
expect_fail "Files.move(p, p) is platform-sensitive" "Fx7.scala calls a platform-sensitive API \(.*move"

fresh
printf 'object Fx8 { def f(p: java.nio.file.Path) = Files.copy(p, p) }\n' \
  > "$WORK/case/repo/modules/pure/src/main/scala/Fx8.scala"
expect_fail "Files.copy(p, p) is platform-sensitive" "Fx8.scala calls a platform-sensitive API \(.*copy"

fresh
printf 'object Fx9 { def f(p: java.nio.file.Path) = Files.setPosixFilePermissions(p, null) }\n' \
  > "$WORK/case/repo/modules/pure/src/main/scala/Fx9.scala"
expect_fail "Files.setPosixFilePermissions(p, null) is platform-sensitive" "Fx9.scala calls a platform-sensitive API \(.*setPosixFilePermissions"

fresh
printf 'object Fx10 { def f(p: java.nio.file.Path) = p.toRealPath() }\n' \
  > "$WORK/case/repo/modules/pure/src/main/scala/Fx10.scala"
expect_fail "p.toRealPath() is platform-sensitive" "Fx10.scala calls a platform-sensitive API \(.*toRealPath"

fresh
printf 'object Fx11 { def f(p: java.nio.file.Path) = Files.createFile(p) }\n' \
  > "$WORK/case/repo/modules/pure/src/main/scala/Fx11.scala"
expect_fail "Files.createFile(p) is platform-sensitive" "Fx11.scala calls a platform-sensitive API \(.*createFile"

fresh
printf 'object Fx12 { def f(p: java.nio.file.Path) = Files.createLink(p, p) }\n' \
  > "$WORK/case/repo/modules/pure/src/main/scala/Fx12.scala"
expect_fail "Files.createLink(p, p) is platform-sensitive" "Fx12.scala calls a platform-sensitive API \(.*createLink"

fresh
printf 'object Fx13 { def f(p: java.nio.file.Path) = Files.newOutputStream(p) }\n' \
  > "$WORK/case/repo/modules/pure/src/main/scala/Fx13.scala"
expect_fail "Files.newOutputStream(p) is platform-sensitive" "Fx13.scala calls a platform-sensitive API \(.*newOutputStream"

fresh
printf 'object Fx14 { def f(p: java.nio.file.Path) = Files.write(p, Array[Byte]()) }\n' \
  > "$WORK/case/repo/modules/pure/src/main/scala/Fx14.scala"
expect_fail "Files.write(p, Array[Byte]()) is platform-sensitive" "Fx14.scala calls a platform-sensitive API \(.*write"

fresh
printf 'object Fx15 { def f(p: java.nio.file.Path) = new java.io.File("a").renameTo(null) }\n' \
  > "$WORK/case/repo/modules/pure/src/main/scala/Fx15.scala"
expect_fail "new java.io.File("a").renameTo(null) is platform-sensitive" "Fx15.scala calls a platform-sensitive API \(.*renameTo"

fresh
printf 'object Fx16 { def f(p: java.nio.file.Path) = new java.io.File("a").mkdirs() }\n' \
  > "$WORK/case/repo/modules/pure/src/main/scala/Fx16.scala"
expect_fail "new java.io.File("a").mkdirs() is platform-sensitive" "Fx16.scala calls a platform-sensitive API \(.*mkdirs"

fresh
printf 'object Fx17 { def f(p: java.nio.file.Path) = new java.io.FileWriter("a") }\n' \
  > "$WORK/case/repo/modules/pure/src/main/scala/Fx17.scala"
expect_fail "new java.io.FileWriter("a") is platform-sensitive" "Fx17.scala calls a platform-sensitive API \(.*FileWriter"

fresh
printf 'object Ok { def f(p: java.nio.file.Path) = java.nio.file.Files.readString(p) + java.nio.file.Files.exists(p) }\n' \
  > "$WORK/case/repo/modules/pure/src/main/scala/Ok.scala"
expect_pass "reading and testing for a file is not platform-sensitive"

fresh
printf 'object More { def run() = sys.process.Process("ls").! }\n' > "$WORK/case/repo/modules/whole/src/main/scala/More.scala"
expect_pass "a new platform-sensitive file in a whole module needs no line"

fresh
printf 'modules/pure/src/main/scala/Parser.scala  none: pure, but listed anyway\n' >> "$WORK/case/sources.txt"
expect_pass "a listed file need not match the pattern"

fresh
printf 'modules/pure/src/main/scala/Gone.scala  pure:org.pure.FilesSpec\n' >> "$WORK/case/sources.txt"
expect_fail "a listed file that does not exist fails" "Gone.scala does not exist"

fresh
printf 'modules/pure/src/main/scala/Sink.scala  pure:org.pure.OtherSpec\n' > "$WORK/case/sources.txt"
expect_fail "coverage by a suite PlatformTests does not list fails" "which PlatformTests.suites does not list for pure"

fresh
printf 'modules/pure/src/main/scala/Sink.scala  whole:org.pure.FilesSpec\n' > "$WORK/case/sources.txt"
expect_fail "coverage by a module with no named suites fails" "PlatformTests.suites has no entry for whole"

fresh
printf 'modules/pure/src/main/scala/Sink.scala  none\n' > "$WORK/case/sources.txt"
expect_fail "none without a reason fails" "needs a reason"

fresh
printf 'modules/pure/src/main/scala/Sink.scala\n' > "$WORK/case/sources.txt"
expect_fail "a line with no coverage fails" "names no coverage"

fresh
printf 'modules/pure/src/main/scala/Sink.scala  none: reason\nmodules/pure/src/main/scala/Sink.scala  none: again\n' \
  > "$WORK/case/sources.txt"
expect_fail "a file listed twice fails" "is listed twice"

fresh
printf 'modules/whole/src/main/scala/Shell.scala  none: redundant\n' >> "$WORK/case/sources.txt"
expect_fail "a line for a file in a whole module fails as redundant" "the line is redundant"

fresh
printf 'modules/pure/  none: the whole directory\n' > "$WORK/case/sources.txt"
expect_pass "a directory line covers the files under it"

fresh
printf 'package org.pure\nclass PosixSpec { val win = System.getProperty("os.name") }\n' \
  > "$WORK/case/repo/modules/pure/src/test/scala/PosixSpec.scala"
expect_fail "a test that probes the OS but does not run on Windows fails" \
  "PosixSpec.scala checks which OS it runs on.*under pure"

fresh
printf 'package org.pure\nclass FilesSpec { val win = System.getProperty("os.name") }\n' \
  > "$WORK/case/repo/modules/pure/src/test/scala/FilesSpec.scala"
expect_pass "a test that probes the OS and runs on Windows passes"

fresh
ruby -rjson -e 'f = ARGV[0]; m = JSON.parse(File.read(f)); m["platformTests"]["suites"]["pure"] = ["org.pure.*"]
  File.write(f, JSON.generate(m))' "$WORK/case/model.json"
printf 'modules/pure/src/main/scala/Sink.scala  pure:org.pure.*\n' > "$WORK/case/sources.txt"
printf 'package org.pure\nclass PosixSpec { val win = System.getProperty("os.name") }\n' \
  > "$WORK/case/repo/modules/pure/src/test/scala/PosixSpec.scala"
expect_pass "a glob in PlatformTests.suites covers the suites it matches"

fresh
ruby -rjson -e 'f = ARGV[0]; m = JSON.parse(File.read(f)); m["platformTests"]["modules"] << "nosuch"
  File.write(f, JSON.generate(m))' "$WORK/case/model.json"
expect_fail "a module that is not a project fails" "lists nosuch, which is not a project"

fresh
ruby -rjson -e 'f = ARGV[0]; m = JSON.parse(File.read(f)); m.delete("platformTests"); File.write(f, JSON.generate(m))' \
  "$WORK/case/model.json"
expect_fail "a build model without platformTests fails" "has no platformTests field"

fresh
printf 'jobs:\n  test:\n    steps:\n      - run: sbt test\n' > "$WORK/case/ci.yml"
expect_fail "a workflow whose Windows job does not run testPlatform fails" "runs .sbt testPlatform. on Windows"

echo
echo "$PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]
