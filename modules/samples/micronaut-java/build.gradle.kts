// A standalone Micronaut 4 application that calls llm4s from Java through llm4s-java-api: a @Factory bean for the
// JLlmClient, configured from application.yml, a controller at /chat and /chat/stream, and an llm4s health indicator.
// See README.md and docs/guide/micronaut.md.
//
// Build against a release with `-Pllm4sVersion=0.5.1` (or later). The default is the locally built snapshot that
// `sbt publishM2` puts in your local Maven repository; CI builds and tests the sample against it, so the sample cannot
// drift from the API on main.
plugins {
    java
    application
}

val llm4sVersion = (findProperty("llm4sVersion") as String?) ?: "0.1.0-SNAPSHOT"
val micronautVersion = "4.10.23"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    // Micronaut's dependency injection is compile-time: its annotation processor writes the bean definitions for this
    // project's classes. It never sees inside the llm4s jars, so the client becomes a bean through a @Factory.
    annotationProcessor(platform("io.micronaut.platform:micronaut-platform:$micronautVersion"))
    annotationProcessor("io.micronaut:micronaut-inject-java")
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")
    implementation(platform("io.micronaut.platform:micronaut-platform:$micronautVersion"))
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut:micronaut-management")
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    runtimeOnly("org.yaml:snakeyaml")
    runtimeOnly("ch.qos.logback:logback-classic")

    // Scala 3 only, and the _3 suffix is part of the name. The POM brings llm4s-core, llm4s-agent and the Scala
    // library; llm4s-java-api brings no provider, so declare the ones the factory builds clients for.
    implementation("org.llm4s:llm4s-java-api_3:$llm4sVersion")
    implementation("org.llm4s:llm4s-ollama_3:$llm4sVersion")
    implementation("org.llm4s:llm4s-openai-compatible_3:$llm4sVersion")

    testAnnotationProcessor(platform("io.micronaut.platform:micronaut-platform:$micronautVersion"))
    testAnnotationProcessor("io.micronaut:micronaut-inject-java")
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testImplementation("io.micronaut:micronaut-http-client")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// llm4s-java-api is JDK 21 bytecode, so the sample compiles for 21 whatever JDK runs Gradle.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.compilerArgs.add("-parameters")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("org.llm4s.samples.micronaut.Application")
}
