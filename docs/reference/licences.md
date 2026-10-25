---
layout: page
title: Dependency Licences
parent: Reference
---

# Dependency licences

llm4s is MIT licensed. Every published `llm4s-*` module also brings its own runtime dependencies, and each module's
tree is different: vendor SDKs, Tika, POI and PDFBox, JDBC drivers, Neo4j, Pekko. This page says, per module, where
to find the licences of those dependencies, which licences the project allows, and how the policy is enforced.

## Where the reports are

- **On each GitHub Release.** The `sbom` job of `.github/workflows/release.yml` attaches one CSV per published module
  that ships a jar, named `<module>-<version>.licences.csv` (for example `llm4s-rag-0.5.0.licences.csv`), next to
  the same module's [SBOM](sbom).
- **Locally**, for any commit:

  ```bash
  sbt licenseReport    # one CSV per published module, written to target/licences
  sbt licenseCheck     # the policy below; prints the licence counts per module and fails on a refused one
  ```

Each report lists the module's **runtime dependency tree** as the build resolves it, transitive dependencies
included, one row per library:

| Column | Meaning |
|--------|---------|
| `group`, `artifact`, `version` | The Maven coordinates |
| `licence` | The SPDX identifier, or `unknown` |
| `source` | Where the licence comes from: `POM: <what the POM declares>`, or `reviewed: <justification>` for an entry of the exceptions file |
| `via` | One shortest path from the module to the library, for example `llm4s-rag -> software.amazon.awssdk:s3 -> software.amazon.awssdk:netty-nio-client -> io.netty:netty-buffer` |

Test frameworks are not runtime dependencies and are not listed, except in `llm4s-provider-testkit` and
`llm4s-agent-testkit`, whose published APIs are built on ScalaTest. Other llm4s modules a module depends on are MIT
and are left out of its report; each has a report of its own. `llm4s-kotlin-api` is built by Gradle and has no report
yet: its dependencies are `llm4s-java-api_3` (reported), `kotlinx-coroutines-core` and the Kotlin standard library,
all Apache-2.0.

## How a licence is determined

The licence is what the dependency's POM declares in `<licenses>` (inherited from a parent POM when the POM has none),
normalised to an [SPDX identifier](https://spdx.org/licenses/): "The Apache Software License, Version 2.0",
"Apache 2" and "Apache-2.0" are all `Apache-2.0`.

- **A choice of licences.** A POM that lists several licences, or one licence that names a choice (such as "CDDL +
  GPLv2 with classpath exception"), lets the user pick. The report takes the first allowed option, so JNA, which is
  `LGPL-2.1-or-later` or `Apache-2.0`, is reported as `Apache-2.0`.
- **Unknown.** A POM that declares no licence, or a licence name the policy does not recognise, gives `unknown`. A
  generic name such as "BSD License" with no URL is `unknown` too, because it does not say which BSD licence.
- **Reviewed exceptions.** `project/licence-exceptions.txt` records, for such a dependency, the licence a review
  established and why, one `group:artifact | SPDX id | justification` per line. The reviewed licence must itself be
  allowed: an entry fills in what a POM omits or says unclearly, and never admits a denied licence. An entry that
  matches no dependency fails the check, so a removed dependency's entry cannot quietly cover a later one.

## The policy

`sbt licenseCheck` (a CI quick check on every pull request) fails when a runtime dependency of a published module has
a licence that is not on the allow-list, naming the module, the dependency, the reason and the path that pulled it in:

```text
[error] 1 runtime dependency(ies) with a licence the policy does not allow:
[error]   llm4s-vectorstore-redis: org.json:json:20260719: licence unknown: its POM declares a licence the policy
          does not recognise (Public Domain); via llm4s-vectorstore-redis -> redis.clients:jedis -> org.json:json
```

| Licence (SPDX) | Decision | Notes |
|----------------|----------|-------|
| `Apache-2.0`, `MIT`, `MIT-0`, `BSD-2-Clause`, `BSD-3-Clause`, `0BSD`, `ISC`, `Zlib`, `BSL-1.0` (Boost), `UPL-1.0`, `Unicode-3.0` | Allowed | Permissive. The Eclipse Distribution License 1.0 is `BSD-3-Clause`; the Bouncy Castle licence is `MIT` |
| `CC0-1.0`, `Unlicense`, `LicenseRef-Public-Domain` | Allowed | Public-domain dedications. A bare "Public Domain" needs a reviewed exception |
| `EPL-1.0`, `EPL-2.0`, `MPL-2.0`, `CDDL-1.0`, `CDDL-1.1` | Allowed | Weak, file-level copyleft: using the library unmodified, as a dependency, places no obligation on your code |
| `GPL-2.0-only WITH Classpath-exception-2.0` | Allowed | The JDK's own licence; the exception permits linking |
| `GPL-*`, `AGPL-*` | Denied | Strong and network copyleft |
| `LGPL-*` without a linking exception | Denied | Allowed only as the non-chosen side of a dual licence |
| `SSPL-1.0`, `BUSL-1.1` (Business Source), Commons Clause | Denied | Not open source, or restricts commercial use |
| The JSON licence | Denied | Its "Good, not Evil" clause is a field-of-use restriction |
| `unknown`, or any licence not listed above | Denied | Until a review records it in `project/licence-exceptions.txt` |

The lists are in `project/LicencePolicy.scala` and its tests in `project/src/test/scala/LicencePolicySpec.scala`
(`sbt "reload plugins" test`). Changing the allow-list is a change to the project's licensing promise: say why in the
pull request.

At the time of writing every runtime dependency of every published module is allowed. One needed a review:
`org.json:json`, reached through Jedis in `llm4s-vectorstore-redis`, whose POM says only "Public Domain"; its
licence file dedicates it to the public domain since release `20231013`.

## Adding a dependency

1. Add it to the module as usual, then run `sbt licenseCheck`.
2. If it fails with a denied licence, choose another library, or move the code that needs it to a module users opt
   into and discuss the licence in the pull request; the allow-list does not change silently.
3. If it fails with `unknown`, read the library's licence file. When it is an allowed licence, add a line to
   `project/licence-exceptions.txt` saying what the file says and where.

## What is not covered

- The reports describe dependencies as their POMs declare them. They are not legal advice and do not inspect the
  licence text inside each jar.
- Test-scope dependencies and the build's own plugins are out of scope: they are not on a user's classpath.
- The container images (`workspace-runner`) have no licence report.
