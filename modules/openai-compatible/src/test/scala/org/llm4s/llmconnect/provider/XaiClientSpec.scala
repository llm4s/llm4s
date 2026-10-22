package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.XaiConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer._
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable.ListBuffer

/**
 * The xAI dialect (#1023), against a local server: the request it sends to `/chat/completions`,
 * `reasoning_content` read as thinking on both paths, reasoning tokens, `reasoning_effort` per
 * Grok model family, and the penalties a reasoning model rejects left out.
 */
class XaiClientSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def config(baseUrl: String, model: String = "grok-4.7"): XaiConfig =
    XaiConfig(
      apiKey = "xai-test-key",
      model = model,
      baseUrl = baseUrl,
      contextWindow = 500000,
      reserveCompletion = 8192
    )

  private val conversation = Conversation(Seq(SystemMessage("Be brief."), UserMessage("Is 91 prime?")))

  private val efforts = Seq(ReasoningEffort.None, ReasoningEffort.Low, ReasoningEffort.Medium, ReasoningEffort.High)

  /** The request bodies `complete` and `streamComplete` send for `model` and `options`. */
  private def sentBodies(
    model: String,
    options: CompletionOptions,
    conv: Conversation = conversation
  ): Seq[ujson.Value] = {
    val sent = new AtomicReference[String]()
    withServer("/chat/completions") { exchange =>
      sent.set(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
      sendJsonResponse(exchange, 200, openAICompletion("No.", model))
    }(baseUrl => new XaiClient(config(baseUrl, model)).complete(conv, options).isRight shouldBe true)
    val completeBody = ujson.read(sent.get)
    withServer("/chat/completions") { exchange =>
      sent.set(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
      sendSseResponse(exchange, openAISseBody(Seq("No."), model))
    }(baseUrl => new XaiClient(config(baseUrl, model)).streamComplete(conv, options, _ => ()).isRight shouldBe true)
    val streamBody = ujson.read(sent.get)
    completeBody.obj.contains("stream") shouldBe false
    streamBody("stream").bool shouldBe true
    Seq(completeBody, streamBody)
  }

  private def effortSent(model: String, effort: ReasoningEffort): Seq[Option[String]] =
    sentBodies(model, CompletionOptions().withReasoning(effort)).map(_.obj.get("reasoning_effort").map(_.str))

  // ---- request shape ----

  "XaiClient" should "post the standard chat-completions request with a bearer key" in {
    val headers = new AtomicReference[Map[String, String]]()
    val sent    = new AtomicReference[String]()
    withServer("/chat/completions") { exchange =>
      headers.set(
        Seq("Authorization", "Content-Type").map(h => h -> exchange.getRequestHeaders.getFirst(h)).toMap
      )
      sent.set(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
      sendJsonResponse(exchange, 200, openAICompletion("No.", "grok-4.7"))
    } { baseUrl =>
      new XaiClient(config(baseUrl)).complete(conversation, CompletionOptions()).value.content shouldBe "No."
    }
    headers.get()("Authorization") shouldBe "Bearer xai-test-key"
    headers.get()("Content-Type") should startWith("application/json")
    val body = ujson.read(sent.get)
    body("model").str shouldBe "grok-4.7"
    body("messages").arr.map(m => m("role").str -> m("content").str) shouldBe Seq(
      "system" -> "Be brief.",
      "user"   -> "Is 91 prime?"
    )
    body.obj.keySet should contain noneOf ("reasoning_effort", "presence_penalty", "frequency_penalty", "stop")
  }

  it should "ask for streamed usage with the standard stream_options" in {
    sentBodies("grok-4.7", CompletionOptions()).last("stream_options")("include_usage").bool shouldBe true
  }

  it should "not send an earlier turn's thinking back, since Chat Completions has no field for it" in {
    val replaying = Conversation(
      Seq(UserMessage("Is 91 prime?"), AssistantMessage("No.").withThinking("7 x 13"), UserMessage("And 97?"))
    )
    sentBodies("grok-3-mini", CompletionOptions(), replaying).foreach { body =>
      val assistant = body("messages").arr.find(_("role").str == "assistant").get
      assistant.obj.keySet shouldBe Set("role", "content")
    }
  }

  // ---- response: thinking and reasoning tokens ----

  "XaiClient.complete" should "read reasoning_content as thinking, and its reasoning tokens" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(
        exchange,
        200,
        """{"id":"x1","created":1,"model":"grok-3-mini",
          |"choices":[{"index":0,"message":{"role":"assistant","content":"No.","reasoning_content":"91 = 7 x 13."},"finish_reason":"stop"}],
          |"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":40,"completion_tokens_details":{"reasoning_tokens":28}}}""".stripMargin
      )
    } { baseUrl =>
      val completion =
        new XaiClient(config(baseUrl, "grok-3-mini")).complete(conversation, CompletionOptions()).value
      completion.content shouldBe "No."
      completion.thinking shouldBe Some("91 = 7 x 13.")
      completion.message.thinking shouldBe Seq(ThinkingBlock.Text("91 = 7 x 13."))
      val usage = completion.usage.get
      usage.thinkingTokens shouldBe Some(28)
      usage.completionTokens shouldBe 2
      usage.totalTokens shouldBe 40
    }

  it should "leave thinking empty when the reply has none, or an empty one" in {
    Seq(
      openAICompletion("Hi", "grok-4.7"),
      openAICompletion("Hi", "grok-4.7").replace("\"content\"", "\"reasoning_content\":\"\",\"content\"")
    ).foreach { reply =>
      withServer("/chat/completions")(sendJsonResponse(_, 200, reply)) { baseUrl =>
        val completion = new XaiClient(config(baseUrl)).complete(conversation, CompletionOptions()).value
        completion.thinking shouldBe None
        completion.usage.flatMap(_.thinkingTokens) shouldBe None
      }
    }
  }

  "XaiClient.streamComplete" should "stream reasoning_content as thinking deltas, then the answer and its usage" in
    withServer("/chat/completions") { exchange =>
      val events = Seq(
        """{"id":"s","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"91 = "}}]}""",
        """{"id":"s","choices":[{"index":0,"delta":{"reasoning_content":"7 x 13."}}]}""",
        """{"id":"s","choices":[{"index":0,"delta":{"content":"No."},"finish_reason":"stop"}]}""",
        """{"id":"s","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":40,"completion_tokens_details":{"reasoning_tokens":28}}}"""
      )
      sendSseResponse(exchange, events.map(e => s"data: $e\n\n").mkString + "data: [DONE]\n\n")
    } { baseUrl =>
      val chunks = ListBuffer.empty[StreamedChunk]
      val completion = new XaiClient(config(baseUrl, "grok-3-mini"))
        .streamComplete(conversation, CompletionOptions(), chunks += _)
        .value
      chunks.flatMap(_.thinkingDelta) shouldBe Seq("91 = ", "7 x 13.")
      completion.thinking shouldBe Some("91 = 7 x 13.")
      completion.content shouldBe "No."
      completion.usage.flatMap(_.thinkingTokens) shouldBe Some(28)
      completion.usage.map(_.promptTokens) shouldBe Some(10)
    }

  // ---- reasoning_effort ----

  "xAI with no reasoning option" should "send no reasoning_effort, on any model" in {
    Seq("grok-4.7", "grok-3-mini", "grok-4").foreach { model =>
      sentBodies(model, CompletionOptions()).foreach(_.obj.contains("reasoning_effort") shouldBe false)
    }
  }

  "xAI on Grok 4.5 to 4.9" should "send each level by name, and None as low with a warning once" in {
    XaiDialect.warnedReasoningNotDisabled.set(false)
    val expected = Map(
      ReasoningEffort.None   -> "low",
      ReasoningEffort.Low    -> "low",
      ReasoningEffort.Medium -> "medium",
      ReasoningEffort.High   -> "high"
    )
    Seq("grok-4.7", "grok-4.6-latest", "GROK-4.5").foreach { model =>
      efforts.foreach { effort =>
        withClue(s"$model $effort: ")(effortSent(model, effort) shouldBe Seq.fill(2)(Some(expected(effort))))
      }
    }
    XaiDialect.warnedReasoningNotDisabled.get shouldBe true
  }

  "xAI on Grok 3 mini" should "send low or high, the two levels it takes" in {
    val expected = Map(
      ReasoningEffort.None   -> "low",
      ReasoningEffort.Low    -> "low",
      ReasoningEffort.Medium -> "high",
      ReasoningEffort.High   -> "high"
    )
    efforts.foreach { effort =>
      withClue(s"$effort: ")(effortSent("grok-3-mini-fast", effort) shouldBe Seq.fill(2)(Some(expected(effort))))
    }
  }

  "xAI on other models" should "send no reasoning_effort, which grok-4 rejects" in {
    Seq("grok-4", "grok-4-0709", "grok-4.20-multi-agent", "grok-4.3", "grok-2-latest", "grok-code-fast-1").foreach {
      model => withClue(s"$model: ")(effortSent(model, ReasoningEffort.High) shouldBe Seq(None, None))
    }
  }

  // ---- penalties ----

  "xAI on a reasoning model" should "leave out the penalties it rejects, with a warning once" in {
    XaiDialect.warnedPenaltiesDropped.set(false)
    val options = CompletionOptions().withPresencePenalty(0.5).withFrequencyPenalty(0.3)
    Seq("grok-4.7", "grok-4", "grok-3-mini", "grok-4.20-0309-reasoning", "grok-code-fast-1").foreach { model =>
      withClue(s"$model: ") {
        sentBodies(model, options).foreach { body =>
          body.obj.keySet should contain noneOf ("presence_penalty", "frequency_penalty")
        }
      }
    }
    XaiDialect.warnedPenaltiesDropped.get shouldBe true
  }

  "xAI on a non-reasoning model" should "send the penalties as given" in {
    val options = CompletionOptions().withPresencePenalty(0.5).withFrequencyPenalty(0.3)
    Seq("grok-4-fast-non-reasoning", "grok-4.20-0309-non-reasoning", "grok-3", "grok-2-latest").foreach { model =>
      withClue(s"$model: ") {
        sentBodies(model, options).foreach { body =>
          body("presence_penalty").num shouldBe 0.5
          body("frequency_penalty").num shouldBe 0.3
        }
      }
    }
  }

  "XaiDialect.family and isReasoningModel" should "classify Grok model ids" in {
    XaiDialect.family("grok-4.7") shouldBe XaiDialect.Family.Effort
    XaiDialect.family("grok-3-mini") shouldBe XaiDialect.Family.LowHigh
    XaiDialect.family("grok-4") shouldBe XaiDialect.Family.Other
    XaiDialect.family("grok-4.20-multi-agent") shouldBe XaiDialect.Family.Other
    XaiDialect.isReasoningModel("grok-4-1-fast-reasoning") shouldBe true
    XaiDialect.isReasoningModel("grok-4-1-fast-non-reasoning") shouldBe false
    XaiDialect.isReasoningModel("grok-2-vision-latest") shouldBe false
  }

  // ---- construction ----

  "XaiClient.apply" should "refuse a blank API key, and build a client from a valid config" in {
    XaiClient(config("http://localhost:1").withApiKey("")).isLeft shouldBe true
    XaiClient(config("http://localhost:1")).isRight shouldBe true
    XaiClient(config("http://localhost:1"), org.llm4s.metrics.MetricsCollector.noop).isRight shouldBe true
  }
}
