---
layout: page
title: Gradle Integration
parent: Getting Started
nav_order: 6
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

## Artifact names

LLM4S is published for **Scala 3 only**, and Gradle does **not** resolve Scala cross-version suffixes automatically. The coordinate is `org.llm4s:llm4s-core_3` - you must write the `_3` suffix yourself, unlike sbt where `%%` adds it.

{: .note }
> Releases up to `0.3.4` used unprefixed names (`core_3`); from `0.4.0` every artifact carries an `llm4s-` prefix. See the [migration guide](/reference/migration#artifact-coordinate-rename-v040).

{: .note }
> The examples use `0.4.1`, where all functionality, including the provider clients, ships in `llm4s-core`. On `main` the provider clients, agent runtime and other features are being split into separate artifacts (`llm4s-openai`, `llm4s-anthropic`, `llm4s-agent`, ...); once those are published you add the modules you use alongside `llm4s-core_3`. The dependency notes below say which version each one applies to.

---

## Kotlin DSL (`build.gradle.kts`)

```kotlin
repositories {
    mavenCentral()
}

val llm4sVersion = "0.4.1"

dependencies {
    // Scala 3 artifact - note the explicit _3 suffix
    implementation("org.llm4s:llm4s-core_3:$llm4sVersion")
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

def llm4sVersion = '0.4.1'

dependencies {
    implementation "org.llm4s:llm4s-core_3:${llm4sVersion}"
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

### Logback conflict (Spring Boot 3.x, `0.4.x` only)

`llm4s-core` `0.4.x` declares `logback-classic` as a compile dependency. Spring Boot 3.2.x defaults to `1.4.x`, and Gradle picks the highest version, so you may see _"multiple SLF4J bindings"_ warnings or a changed log format. From `0.5.0` the split modules depend on `slf4j-api` only (logback is a test dependency), so this recipe is not needed.

**Kotlin DSL:**
```kotlin
implementation("org.llm4s:llm4s-core_3:$llm4sVersion") {
    exclude(group = "ch.qos.logback", module = "logback-classic")
}
```

**Groovy DSL:**
```groovy
implementation("org.llm4s:llm4s-core_3:${llm4sVersion}") {
    exclude group: 'ch.qos.logback', module: 'logback-classic'
}
```

Then declare your preferred Logback version directly:

```kotlin
runtimeOnly("ch.qos.logback:logback-classic:1.4.14") // or let Spring BOM manage it
```

---

### Azure SDK exclusion (non-Azure projects, `0.4.x` only)

`llm4s-core` `0.4.x` depends on `com.azure:azure-ai-openai`, which transitively pulls the Azure SDK jars. If you do not use the Azure provider, exclude it:

**Kotlin DSL:**
```kotlin
implementation("org.llm4s:llm4s-core_3:$llm4sVersion") {
    exclude(group = "com.azure", module = "azure-ai-openai")
}
```

**Groovy DSL:**
```groovy
implementation("org.llm4s:llm4s-core_3:${llm4sVersion}") {
    exclude group: 'com.azure', module: 'azure-ai-openai'
}
```

The split modules on `main` have no Azure dependency.

---

### OkHttp and Kotlin standard library alignment

The OpenAI and Anthropic SDKs (`openai-java`, `anthropic-java`) both use OkHttp 4.12 and Kotlin standard library 1.9.x. If your build (for example a Ktor project) uses other versions, Gradle resolves to the highest on the classpath. That is normally harmless; if you need to pin them, use a constraint rather than an exclusion, because removing OkHttp from the SDKs breaks them:

```kotlin
dependencies {
    constraints {
        implementation("com.squareup.okhttp3:okhttp:4.12.0")
    }
}
```

Use `./gradlew dependencyInsight --dependency okhttp --configuration runtimeClasspath` to see which version wins and why.

---

## Spring Boot BOM alignment

If you use the Spring Boot dependency-management plugin, align the BOM with your Spring Boot version. With `llm4s-core` `0.4.x`, also exclude the llm4s logback dependency:

```kotlin
plugins {
    id("org.springframework.boot") version "3.2.5"
    id("io.spring.dependency-management") version "1.1.5"
}

dependencies {
    implementation("org.llm4s:llm4s-core_3:$llm4sVersion") {
        exclude(group = "ch.qos.logback", module = "logback-classic")
    }
    // Spring Boot BOM manages logback version automatically
}
```

---

## Ktor setup

```kotlin
val llm4sVersion = "0.4.1"
val ktorVersion = "2.3.12"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")

    implementation("org.llm4s:llm4s-core_3:$llm4sVersion") {
        // Ktor manages its own logging backend (0.4.x only; see above)
        exclude(group = "ch.qos.logback", module = "logback-classic")
    }
}
```

---

## Reference files

The `modules/gradle-demo/` directory in the llm4s repository contains:

- `build.gradle.kts` - Kotlin DSL reference with the exclusion recipes commented in-line
- `build.gradle` - Groovy DSL equivalent
- `settings.gradle.kts` - Minimal settings file

These are standalone reference files, not part of the sbt build. Copy them directly into your project as a starting point. The same directory also holds `ConversationTemplates` and `GradleSnippets`, small Scala helpers with their own tests.

---

## Troubleshooting

### `Could not resolve org.llm4s:...`
Gradle could not find the artifact. Use the prefixed name and the explicit Scala suffix: `org.llm4s:llm4s-core_3`. There is no Scala 2.13 artifact.

### `Multiple SLF4J bindings found`
Two SLF4J implementations are on the classpath. With `llm4s-core` `0.4.x`, exclude `logback-classic` (see [Logback conflict](#logback-conflict-spring-boot-3x-04x-only)) and ensure only one binding is declared.

### `Binary incompatible Scala library versions`
Add the `resolutionStrategy` block shown above to pin `org.scala-lang` to `3.7.1`.

---

See the [dependency-conflicts reference](/reference/dependency-conflicts) for a full conflict matrix.
