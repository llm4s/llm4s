---
layout: page
title: Support Policy
description: "Which LLM4S releases get fixes, the supported platforms, release cadence, provider API changes and end of support."
search_title: "LLM4S support policy and supported versions"
parent: Reference
nav_order: 26
---

# Support Policy

Which releases of LLM4S get fixes, which platforms they run on, and how you find out when that changes. This page
states what the project does from 1.0. The compatibility promise (what an upgrade may break) is in the
[Compatibility and Deprecation Policy](compatibility-policy); this page covers the support lifecycle.

LLM4S is maintained by volunteers ([MAINTAINERS.md](https://github.com/llm4s/llm4s/blob/main/MAINTAINERS.md)), so
everything here is best effort and there is no service-level agreement. Commercial support is not offered; questions
about sponsoring or partnering go to the contact on the [Foundation](../foundation/) page. For where to ask for help,
see [SUPPORT.md](https://github.com/llm4s/llm4s/blob/main/SUPPORT.md).

---

## Current lines

{% for l in site.data.support.lines %}
{% if forloop.first %}| Line | Latest release | Released | Status | Security fixes until | Ended | Notes |
|---|---|---|---|---|---|---|
{% endif %}| {{ l.line }}.x | {{ l.version }} | {{ l.released }} | {{ l.status }} | {{ l.security_until | default: "-" }} | {{ l.ended_on | default: "-" }} | {{ l.note }} |
{% endfor %}

*Status*: `latest` gets every fix; `security-only` gets security fixes until the date shown; `eol` gets none, from
the date in *Ended*. (`superseded` was the status of a replaced 0.x line before 1.0; those lines are now `eol`.)

Every published `llm4s-*` module has the same version, so a line means the same version for every module. The list of
published releases is on [GitHub](https://github.com/llm4s/llm4s/releases).

---

## Supported lines

| Line | Gets |
|---|---|
| The latest 1.x minor | Bug fixes and security fixes |
| The previous 1.x minor | Security fixes only, for three months after its successor is released |
| 0.7.x | Critical security fixes only, until 2027-02-25 (three months after 1.0.0), to cover the migration |
| Every other 0.x release | Nothing: end of life since 1.0.0 |

This is also stated in [SECURITY.md](https://github.com/llm4s/llm4s/blob/main/SECURITY.md#supported-versions).
Before 1.0 only the latest published release got fixes, and any release could break compatibility.

There is no long-term-support line before a 2.0 exists.

---

## Release cadence

Releases are cut from a tag by CI ([Release Process](../contribute/release)). There is no fixed schedule: releases happen when the
maintainers have a set of changes ready, and the date of each is in the table above and on the GitHub Releases page.
Before 1.0 they were closer together: 0.5.0, 0.5.1, 0.6.0, 0.7.0 and 1.0.0 came within two months.
From 1.0, minor releases come at most about once a quarter. Maven Central's upload limits allow roughly one
release a month at any time, and a security patch takes that slot ahead of a planned release.

---

## Platform support

What is built and tested today is stated once, in [Scala and JDK support](v1-scope#scala-and-jdk-support), and
generated into the [Compatibility Matrix](compatibility-matrix). In short:

- **Scala:** Scala 3 only ({{ site.data["tested-versions"].scala }}). Scala 2.13 support is deferred to after 1.0.
  Calling LLM4S from Scala 2.13 through the TASTy reader is best effort: CI probes it, but it is not a supported configuration and there are no 2.13 artifacts ([the TASTy reader guide](../getting-started/scala-2-13)).
- **JDK:** the minimum is JDK {{ site.data["tested-versions"].jdk_minimum }} for every artifact, and CI runs JDK {{ site.data["tested-versions"].jdk_ci | join: ", " }}.
- **Packaging:** classpath libraries. The artifacts are not JPMS modules or OSGi bundles
  ([why](compatibility-policy#supported-platforms)).

The Scala 3 minor series and the minimum JDK are raised only in a **minor** release, never in a patch release. Each
raise is announced one minor release ahead, in that release's CHANGELOG and notes, which is the end-of-support notice
for the older Scala or JDK; the raise itself is listed under *Changed* with a migration note
([rule](compatibility-policy#supported-platforms)).

---

## When a provider changes its API

Provider endpoints and models are outside the project's control. When a vendor deprecates or changes one, the fix is
made in the provider's module and shipped in the next release, as a patch where it does not change the module's API.
A fix to a Beta or Experimental module may change its API in a minor release, with a migration note in the
CHANGELOG ([1.0 Scope](v1-scope)); a Frozen module's API changes only as the
[Compatibility and Deprecation Policy](compatibility-policy#deprecating-and-removing) allows.

---

## End of support

A line's end of support follows from [Supported lines](#supported-lines): the previous minor's
security window ends three months after its successor ships, and 0.7.x's on 2027-02-25, three months after 1.0.0.
(Before 1.0 a release stopped getting fixes when a newer one was published.) Each end date is
shown in the table above and stated in the release notes of the release that starts the window. A Scala or JDK floor
is raised only after a notice one minor release ahead ([Platform support](#platform-support)).

---

## Keeping this page current

The table comes from `docs/_data/support.yml`, which a release updates by hand
([Release Process](../contribute/release#update-the-support-table)). `scripts/check-support-table.sh` checks it: CI fails a pull
request whose table lacks the newest release in the CHANGELOG, and the release workflow fails a release tag that is not
the table's `latest` line before anything is published.
