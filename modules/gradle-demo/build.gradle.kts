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

val llm4sVersion = "0.5.1"

dependencies {
    // The _3 suffix selects the Scala 3 artifact. Gradle does not resolve Scala
    // cross-version suffixes automatically — you must specify it explicitly.
    implementation("org.llm4s:llm4s-core_3:$llm4sVersion")

    // llm4s-core ships no provider client: add the module of each provider you call
    // (llm4s-openai, llm4s-anthropic, llm4s-gemini, llm4s-ollama, llm4s-openai-compatible, ...).
    implementation("org.llm4s:llm4s-openai_3:$llm4sVersion")

    // From 0.5.0 llm4s depends on slf4j-api only and brings no logging backend, and core
    // carries no vendor SDK, so the logback and Azure exclusions 0.4.x needed are gone.
}

// ── Scala library version pinning ───────────────────────────────────────────
// Gradle may resolve multiple Scala 3 micro-versions from transitive deps.
// Pin scala3-library_3 to a single known-good version to avoid binary incompatibility.
// Do not match the whole org.scala-lang group: scala-library stays at 2.13.x and has no 3.x release.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.scala-lang" && requested.name == "scala3-library_3") {
            useVersion("3.7.1")
        }
    }
}

application {
    // Replace with your own main class
    mainClass.set("org.example.Main")
}
