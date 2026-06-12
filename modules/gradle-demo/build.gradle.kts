// Reference: build.gradle.kts (Kotlin DSL) for a project using llm4s
// ─────────────────────────────────────────────────────────────────
// Copy the relevant sections into your existing build file.
// See docs/getting-started/gradle.md for the full guide.

plugins {
    application
    // Use the Scala plugin if you want to write Scala code that calls llm4s directly.
    // Java and Kotlin callers do NOT need the Scala plugin — the Scala runtime is
    // pulled in automatically as a transitive dependency.
    // id("scala")   // uncomment if writing Scala
}

repositories {
    mavenCentral()
}

val llm4sVersion = "0.1.16"

dependencies {
    // ── Core module ──────────────────────────────────────────────────────────
    // The _3 suffix selects the Scala 3 artifact. Gradle does not resolve Scala
    // cross-version suffixes automatically — you must specify it explicitly.
    implementation("org.llm4s:core_3:$llm4sVersion")

    // ── Java-friendly API (optional) ─────────────────────────────────────────
    // Use this if you prefer a Java/Kotlin idiomatic API (no Scala Either/Option).
    // implementation("org.llm4s:java-api_3:$llm4sVersion")

    // ── Logback exclusion (Spring Boot / Ktor projects) ──────────────────────
    // llm4s bundles logback-classic 1.5.x as a runtime dependency.
    // Spring Boot 3.2.x ships 1.4.x; having both on the classpath causes
    // "multiple SLF4J bindings" warnings or log-format changes.
    // Uncomment the block below if your project manages logging separately:
    //
    // implementation("org.llm4s:core_3:$llm4sVersion") {
    //     exclude(group = "ch.qos.logback", module = "logback-classic")
    // }

    // ── Azure exclusion (non-Azure projects) ─────────────────────────────────
    // If you only use OpenAI or Anthropic providers you can shed the Azure SDK
    // transitive dependency tree (~30 MB) with:
    //
    // implementation("org.llm4s:core_3:$llm4sVersion") {
    //     exclude(group = "com.azure", module = "azure-ai-openai")
    // }
}

// ── Scala library version pinning ───────────────────────────────────────────
// Gradle may resolve multiple Scala 3 micro-versions from transitive deps.
// Pin to a single known-good version to avoid binary incompatibility.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.scala-lang") {
            useVersion("3.7.1")
        }
    }
}

application {
    // Replace with your own main class
    mainClass.set("org.example.Main")
}
