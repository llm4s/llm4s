package org.llm4s.javaapi

import org.llm4s.error.{ CancelledError, ServiceError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

/**
 * `JLlmClient.completion` and the [[JCompletion]] it returns (#1487): the model, usage, cost and tool calls of a reply
 * reach Java, read with Java types, from a fake client that records what it was sent.
 */
class JCompletionSpec extends AnyFlatSpec with Matchers {

  private val toolCall = ToolCall("call-1", "weather", ujson.Obj("city" -> "Paris"))

  private val full: Completion = Completion(
    id = "reply-1",
    created = 0L,
    content = "Let me check.",
    model = "gpt-4o-2024-08-06",
    message = AssistantMessage("Let me check.").withThinking("The user wants the weather."),
    toolCalls = List(toolCall, ToolCall("call-2", "clock", ujson.Obj())),
    usage = Some(
      TokenUsage(
        promptTokens = 120,
        completionTokens = 30,
        totalTokens = 150,
        thinkingTokens = Some(7),
        cachedTokens = Some(80),
        cacheCreationTokens = Some(25)
      )
    ),
    estimatedCost = Some(0.0015)
  )

  private val bare: Completion = Completion("reply-2", 0L, "4", "m", AssistantMessage("4"))

  /** A client that records each conversation and options it is sent, and answers with `reply`. */
  private class Recording(reply: => Result[Completion]) extends LLMClient {
    val sent: ArrayBuffer[(Conversation, CompletionOptions)] = ArrayBuffer.empty
    override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = {
      sent += (c -> o)
      reply
    }
    override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
      complete(c, o)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  private def replying(completion: Completion): Recording = new Recording(Right(completion))

  "JLlmClient.completion" should "return the model, the usage, the cost and the tool calls of the reply" in {
    val reply = new JLlmClient(replying(full)).completion("Weather in Paris?").get()

    reply.id shouldBe "reply-1"
    reply.content shouldBe "Let me check."
    reply.model shouldBe "gpt-4o-2024-08-06"
    reply.toolCalls.asScala.map(c => (c.id, c.name, c.argumentsJson)) shouldBe Seq(
      ("call-1", "weather", """{"city":"Paris"}"""),
      ("call-2", "clock", "{}")
    )
    val usage = reply.usage.get()
    (usage.promptTokens, usage.completionTokens, usage.totalTokens, usage.thinkingTokens) shouldBe (120, 30, 150, 7)
    (usage.cachedTokens, usage.cacheCreationTokens) shouldBe ((80, 25))
    reply.estimatedCost.get() shouldBe new java.math.BigDecimal("0.0015")
    reply.thinking.get() shouldBe "The user wants the weather."
  }

  it should "read the same reply from Java, with Java types only" in {
    CompletionCheck.readBack(new JLlmClient(replying(full)).completion("q").get()).asScala.toList shouldBe List(
      "id:reply-1",
      "content:Let me check.",
      "model:gpt-4o-2024-08-06",
      """tool:call-1:weather:{"city":"Paris"}""",
      "tool:call-2:clock:{}",
      "usage:120/30/150/7",
      "cache:80/25",
      "cost:0.0015",
      "thinking:The user wants the weather."
    )
  }

  it should "leave usage, cost and thinking empty, and the tool calls an empty list, when the provider reported none" in {
    val reply = new JLlmClient(replying(bare)).completion("q").get()

    reply.usage.isPresent shouldBe false
    reply.estimatedCost.isPresent shouldBe false
    reply.thinking.isPresent shouldBe false
    reply.toolCalls shouldBe java.util.List.of()
    CompletionCheck.readBack(reply).asScala.toList shouldBe List(
      "id:reply-2",
      "content:4",
      "model:m",
      "cost:unknown",
      "thinking:none"
    )
  }

  it should "read thinking and cache tokens the provider did not report as zero, as the agent's usage does" in {
    val unreported = full.withUsage(TokenUsage(promptTokens = 1, completionTokens = 2, totalTokens = 3))
    val usage      = new JLlmClient(replying(unreported)).completion("q").get().usage.get()
    (usage.thinkingTokens, usage.cachedTokens, usage.cacheCreationTokens) shouldBe ((0, 0, 0))
  }

  it should "read cache reads and cache writes apart, each as the provider reported it" in {
    def read(cached: Option[Int], creation: Option[Int]): (Int, Int) = {
      val tokens = TokenUsage(10, 2, 12, cachedTokens = cached, cacheCreationTokens = creation)
      val usage  = new JLlmClient(replying(full.withUsage(tokens))).completion("q").get().usage.get()
      (usage.cachedTokens, usage.cacheCreationTokens)
    }
    read(Some(6), None) shouldBe ((6, 0))
    read(None, Some(9)) shouldBe ((0, 9))
  }

  it should "give the cost as core's agent usage adds it, so a reply's cost and an agent turn's total agree" in {
    val cost  = 0.1 + 0.2 // 0.30000000000000004 as a Double
    val reply = new JLlmClient(replying(full.withEstimatedCost(cost))).completion("q").get()
    val turn  = UsageSummary().add("m", full.usage.get, Some(cost))

    reply.estimatedCost.get() shouldBe turn.totalCost.bigDecimal
  }

  it should "read a cost that is not a number as unknown, not fail" in {
    Seq(Double.NaN, Double.PositiveInfinity).foreach { cost =>
      val result = new JLlmClient(replying(full.withEstimatedCost(cost))).completion("q")
      result.get().estimatedCost.isPresent shouldBe false
    }
  }

  it should "read a null text as empty, as the agent's messages do" in {
    new JLlmClient(replying(bare.withContent(null))).completion("q").get().content shouldBe ""
  }

  it should "send the query as one user message with default options" in {
    val client = replying(bare)
    new JLlmClient(client).completion("What is 2+2?")
    client.sent.toList shouldBe List(Conversation(Seq(UserMessage("What is 2+2?"))) -> CompletionOptions())
  }

  it should "send a conversation as it is, alone or with the options a JCompletionOptions builder made" in {
    val client       = replying(bare)
    val conversation = ConversationBuilder.create().system("be brief").user("hi").build()
    val options      = JCompletionOptions.builder().temperature(0.1).maxTokens(64).build()

    new JLlmClient(client).completion(conversation).get().content shouldBe "4"
    new JLlmClient(client).completion(conversation, options).get().content shouldBe "4"

    client.sent.toList shouldBe List(
      conversation -> CompletionOptions(),
      conversation -> CompletionOptions(temperature = 0.1, maxTokens = Some(64))
    )
  }

  it should "offer every overload from Java source" in {
    val client = replying(full)
    CompletionCheck.everyOverload(new JLlmClient(client)).asScala.map(_.get().model) shouldBe Seq.fill(3)(full.model)
    client.sent.map(_._2.maxTokens).toList shouldBe List(None, None, Some(64))
  }

  it should "fail, not throw, for a null argument - a literal null options included, which Java passes without a cast" in {
    val client       = replying(bare)
    val jClient      = new JLlmClient(client)
    val conversation = ConversationBuilder.create().user("hi").build()
    val options      = JCompletionOptions.builder().build()

    val failures = List(
      "query"        -> jClient.completion(null.asInstanceOf[String]),
      "conversation" -> jClient.completion(null.asInstanceOf[Conversation]),
      "conversation" -> jClient.completion(null, options),
      "options"      -> jClient.completion(conversation, null),
      "options"      -> CompletionCheck.nullOptions(jClient)
    )
    failures.foreach { case (name, result) =>
      result.isFailure shouldBe true
      result.getError().error shouldBe ValidationError.required(name)
    }
    client.sent shouldBe empty
  }

  it should "pass the client's failure through" in {
    val down   = ServiceError(503, "openai", "overloaded")
    val result = new JLlmClient(new Recording(Left(down))).completion("q")
    result.getError().error shouldBe down
  }

  it should "turn an exception from the client, or from reading its reply, into a failed result" in {
    val throwing = new Recording(throw new IllegalStateException("provider bug"))
    new JLlmClient(throwing).completion("q").getError().getMessage should include("provider bug")

    val unreadable = full.withToolCalls(List(ToolCall("c", "t", null)))
    new JLlmClient(replying(unreadable)).completion("q").isFailure shouldBe true
  }

  it should "turn an InterruptedException into a CancelledError, with the interrupt flag restored" in {
    val interrupted = new Recording(throw new InterruptedException("stop"))
    val outcome     = new AtomicReference[(LlmResult[JCompletion], Boolean)]()
    val thread =
      new Thread(() => outcome.set(new JLlmClient(interrupted).completion("q") -> Thread.currentThread().isInterrupted))
    thread.setDaemon(true)
    thread.start()
    thread.join(20000L)

    val (result, flagSet) = outcome.get()
    result.getError().error shouldBe a[CancelledError]
    result.getError().error.context("operation") shouldBe "JLlmClient.completion"
    result.getError().getKind shouldBe LlmErrorKind.CANCELLED
    flagSet shouldBe true
  }

  "the existing complete overloads" should "still return the text alone" in {
    val client       = new JLlmClient(replying(full))
    val conversation = ConversationBuilder.create().user("hi").build()
    client.complete("q").get() shouldBe "Let me check."
    client.complete(conversation).get() shouldBe "Let me check."
    client.complete(conversation, JCompletionOptions.builder().build()).get() shouldBe "Let me check."
    client.complete(conversation, CompletionOptions()).get() shouldBe "Let me check."
  }

  "JCompletion" should "be a value: equal, with equal hash codes, when every field is" in {
    def reply(c: Completion): JCompletion = JCompletion.of(c)

    reply(full) shouldBe reply(full)
    reply(full).hashCode shouldBe reply(full).hashCode
    reply(full) should not be reply(full.withModel("other"))
    reply(full) should not be reply(full.withEstimatedCost(None))
    reply(full).equals("reply") shouldBe false
  }

  it should "print its id, model, tool-call count and full text" in {
    JCompletion.of(full).toString shouldBe "JCompletion(reply-1, gpt-4o-2024-08-06, 2 tool calls: Let me check.)"
  }

  it should "hand out an unmodifiable list of tool calls" in {
    an[UnsupportedOperationException] should be thrownBy JCompletion.of(full).toolCalls.clear()
  }

  "JTokenUsage" should "be a value, and print every count" in {
    val usage = JTokenUsage.of(TokenUsage(promptTokens = 3, completionTokens = 4, totalTokens = 7))
    usage shouldBe JTokenUsage.of(TokenUsage(promptTokens = 3, completionTokens = 4, totalTokens = 7))
    usage.hashCode shouldBe JTokenUsage.of(TokenUsage(promptTokens = 3, completionTokens = 4, totalTokens = 7)).hashCode
    usage should not be JTokenUsage.of(TokenUsage(promptTokens = 3, completionTokens = 4, totalTokens = 8))
    usage.equals("usage") shouldBe false
    usage.toString shouldBe "JTokenUsage(3 prompt, 4 completion, 7 total, 0 thinking, 0 cached, 0 cache creation)"
    JTokenUsage.of(full.usage.get).toString shouldBe
      "JTokenUsage(120 prompt, 30 completion, 150 total, 7 thinking, 80 cached, 25 cache creation)"
  }

  it should "tell apart usages that differ only in their cache counts" in {
    val plain = TokenUsage(promptTokens = 3, completionTokens = 4, totalTokens = 7)
    val base  = JTokenUsage.of(plain)
    JTokenUsage.of(plain.withCachedTokens(2)) should not be base
    JTokenUsage.of(plain.withCacheCreationTokens(2)) should not be base
    JTokenUsage.of(plain.withCachedTokens(2)) should not be JTokenUsage.of(plain.withCacheCreationTokens(2))
    JTokenUsage.of(plain.withCachedTokens(2)) shouldBe JTokenUsage.of(plain.withCachedTokens(2))
    JTokenUsage.of(plain.withCachedTokens(2)).hashCode shouldBe JTokenUsage.of(plain.withCachedTokens(2)).hashCode
  }

  it should "count what an agent turn's usage sums: prompt tokens as input, completion tokens as output" in {
    val tokens = full.usage.get
    val usage  = JTokenUsage.of(tokens)
    val turn   = JUsageSummary.of(UsageSummary().add("m", tokens, None).add("m", tokens, None))

    turn.inputTokens shouldBe 2L * usage.promptTokens
    turn.outputTokens shouldBe 2L * usage.completionTokens
    turn.thinkingTokens shouldBe 2L * usage.thinkingTokens
  }
}
