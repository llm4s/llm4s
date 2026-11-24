---
layout: page
title: Gradle Integration
description: "Use LLM4S from a Gradle build in Java, Kotlin or Kotlin/Ktor: the _3 artifact names, dependency declarations and a first call."
search_title: "Use LLM4S from Gradle (Java and Kotlin)"
parent: Build Tools and Playgrounds
nav_order: 3
grand_parent: Getting Started
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
> The provider clients, agent runtime and other features are separate artifacts (`llm4s-openai_3`, `llm4s-anthropic_3`, `llm4s-agent_3`, ...): add the modules you use alongside `llm4s-core_3`, or align them with the [BOM](installation#align-versions-with-the-bom).

---

## Kotlin DSL (`build.gradle.kts`)

```kotlin
repositories {
    mavenCentral()
}

val llm4sVersion = "{{ site.data.project.latest_release }}"

dependencies {
    // Scala 3 artifacts - note the explicit _3 suffix
    implementation("org.llm4s:llm4s-core_3:$llm4sVersion")
    implementation("org.llm4s:llm4s-openai_3:$llm4sVersion") // the provider module you use
}

// Pin the Scala 3 library to avoid binary incompatibility from transitive deps.
// Match scala3-library_3 only: scala-library stays at 2.13.x and has no 3.x version.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.scala-lang" && requested.name == "scala3-library_3") {
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

def llm4sVersion = '{{ site.data.project.latest_release }}'

dependencies {
    implementation "org.llm4s:llm4s-core_3:${llm4sVersion}"
    implementation "org.llm4s:llm4s-openai_3:${llm4sVersion}" // the provider module you use
}

configurations.all {
    resolutionStrategy.eachDependency { details ->
        if (details.requested.group == 'org.scala-lang' && details.requested.name == 'scala3-library_3') {
            details.useVersion '3.7.1'
        }
    }
}
```

---

## Dependency alignment

llm4s depends on `slf4j-api` only and brings no logging backend: declare the one you use (for example
`runtimeOnly("ch.qos.logback:logback-classic:...")`, or let the Spring Boot BOM manage it).

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

If you use the Spring Boot dependency-management plugin, align the BOM with your Spring Boot version; it manages the
logging backend, and llm4s needs no exclusion:

```kotlin
plugins {
    id("org.springframework.boot") version "3.2.5"
    id("io.spring.dependency-management") version "1.1.5"
}

dependencies {
    implementation("org.llm4s:llm4s-core_3:$llm4sVersion")
    implementation("org.llm4s:llm4s-openai_3:$llm4sVersion") // the provider module you use
}
```

For the auto-configuration itself, see the [Spring Boot starter](/guide/spring-boot).

---

## Ktor setup

```kotlin
val llm4sVersion = "{{ site.data.project.latest_release }}"
val ktorVersion = "2.3.12"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")

    implementation("org.llm4s:llm4s-core_3:$llm4sVersion")
    implementation("org.llm4s:llm4s-openai_3:$llm4sVersion")
}
```

---

## Java sample

[`modules/samples/gradle-java`](https://github.com/llm4s/llm4s/tree/main/modules/samples/gradle-java) is a
standalone Gradle project that calls llm4s from Java through `llm4s-java-api`: a client, a question, a
conversation, and `LlmResult` error handling, with no Scala types in the code. Its README says how to build and
run it. CI builds it on every pull request, so it stays current.

## Reference files

The `modules/gradle-demo/` directory in the llm4s repository contains:

- `build.gradle.kts` - Kotlin DSL reference: `llm4s-core` plus a provider module
- `build.gradle` - Groovy DSL equivalent
- `settings.gradle.kts` - Minimal settings file

These are standalone reference files, not part of the sbt build. Copy them directly into your project as a starting point. The same directory also holds `ConversationTemplates` and `GradleSnippets`, small Scala helpers with their own tests.

---

## Troubleshooting

For a configuration problem, run the [doctor](/reference/doctor#gradle) first: with `llm4s-doctor_3` on the test runtime
classpath and its `llm4sDoctor` `JavaExec` task, `gradle llm4sDoctor` checks your `application.conf`, provider module and
API key, and says what to fix.

### `Could not resolve org.llm4s:...`
Gradle could not find the artifact. Use the prefixed name and the explicit Scala suffix: `org.llm4s:llm4s-core_3`. There is no Scala 2.13 artifact.

### `Multiple SLF4J bindings found`
Two SLF4J implementations are on the classpath. llm4s brings none, so another dependency brought the second: find it with `./gradlew dependencyInsight --dependency slf4j --configuration runtimeClasspath` and keep only one binding.

### `Binary incompatible Scala library versions`
Add the `resolutionStrategy` block shown above to pin `org.scala-lang:scala3-library_3` to `3.7.1`. Pin that artifact only: Scala 3 runs on the Scala 2.13 `scala-library` (`2.13.x`), which has no `3.x` release, so a rule that matches the whole `org.scala-lang` group fails with `Could not find org.scala-lang:scala-library:3.7.1`.

---

See the [dependency-conflicts reference](/reference/dependency-conflicts) for a full conflict matrix.
