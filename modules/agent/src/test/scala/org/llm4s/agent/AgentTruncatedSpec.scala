package org.llm4s.agent

import org.llm4s.agent.AgentFixture.*
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.{ AssistantMessage, FinishReason }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * An answer the model's output limit cut off (`FinishReason.Length`) ends the turn `AgentStatus.Truncated`, never
 * `Completed` (RFC 0009, open question 6).
 */
class AgentTruncatedSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def cut(text: String) = CompletionFixture.simple(text).withFinishReason(FinishReason.Length)

  "A turn whose answer was cut off" should "end Truncated with the partial answer, and no answer" in {
    val result = built(Agent.builder("assistant", ScriptedLLMClient.of(cut("The first half")))).run("go").value
    result.status shouldBe AgentStatus.Truncated("The first half")
    result.answer shouldBe None
    result.messages.last shouldBe AssistantMessage("The first half")
    result.decodeOutput[Map[String, Int]].left.value shouldBe a[ValidationError]
    result.decodeOutput[Map[String, Int]].left.value.message should include("Truncated")
  }

  it should "end Truncated after a tool round too, and leave the thread for a next turn that completes" in {
    val call = SpecTools.calling(SpecTools.call("c1", "echo"))
    val echo =
      SpecTools.tool("echo")((a, _) => graph.tool.ToolOutcome.success(graph.tool.ToolContent.Json(ujson.Str(a.text))))
    val client = ScriptedLLMClient.of(call, cut("partial"), CompletionFixture.simple("the rest"))
    val agent  = built(Agent.builder("assistant", client).withToolSet(SpecTools.set(echo)))
    val first  = agent.run("go").value
    first.status shouldBe AgentStatus.Truncated("partial")
    val second = agent.continueConversation(first, "go on").value
    second.status shouldBe AgentStatus.Completed("the rest")
    second.answer shouldBe Some("the rest")
  }

  it should "stop runMultiTurn, as any status but Completed does" in {
    val client = ScriptedLLMClient.of(cut("partial"), CompletionFixture.simple("never asked"))
    val result = built(Agent.builder("assistant", client)).runMultiTurn("first", Seq("second")).value
    result.status shouldBe AgentStatus.Truncated("partial")
  }

  "A turn whose answer ended normally" should "still end Completed" in {
    val stopped = CompletionFixture.simple("whole").withFinishReason(FinishReason.Stop)
    built(Agent.builder("assistant", ScriptedLLMClient.of(stopped))).run("go").value.status shouldBe
      AgentStatus.Completed("whole")
  }
}
