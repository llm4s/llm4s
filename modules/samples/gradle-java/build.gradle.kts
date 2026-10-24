// A standalone Gradle project that calls llm4s from Java through `llm4s-java-api`.
//
// Run against a release with `-Pllm4sVersion=0.5.0` (or later). The default is the locally built snapshot that
// `sbt publishM2` puts in your local Maven repository (see README.md); CI builds the sample against it, so the
// sample cannot drift from the API on `main`.
plugins {
    java
    application
}

val llm4sVersion = (findProperty("llm4sVersion") as String?) ?: "0.1.0-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    // The POM brings llm4s-core, llm4s-agent and the provider modules, and the Scala 3 library at the
    // version they were built with: nothing here pins any of them.
    implementation("org.llm4s:llm4s-java-api_3:$llm4sVersion")

    // llm4s logs through SLF4J and ships no logging backend; without one SLF4J prints a warning and drops the logs.
    runtimeOnly("ch.qos.logback:logback-classic:1.5.34")
}

// The sample's own code is compiled for Java 21, the minimum JDK of llm4s, whatever JDK runs Gradle: llm4s-java-api
// is JDK 21 bytecode and depends on Ox, which is too (https://github.com/llm4s/llm4s/issues/1493), so a lower target
// would only move the failure from compile time to run time.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

application {
    mainClass.set("org.llm4s.samples.HelloLLM4S")
    applicationName = "hello-llm4s"
}

// `gradle runAsyncCalls` runs AsyncCalls: async calls against FakeLlmClient, so it needs no API key (#2276).
tasks.register<JavaExec>("runAsyncCalls") {
    group = "application"
    description = "Runs the AsyncCalls sample, which needs no API key."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.llm4s.samples.AsyncCalls")
}
