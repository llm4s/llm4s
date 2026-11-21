package org.llm4s.llmconnect.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/**
 * Each `with*` setter of `Completion`, `TokenUsage` and `StreamedChunk` changes its own field and nothing else.
 *
 * Every setter runs on a value with all fields set, so a setter that also reset another field fails the
 * comparison. Each `Option` overload is also checked on a value with the field unset: the plain overload
 * gives `Some`, and the `None` overload clears it again.
 */
class CompletionSettersSpec extends AnyFlatSpec with Matchers {

  private val createdAt      = Instant.parse("2026-01-01T00:00:00Z")
  private val sampleToolCall = ToolCall("call-1", "lookup", ujson.Obj("q" -> "scala"))
  private val citation       = Citation("https://example.com/a")
  private val bareUsage      = TokenUsage(promptTokens = 10, completionTokens = 20, totalTokens = 30)

  // Every value the tests read is set here, before any test is registered (-Wsafe-init).
  private val fullCompletion = completion()
  private val bareCompletion =
    Completion(
      id = "c-1",
      created = createdAt,
      content = "answer",
      model = "model-a",
      message = AssistantMessage("answer")
    )
  private val fullUsage = tokenUsage()
  private val fullChunk = chunk()
  private val bareChunk = StreamedChunk(id = "s-1", content = None)

  // ================================= COMPLETION =================================

  /** A completion with every field set; each argument overrides one field. */
  private def completion(
    id: String = "c-1",
    created: Instant = createdAt,
    content: String = "answer",
    model: String = "model-a",
    message: AssistantMessage = AssistantMessage("answer"),
    toolCalls: List[ToolCall] = List(sampleToolCall),
    usage: Option[TokenUsage] = Some(bareUsage),
    estimatedCost: Option[Double] = Some(0.25),
    citations: List[Citation] = List(citation)
  ): Completion =
    Completion(
      id = id,
      created = created,
      content = content,
      model = model,
      message = message,
      toolCalls = toolCalls,
      usage = usage,
      estimatedCost = estimatedCost,
      citations = citations
    )

  "Completion setters" should "set id, created, content, model and message" in {
    val later   = Instant.parse("2026-06-30T12:00:00Z")
    val message = AssistantMessage("other")

    fullCompletion.withId("c-2") shouldBe completion(id = "c-2")
    fullCompletion.withCreated(later) shouldBe completion(created = later)
    fullCompletion.withContent("other") shouldBe completion(content = "other")
    fullCompletion.withModel("model-b") shouldBe completion(model = "model-b")
    fullCompletion.withMessage(message) shouldBe completion(message = message)
  }

  it should "set and clear the tool calls and citations" in {
    val second = ToolCall("call-2", "fetch", ujson.Obj())
    val other  = Citation("https://example.com/b")

    fullCompletion.withToolCalls(List(sampleToolCall, second)) shouldBe completion(toolCalls =
      List(sampleToolCall, second)
    )
    fullCompletion.withToolCalls(Nil) shouldBe completion(toolCalls = Nil)
    fullCompletion.withCitations(List(other)) shouldBe completion(citations = List(other))
    fullCompletion.withCitations(Nil) shouldBe completion(citations = Nil)
  }

  it should "set and clear the usage, with both overloads" in {
    val other = TokenUsage(promptTokens = 1, completionTokens = 2, totalTokens = 3)

    fullCompletion.withUsage(other) shouldBe completion(usage = Some(other))
    fullCompletion.withUsage(Some(other)) shouldBe completion(usage = Some(other))
    fullCompletion.withUsage(None) shouldBe completion(usage = None)

    bareCompletion.withUsage(bareUsage).usage shouldBe Some(bareUsage)
    bareCompletion.withUsage(bareUsage).withUsage(None) shouldBe bareCompletion
  }

  it should "set and clear the estimated cost, with both overloads" in {
    fullCompletion.withEstimatedCost(1.5) shouldBe completion(estimatedCost = Some(1.5))
    fullCompletion.withEstimatedCost(Some(1.5)) shouldBe completion(estimatedCost = Some(1.5))
    fullCompletion.withEstimatedCost(None) shouldBe completion(estimatedCost = None)

    bareCompletion.withEstimatedCost(0.75).estimatedCost shouldBe Some(0.75)
    bareCompletion.withEstimatedCost(0.75).withEstimatedCost(None) shouldBe bareCompletion
  }

  it should "update hasToolCalls and hasCitations" in {
    bareCompletion.hasToolCalls shouldBe false
    bareCompletion.withToolCalls(List(sampleToolCall)).hasToolCalls shouldBe true
    fullCompletion.withToolCalls(Nil).hasToolCalls shouldBe false

    bareCompletion.hasCitations shouldBe false
    bareCompletion.withCitations(List(citation)).hasCitations shouldBe true
    fullCompletion.withCitations(Nil).hasCitations shouldBe false
  }

  // ================================= TOKEN USAGE =================================

  /** A usage with every field set; each argument overrides one field. */
  private def tokenUsage(
    promptTokens: Int = 100,
    completionTokens: Int = 40,
    totalTokens: Int = 150,
    thinkingTokens: Option[Int] = Some(10),
    cachedTokens: Option[Int] = Some(60),
    cacheCreationTokens: Option[Int] = Some(20)
  ): TokenUsage =
    TokenUsage(
      promptTokens = promptTokens,
      completionTokens = completionTokens,
      totalTokens = totalTokens,
      thinkingTokens = thinkingTokens,
      cachedTokens = cachedTokens,
      cacheCreationTokens = cacheCreationTokens
    )

