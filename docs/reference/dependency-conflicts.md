---
layout: page
title: Dependency Conflicts
parent: Reference
nav_order: 12
---

# Dependency Conflict Resolution
{: .no_toc }

LLM4S transitively pulls several large dependencies. This page documents known conflicts and their resolutions.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Which version does this apply to?

LLM4S is being split from one `llm4s-core` jar into focused modules (see the [migration guide](/reference/migration)). The conflicts differ between the two shapes:

- **`0.4.x`** (latest release): `llm4s-core` contains everything, and declares `logback-classic` and `com.azure:azure-ai-openai` as compile dependencies.
- **`main` / the split modules**: `llm4s-core` and the provider modules depend on `slf4j-api` only (logback is test-scoped) and have no Azure dependency. Provider modules bring the vendor SDKs (`openai-java`, `anthropic-java`) and with them OkHttp and the Kotlin standard library.

These were checked against the `0.4.1` build definition and the dependency trees of the `openai`, `anthropic` and `gemini` modules on `main`.

---

## Conflict Matrix

| Conflict | Affected scenario | Symptom | Resolution |
|---|---|---|---|
| `logback-classic` 1.4 vs 1.5 | Spring Boot 3.2.x + `llm4s-core` 0.4.x | `Multiple SLF4J bindings` warning or changed log format | Exclude `ch.qos.logback:logback-classic` from llm4s; let Spring Boot manage it |
| Multiple SLF4J bindings | Any project with 2+ logging frameworks | `SLF4J: Class path contains multiple SLF4J bindings` | Keep exactly one SLF4J implementation on the classpath |
| `azure-ai-openai` transitive tree | Non-Azure projects on 0.4.x | Extra Azure SDK jars on the classpath | Exclude `com.azure:azure-ai-openai` from llm4s |
| OkHttp / Kotlin stdlib versions | Ktor, Kotlin projects + the OpenAI or Anthropic modules | Gradle upgrades OkHttp or `kotlin-stdlib` to the highest requested version | Check with `dependencyInsight`; pin with a constraint if needed |
| Scala 3 micro-version mismatch | Any multi-lib Gradle project | `IncompatibleClassChangeError` at runtime | Pin `org.scala-lang` to `3.7.1` via `resolutionStrategy` |
| Scala `_3` artifact suffix | Gradle (does not auto-resolve) | `Could not resolve org.llm4s:llm4s-core` | Use explicit artifact names, e.g. `llm4s-core_3` |

---

## Logback conflict (0.4.x)

### Root cause

`llm4s-core` `0.4.x` declares `ch.qos.logback:logback-classic` as a compile dependency. Spring Boot 3.2.x uses `1.4.x` via its BOM. Gradle's default resolution strategy (highest wins) can pick the llm4s version, which may change log format output or trigger duplicate-binding errors if another binding is already present.

On `main` this no longer applies: the library modules depend on `slf4j-api` only.

### Fix - Gradle (Kotlin DSL)

```kotlin
implementation("org.llm4s:llm4s-core_3:0.4.1") {
    exclude(group = "ch.qos.logback", module = "logback-classic")
}
```

### Fix - Maven

```xml
<dependency>
    <groupId>org.llm4s</groupId>
    <artifactId>llm4s-core_3</artifactId>
    <version>0.4.1</version>
    <exclusions>
        <exclusion>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

### Fix - sbt

```scala
libraryDependencies += ("org.llm4s" %% "llm4s-core" % "0.4.1").exclude("ch.qos.logback", "logback-classic")
```

---

## Azure SDK transitive tree (0.4.x)

### Root cause

`llm4s-core` `0.4.x` includes the Azure OpenAI provider, which depends on `com.azure:azure-ai-openai` and, through it, the Azure core HTTP client stack. If you use neither Azure, this is dead weight and can conflict with a project's own Netty version.

### Fix - Gradle (Kotlin DSL)

```kotlin
implementation("org.llm4s:llm4s-core_3:0.4.1") {
    exclude(group = "com.azure", module = "azure-ai-openai")
}
```

### Fix - Maven

```xml
<exclusions>
    <exclusion>
        <groupId>com.azure</groupId>
        <artifactId>azure-ai-openai</artifactId>
    </exclusion>
</exclusions>
```

---

## OkHttp and Kotlin standard library

### Root cause

`openai-java` and `anthropic-java` (pulled in by `llm4s-openai` and `llm4s-anthropic`) use OkHttp 4.12 and Kotlin standard library 1.9.x. Ktor and other Kotlin projects often bring their own versions of both. Gradle resolves to the highest version requested, which is normally harmless.

Do not exclude OkHttp from the SDKs: they need it at runtime.

### Fix - pin a version (Gradle, Kotlin DSL)

```kotlin
dependencies {
    constraints {
        implementation("com.squareup.okhttp3:okhttp:4.12.0")
    }
}
```

---

## Scala 3 artifact suffix in Gradle

### Root cause

In sbt, `%%` automatically appends the Scala binary version suffix (`_3`). Gradle has no equivalent. If you write `org.llm4s:llm4s-core:0.4.1` without a suffix, Gradle cannot resolve the artifact. LLM4S is Scala 3 only, so the suffix is always `_3`.

### Fix

```kotlin
implementation("org.llm4s:llm4s-core_3:0.4.1")
```

---

## Scala library version pinning

Multiple llm4s transitive dependencies may request different `org.scala-lang:scala3-library_3` micro-versions. Gradle resolves to the highest, which is usually fine, but an explicit pin avoids unexpected upgrades.

### Fix - Gradle (Kotlin DSL)

```kotlin
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.scala-lang") {
            useVersion("3.7.1")
        }
    }
}
```

---

## Checking your dependency tree

Run Gradle's dependency insight to verify resolutions:

```bash
# Show what resolved logback-classic
./gradlew dependencies --configuration runtimeClasspath | grep logback

# Show full tree for a specific module
./gradlew dependencyInsight --dependency logback-classic --configuration runtimeClasspath
```

For sbt:

```bash
sbt "show dependencyTree"
sbt evicted
```
