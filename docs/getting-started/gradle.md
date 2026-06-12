---
layout: page
title: Gradle Integration
parent: Getting Started
nav_order: 2
---

# Gradle Integration
{: .no_toc }

Add LLM4S to a Gradle project (Java, Kotlin, or Kotlin/Ktor).
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Why explicit artifact suffixes matter

Gradle does **not** resolve Scala cross-version suffixes automatically. The Maven artifact for LLM4S is `core_3` (Scala 3) or `core_2.13` (Scala 2.13). You must append the suffix yourself — unlike sbt where `%%` handles this.

---

## Kotlin DSL (`build.gradle.kts`)

```kotlin
repositories {
    mavenCentral()
}

val llm4sVersion = "0.1.16"

dependencies {
    // Scala 3 artifact — note the explicit _3 suffix
    implementation("org.llm4s:core_3:$llm4sVersion")

    // For a Java/Kotlin-idiomatic API (no Scala Either/Option):
    // implementation("org.llm4s:java-api_3:$llm4sVersion")
}

// Pin the Scala library version to avoid binary incompatibility from transitive deps
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.scala-lang") {
            useVersion("3.7.1")
        }
    }
}
```

---

## Groovy DSL (`build.gradle`)

```groovy
repositories {
    mavenCentral()
}

def llm4sVersion = '0.1.16'

dependencies {
    implementation "org.llm4s:core_3:${llm4sVersion}"
    // implementation "org.llm4s:java-api_3:${llm4sVersion}"
}

configurations.all {
    resolutionStrategy.eachDependency { details ->
        if (details.requested.group == 'org.scala-lang') {
            details.useVersion '3.7.1'
        }
    }
}
```

---

## Dependency exclusion recipes

### Logback conflict (Spring Boot 3.x)

LLM4S bundles `logback-classic:1.5.x` as a runtime dependency. Spring Boot 3.2.x defaults to `1.4.x`. Having both on the classpath produces _"multiple SLF4J bindings"_ warnings or changes your log format.

**Kotlin DSL:**
```kotlin
implementation("org.llm4s:core_3:$llm4sVersion") {
    exclude(group = "ch.qos.logback", module = "logback-classic")
}
```

**Groovy DSL:**
```groovy
implementation("org.llm4s:core_3:${llm4sVersion}") {
    exclude group: 'ch.qos.logback', module: 'logback-classic'
}
```

Then declare your preferred Logback version directly:

```kotlin
runtimeOnly("ch.qos.logback:logback-classic:1.4.14") // or let Spring BOM manage it
```

---

### Azure SDK exclusion (non-Azure projects)

`azure-ai-openai` transitively pulls ~30 MB of Azure SDK jars. If you only use OpenAI or Anthropic providers, exclude it:

**Kotlin DSL:**
```kotlin
implementation("org.llm4s:core_3:$llm4sVersion") {
    exclude(group = "com.azure", module = "azure-ai-openai")
}
```

**Groovy DSL:**
```groovy
implementation("org.llm4s:core_3:${llm4sVersion}") {
    exclude group: 'com.azure', module: 'azure-ai-openai'
}
```

---

### Apache HTTP client conflict (Ktor / Spring WebFlux)

`anthropic-java` transitively pulls Apache HTTP client 5. Ktor uses OkHttp; Spring WebFlux uses Reactor Netty. Both HTTP clients may end up on the classpath.

**Kotlin DSL:**
```kotlin
implementation("org.llm4s:core_3:$llm4sVersion") {
    exclude(group = "org.apache.httpcomponents.client5", module = "httpclient5")
}
```

---

## Spring Boot BOM alignment

If you use the Spring Boot dependency-management plugin, align the BOM with your Spring Boot version and then exclude the llm4s logback dependency:

```kotlin
plugins {
    id("org.springframework.boot") version "3.2.5"
    id("io.spring.dependency-management") version "1.1.5"
}

dependencies {
    implementation("org.llm4s:core_3:$llm4sVersion") {
        exclude(group = "ch.qos.logback", module = "logback-classic")
    }
    // Spring Boot BOM manages logback version automatically
}
```

---

## Ktor setup

```kotlin
val llm4sVersion = "0.1.16"
val ktorVersion = "2.3.12"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")

    implementation("org.llm4s:java-api_3:$llm4sVersion") {
        // Ktor uses OkHttp; exclude the Apache HTTP client pulled by anthropic-java
        exclude(group = "org.apache.httpcomponents.client5", module = "httpclient5")
        // Ktor brings its own Netty; exclude the Azure SDK Netty variant
        exclude(group = "com.azure", module = "azure-ai-openai")
    }
}
```

---

## Using the Java-friendly API from Kotlin

LLM4S ships a `java-api` module with a Kotlin/Java-idiomatic wrapper that avoids Scala's `Either` and `Option` types.

```kotlin
import org.llm4s.java.Llm4s
import org.llm4s.java.ConversationBuilder

fun main() {
    val client = Llm4s.createDefaultClient()

    if (client.isSuccess) {
        val conversation = ConversationBuilder.create()
            .system("You are a helpful assistant.")
            .user("What is the Scala programming language?")
            .build()

        client.get().complete(conversation)
            .ifSuccess { println(it) }
            .ifFailure { System.err.println(it.message) }
    } else {
        System.err.println("Failed to create client: ${client.getError().message}")
    }
}
```

---

## Reference files

The `modules/gradle-demo/` directory in the llm4s repository contains:

- `build.gradle.kts` — Kotlin DSL reference with all exclusion recipes commented in-line
- `build.gradle` — Groovy DSL equivalent
- `settings.gradle.kts` — Minimal settings file

These are standalone reference files, not part of the sbt build. Copy them directly into your project as a starting point.

---

## Troubleshooting

### `Could not resolve org.llm4s:core`
Gradle could not find the artifact. Make sure you use the explicit suffix:
- Scala 3: `core_3`
- Scala 2.13: `core_2.13`

### `Multiple SLF4J bindings found`
Two SLF4J implementations are on the classpath. Exclude `logback-classic` from llm4s (see [Logback conflict](#logback-conflict-spring-boot-3x)) and ensure only one binding is declared.

### `Binary incompatible Scala library versions`
Add the `resolutionStrategy` block shown above to pin `org.scala-lang` to `3.7.1`.

---

See [dependency-conflicts reference](/reference/dependency-conflicts) for a full conflict matrix.
