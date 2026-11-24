package org.llm4s.llmconnect.model

import org.llm4s.llmconnect.model.ToolCallId
import org.llm4s.types.ProviderModelTypes.ModelName
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
  private val sampleToolCall = ToolCall(ToolCallId("call-1"), "lookup", ujson.Obj("q" -> "scala"))
  private val citation       = Citation("https://example.com/a")
  private val bareUsage      = TokenUsage(promptTokens = 10, completionTokens = 20, totalTokens = 30)

  // Every value the tests read is set here, before any test is registered (-Wsafe-init).
  private val fullCompletion = completion()
  private val bareCompletion =
    Completion(
      id = "c-1",
      created = createdAt,
      model = ModelName("model-a"),
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
    model: String = "model-a",
    message: AssistantMessage = AssistantMessage("answer", Seq(sampleToolCall)),
    usage: Option[TokenUsage] = Some(bareUsage),
    estimatedCost: Option[BigDecimal] = Some(BigDecimal("0.25")),
    citations: List[Citation] = List(citation)
  ): Completion =
    Completion(
      id = id,
      created = created,
      model = ModelName(model),
      message = message,
      usage = usage,
      estimatedCost = estimatedCost,
      citations = citations
    )

  "Completion setters" should "set id, created, model and message" in {
    val later   = Instant.parse("2026-06-30T12:00:00Z")
    val message = AssistantMessage("other")

    fullCompletion.withId("c-2") shouldBe completion(id = "c-2")
    fullCompletion.withCreated(later) shouldBe completion(created = later)
    fullCompletion.withModel(ModelName("model-b")) shouldBe completion(model = "model-b")
    fullCompletion.withMessage(message) shouldBe completion(message = message)
  }

  it should "read content and toolCalls from the message, so they cannot disagree with it (#2807)" in {
    val second  = ToolCall(ToolCallId("call-2"), "fetch", ujson.Obj())
    val message = AssistantMessage("other", Seq(sampleToolCall, second))
    val changed = fullCompletion.withMessage(message)

    changed.content shouldBe "other"
    changed.toolCalls shouldBe Seq(sampleToolCall, second)
    changed.withMessage(message.withToolCalls(Nil)).toolCalls shouldBe empty
    changed.withMessage(AssistantMessage(None, Seq(second))).content shouldBe ""
  }

  it should "set and clear the citations" in {
    val other = Citation("https://example.com/b")

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
    val cost = BigDecimal("1.5")
    fullCompletion.withEstimatedCost(cost) shouldBe completion(estimatedCost = Some(cost))
    fullCompletion.withEstimatedCost(Some(cost)) shouldBe completion(estimatedCost = Some(cost))
    fullCompletion.withEstimatedCost(None) shouldBe completion(estimatedCost = None)

    val small = BigDecimal("0.75")
    bareCompletion.withEstimatedCost(small).estimatedCost shouldBe Some(small)
    bareCompletion.withEstimatedCost(small).withEstimatedCost(None) shouldBe bareCompletion
  }

  it should "update hasToolCalls and hasCitations" in {
    bareCompletion.hasToolCalls shouldBe false
    bareCompletion.withMessage(AssistantMessage(None, Seq(sampleToolCall))).hasToolCalls shouldBe true
    fullCompletion.withMessage(AssistantMessage("no calls")).hasToolCalls shouldBe false

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
    finishReason: Option[FinishReason] = Some(FinishReason.Stop),
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
    val other = ToolCall(ToolCallId("call-2"), "fetch", ujson.Obj())

    fullChunk.withToolCall(other) shouldBe chunk(toolCall = Some(other))
    fullChunk.withToolCall(Some(other)) shouldBe chunk(toolCall = Some(other))
    fullChunk.withToolCall(None) shouldBe chunk(toolCall = None)

    bareChunk.withToolCall(other).toolCall shouldBe Some(other)
    bareChunk.withToolCall(other).withToolCall(None) shouldBe bareChunk
  }

  it should "set and clear the finish reason, with both overloads" in {
    fullChunk.withFinishReason(FinishReason.Length) shouldBe chunk(finishReason = Some(FinishReason.Length))
    fullChunk.withFinishReason(Some(FinishReason.Length)) shouldBe chunk(finishReason = Some(FinishReason.Length))
    fullChunk.withFinishReason(None) shouldBe chunk(finishReason = None)

    bareChunk.withFinishReason(FinishReason.Stop).finishReason shouldBe Some(FinishReason.Stop)
    bareChunk.withFinishReason(FinishReason.Stop).withFinishReason(None) shouldBe bareChunk
  }

  it should "set and clear the thinking delta, with both overloads" in {
    fullChunk.withThinkingDelta("more") shouldBe chunk(thinkingDelta = Some("more"))
    fullChunk.withThinkingDelta(Some("more")) shouldBe chunk(thinkingDelta = Some("more"))
    fullChunk.withThinkingDelta(None) shouldBe chunk(thinkingDelta = None)

    bareChunk.withThinkingDelta("hmm").thinkingDelta shouldBe Some("hmm")
    bareChunk.withThinkingDelta("hmm").withThinkingDelta(None) shouldBe bareChunk
  }
}
