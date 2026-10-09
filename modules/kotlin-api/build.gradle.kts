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

// Pin the JVM target (the bytecode level of the Kotlin classes) to 17, whatever JDK is installed
// (Kotlin 2.x doesn't yet support JDK 25+ as a target). It does not make the module run on JDK 17:
// running it needs JDK 21, because AgentKt starts a virtual thread (Thread.ofVirtual, #1678) and
// llm4s-java-api needs JDK 21 too (#1493).
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    implementation("org.llm4s:llm4s-java-api_3:0.1.0-SNAPSHOT")
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
