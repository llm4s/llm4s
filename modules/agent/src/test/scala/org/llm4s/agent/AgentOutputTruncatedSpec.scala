package org.llm4s.agent

import org.llm4s.agent.AgentFixture.*
import org.llm4s.agent.AgentOutputSpec.{ invoiceSchema, Invoice }
import org.llm4s.error.NetworkError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.testutil.TestTimeLimit
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * An answer cut off at the output limit (`FinishReason.Length`) is incomplete, not malformed (#2933): an agent with a
 * response format neither checks it nor spends its corrective retry on it, and the turn ends `Truncated` as any
 * agent's does - also when the model wrote no text at all, every output token having gone to thinking.
 */
class AgentOutputTruncatedSpec extends AnyFlatSpec with TestTimeLimit with Matchers {

  private val valid = """{"customer":"Acme","total":12.5}"""
  private val half  = """{"customer":"Acme","items":[1,2,3],"tot"""

  private def cut(text: String): Completion =
    CompletionFixture.simple(text).withFinishReason(FinishReason.Length)

  /** A reply with no text: the whole output allowance spent on thinking. */
  private val thoughtOnly: Completion =
    cut("")
      .withMessage(AssistantMessage("").withThinking(Seq(ThinkingBlock.Text("considering every room"))))
      .withUsage(
        Some(TokenUsage(promptTokens = 10, completionTokens = 0, totalTokens = 16010).withThinkingTokens(16000))
      )

  private def withFormat(client: LLMClient): AgentBuilder =
    Agent.builder("extractor", client).withOutput(invoiceSchema)

  "A withOutput answer cut off at the output limit" should "end Truncated with the partial answer, in one model call" in {
    val client = ScriptedLLMClient.of(cut(half), CompletionFixture.simple(valid))
    val result = built(withFormat(client)).run("bill Acme").value

    result.status shouldBe AgentStatus.Truncated(half)
    result.answer shouldBe None
    client.callCount shouldBe 1
    result.messages.last shouldBe AssistantMessage(half)
    result.usage.requestCount shouldBe 1
  }

  it should "say the output limit cut it off when it is decoded" in {
    val result  = built(withFormat(ScriptedLLMClient.of(cut(half)))).run("bill Acme").value
    val message = result.decodeOutput[Invoice].left.map(_.message).swap.getOrElse("")
    message should include("cut off at the model's output limit")
    message should include("maxTokens")
  }

  it should "not be checked even when it happens to hold JSON the schema would refuse" in {
    // the lenient extraction finds the complete inner array of an unfinished object (#2933, call 2)
    val inner  = """{"customer":"Acme","rooms":[1,2,3],"total":"""
    val result = built(withFormat(ScriptedLLMClient.of(cut(inner)))).run("bill Acme").value
    result.status shouldBe AgentStatus.Truncated(inner)
  }

  it should "end Truncated when it is the corrective call's answer that is cut off" in {
    val client = ScriptedLLMClient.of(CompletionFixture.simple("not json"), cut(half))
    val result = built(withFormat(client)).run("bill Acme").value
    result.status shouldBe AgentStatus.Truncated(half)
    client.callCount shouldBe 2
  }

  it should "skip an application check too, which shares the format's retry" in {
    var seen   = 0
    val client = ScriptedLLMClient.of(cut(valid), CompletionFixture.simple(valid))
    val agent = built(withFormat(client).withOutputJsonCheck { _ =>
      seen += 1
      Seq("never asked")
    })
    agent.run("bill Acme").value.status shouldBe AgentStatus.Truncated(valid)
    client.callCount shouldBe 1
    seen shouldBe 0
  }

  it should "leave the thread for a next turn that completes" in {
    val client = ScriptedLLMClient.of(cut(half), CompletionFixture.simple(valid))
    val agent  = built(withFormat(client))
    val first  = agent.run("bill Acme").value
    agent.continueConversation(first, "again, shorter").value.decodeOutput[Invoice] shouldBe
      Right(Invoice("Acme", 12.5))
  }

  it should "end Truncated when the agent streams" in {
    val streamed = new LLMClient:
      override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
        Left(NetworkError("complete is not called when the agent streams", None, "test"))
      override def streamComplete(conversation: Conversation, options: CompletionOptions)(
        onChunk: StreamedChunk => Unit
      ): Result[Completion] =
        onChunk(StreamedChunk(id = "s", contentDelta = Some(half), finishReason = Some(FinishReason.Length)))
        Right(cut(half))
      override def contextWindow: Int     = 4096
      override def reserveCompletion: Int = 1024
    built(withFormat(streamed).withStreaming()).run("bill Acme").value.status shouldBe AgentStatus.Truncated(half)
  }

  "A cut-off reply with no text at all" should "end Truncated with an empty partial answer, without a retry" in {
    val client = ScriptedLLMClient.of(thoughtOnly, CompletionFixture.simple(valid))
    val result = built(withFormat(client)).run("bill Acme").value

    result.status shouldBe AgentStatus.Truncated("")
    client.callCount shouldBe 1
    // nothing of the reply is stored - a blank assistant message is not a valid one - but its call is counted
    result.messages.last shouldBe UserMessage("bill Acme")
    result.usage.requestCount shouldBe 1
  }

  it should "say the allowance went on thinking when it is decoded" in {
    val result  = built(withFormat(ScriptedLLMClient.of(thoughtOnly))).run("bill Acme").value
    val message = result.decodeOutput[Invoice].left.map(_.message).swap.getOrElse("")
    message should include("no text")
    message should include("reasoning effort")
  }

  it should "end Truncated for an agent without a response format too" in {
    val client = ScriptedLLMClient.of(thoughtOnly)
    built(Agent.builder("assistant", client)).run("go").value.status shouldBe AgentStatus.Truncated("")
  }

  it should "not report an earlier turn's answer as its partial answer" in {
    val client = ScriptedLLMClient.of(CompletionFixture.simple(valid), thoughtOnly, CompletionFixture.simple(valid))
    val agent  = built(withFormat(client))
    val first  = agent.run("bill Acme").value
    val second = agent.continueConversation(first, "and Beta").value
    second.status shouldBe AgentStatus.Truncated("")
    // the thread stays usable: the next turn's query follows the unanswered one
    agent.continueConversation(second, "again").value.status shouldBe AgentStatus.Completed(valid)
  }
}
