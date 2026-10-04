package org.llm4s.gradle

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GradleSnippetsSpec extends AnyFlatSpec with Matchers {

  private val coreCoordinate = s"org.llm4s:llm4s-core_3:${GradleSnippets.LLM4S_VERSION}"

  "GradleSnippets.LLM4S_VERSION" should "be a semantic version" in {
    (GradleSnippets.LLM4S_VERSION should fullyMatch).regex("""^\d+\.\d+\.\d+.*""")
  }

  "GradleSnippets.kotlinDslDependency" should "produce the Scala 3 llm4s-core coordinate by default" in {
    GradleSnippets.kotlinDslDependency() shouldBe s"""implementation("$coreCoordinate")"""
  }

  it should "accept a custom module" in {
    GradleSnippets.kotlinDslDependency("llm4s-openai") shouldBe
      s"""implementation("org.llm4s:llm4s-openai_3:${GradleSnippets.LLM4S_VERSION}")"""
  }

  "GradleSnippets.groovyDslDependency" should "produce the Scala 3 llm4s-core coordinate by default" in {
    GradleSnippets.groovyDslDependency() shouldBe s"implementation '$coreCoordinate'"
  }

  it should "accept a custom module" in {
    GradleSnippets.groovyDslDependency("llm4s-agent") shouldBe
      s"implementation 'org.llm4s:llm4s-agent_3:${GradleSnippets.LLM4S_VERSION}'"
  }

  "GradleSnippets.kotlinDslWithLogbackExclusion" should "exclude logback-classic from the dependency" in {
    GradleSnippets.kotlinDslWithLogbackExclusion() shouldBe
      s"""implementation("$coreCoordinate") {
         |    exclude(group = "ch.qos.logback", module = "logback-classic")
         |}""".stripMargin
  }

  it should "accept a custom module" in {
    GradleSnippets.kotlinDslWithLogbackExclusion("llm4s-agent") should startWith(
      "implementation(\"org.llm4s:llm4s-agent_3:"
    )
  }

  "GradleSnippets.kotlinDslWithAzureExclusion" should "exclude azure-ai-openai from the dependency" in {
    GradleSnippets.kotlinDslWithAzureExclusion() shouldBe
      s"""implementation("$coreCoordinate") {
         |    exclude(group = "com.azure", module = "azure-ai-openai")
         |}""".stripMargin
  }

  it should "accept a custom module" in {
    GradleSnippets.kotlinDslWithAzureExclusion("llm4s-agent") should startWith(
      "implementation(\"org.llm4s:llm4s-agent_3:"
    )
  }

  "GradleSnippets.kotlinDslScalaResolutionStrategy" should "pin org.scala-lang to the default Scala version" in {
    GradleSnippets.kotlinDslScalaResolutionStrategy() shouldBe
      """configurations.all {
        |    resolutionStrategy.eachDependency {
        |        if (requested.group == "org.scala-lang") {
        |            useVersion("3.7.1")
        |        }
        |    }
        |}""".stripMargin
  }

  it should "accept a custom Scala version" in {
    val snippet = GradleSnippets.kotlinDslScalaResolutionStrategy("3.6.0")
    snippet should include("""useVersion("3.6.0")""")
    (snippet should not).include("3.7.1")
  }

  "GradleSnippets.groovyDslWithLogbackExclusion" should "exclude logback-classic in Groovy style" in {
    GradleSnippets.groovyDslWithLogbackExclusion() shouldBe
      s"""implementation('$coreCoordinate') {
         |    exclude group: 'ch.qos.logback', module: 'logback-classic'
         |}""".stripMargin
  }

  it should "accept a custom module" in {
    GradleSnippets.groovyDslWithLogbackExclusion("llm4s-agent") should startWith(
      "implementation('org.llm4s:llm4s-agent_3:"
    )
  }

  "GradleSnippets.groovyDslWithAzureExclusion" should "exclude azure-ai-openai in Groovy style" in {
    GradleSnippets.groovyDslWithAzureExclusion() shouldBe
      s"""implementation('$coreCoordinate') {
         |    exclude group: 'com.azure', module: 'azure-ai-openai'
         |}""".stripMargin
  }

  it should "accept a custom module" in {
    GradleSnippets.groovyDslWithAzureExclusion("llm4s-agent") should startWith(
      "implementation('org.llm4s:llm4s-agent_3:"
    )
  }
}
