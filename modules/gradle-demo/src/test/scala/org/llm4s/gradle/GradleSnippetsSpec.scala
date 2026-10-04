package org.llm4s.gradle

import org.llm4s.error.InvalidInputError
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GradleSnippetsSpec extends AnyFlatSpec with Matchers {

  private val coreCoordinate = s"org.llm4s:llm4s-core_3:${GradleSnippets.LLM4S_VERSION}"

  private def snippet(r: Result[String]): String =
    r match {
      case Right(s) => s
      case Left(e)  => fail(s"expected a snippet, got $e")
    }

  private def rejected(r: Result[String]): InvalidInputError =
    r match {
      case Left(e: InvalidInputError) => e
      case other                      => fail(s"expected InvalidInputError, got $other")
    }

  // Every snippet that takes a module name must refuse a value that is not an artifact id, because
  // the value is written between quotes into a build file.
  private val moduleSnippets: Seq[(String, String => Result[String])] = Seq(
    "kotlinDslDependency"           -> (m => GradleSnippets.kotlinDslDependency(m)),
    "groovyDslDependency"           -> (m => GradleSnippets.groovyDslDependency(m)),
    "kotlinDslWithLogbackExclusion" -> (m => GradleSnippets.kotlinDslWithLogbackExclusion(m)),
    "kotlinDslWithAzureExclusion"   -> (m => GradleSnippets.kotlinDslWithAzureExclusion(m)),
    "groovyDslWithLogbackExclusion" -> (m => GradleSnippets.groovyDslWithLogbackExclusion(m)),
    "groovyDslWithAzureExclusion"   -> (m => GradleSnippets.groovyDslWithAzureExclusion(m))
  )

  private val badModules = Seq(
    ""                                -> "empty",
    "   "                             -> "blank",
    "\t\n"                            -> "whitespace",
    "llm4s core"                      -> "contains a space",
    "llm4s-core\n"                    -> "trailing newline",
    "llm4s-core\")\nmaliciousCall(\"" -> "double-quote injection (Kotlin)",
    "llm4s-core')\nmaliciousCall('"   -> "single-quote injection (Groovy)",
    "llm4s-core${evil}"               -> "string template",
    "llm4s-core:9.9.9"                -> "embedded version",
    "org.llm4s:llm4s-core"            -> "embedded group",
    "-llm4s-core"                     -> "leading dash",
    "llm4s-core_3"                    -> "Scala suffix already appended",
    "llm4s-core_2.13"                 -> "Scala 2.13 suffix",
    "llm4s-cöre"                      -> "non-ASCII letter",
    "llm4s-コア"                        -> "CJK",
    "llm4s-core😀"                    -> "emoji",
    "llm4s-core‮"                     -> "right-to-left override",
    "llm4s-core\u0000"                -> "NUL"
  )

  private val badVersions = Seq(
    ""                     -> "empty",
    " "                    -> "blank",
    "3"                    -> "major only",
    "3.7"                  -> "no patch",
    "3.7.1 "               -> "trailing space",
    "3.7.1\n"              -> "trailing newline",
    "v3.7.1"               -> "leading v",
    "3.7.x"                -> "non-numeric patch",
    "3.7.1\")\nevil()\n//" -> "double-quote injection",
    "3.7.1'; evil(); '"    -> "single-quote injection",
    "3.7.1${x}"            -> "string template",
    "3.7.1-"               -> "dangling pre-release dash",
    "٣.٧.١"                -> "Arabic-Indic digits"
  )

  "GradleSnippets.LLM4S_VERSION" should "be a semantic version" in {
    (GradleSnippets.LLM4S_VERSION should fullyMatch).regex("""^\d+\.\d+\.\d+.*""")
  }

  "GradleSnippets.kotlinDslDependency" should "produce the Scala 3 llm4s-core coordinate by default" in {
    snippet(GradleSnippets.kotlinDslDependency()) shouldBe s"""implementation("$coreCoordinate")"""
  }

  it should "accept a custom module" in {
    snippet(GradleSnippets.kotlinDslDependency("llm4s-openai")) shouldBe
      s"""implementation("org.llm4s:llm4s-openai_3:${GradleSnippets.LLM4S_VERSION}")"""
  }

  "GradleSnippets.groovyDslDependency" should "produce the Scala 3 llm4s-core coordinate by default" in {
    snippet(GradleSnippets.groovyDslDependency()) shouldBe s"implementation '$coreCoordinate'"
  }

  it should "accept a custom module" in {
    snippet(GradleSnippets.groovyDslDependency("llm4s-agent")) shouldBe
      s"implementation 'org.llm4s:llm4s-agent_3:${GradleSnippets.LLM4S_VERSION}'"
  }

  "GradleSnippets.kotlinDslWithLogbackExclusion" should "exclude logback-classic from the dependency" in {
    snippet(GradleSnippets.kotlinDslWithLogbackExclusion()) shouldBe
      s"""implementation("$coreCoordinate") {
         |    exclude(group = "ch.qos.logback", module = "logback-classic")
         |}""".stripMargin
  }

  it should "accept a custom module" in {
    snippet(GradleSnippets.kotlinDslWithLogbackExclusion("llm4s-agent")) should startWith(
      "implementation(\"org.llm4s:llm4s-agent_3:"
    )
  }

  "GradleSnippets.kotlinDslWithAzureExclusion" should "exclude azure-ai-openai from the dependency" in {
    snippet(GradleSnippets.kotlinDslWithAzureExclusion()) shouldBe
      s"""implementation("$coreCoordinate") {
         |    exclude(group = "com.azure", module = "azure-ai-openai")
         |}""".stripMargin
  }

  it should "accept a custom module" in {
    snippet(GradleSnippets.kotlinDslWithAzureExclusion("llm4s-agent")) should startWith(
      "implementation(\"org.llm4s:llm4s-agent_3:"
    )
  }

  "GradleSnippets.kotlinDslScalaResolutionStrategy" should "pin scala3-library_3 only, to the default Scala version" in {
    snippet(GradleSnippets.kotlinDslScalaResolutionStrategy()) shouldBe
      """configurations.all {
        |    resolutionStrategy.eachDependency {
        |        if (requested.group == "org.scala-lang" && requested.name == "scala3-library_3") {
        |            useVersion("3.7.1")
        |        }
        |    }
        |}""".stripMargin
  }

  it should "accept a custom Scala version" in {
    val s = snippet(GradleSnippets.kotlinDslScalaResolutionStrategy("3.6.0"))
    s should include("""useVersion("3.6.0")""")
    (s should not).include("3.7.1")
  }

  it should "accept a pre-release Scala version" in {
    snippet(GradleSnippets.kotlinDslScalaResolutionStrategy("3.8.0-RC1")) should include("""useVersion("3.8.0-RC1")""")
  }

  it should "not match the whole org.scala-lang group, which would force scala-library to a 3.x that does not exist" in {
    // Gradle fails with "Could not find org.scala-lang:scala-library:3.7.1" if the rule has no name filter.
    snippet(GradleSnippets.kotlinDslScalaResolutionStrategy()) should include(
      """requested.name == "scala3-library_3""""
    )
    snippet(GradleSnippets.groovyDslScalaResolutionStrategy()) should include(
      "details.requested.name == 'scala3-library_3'"
    )
  }

  "GradleSnippets.groovyDslScalaResolutionStrategy" should "pin scala3-library_3 only, to the default Scala version" in {
    snippet(GradleSnippets.groovyDslScalaResolutionStrategy()) shouldBe
      """configurations.all {
        |    resolutionStrategy.eachDependency { details ->
        |        if (details.requested.group == 'org.scala-lang' && details.requested.name == 'scala3-library_3') {
        |            details.useVersion '3.7.1'
        |        }
        |    }
        |}""".stripMargin
  }

  it should "reject a Scala version that would break out of the quoted string" in {
    rejected(GradleSnippets.groovyDslScalaResolutionStrategy("3.7.1'; evil()")).field shouldBe "scalaVersion"
  }

  "GradleSnippets.groovyDslWithLogbackExclusion" should "exclude logback-classic in Groovy style" in {
    snippet(GradleSnippets.groovyDslWithLogbackExclusion()) shouldBe
      s"""implementation('$coreCoordinate') {
         |    exclude group: 'ch.qos.logback', module: 'logback-classic'
         |}""".stripMargin
  }

  it should "accept a custom module" in {
    snippet(GradleSnippets.groovyDslWithLogbackExclusion("llm4s-agent")) should startWith(
      "implementation('org.llm4s:llm4s-agent_3:"
    )
  }

  "GradleSnippets.groovyDslWithAzureExclusion" should "exclude azure-ai-openai in Groovy style" in {
    snippet(GradleSnippets.groovyDslWithAzureExclusion()) shouldBe
      s"""implementation('$coreCoordinate') {
         |    exclude group: 'com.azure', module: 'azure-ai-openai'
         |}""".stripMargin
  }

  it should "accept a custom module" in {
    snippet(GradleSnippets.groovyDslWithAzureExclusion("llm4s-agent")) should startWith(
      "implementation('org.llm4s:llm4s-agent_3:"
    )
  }

  moduleSnippets.foreach { case (name, f) =>
    s"GradleSnippets.$name" should "reject every module name that is not a plain artifact id" in {
      badModules.foreach { case (bad, why) =>
        withClue(s"$why ($bad): ") {
          val e = rejected(f(bad))
          e.field shouldBe "module"
          e.value shouldBe bad
        }
      }
    }

    it should "accept dotted, underscored and numeric artifact ids" in {
      Seq("llm4s-core", "llm4s-openai-compatible", "llm4s.core", "llm4s_core", "a1").foreach { ok =>
        snippet(f(ok)) should include(s"org.llm4s:${ok}_3:${GradleSnippets.LLM4S_VERSION}")
      }
    }

    it should "keep the module inside the quotes and emit balanced quotes and braces" in {
      val s = snippet(f("llm4s-core"))
      s.count(_ == '"')  % 2 shouldBe 0
      s.count(_ == '\'') % 2 shouldBe 0
      s.count(_ == '(') shouldBe s.count(_ == ')')
      s.count(_ == '{') shouldBe s.count(_ == '}')
      s.linesIterator.count(_.contains("org.llm4s:")) shouldBe 1
    }
  }

  "GradleSnippets Scala version" should "be rejected by both resolution strategies unless it looks like a version" in {
    Seq[String => Result[String]](
      GradleSnippets.kotlinDslScalaResolutionStrategy(_),
      GradleSnippets.groovyDslScalaResolutionStrategy(_)
    ).foreach { f =>
      badVersions.foreach { case (bad, why) =>
        withClue(s"$why ($bad): ") {
          val e = rejected(f(bad))
          e.field shouldBe "scalaVersion"
          e.value shouldBe bad
        }
      }
    }
  }

  it should "yield balanced braces and a single useVersion line when valid" in {
    Seq(GradleSnippets.kotlinDslScalaResolutionStrategy(), GradleSnippets.groovyDslScalaResolutionStrategy())
      .map(snippet)
      .foreach { s =>
        s.count(_ == '{') shouldBe s.count(_ == '}')
        s.linesIterator.count(_.contains("useVersion")) shouldBe 1
      }
  }

  "GradleSnippets rejection" should "carry the offending value in the error context" in {
    val e = rejected(GradleSnippets.kotlinDslDependency("bad name"))
    e.context("field") shouldBe "module"
    e.context("value") shouldBe "bad name"
  }
}
