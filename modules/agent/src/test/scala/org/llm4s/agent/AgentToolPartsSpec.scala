package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.SpecTools.{ call, calling }
import org.llm4s.agent.graph.ThreadId
import org.llm4s.agent.graph.tool.{ ToolContent, ToolOutcome }
import org.llm4s.llmconnect.model._
import org.llm4s.media.{ MediaSource, MediaType }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI

/** A tool's result carries content parts into the `ToolMessage` the model is sent (#1913). */
class AgentToolPartsSpec extends AnyFlatSpec with Matchers {

  private def part[A](built: org.llm4s.types.Result[A]): A = built.fold(e => fail(e.message), identity)

  private val chart = part(
    ContentPart.image(MediaSource.Url(URI.create("https://example.com/chart.png")), MediaType.Png)
  )

  private def inlineImage(bytes: Int): ContentPart =
    part(ContentPart.image(MediaSource.Inline(IArray.fill[Byte](bytes)(1)), MediaType.Png))

  /** Runs one call of a tool returning `content`; the tool message the model is sent next. */
  private def resultOf(content: ToolContent): ToolMessage = {
    val tool   = SpecTools.tool("render")((_, _) => ToolOutcome.Success(content))
    val client = ScriptedLLMClient.of(calling(call("c1", "render")), CompletionFixture.simple("done"))
    val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(tool)))
    agent.run(ThreadId("parts"), "go").value.answer shouldBe Some("done")
    client.sent(1).collect { case t: ToolMessage => t }.head
  }

  "A tool's Parts" should "reach the model unchanged, in the tool message" in {
    val parts = Vector(ContentPart.Text("Here is the chart."), chart)
    resultOf(ToolContent.Parts(parts)).parts shouldBe parts
  }

  "A tool's Json" should "be one text part, rendered as before" in {
    resultOf(ToolContent.Json(ujson.Str("plain"))).parts shouldBe Vector(ContentPart.Text("plain"))
    resultOf(ToolContent.Json(ujson.Obj("n" -> 1))).parts shouldBe Vector(ContentPart.Text("""{"n":1}"""))
  }

  "Parts with no content" should "be the call's error result, decided when the tool runs" in {
    resultOf(ToolContent.Parts(Vector.empty)).content shouldBe """{"error":"Tool 'render' returned no content"}"""
    resultOf(ToolContent.Parts(Vector(ContentPart.Text(" ")))).content should include("returned no content")
  }

  "Parts over the inline bound in total" should "be the call's error result" in {
    val half = ContentPart.MaxInlineBytes / 2 + 1
    val over = resultOf(ToolContent.Parts(Vector(inlineImage(half), inlineImage(half))))
    over.parts.forall(_.isInstanceOf[ContentPart.Text]) shouldBe true
    over.content should include(s"${2 * half} inline bytes")
    over.content should include("Url or a ProviderFile")
    resultOf(ToolContent.Parts(Vector(inlineImage(half)))).parts.size shouldBe 1
  }
}
