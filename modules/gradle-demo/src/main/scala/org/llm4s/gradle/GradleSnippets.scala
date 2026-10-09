package org.llm4s.gradle

import org.llm4s.error.InvalidInputError
import org.llm4s.types.Result

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
 *
 *  `LLM4S_VERSION` is the latest published release. At 0.4.1 only `llm4s-core` exists as an
 *  artifact; the other module names (`llm4s-agent`, `llm4s-openai`, ...) resolve from 0.5.0, so
 *  bump `LLM4S_VERSION` when that release is published.
 *
 *  Snippets that take a module name or a Scala version return a [[org.llm4s.types.Result]]:
 *  both values are written into a build file between quotes, so a value that is not a plausible
 *  artifact id or version (a quote, a newline, a blank, a Scala suffix already appended) is
 *  rejected with `InvalidInputError` instead of producing a snippet that breaks, or alters, the
 *  build file it is pasted into.
 */
object GradleSnippets {

  /** The latest released version whose coordinates the snippets use. */
  val LLM4S_VERSION: String = "0.4.1"

  private val SCALA_SUFFIX = "3"

  /** Maven artifact ids: ASCII letters, digits, `.`, `_` and `-`, starting with a letter or digit. */
  private val ArtifactId = """[A-Za-z0-9][A-Za-z0-9._-]*""".r

  /** A name that already ends in a Scala binary suffix, such as `llm4s-core_3` or `x_2.13`. */
  private val HasScalaSuffix = """.*_\d+(\.\d+)?""".r

  /** `major.minor.patch`, optionally followed by a pre-release such as `-RC1` or `-nonbinary-1`. */
  private val ScalaVersionPattern = """\d+\.\d+\.\d+(-[A-Za-z0-9.]+)*""".r

  private def validModule(module: String): Result[String] =
    if (HasScalaSuffix.matches(module))
      Left(InvalidInputError("module", module, "must not include the Scala suffix; `_3` is added for you"))
    else if (ArtifactId.matches(module)) Right(module)
    else Left(InvalidInputError("module", module, "must be a Maven artifact id such as `llm4s-core`"))

  private def validScalaVersion(scalaVersion: String): Result[String] =
    if (ScalaVersionPattern.matches(scalaVersion)) Right(scalaVersion)
    else Left(InvalidInputError("scalaVersion", scalaVersion, "must look like `3.7.1`"))

  private def coordinate(module: String): String = s"org.llm4s:${module}_$SCALA_SUFFIX:$LLM4S_VERSION"

  def kotlinDslDependency(module: String = "llm4s-core"): Result[String] =
    validModule(module).map(m => s"""implementation("${coordinate(m)}")""")

  def groovyDslDependency(module: String = "llm4s-core"): Result[String] =
    validModule(module).map(m => s"""implementation '${coordinate(m)}'""")

  def kotlinDslWithLogbackExclusion(module: String = "llm4s-core"): Result[String] =
    validModule(module).map(m => s"""implementation("${coordinate(m)}") {
                                    |    exclude(group = "ch.qos.logback", module = "logback-classic")
                                    |}""".stripMargin)

  def kotlinDslWithAzureExclusion(module: String = "llm4s-core"): Result[String] =
    validModule(module).map(m => s"""implementation("${coordinate(m)}") {
                                    |    exclude(group = "com.azure", module = "azure-ai-openai")
                                    |}""".stripMargin)

  /**
   * Pins `scala3-library_3` only. The Scala 3 runtime sits on the Scala 2.13 `scala-library`,
   * which has no `3.x` release, so matching the whole `org.scala-lang` group makes Gradle fail
   * with `Could not find org.scala-lang:scala-library:3.7.1`.
   */
  def kotlinDslScalaResolutionStrategy(scalaVersion: String = "3.7.1"): Result[String] =
    validScalaVersion(scalaVersion).map(v => s"""configurations.all {
                                                |    resolutionStrategy.eachDependency {
                                                |        if (requested.group == "org.scala-lang" && requested.name == "scala3-library_3") {
                                                |            useVersion("$v")
                                                |        }
                                                |    }
                                                |}""".stripMargin)

  /** Groovy DSL form of [[kotlinDslScalaResolutionStrategy]]. */
  def groovyDslScalaResolutionStrategy(scalaVersion: String = "3.7.1"): Result[String] =
    validScalaVersion(scalaVersion).map(v => s"""configurations.all {
                                                |    resolutionStrategy.eachDependency { details ->
                                                |        if (details.requested.group == 'org.scala-lang' && details.requested.name == 'scala3-library_3') {
                                                |            details.useVersion '$v'
                                                |        }
                                                |    }
                                                |}""".stripMargin)

  def groovyDslWithLogbackExclusion(module: String = "llm4s-core"): Result[String] =
    validModule(module).map(m => s"""implementation('${coordinate(m)}') {
                                    |    exclude group: 'ch.qos.logback', module: 'logback-classic'
                                    |}""".stripMargin)

  def groovyDslWithAzureExclusion(module: String = "llm4s-core"): Result[String] =
    validModule(module).map(m => s"""implementation('${coordinate(m)}') {
                                    |    exclude group: 'com.azure', module: 'azure-ai-openai'
                                    |}""".stripMargin)
}
