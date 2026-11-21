---
layout: page
title: Verifying Releases
parent: Reference
nav_order: 30
description: "How to check that an LLM4S jar, SBOM or container image was built by the project's release workflow: PGP signatures and SLSA build provenance."
---

# Verifying Releases
{: .no_toc }

Two checks answer the two questions a supply-chain review asks of a downloaded LLM4S artifact. A **PGP
signature** says the project's release key signed it; a **build provenance attestation** says which commit, tag
and workflow run built it. This page shows both, with commands you can paste.

1. TOC
{:toc}

## What is signed and attested

| Artifact | Where | PGP signature | Build provenance |
|----------|-------|---------------|------------------|
| Jars, sources and javadoc jars, POMs (`org.llm4s:*`) | Maven Central | Yes, a `.asc` beside each file | Yes |
| `llm4s-kotlin-api` (built by Gradle) | Maven Central | Yes | Yes |
| CycloneDX SBOMs (`*.bom.json`) and licence reports (`*.licences.csv`) | the GitHub Release | No | Yes |
| `workspace-runner` container image | `ghcr.io/llm4s/workspace-runner` | No | Yes, stored beside the image in the registry |

Provenance is produced by [`release.yml`](https://github.com/llm4s/llm4s/blob/main/.github/workflows/release.yml)
with `actions/attest-build-provenance`: a [SLSA v1](https://slsa.dev/spec/v1.0/provenance) statement signed
with a short-lived [Sigstore](https://www.sigstore.dev/) certificate issued to the workflow, with no long-lived
key, and recorded in Sigstore's public transparency log. Each file is attested in the job that built it, before
it is uploaded. The attestations are listed at
[github.com/llm4s/llm4s/attestations](https://github.com/llm4s/llm4s/attestations).

Releases up to and including 0.6.0 were published before the workflow attested anything: they carry PGP
signatures only, and `gh attestation verify` finds no attestation for them.

## The release signing key

The Maven Central artifacts are signed with this OpenPGP key:

| | |
|---|---|
| Fingerprint | `2246 615C FDEA D8F4 50C0 8555 2550 305A 740B C5E3` |
| Algorithm | ed25519 (signing), with a cv25519 encryption subkey |
| User ID | `Rory Graves <rory.graves@fieldmark.co.uk>` |
| Created / expires | 2025-04-03 / 2028-04-02 |
| Published on | [keyserver.ubuntu.com](https://keyserver.ubuntu.com/pks/lookup?op=index&search=0x2246615CFDEAD8F450C085552550305A740BC5E3&fingerprint=on), [keys.openpgp.org](https://keys.openpgp.org/search?q=2246615CFDEAD8F450C085552550305A740BC5E3) |

The 0.5.0, 0.5.1 and 0.6.0 jars and POMs on Maven Central verify against it. Both keyservers serve it with its user
ID; the fingerprint is what to check.

<!-- Maintainers: the release workflow's PGP_SECRET holds this key (the 0.6.0 artifacts, signed by release run
     44886740482, verify against it), and its user ID is confirmed on keys.openpgp.org. Update this table whenever
     the key is rotated or its expiry is extended. -->

## Check a jar's PGP signature

```bash
VERSION={{ site.data.project.latest_release }}
BASE=https://repo1.maven.org/maven2/org/llm4s/llm4s-core_3/$VERSION
curl -sfO "$BASE/llm4s-core_3-$VERSION.jar"
curl -sfO "$BASE/llm4s-core_3-$VERSION.jar.asc"

gpg --keyserver hkps://keyserver.ubuntu.com --recv-keys 2246615CFDEAD8F450C085552550305A740BC5E3
gpg --verify "llm4s-core_3-$VERSION.jar.asc" "llm4s-core_3-$VERSION.jar"
```

A good result names the key by its full fingerprint:

```text
gpg:                using EDDSA key 2246615CFDEAD8F450C085552550305A740BC5E3
gpg: Good signature from "Rory Graves <rory.graves@fieldmark.co.uk>" [unknown]
```

The `WARNING: This key is not certified with a trusted signature!` that follows is about your own web of trust,
not the signature: compare the fingerprint with the table above.

## Check a jar's build provenance

With the [GitHub CLI](https://cli.github.com/) (2.49 or later), on a jar downloaded as above:

```bash
gh attestation verify "llm4s-core_3-$VERSION.jar" -R llm4s/llm4s \
  --signer-workflow llm4s/llm4s/.github/workflows/release.yml
```

`gh` hashes the file, looks up attestations for that digest, checks the Sigstore signature and the transparency
log entry, and checks that the certificate was issued to a workflow of `llm4s/llm4s`. `--signer-workflow`
additionally requires that the release workflow, not some other workflow of the repository, built it. The
output names the source commit and the tag the run was started from; `--format json` prints the full statement.

To check every artifact of a release at once, use the release script's attestation mode:

```bash
scripts/verify-release.sh "$VERSION" --attestations
```

It downloads each published jar (the POM for the BOM and the relocation stubs) from Maven Central and runs
`gh attestation verify` on it, then checks the container image.

## Check an SBOM

The SBOMs and licence reports are assets of the GitHub Release for the tag ([Software Bill of Materials](sbom)),
and are attested there, by the release workflow's `sbom` job. They are not published to Maven Central: releases up to
0.6.0 also uploaded a `-cyclonedx.json` beside each module there, which carries a PGP signature but no attestation.

```bash
gh release download "v$VERSION" -R llm4s/llm4s -p "llm4s-core_3-$VERSION.bom.json"
gh attestation verify "llm4s-core_3-$VERSION.bom.json" -R llm4s/llm4s \
  --signer-workflow llm4s/llm4s/.github/workflows/release.yml
```

## Check the container image

The provenance of `workspace-runner` is bound to the image's digest and stored in the registry beside it:

```bash
gh attestation verify "oci://ghcr.io/llm4s/workspace-runner:$VERSION" -R llm4s/llm4s \
  --signer-workflow llm4s/llm4s/.github/workflows/release.yml
```

A tag can be moved; a digest cannot. Once verified, pull and deploy the image by the digest `gh` reports
(`ghcr.io/llm4s/workspace-runner@sha256:...`) rather than by its tag.

## Verify in your build

### Maven

The [pgpverify-maven-plugin](https://www.simplify4u.org/pgpverify-maven-plugin/) checks the signature of every
dependency. Pin the LLM4S group to the release key in its keys map:

```text
# keysmap.list
org.llm4s = 0x2246615CFDEAD8F450C085552550305A740BC5E3
```

```bash
mvn org.simplify4u.plugins:pgpverify-maven-plugin:check -DkeysMapLocation=keysmap.list
```

### Gradle

Gradle's [dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html)
checks signatures and checksums from `gradle/verification-metadata.xml`. Generate it, then trust the release
key for the group:

```bash
./gradlew --write-verification-metadata pgp,sha256 help
```

```xml
<!-- gradle/verification-metadata.xml, inside <configuration><trusted-keys> -->
<trusted-key id="2246615CFDEAD8F450C085552550305A740BC5E3" group="org.llm4s"/>
```

### sbt

sbt has no built-in signature check. Check the jars it resolved with the commands above, or with
`gh attestation verify` on each file in the Coursier cache (`cs fetch org.llm4s:llm4s-core_3:$VERSION` prints
their paths).

## See also

- [Software Bill of Materials](sbom): what the SBOMs cover and how they are scanned
- [Release Process](release): how a release is cut, and the checks run after it
- [Security Reference](security): the threat model
