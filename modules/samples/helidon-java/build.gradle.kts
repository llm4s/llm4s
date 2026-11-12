// A standalone Helidon SE 4 application that calls llm4s from Java through llm4s-java-api: the JLlmClient built from
// application.yaml, an HttpService at /chat and /chat/stream that closes the client when the server stops, and an
// llm4s health check at /observe/health. See README.md and docs/guide/helidon.md.
//
// Build against a release with `-Pllm4sVersion=0.5.1` (or later). The default is the locally built snapshot that
// `sbt publishM2` puts in your local Maven repository; CI builds and tests the sample against it, so the sample cannot
// drift from the API on main.
plugins {
    java
    application
}

val llm4sVersion = (findProperty("llm4sVersion") as String?) ?: "0.1.0-SNAPSHOT"
val helidonVersion = "4.5.6"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation(platform("io.helidon:helidon-dependencies:$helidonVersion"))
    implementation("io.helidon.webserver:helidon-webserver")
    implementation("io.helidon.webserver:helidon-webserver-sse")
    implementation("io.helidon.webserver.observe:helidon-webserver-observe-health")
    implementation("io.helidon.config:helidon-config-yaml")

    // Scala 3 only, and the _3 suffix is part of the name. The POM brings llm4s-core, llm4s-agent and the Scala
    // library; llm4s-java-api brings no provider, so declare the ones Llm4sSettings builds clients for.
    implementation("org.llm4s:llm4s-java-api_3:$llm4sVersion")
    implementation("org.llm4s:llm4s-ollama_3:$llm4sVersion")
    implementation("org.llm4s:llm4s-openai-compatible_3:$llm4sVersion")
    // llm4s logs through SLF4J; Helidon logs through java.util.logging. This sends llm4s's logs to the same place.
    runtimeOnly("org.slf4j:slf4j-jdk14:2.0.17")

    testImplementation("io.helidon.webserver.testing.junit5:helidon-webserver-testing-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// llm4s-java-api is JDK 21 bytecode, so the sample compiles for 21 whatever JDK runs Gradle.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("org.llm4s.samples.helidon.Main")
}
