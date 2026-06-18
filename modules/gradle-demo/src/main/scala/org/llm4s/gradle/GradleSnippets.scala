package org.llm4s.gradle

/**
 * Ready-to-paste Gradle dependency snippets for adding llm4s to a project.
 *
 *  These snippets are intended to be embedded in documentation, IDE plugins,
 *  or scaffolding tools that generate Gradle build files for llm4s consumers.
 *
 *  All methods return standalone snippet strings that compile correctly in
 *  either Kotlin DSL (`build.gradle.kts`) or Groovy DSL (`build.gradle`).
 */
object GradleSnippets {

  val LLM4S_VERSION: String = "0.1.16"

  def kotlinDslDependency(module: String = "core", scalaSuffix: String = "3"): String =
    s"""implementation("org.llm4s:${module}_$scalaSuffix:$LLM4S_VERSION")"""

  def groovyDslDependency(module: String = "core", scalaSuffix: String = "3"): String =
    s"""implementation 'org.llm4s:${module}_$scalaSuffix:$LLM4S_VERSION'"""

  def kotlinDslWithLogbackExclusion(module: String = "core", scalaSuffix: String = "3"): String =
    s"""implementation("org.llm4s:${module}_$scalaSuffix:$LLM4S_VERSION") {
       |    exclude(group = "ch.qos.logback", module = "logback-classic")
       |}""".stripMargin

  def kotlinDslWithAzureExclusion(module: String = "core", scalaSuffix: String = "3"): String =
    s"""implementation("org.llm4s:${module}_$scalaSuffix:$LLM4S_VERSION") {
       |    exclude(group = "com.azure", module = "azure-ai-openai")
       |}""".stripMargin

  def kotlinDslWithAnthropicHttpExclusion(module: String = "core", scalaSuffix: String = "3"): String =
    s"""implementation("org.llm4s:${module}_$scalaSuffix:$LLM4S_VERSION") {
       |    exclude(group = "org.apache.httpcomponents.client5", module = "httpclient5")
       |}""".stripMargin

  def kotlinDslScalaResolutionStrategy(scalaVersion: String = "3.7.1"): String =
    s"""configurations.all {
       |    resolutionStrategy.eachDependency {
       |        if (requested.group == "org.scala-lang") {
       |            useVersion("$scalaVersion")
       |        }
       |    }
       |}""".stripMargin

  def groovyDslWithLogbackExclusion(module: String = "core", scalaSuffix: String = "3"): String =
    s"""implementation('org.llm4s:${module}_$scalaSuffix:$LLM4S_VERSION') {
       |    exclude group: 'ch.qos.logback', module: 'logback-classic'
       |}""".stripMargin

  def groovyDslWithAzureExclusion(module: String = "core", scalaSuffix: String = "3"): String =
    s"""implementation('org.llm4s:${module}_$scalaSuffix:$LLM4S_VERSION') {
       |    exclude group: 'com.azure', module: 'azure-ai-openai'
       |}""".stripMargin
}
