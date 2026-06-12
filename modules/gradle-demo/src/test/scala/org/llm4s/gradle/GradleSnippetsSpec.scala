package org.llm4s.gradle

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GradleSnippetsSpec extends AnyFlatSpec with Matchers {

  "GradleSnippets.LLM4S_VERSION" should "be a non-empty version string" in {
    GradleSnippets.LLM4S_VERSION should not be empty
    (GradleSnippets.LLM4S_VERSION should fullyMatch).regex("""^\d+\.\d+\.\d+.*""")
  }

  "GradleSnippets.kotlinDslDependency" should "produce a valid Kotlin DSL dependency string with defaults" in {
    val snippet = GradleSnippets.kotlinDslDependency()
    snippet should include("org.llm4s:core_3")
    snippet should include(GradleSnippets.LLM4S_VERSION)
    snippet should startWith("implementation(")
  }

  it should "accept custom module and scala suffix" in {
    val snippet = GradleSnippets.kotlinDslDependency("java-api", "2.13")
    snippet should include("org.llm4s:java-api_2.13")
    snippet should include(GradleSnippets.LLM4S_VERSION)
  }

  "GradleSnippets.groovyDslDependency" should "produce a valid Groovy DSL dependency string with defaults" in {
    val snippet = GradleSnippets.groovyDslDependency()
    snippet should include("org.llm4s:core_3")
    snippet should include(GradleSnippets.LLM4S_VERSION)
    snippet should startWith("implementation '")
  }

  it should "accept custom module and scala suffix" in {
    val snippet = GradleSnippets.groovyDslDependency("java-api", "2.13")
    snippet should include("org.llm4s:java-api_2.13")
  }

  "GradleSnippets.kotlinDslWithLogbackExclusion" should "include logback exclusion block" in {
    val snippet = GradleSnippets.kotlinDslWithLogbackExclusion()
    snippet should include("ch.qos.logback")
    snippet should include("logback-classic")
    snippet should include("exclude(")
    snippet should include("org.llm4s:core_3")
  }

  it should "accept custom module" in {
    val snippet = GradleSnippets.kotlinDslWithLogbackExclusion("java-api")
    snippet should include("org.llm4s:java-api_3")
    snippet should include("logback-classic")
  }

  "GradleSnippets.kotlinDslWithAzureExclusion" should "include Azure exclusion block" in {
    val snippet = GradleSnippets.kotlinDslWithAzureExclusion()
    snippet should include("com.azure")
    snippet should include("azure-ai-openai")
    snippet should include("exclude(")
  }

  it should "accept custom module and scala suffix" in {
    val snippet = GradleSnippets.kotlinDslWithAzureExclusion("core", "2.13")
    snippet should include("org.llm4s:core_2.13")
    snippet should include("com.azure")
  }

  "GradleSnippets.kotlinDslWithAnthropicHttpExclusion" should "include Apache HTTP client exclusion block" in {
    val snippet = GradleSnippets.kotlinDslWithAnthropicHttpExclusion()
    snippet should include("org.apache.httpcomponents.client5")
    snippet should include("httpclient5")
    snippet should include("exclude(")
  }

  it should "accept custom module" in {
    val snippet = GradleSnippets.kotlinDslWithAnthropicHttpExclusion("java-api")
    snippet should include("org.llm4s:java-api_3")
    snippet should include("httpclient5")
  }

  "GradleSnippets.kotlinDslScalaResolutionStrategy" should "produce a resolution strategy block with default Scala version" in {
    val snippet = GradleSnippets.kotlinDslScalaResolutionStrategy()
    snippet should include("configurations.all")
    snippet should include("resolutionStrategy")
    snippet should include("org.scala-lang")
    snippet should include("3.7.1")
  }

  it should "accept a custom Scala version" in {
    val snippet = GradleSnippets.kotlinDslScalaResolutionStrategy("3.6.0")
    snippet should include("3.6.0")
    (snippet should not).include("3.7.1")
  }

  "GradleSnippets.groovyDslWithLogbackExclusion" should "include logback exclusion in Groovy style" in {
    val snippet = GradleSnippets.groovyDslWithLogbackExclusion()
    snippet should include("ch.qos.logback")
    snippet should include("logback-classic")
    snippet should include("exclude group:")
  }

  it should "accept custom module" in {
    val snippet = GradleSnippets.groovyDslWithLogbackExclusion("java-api")
    snippet should include("org.llm4s:java-api_3")
    snippet should include("logback-classic")
  }

  "GradleSnippets.groovyDslWithAzureExclusion" should "include Azure exclusion in Groovy style" in {
    val snippet = GradleSnippets.groovyDslWithAzureExclusion()
    snippet should include("com.azure")
    snippet should include("azure-ai-openai")
    snippet should include("exclude group:")
  }

  it should "accept custom module and scala suffix" in {
    val snippet = GradleSnippets.groovyDslWithAzureExclusion("core", "2.13")
    snippet should include("org.llm4s:core_2.13")
    snippet should include("com.azure")
  }
}
