package org.llm4s.samples.docs

import org.llm4s.agent.Agent
import org.llm4s.llmconnect.model._
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ToolCallingGuideSpec extends AnyFlatSpec with Matchers {

  // This spec EXECUTES the testing guide's tool-calling example; the page's own copy is only
  // compiled (project/DocSnippets.scala). The two must not drift apart silently, so the lines that
  // carry the example's substance must appear verbatim in the page.
  "the testing guide" should "contain the mock this spec executes, line for line" in {
    val page = {
      // the test runs with the repository root or the module directory as cwd; walk up to docs/
      var dir = new java.io.File(".").getCanonicalFile
      while (dir != null && !new java.io.File(dir, "docs/getting-started/testing-guide.md").isFile)
        dir = dir.getParentFile
      dir should not be null
      val src = scala.io.Source.fromFile(new java.io.File(dir, "docs/getting-started/testing-guide.md"), "UTF-8")
      try src.mkString
      finally src.close()
    }
    Seq(
      """Reply.toolCall("get_weather", """ + "\"\"\"" + """{"city": "London"}""" + "\"\"\"" + """),""",
      """Reply.text("It is 20°C in London.")""",
      "agent <- Agent.builder(\"test-agent\", client).withTools(new ToolRegistry(Seq(tool))).build()",
      "outcome.isRight shouldBe true",
      """capturedCity shouldBe Some("London")""",
      "client.callCount shouldBe 2",
      """client.calls(1).messages.collect { case t: ToolMessage => t.content } shouldBe Seq("Weather in London: 20°C")"""
    ).foreach(line => withClue(s"the guide no longer shows: $line -- ")(page should include(line)))
  }

  "Agent" should "invoke weather tool" in {
    var toolWasCalled                = false
    var capturedCity: Option[String] = None

    // A tool that records the arguments it was called with
    val weatherTool = ToolBuilder[Map[String, Any], String](
      "get_weather",
      "Get weather for a city",
      Schema
        .`object`[Map[String, Any]]("Weather query parameters")
        .withProperty(Schema.property("city", Schema.string("City name")))
    ).withHandler { extractor =>
      toolWasCalled = true
      extractor.getString("city").map { city =>
        capturedCity = Some(city)
        s"Weather in $city: 20°C"
      }
    }.buildSafe()

    val client = ScriptedLLMClient.sequence(
      Reply.toolCall("get_weather", """{"city": "London"}"""),
      Reply.text("It is 20°C in London.")
    )

    val outcome = for {
      tool  <- weatherTool
      agent <- Agent.builder("test-agent", client).withTools(new ToolRegistry(Seq(tool))).build()
      _     <- agent.run("What's the weather in London?")
    } yield ()

    outcome.isRight shouldBe true
    toolWasCalled shouldBe true
    capturedCity shouldBe Some("London")
    client.callCount shouldBe 2
    // the second request carries the tool's result back to the model
    client.calls(1).messages.collect { case t: ToolMessage => t.content } shouldBe Seq("Weather in London: 20°C")
  }
}
