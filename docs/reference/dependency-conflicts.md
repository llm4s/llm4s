---
layout: page
title: Dependency Conflicts
parent: Reference
nav_order: 10
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

## Conflict Matrix

| Conflict | Affected scenario | Symptom | Resolution |
|---|---|---|---|
| `logback-classic` 1.4 vs 1.5 | Spring Boot 3.2.x + llm4s | `Multiple SLF4J bindings` warning or changed log format | Exclude `ch.qos.logback:logback-classic` from llm4s; let Spring Boot manage it |
| Multiple SLF4J bindings | Any project with 2+ logging frameworks | `SLF4J: Class path contains multiple SLF4J bindings` | Keep exactly one SLF4J implementation on the classpath |
| `azure-ai-openai` transitive tree | Non-Azure projects | ~30 MB extra jars; possible Netty version conflict | Exclude `com.azure:azure-ai-openai` from llm4s |
| Apache HTTP client 5 vs OkHttp / Reactor Netty | Ktor or Spring WebFlux + Anthropic provider | Both HTTP clients on classpath; port conflicts possible | Exclude `org.apache.httpcomponents.client5:httpclient5` |
| Scala 3 micro-version mismatch | Any multi-lib Gradle project | `IncompatibleClassChangeError` at runtime | Pin `org.scala-lang` to `3.7.1` via `resolutionStrategy` |
| Scala `_3` artifact suffix | Gradle (does not auto-resolve) | `Could not resolve org.llm4s:core` | Use explicit artifact names: `core_3`, `java-api_3` |

---

## Logback conflict

### Root cause

LLM4S declares `ch.qos.logback:logback-classic:1.5.18` as a `runtime` dependency. Spring Boot 3.2.x uses `1.4.x` via its BOM. Gradle's default resolution strategy (highest wins) picks `1.5.x`, which may change log format output or trigger duplicate-binding errors if another binding is already present.

### Fix — Gradle (Kotlin DSL)

```kotlin
implementation("org.llm4s:core_3:0.1.16") {
    exclude(group = "ch.qos.logback", module = "logback-classic")
}
```

### Fix — Maven

```xml
<dependency>
    <groupId>org.llm4s</groupId>
    <artifactId>core_3</artifactId>
    <version>0.1.16</version>
    <exclusions>
        <exclusion>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

### Fix — sbt

```scala
libraryDependencies += "org.llm4s" %% "core" % "0.1.16" exclude("ch.qos.logback", "logback-classic")
```

---

## Azure SDK transitive tree

### Root cause

The Azure OpenAI provider depends on `com.azure:azure-ai-openai` which transitively pulls the full Azure Identity SDK, Azure Core HTTP Netty client, and reactor-netty. If you only use OpenAI or Anthropic providers, this adds ~30 MB and can conflict with a project's own Netty version.

### Fix — Gradle (Kotlin DSL)

```kotlin
implementation("org.llm4s:core_3:0.1.16") {
    exclude(group = "com.azure", module = "azure-ai-openai")
}
```

### Fix — Maven

```xml
<exclusions>
    <exclusion>
        <groupId>com.azure</groupId>
        <artifactId>azure-ai-openai</artifactId>
    </exclusion>
</exclusions>
```

---

## Apache HTTP client conflict (Ktor / Spring WebFlux)

### Root cause

`anthropic-java:2.x` pulls `org.apache.httpcomponents.client5:httpclient5`. Ktor uses OkHttp; Spring WebFlux uses Reactor Netty. Having Apache HTTP client 5 on the classpath alongside OkHttp is harmless but wastes classpath space and can trigger dependency-resolution version conflicts in multi-project builds.

### Fix — Gradle (Kotlin DSL)

```kotlin
implementation("org.llm4s:core_3:0.1.16") {
    exclude(group = "org.apache.httpcomponents.client5", module = "httpclient5")
}
```

---

## Scala 3 artifact suffix in Gradle

### Root cause

In sbt, `%%` automatically appends the Scala binary version suffix (`_3` or `_2.13`). Gradle has no equivalent. If you write `org.llm4s:core:0.1.16` without a suffix, Gradle cannot resolve the artifact.

### Fix

Always use the explicit suffix:

```kotlin
// Scala 3 (recommended)
implementation("org.llm4s:core_3:0.1.16")

// Scala 2.13 (if needed)
implementation("org.llm4s:core_2.13:0.1.16")
```

---

## Scala library version pinning

Multiple llm4s transitive dependencies may request different `org.scala-lang:scala3-library_3` micro-versions. Gradle resolves to the highest, which is usually fine, but an explicit pin avoids unexpected upgrades.

### Fix — Gradle (Kotlin DSL)

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
