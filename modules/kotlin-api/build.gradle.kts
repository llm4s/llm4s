plugins {
    kotlin("jvm") version "2.0.21"
    `java-library`
    jacoco
    `maven-publish`
    signing
    id("org.jetbrains.dokka") version "2.0.0"
}

// The release version, the one sbt publishes the rest of the library at. The release workflow passes it from the tag
// (`-Pllm4sVersion=0.5.0` for v0.5.0, the version sbt-dynver derives from that tag), so the Kotlin artifact and the
// llm4s-java-api it depends on are always the same release. Without it, the build uses the 0.1.0-SNAPSHOT the CI job
// publishes the library at with `sbt publishM2` (see docs/reference/release.md).
val llm4sVersion: String = providers.gradleProperty("llm4sVersion").getOrElse("0.1.0-SNAPSHOT")

group = "org.llm4s"
version = llm4sVersion

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
    // `api`, not `implementation`: the public signatures expose llm4s-java-api's types (JAgentResult, Answer,
    // PendingInterrupt, ...) and kotlinx-coroutines' Flow, so a consumer must compile against both. `api` puts them in
    // the published POM at compile scope; `implementation` would put them at runtime scope, off the consumer's compile
    // classpath.
    api("org.llm4s:llm4s-java-api_3:$llm4sVersion")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

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

// ---- publishing: org.llm4s:llm4s-kotlin-api on Maven Central ----
//
// The artifact has no `_3` suffix: that suffix names a Scala binary version, and this module is Kotlin. Maven Central
// requires a sources jar and a javadoc jar; the javadoc jar holds the Dokka HTML of the KDoc, as Kotlin libraries
// publish it. `publishMavenPublicationToStagingRepository` writes the signed publication to build/staging-deploy, a
// file repository: CI writes it unsigned to check the POM and to build a project against it, and the release
// workflow signs it and uploads that directory to the Central Portal (scripts/publish-kotlin-api.sh).

dokka {
    moduleName.set("llm4s-kotlin-api")
}

java {
    withSourcesJar()
}

val dokkaJavadocJar by tasks.registering(Jar::class) {
    description = "A javadoc jar holding the Dokka HTML of the KDoc, as Maven Central requires"
    group = "documentation"
    archiveClassifier.set("javadoc")
    from(tasks.named("dokkaGeneratePublicationHtml"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "llm4s-kotlin-api"
            from(components["java"])
            artifact(dokkaJavadocJar)
            // The same project metadata as every sbt-published llm4s artifact (build.sbt, `inThisBuild`);
            // scripts/check-kotlin-publication.sh fails when the two disagree.
            pom {
                name.set("llm4s-kotlin-api")
                description.set(
                    "Kotlin coroutine API for LLM4S: suspend functions and Flows over the Java facade's client and agent."
                )
                url.set("https://llm4s.org")
                properties.put("info.versionScheme", "early-semver")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://mit-license.org/")
                        distribution.set("repo")
                    }
                }
                organization {
                    name.set("llm4s")
                    url.set("https://github.com/llm4s/")
                }
                scm {
                    url.set("https://github.com/llm4s/llm4s")
                    connection.set("scm:git:https://github.com/llm4s/llm4s.git")
                }
                developers {
                    developer {
                        id.set("rorygraves")
                        name.set("Rory Graves")
                        url.set("https://github.com/rorygraves")
                        email.set("rory.graves@fieldmark.co.uk")
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

// Signed when a key is given (the release workflow passes the PGP_SECRET it signs the sbt artifacts with, as
// ORG_GRADLE_PROJECT_signingKey and ORG_GRADLE_PROJECT_signingPassword); unsigned otherwise, so CI and a local dry run
// need no secret. scripts/publish-kotlin-api.sh refuses to upload a publication without signatures.
val signingKey: String? = providers.gradleProperty("signingKey").orNull
signing {
    isRequired = signingKey != null
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, providers.gradleProperty("signingPassword").orNull)
        sign(publishing.publications["maven"])
    }
}
