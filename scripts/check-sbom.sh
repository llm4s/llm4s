#!/usr/bin/env bash
# Validates the CycloneDX BOMs that `sbt publishedBoms` writes (see docs/reference/sbom.md).
#
# Usage: scripts/check-sbom.sh BOM_DIR [PUBLISHED_LIST]
#   BOM_DIR         the directory holding the *.bom.json files (target/boms)
#   PUBLISHED_LIST  optional: the output of `sbt -error listPublishedArtifacts` (`artifact llm4s-core_3`
#                   lines, or bare artifact names). When given, there must be exactly one BOM per published
#                   artifact: a published module without one, or a BOM for something that is not published,
#                   fails.
#
# What it fails on, naming the file:
#   - a BOM that is not JSON, is not CycloneDX, or has no `specVersion` (1.x) or no described component;
#   - a BOM with no components (every module depends on the Scala library, so an empty one means the
#     dependency resolution did not run), or a component without a name, a version or a `pkg:maven` purl;
#   - a build-machine path (/Users/, /home/, /private/, /tmp/, a drive letter) anywhere in the file;
#   - a test framework among the components of a module that does not ship one (llm4s-provider-testkit's
#     API is built on ScalaTest, so it is allowed to);
#   - a component the described module does not reach through the BOM's own dependency graph: that is how a
#     `dependsOn(other % Test)` project showed up before `sbt publishedBoms` pruned it (project/SbomPrune.scala);
#   - the llm4s-core BOM lacking one of the libraries llm4s-core is known to depend on.
#
# Needs python3 (already used by scripts/check-doc-links.sh). Works with bash 3.2 (macOS).
set -euo pipefail

if [ $# -lt 1 ] || [ $# -gt 2 ]; then
  echo "usage: $0 BOM_DIR [PUBLISHED_LIST]" >&2
  exit 2
fi

BOM_DIR="$1"
PUBLISHED_LIST="${2:-}"

python3 - "$BOM_DIR" "$PUBLISHED_LIST" <<'PYEOF'
import glob
import json
import os
import re
import sys

bom_dir, published_list = sys.argv[1], sys.argv[2]

# Components llm4s-core is known to depend on (group:name with the Scala binary suffix removed).
CORE_REQUIRED = [
    "com.lihaoyi:ujson",
    "com.lihaoyi:upickle",
    "org.typelevel:cats-core",
    "org.slf4j:slf4j-api",
    "com.typesafe:config",
    "com.knuddels:jtokkit",
    "com.github.pureconfig:pureconfig-core",
]
# Modules that ship a test framework as part of their published API.
MAY_CONTAIN_TEST_FRAMEWORK = {"llm4s-provider-testkit_3"}
TEST_FRAMEWORK = re.compile(r"scalatest|scalamock|scalacheck|junit|mockito|munit|weaver|specs2", re.I)
BUILD_MACHINE_PATH = re.compile(r"(/Users/|/home/|/private/|/tmp/|[A-Za-z]:\\\\)")

errors = []


def fail(file, message):
    errors.append(f"{os.path.basename(file)}: {message}")


def strip_scala_suffix(name):
    return re.sub(r"_(2\.13|3)$", "", name)


files = sorted(glob.glob(os.path.join(bom_dir, "*.bom.json")))
if not files:
    print(f"no *.bom.json files in {bom_dir}", file=sys.stderr)
    sys.exit(1)

described = {}
for f in files:
    raw = open(f, encoding="utf-8").read()
    try:
        bom = json.loads(raw)
    except ValueError as e:
        fail(f, f"not valid JSON ({e})")
        continue
    if not isinstance(bom, dict) or bom.get("bomFormat") != "CycloneDX":
        fail(f, "bomFormat is not CycloneDX")
        continue
    if not re.fullmatch(r"1\.\d+", str(bom.get("specVersion", ""))):
        fail(f, f"specVersion {bom.get('specVersion')!r} is not a CycloneDX 1.x version")
    component = (bom.get("metadata") or {}).get("component") or {}
    name = component.get("name")
    if not name or not component.get("version"):
        fail(f, "metadata.component has no name or no version")
        name = name or os.path.basename(f)
    described[name] = f
    if BUILD_MACHINE_PATH.search(raw):
        fail(f, "contains a build-machine path (" + BUILD_MACHINE_PATH.search(raw).group(1) + ")")
    components = bom.get("components")
    if not isinstance(components, list) or not components:
        fail(f, "has no components")
        continue
    keys = set()
    for c in components:
        label = f"{c.get('group', '')}:{c.get('name', '?')}"
        if not c.get("name") or not c.get("version"):
            fail(f, f"component {label} has no name or no version")
        if not str(c.get("purl", "")).startswith("pkg:maven/"):
            fail(f, f"component {label} has no pkg:maven purl")
        keys.add(f"{c.get('group', '')}:{strip_scala_suffix(c.get('name', ''))}")
        if TEST_FRAMEWORK.search(label) and name not in MAY_CONTAIN_TEST_FRAMEWORK:
            fail(f, f"contains the test framework {label}, which a published module must not depend on")
    root_ref = component.get("bom-ref")
    if not root_ref:
        fail(f, "metadata.component has no bom-ref, so the dependency graph cannot be followed")
    else:
        edges = {d.get("ref"): d.get("dependsOn", []) for d in bom.get("dependencies", []) if isinstance(d, dict)}
        reachable, pending = set(), [root_ref]
        while pending:
            ref = pending.pop()
            if ref not in reachable:
                reachable.add(ref)
                pending.extend(edges.get(ref, []))
        for c in components:
            if c.get("bom-ref") not in reachable:
                fail(f, f"component {c.get('group', '')}:{c.get('name', '?')} is not reachable from {name} through "
                        "the dependency graph (a test-only dependency?)")
    if name.startswith("llm4s-core_"):
        for required in CORE_REQUIRED:
            if required not in keys:
                fail(f, f"the llm4s-core BOM is missing {required}")

if published_list:
    published = set()
    for line in open(published_list, encoding="utf-8"):
        line = line.strip()
        if line.startswith("artifact "):
            published.add(line.split(None, 1)[1])
        elif line and not line.startswith(("stub ", "#")):
            published.add(line)
    for missing in sorted(published - set(described)):
        errors.append(f"no BOM for the published artifact {missing}")
    for extra in sorted(set(described) - published):
        errors.append(f"{os.path.basename(described[extra])}: describes {extra}, which is not a published artifact")

if errors:
    print(f"SBOM check failed: {len(errors)} problem(s)", file=sys.stderr)
    for e in errors:
        print("  " + e, file=sys.stderr)
    sys.exit(1)
print(f"SBOM check passed: {len(files)} BOM(s)" + (f", one per published artifact ({len(published)})" if published_list else ""))
PYEOF
