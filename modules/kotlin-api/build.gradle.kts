plugins {
    kotlin("jvm") version "2.0.21"
    jacoco
}

group = "org.llm4s"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
}

// Pin JVM target to 17 so the build is compatible with JDK 17+ regardless of the
// installed JDK version (Kotlin 2.x doesn't yet support JDK 25+ as a target).
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    implementation("org.llm4s:java-api_3:0.1.0-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // mockk 1.13.x uses ByteBuddy self-attachment (no separate -javaagent needed).
    // mockk-agent-jvm must be on the test classpath so the instrumentation classes are available.
    testImplementation("io.mockk:mockk:1.13.16")
    testImplementation("io.mockk:mockk-agent-jvm:1.13.16")
}

tasks.test {
    useJUnitPlatform()
    // Enable self-attachment for ByteBuddy (needed on Java 9+ for inline mocking of final classes).
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "1.00".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
