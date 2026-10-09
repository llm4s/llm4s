#!/usr/bin/env bash
# Tests for scripts/check-sbom.sh: a good set of BOMs passes, and each kind of defect fails with a message
# that names it. No sbt is needed: every case writes a small fixture directory of CycloneDX files (the
# shape `sbt publishedBoms` produces) with one thing changed.
#
# Usage: scripts/test-check-sbom.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="$REPO_ROOT/scripts/check-sbom.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FAILED=0

# make_fixture DIR [MUTATION]: a core BOM, an agent BOM and a testkit BOM; MUTATION names the one defect.
make_fixture() {
  python3 - "$1" "${2:-none}" <<'PY'
import json, os, sys

out, mutation = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)


def comp(group, name, version="1.0.0"):
    purl = f"pkg:maven/{group}/{name}@{version}"
    return {"type": "library", "bom-ref": purl, "group": group, "name": name, "version": version, "purl": purl}


CORE = [comp("com.lihaoyi", "ujson_3"), comp("com.lihaoyi", "upickle_3"), comp("org.typelevel", "cats-core_3"),
        comp("org.slf4j", "slf4j-api"), comp("com.typesafe", "config"), comp("com.knuddels", "jtokkit"),
        comp("com.github.pureconfig", "pureconfig-core_3"), comp("org.scala-lang", "scala3-library_3")]
AGENT = [comp("org.scala-lang", "scala3-library_3")]
TESTKIT = [comp("org.scala-lang", "scala3-library_3"), comp("org.scalatest", "scalatest_3")]


def bom(name, components):
    root = f"pkg:maven/org.llm4s/{name}@0.5.0"
    return {"bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
            "metadata": {"component": {"type": "library", "bom-ref": root, "name": name, "version": "0.5.0"}},
            "components": components,
            "dependencies": [{"ref": root, "dependsOn": [c["bom-ref"] for c in components]}]
                            + [{"ref": c["bom-ref"]} for c in components]}


boms = {"llm4s-core": bom("llm4s-core_3", CORE), "llm4s-agent": bom("llm4s-agent_3", AGENT),
        "llm4s-provider-testkit": bom("llm4s-provider-testkit_3", TESTKIT)}
raw = {}

if mutation == "no-bomformat":
    boms["llm4s-agent"]["bomFormat"] = "SPDX"
elif mutation == "no-spec-version":
    del boms["llm4s-agent"]["specVersion"]
elif mutation == "spec-version-2":
    boms["llm4s-agent"]["specVersion"] = "2.0"
elif mutation == "no-metadata-component":
    del boms["llm4s-agent"]["metadata"]["component"]
elif mutation == "no-components":
    boms["llm4s-agent"]["components"] = []
elif mutation == "no-purl":
    del boms["llm4s-agent"]["components"][0]["purl"]
elif mutation == "non-maven-purl":
    boms["llm4s-agent"]["components"][0]["purl"] = "pkg:npm/left-pad@1.0.0"
elif mutation == "no-version":
    del boms["llm4s-agent"]["components"][0]["version"]
elif mutation == "path-users":
    boms["llm4s-agent"]["components"][0]["description"] = "built in /Users/someone/work"
elif mutation == "path-runner-home":
    boms["llm4s-agent"]["components"][0]["description"] = "built in /home/runner/work/llm4s"
elif mutation == "path-drive-letter":
    boms["llm4s-agent"]["components"][0]["description"] = "C:\\\\Users\\\\x"
elif mutation == "test-framework":
    boms["llm4s-agent"]["components"].append(comp("org.scalatest", "scalatest_3"))
elif mutation == "core-missing-cats":
    boms["llm4s-core"]["components"] = [c for c in CORE if c["name"] != "cats-core_3"]
elif mutation == "core-missing-jtokkit":
    boms["llm4s-core"]["components"] = [c for c in CORE if c["name"] != "jtokkit"]
elif mutation == "orphan-component":
    # a project listed only because another module depends on it for its tests, with no edge from the module
    boms["llm4s-agent"]["components"].append(comp("org.llm4s", "llm4s-provider-testkit_3", "0.5.0"))
elif mutation == "no-dependency-graph":
    del boms["llm4s-agent"]["dependencies"]
elif mutation == "no-root-bom-ref":
    del boms["llm4s-agent"]["metadata"]["component"]["bom-ref"]
elif mutation == "invalid-json":
    raw["llm4s-agent"] = "{ not json"
elif mutation == "not-an-object":
    raw["llm4s-agent"] = "[]"

