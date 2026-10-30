---
layout: page
title: Support Policy
parent: Reference
nav_order: 26
---

# Support Policy

Which releases of LLM4S get fixes, which platforms they run on, and how you find out when that changes. This page
states what the project does today and marks what is still to be decided. It does not make a commitment that the
maintainers have not made. The compatibility promise (what an upgrade may break) is in the
[Compatibility and Deprecation Policy](compatibility-policy); this page covers the support lifecycle.

LLM4S is maintained by volunteers ([MAINTAINERS.md](https://github.com/llm4s/llm4s/blob/main/MAINTAINERS.md)), so
everything here is best effort and there is no service-level agreement. Commercial support is not offered; questions
about sponsoring or partnering go to the contact on the [Foundation](../foundation/) page. For where to ask for help,
see [SUPPORT.md](https://github.com/llm4s/llm4s/blob/main/SUPPORT.md).

---

## Current lines

{% for l in site.data.support.lines %}
{% if forloop.first %}| Version | Released | Status | Notes |
|---|---|---|---|
{% endif %}| {{ l.version }} | {{ l.released }} | {{ l.status }} | {{ l.note }} |
{% endfor %}

Every published `llm4s-*` module has the same version, so a line means the same version for every module. The list of
published releases is on [GitHub](https://github.com/llm4s/llm4s/releases).

---

## Supported lines

**Today (pre-baseline, 0.5.x):** only the latest published release gets fixes, bug fixes and security fixes alike.
Older releases are not patched. Any release may break compatibility, each break listed in the CHANGELOG with a
migration note. This is also stated in [SECURITY.md](https://github.com/llm4s/llm4s/blob/main/SECURITY.md#supported-versions).

**After 1.0 (planned):** the project intends to patch more than the latest release once a binary-compatibility
baseline exists ([#1281](https://github.com/llm4s/llm4s/issues/1281)). The number of supported lines, any
long-term-support line and its window are **to be decided by the maintainers before 1.0**. Until they are published
here, assume "latest release only".

---

## Release cadence

Releases are cut from a tag by CI ([Release Process](release)). There is no fixed schedule: releases happen when the
maintainers have a set of changes ready, and the date of each is in the table above and on the GitHub Releases page.
A target rhythm for minor and patch releases is **to be decided before 1.0**.

---

## Platform support

What is built and tested today is stated once, in [Scala and JDK support](v1-scope#scala-and-jdk-support), and
generated into the [Compatibility Matrix](compatibility-matrix). In short:

- **Scala:** Scala 3 only ({{ site.data["tested-versions"].scala }}). Scala 2.13 support is deferred to after 1.0.
- **JDK:** the minimum is JDK {{ site.data["tested-versions"].jdk_minimum }} for every artifact, and CI runs JDK {{ site.data["tested-versions"].jdk_ci | join: ", " }}.

The Scala 3 minor series and the minimum JDK are raised only in a **minor** release, never in a patch release, and each
raise is announced in the CHANGELOG under *Changed* with a migration note
([rule](compatibility-policy#supported-platforms)). How long an older Scala or JDK line stays supported after a newer
one is adopted is **to be decided before 1.0**.

---

## When a provider changes its API

Provider endpoints and models are outside the project's control. When a vendor deprecates or changes one, the fix is
made in the provider's module and shipped in the next release, as a patch where it does not change the module's API.
A change that does is listed under *Breaking changes* in the CHANGELOG with a migration note. A fix to a
Beta or Experimental module may change its API in a minor release ([1.0 Scope](v1-scope)).

---

## End of support

Today a release stops getting fixes when a newer one is published, and nothing more is announced: the table above shows
it as superseded. When longer-lived lines exist, an end-of-support date will be added to the table, and announced in the
CHANGELOG and the GitHub Release of the release that ends it. How far ahead, and whether Discord or a pinned
Discussion is also used, is **to be decided before 1.0**.

---

## Keeping this page current

The table comes from `docs/_data/support.yml`, which a release updates by hand
([Release Process](release#update-the-support-table)). A mechanical check that a release tag appears in the table is
not in place yet.
