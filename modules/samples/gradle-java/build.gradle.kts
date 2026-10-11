// A standalone Gradle project that calls llm4s from Java through `llm4s-java-api`.
//
// llm4s-java-api is not published yet: it ships with 0.5.0. Until then this build resolves it from your
// local Maven repository, where `sbt publishM2` puts it (see README.md). Once 0.5.0 is on Maven Central, run
// with `-Pllm4sVersion=0.5.0`, and change the default below to that release.
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

// The sample's own code is compiled for Java 17 whatever JDK runs Gradle. Running it needs JDK 21,
// because llm4s-java-api depends on Ox (see https://github.com/llm4s/llm4s/issues/1493).
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

application {
    mainClass.set("org.llm4s.samples.HelloLLM4S")
    applicationName = "hello-llm4s"
}