for key, value in boms.items():
    with open(os.path.join(out, f"{key}-0.5.0.bom.json"), "w") as f:
        f.write(raw.get(key, json.dumps(value)))
PY
}

published_list() {
  printf '%s\n' "artifact llm4s-core_3" "artifact llm4s-agent_3" "artifact llm4s-provider-testkit_3" "stub core_3" > "$1"
}

# expect_pass NAME DIR [LIST]
expect_pass() {
  if out=$("$CHECK" "${@:2}" 2>&1); then echo "ok   [$1]"; else echo "FAIL [$1]: expected a pass, got:"; echo "$out" | sed 's/^/       /'; FAILED=1; fi
}

# expect_fail NAME FRAGMENT DIR [LIST]: must exit non-zero and mention FRAGMENT
expect_fail() {
  if out=$("$CHECK" "${@:3}" 2>&1); then
    echo "FAIL [$1]: expected a failure, but the check passed"; FAILED=1
  elif echo "$out" | grep -qF -- "$2"; then
    echo "ok   [$1]"
  else
    echo "FAIL [$1]: failed, but the message does not mention '$2':"; echo "$out" | sed 's/^/       /'; FAILED=1
  fi
}

make_fixture "$WORK/good"
published_list "$WORK/published.txt"
expect_pass "a good set passes" "$WORK/good"
expect_pass "a good set passes against the published list (stubs are ignored)" "$WORK/good" "$WORK/published.txt"

defect() { # defect MUTATION FRAGMENT DESCRIPTION
  make_fixture "$WORK/$1" "$1"
  expect_fail "$3" "$2" "$WORK/$1"
}
defect no-bomformat "bomFormat is not CycloneDX" "a BOM that is not CycloneDX fails"
defect no-spec-version "specVersion" "a BOM without specVersion fails"
defect spec-version-2 "specVersion '2.0'" "a specVersion that is not 1.x fails"
defect no-metadata-component "no name or no version" "a BOM that describes no component fails"
defect no-components "has no components" "a BOM with no components fails"
defect no-purl "has no pkg:maven purl" "a component without a purl fails"
defect non-maven-purl "has no pkg:maven purl" "a component with a non-Maven purl fails"
defect no-version "no name or no version" "a component without a version fails"
defect path-users "build-machine path (/Users/)" "a macOS home path fails"
defect path-runner-home "build-machine path (/home/)" "a CI runner path fails"
defect path-drive-letter "build-machine path" "a Windows drive path fails"
defect test-framework "test framework org.scalatest:scalatest_3" "a test framework in a normal module fails"
defect core-missing-cats "missing org.typelevel:cats-core" "a core BOM without cats fails"
defect core-missing-jtokkit "missing com.knuddels:jtokkit" "a core BOM without jtokkit fails"
defect orphan-component "is not reachable from llm4s-agent_3" "a component the module does not reach (a test-only project) fails"
defect no-dependency-graph "is not reachable from llm4s-agent_3" "a BOM without a dependency graph fails"
defect no-root-bom-ref "has no bom-ref" "a described component without a bom-ref fails"
defect invalid-json "not valid JSON" "a file that is not JSON fails"
defect not-an-object "bomFormat is not CycloneDX" "JSON that is not an object fails"

# the file is named in the message
make_fixture "$WORK/named" no-components
expect_fail "the failing file is named" "llm4s-agent-0.5.0.bom.json" "$WORK/named"

# the testkit may contain ScalaTest: that is its API (the good fixture already includes it and passes)

# the published list
make_fixture "$WORK/missing-bom"
rm "$WORK/missing-bom/llm4s-agent-0.5.0.bom.json"
expect_fail "a published artifact without a BOM fails" "no BOM for the published artifact llm4s-agent_3" "$WORK/missing-bom" "$WORK/published.txt"

make_fixture "$WORK/extra-bom"
printf '%s\n' "artifact llm4s-core_3" "artifact llm4s-provider-testkit_3" > "$WORK/published-short.txt"
expect_fail "a BOM for an unpublished module fails" "which is not a published artifact" "$WORK/extra-bom" "$WORK/published-short.txt"

mkdir "$WORK/empty-dir"
expect_fail "a directory with no BOMs fails" "no *.bom.json files" "$WORK/empty-dir"

if "$CHECK" >/dev/null 2>&1; then echo "FAIL [no arguments]: expected a usage error"; FAILED=1; else echo "ok   [no arguments is a usage error]"; fi

if [ "$FAILED" -ne 0 ]; then
  echo "SBOM check tests FAILED"
  exit 1
fi
echo "All SBOM check cases passed"
