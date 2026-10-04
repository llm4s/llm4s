package org.llm4s.gradle

/**
 * Ready-to-paste Gradle dependency snippets for adding llm4s to a project.
 *
 *  These snippets are intended to be embedded in documentation, IDE plugins,
 *  or scaffolding tools that generate Gradle build files for llm4s consumers.
 *
 *  llm4s is published for Scala 3 only, so every snippet uses the `_3` artifact
 *  suffix (Gradle does not append it for you, unlike sbt's `%%`). The logback and
 *  Azure exclusions apply to `llm4s-core` 0.4.x, which declares those dependencies;
 *  the split modules on `main` do not.
 */
object GradleSnippets {

  /** The latest released version whose coordinates the snippets use. */
  val LLM4S_VERSION: String = "0.4.1"

  private val SCALA_SUFFIX = "3"

  private def coordinate(module: String): String = s"org.llm4s:${module}_$SCALA_SUFFIX:$LLM4S_VERSION"

  def kotlinDslDependency(module: String = "llm4s-core"): String =
    s"""implementation("${coordinate(module)}")"""

  def groovyDslDependency(module: String = "llm4s-core"): String =
    s"""implementation '${coordinate(module)}'"""

  def kotlinDslWithLogbackExclusion(module: String = "llm4s-core"): String =
    s"""implementation("${coordinate(module)}") {
       |    exclude(group = "ch.qos.logback", module = "logback-classic")
       |}""".stripMargin

  def kotlinDslWithAzureExclusion(module: String = "llm4s-core"): String =
    s"""implementation("${coordinate(module)}") {
       |    exclude(group = "com.azure", module = "azure-ai-openai")
       |}""".stripMargin

  def kotlinDslScalaResolutionStrategy(scalaVersion: String = "3.7.1"): String =
    s"""configurations.all {
       |    resolutionStrategy.eachDependency {
       |        if (requested.group == "org.scala-lang") {
       |            useVersion("$scalaVersion")
       |        }
       |    }
       |}""".stripMargin

  def groovyDslWithLogbackExclusion(module: String = "llm4s-core"): String =
    s"""implementation('${coordinate(module)}') {
       |    exclude group: 'ch.qos.logback', module: 'logback-classic'
       |}""".stripMargin

  def groovyDslWithAzureExclusion(module: String = "llm4s-core"): String =
    s"""implementation('${coordinate(module)}') {
       |    exclude group: 'com.azure', module: 'azure-ai-openai'
       |}""".stripMargin
}