  "TokenUsage setters" should "set the prompt, completion and total tokens" in {
    fullUsage.withPromptTokens(7) shouldBe tokenUsage(promptTokens = 7)
    fullUsage.withCompletionTokens(8) shouldBe tokenUsage(completionTokens = 8)
    fullUsage.withTotalTokens(9) shouldBe tokenUsage(totalTokens = 9)
  }

  it should "set and clear the thinking tokens, with both overloads" in {
    fullUsage.withThinkingTokens(5) shouldBe tokenUsage(thinkingTokens = Some(5))
    fullUsage.withThinkingTokens(Some(5)) shouldBe tokenUsage(thinkingTokens = Some(5))
    fullUsage.withThinkingTokens(None) shouldBe tokenUsage(thinkingTokens = None)

    bareUsage.withThinkingTokens(5).thinkingTokens shouldBe Some(5)
    bareUsage.withThinkingTokens(5).withThinkingTokens(None) shouldBe bareUsage
  }

  it should "set and clear the cached tokens, with both overloads" in {
    fullUsage.withCachedTokens(5) shouldBe tokenUsage(cachedTokens = Some(5))
    fullUsage.withCachedTokens(Some(5)) shouldBe tokenUsage(cachedTokens = Some(5))
    fullUsage.withCachedTokens(None) shouldBe tokenUsage(cachedTokens = None)

    bareUsage.withCachedTokens(5).cachedTokens shouldBe Some(5)
    bareUsage.withCachedTokens(5).withCachedTokens(None) shouldBe bareUsage
  }

  it should "set and clear the cache creation tokens, with both overloads" in {
    fullUsage.withCacheCreationTokens(5) shouldBe tokenUsage(cacheCreationTokens = Some(5))
    fullUsage.withCacheCreationTokens(Some(5)) shouldBe tokenUsage(cacheCreationTokens = Some(5))
    fullUsage.withCacheCreationTokens(None) shouldBe tokenUsage(cacheCreationTokens = None)

    bareUsage.withCacheCreationTokens(5).cacheCreationTokens shouldBe Some(5)
    bareUsage.withCacheCreationTokens(5).withCacheCreationTokens(None) shouldBe bareUsage
  }

  it should "update totalOutputTokens and hasThinkingTokens" in {
    // Thinking counted apart from the completion and included in the total (prompt 10 + completion 20 + thinking 50).
    val separate = TokenUsage(promptTokens = 10, completionTokens = 20, totalTokens = 80)

    separate.hasThinkingTokens shouldBe false
    separate.totalOutputTokens shouldBe 20

    separate.withThinkingTokens(50).hasThinkingTokens shouldBe true
    separate.withThinkingTokens(50).totalOutputTokens shouldBe 70

    separate.withThinkingTokens(50).withThinkingTokens(None).hasThinkingTokens shouldBe false
    separate.withThinkingTokens(50).withThinkingTokens(None).totalOutputTokens shouldBe 20

    separate.withThinkingTokens(0).hasThinkingTokens shouldBe false
    separate.withThinkingTokens(0).totalOutputTokens shouldBe 20
  }

  // ================================= STREAMED CHUNK =================================

  /** A chunk with every field set; each argument overrides one field. */
  private def chunk(
    id: String = "s-1",
    content: Option[String] = Some("delta"),
    toolCall: Option[ToolCall] = Some(sampleToolCall),
    finishReason: Option[String] = Some("stop"),
    thinkingDelta: Option[String] = Some("hmm")
  ): StreamedChunk =
    StreamedChunk(
      id = id,
      content = content,
      toolCall = toolCall,
      finishReason = finishReason,
      thinkingDelta = thinkingDelta
    )

  "StreamedChunk setters" should "set the id" in {
    fullChunk.withId("s-2") shouldBe chunk(id = "s-2")
  }

  it should "set and clear the content, with both overloads" in {
    fullChunk.withContent("other") shouldBe chunk(content = Some("other"))
    fullChunk.withContent(Some("other")) shouldBe chunk(content = Some("other"))
    fullChunk.withContent(None) shouldBe chunk(content = None)

    bareChunk.withContent("x").content shouldBe Some("x")
    bareChunk.withContent("x").withContent(None) shouldBe bareChunk
  }

  it should "set and clear the tool call, with both overloads" in {
    val other = ToolCall("call-2", "fetch", ujson.Obj())

    fullChunk.withToolCall(other) shouldBe chunk(toolCall = Some(other))
    fullChunk.withToolCall(Some(other)) shouldBe chunk(toolCall = Some(other))
    fullChunk.withToolCall(None) shouldBe chunk(toolCall = None)

    bareChunk.withToolCall(other).toolCall shouldBe Some(other)
    bareChunk.withToolCall(other).withToolCall(None) shouldBe bareChunk
  }

  it should "set and clear the finish reason, with both overloads" in {
    fullChunk.withFinishReason("length") shouldBe chunk(finishReason = Some("length"))
    fullChunk.withFinishReason(Some("length")) shouldBe chunk(finishReason = Some("length"))
    fullChunk.withFinishReason(None) shouldBe chunk(finishReason = None)

    bareChunk.withFinishReason("stop").finishReason shouldBe Some("stop")
    bareChunk.withFinishReason("stop").withFinishReason(None) shouldBe bareChunk
  }

  it should "set and clear the thinking delta, with both overloads" in {
    fullChunk.withThinkingDelta("more") shouldBe chunk(thinkingDelta = Some("more"))
    fullChunk.withThinkingDelta(Some("more")) shouldBe chunk(thinkingDelta = Some("more"))
    fullChunk.withThinkingDelta(None) shouldBe chunk(thinkingDelta = None)

    bareChunk.withThinkingDelta("hmm").thinkingDelta shouldBe Some("hmm")
    bareChunk.withThinkingDelta("hmm").withThinkingDelta(None) shouldBe bareChunk
  }
}
