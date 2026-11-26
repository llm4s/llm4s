package org.llm4s.llmconnect.smoke

import org.llm4s.types.ProviderModelTypes.BaseUrl
import org.llm4s.types.ProviderModelTypes.{ ModelName, ProviderId }
import org.llm4s.security.Secret
import org.scalatest.EitherValues
import org.llm4s.error.{ AuthenticationError, ModelNotFoundError }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIApi, OpenAIConfig, OpenAIReasoningSummary }
import org.llm4s.llmconnect.{
  LLMClient,
  LlmClientOptions,
  ProviderExchange,
  ProviderExchangeLogging,
  ProviderExchangeSink
}
import org.llm4s.media.{ MediaSource, MediaType }
import scala.jdk.CollectionConverters.*
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  ContentPart,
  CompletionOptions,
  Conversation,
  ReasoningEffort,
  StreamedChunk,
  ThinkingBlock,
  ToolMessage,
  UserMessage
}
import org.llm4s.llmconnect.provider.OpenAIClient
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud

/**
 * Cloud smoke tests for OpenAI.
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with `sbt "it/testOnly org.llm4s.llmconnect.smoke.*"`
 * or the `sbt testSmoke` alias.
 *
 * Requires: `OPENAI_API_KEY` environment variable.
 */
@Cloud
class OpenAISmokeSpec extends AnyFlatSpec with Matchers with EitherValues with ProviderSmokeContract {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val apiKey: Option[String] = Option(System.getenv("OPENAI_API_KEY")).filter(_.nonEmpty)

  /**
   * The cheapest reasoning model in the gpt-5 family. The registry flags it `supports_reasoning`,
   * so it gets `reasoning_effort`, `max_completion_tokens` and no sampling parameters.
   */
  private val ReasoningModel = "gpt-5.6-luna" // gpt-5-mini shuts down on 2026-12-11 (OpenAI deprecations page)

  private def config(key: String, model: String = "gpt-4o-mini"): OpenAIConfig =
    OpenAIConfig
      .fromValues(
        modelName = ModelName(model),
        apiKey = Secret(key),
        organization = None,
        baseUrl = BaseUrl("https://api.openai.com/v1")
      )
      .value

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in one word")))

  "OpenAI" should "complete a basic request" in {
    Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

    val clientResult = OpenAIClient(config(apiKey.get))
    withClue(s"Client creation failed: ${clientResult.swap.toOption}") {
      clientResult.isRight shouldBe true
    }

    val client     = clientResult.toOption.get
    val completion = client.complete(conversation, CompletionOptions())

    withClue(s"Completion failed: ${completion.swap.toOption}") {
      completion.isRight shouldBe true
    }
    completion.toOption.get.content should not be empty
  }

  it should "stream a response" in {
    Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

    val client = OpenAIClient(config(apiKey.get)).toOption.get
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conversation, CompletionOptions())(c => chunks += c)

    withClue(s"Streaming failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    result.toOption.get.content should not be empty
    chunks should not be empty
  }

  it should "complete with a reasoning effort on a reasoning model and report its reasoning tokens" in {
    Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

    val client = OpenAIClient(config(apiKey.get, ReasoningModel)).toOption.get
    // max_completion_tokens counts reasoning tokens too; too small a cap can be spent entirely on
    // reasoning and leave no visible content, so this leaves room at low effort.
    val options    = CompletionOptions(maxTokens = Some(1024)).withReasoning(ReasoningEffort.Low)
    val completion = client.complete(conversation, options)
    client.close()

    withClue(s"Reasoning completion failed (was reasoning_effort rejected?): ${completion.swap.toOption}") {
      completion.isRight shouldBe true
    }
    completion.toOption.get.content should not be empty
    // OpenAI reports completion_tokens_details.reasoning_tokens for reasoning models, but at low
    // effort on a trivial prompt the count can legitimately be 0, so assert it is reported, not
    // that it is positive.
    val usage = completion.toOption.get.usage
    usage shouldBe defined
    usage.get.thinkingTokens shouldBe defined
    usage.get.thinkingTokens.get should be >= 0
  }

  it should "complete on a gpt-5 model with default CompletionOptions" in {
    Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

    // The defaults include temperature = 0.7, which the gpt-5 family rejects; the client must
    // leave sampling parameters out for reasoning models. No maxTokens: these are the defaults.
    val client     = OpenAIClient(config(apiKey.get, ReasoningModel)).toOption.get
    val completion = client.complete(conversation, CompletionOptions())
    client.close()

    withClue(s"gpt-5 completion with default options failed: ${completion.swap.toOption}") {
      completion.isRight shouldBe true
    }
    completion.toOption.get.content should not be empty
  }

  it should "report token usage on a streamed completion" in {
    Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

    // Streams carry usage only because the client sets stream_options.include_usage; OpenAI
    // then sends it on a final chunk with no choices.
    val client = OpenAIClient(config(apiKey.get)).toOption.get
    val result = client.streamComplete(conversation, CompletionOptions(maxTokens = Some(16)))(_ => ())
    client.close()

    withClue(s"Streaming failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    val usage = result.toOption.get.usage
    withClue("A streamed completion reported no usage: ") {
      usage shouldBe defined
    }
    usage.get.promptTokens should be > 0
    usage.get.completionTokens should be > 0
    usage.get.totalTokens should be > 0
  }

  // #2940: on Chat Completions (pinned: the default for these is now the Responses API) these models call a function only at reasoning_effort none, which the client sends
  // whether or not an effort was asked for. A wrong rule shows here as OpenAI's 400.
  for {
    model  <- Seq("gpt-5.6-luna", "gpt-5.6-terra", "gpt-6-luna", "gpt-6.1-terra")
    effort <- Seq(None, Some(ReasoningEffort.Low))
  } it should s"call a tool on $model, reasoning ${effort.fold("unset")(_.name)}" in {
    Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

    val weather = org.llm4s.toolapi
      .ToolBuilder[String](
        "get_weather",
        "Returns the current weather for a city",
        org.llm4s.toolapi.Schema
          .`object`[Map[String, Any]]("Arguments")
          .withProperty(org.llm4s.toolapi.Schema.property("city", org.llm4s.toolapi.Schema.string("The city")))
      )
      .withHandler(_ => Right("sunny"))
      .build()
      .value
    val options = CompletionOptions().withTools(Seq(weather)).withReasoning(effort)
    val ask     = Conversation(Seq(UserMessage("What is the weather in Paris? Use the get_weather tool.")))

    val client   = OpenAIClient(config(apiKey.get, model).withApi(OpenAIApi.ChatCompletions)).toOption.get
    val sync     = client.complete(ask, options)
    val streamed = client.streamComplete(ask, options)(_ => ())
    client.close()

    Seq("complete" -> sync, "streamComplete" -> streamed).foreach { case (call, result) =>
      withClue(s"$call on $model failed: ${result.swap.toOption}: ") {
        result.value.message.toolCalls.map(_.name) shouldBe Seq("get_weather")
      }
    }
  }

  // ---- the Responses API (#2900): the default for a model the registry lists for /v1/responses on api.openai.com ----

  private def submitAnswerTool = org.llm4s.toolapi
    .ToolBuilder[String](
      "submit_answer",
      "Submits the final numeric answer",
      org.llm4s.toolapi.Schema
        .`object`[Map[String, Any]]("Arguments")
        .withProperty(org.llm4s.toolapi.Schema.property("answer", org.llm4s.toolapi.Schema.string("The answer")))
    )
    .withHandler(_ => Right("recorded"))
    .build()
    .value

  // A calculation before the call, so the model reasons: it skips reasoning for a greeting.
  private def calculationMessage = UserMessage(
    "What is the product of the decimal digits of 7^13? Work it out, then call submit_answer with the result."
  )

  for (model <- Seq("gpt-6.1-terra", "gpt-5.6-luna")) {
    it should s"call a tool at effort low on the Responses API on $model and report reasoning tokens" in {
      Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

      val options =
        CompletionOptions(maxTokens = Some(2048)).withTools(Seq(submitAnswerTool)).withReasoning(ReasoningEffort.Low)
      val client = OpenAIClient(config(apiKey.get, model)).toOption.get
      val result = client.complete(Conversation(Seq(calculationMessage)), options)
      client.close()

      val completion = withClue(s"Responses complete on $model failed: ${result.swap.toOption}: ")(result.value)
      val usage      = completion.usage
      info(s"$model reasoning tokens: ${usage.flatMap(_.thinkingTokens)}")
      withClue(
        s"$model answered without the tool: content=${completion.content}, tool calls=${completion.message.toolCalls
            .map(_.name)}: "
      ) {
        completion.message.toolCalls.map(_.name) shouldBe Seq("submit_answer")
      }
      withClue(s"$model reported no reasoning tokens, usage=$usage: ") {
        usage.flatMap(_.thinkingTokens).exists(_ > 0) shouldBe true
      }
      completion.adjustments shouldBe empty
    }

    it should s"finish a tool loop on the Responses API on $model whose second request sends the function_call without its reasoning item" in {
      Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

      val options =
        CompletionOptions(maxTokens = Some(2048)).withTools(Seq(submitAnswerTool)).withReasoning(ReasoningEffort.Low)
      val client  = OpenAIClient(config(apiKey.get, model)).toOption.get
      val first   = client.complete(Conversation(Seq(calculationMessage)), options)
      val firstOk = withClue(s"Responses turn 1 on $model failed: ${first.swap.toOption}: ")(first.value)
      val calls   = firstOk.message.toolCalls
      // The case tests the replay: a model that made no tool call leaves nothing to replay.
      if (calls.isEmpty) {
        client.close()
        cancel(s"$model made no tool call on turn 1, content: ${firstOk.content}")
      }
      // Only the function_calls go back, one result each: the reply's reasoning item is not sent in this release.
      val turn2 = Conversation(
        Seq(calculationMessage, AssistantMessage(None, calls)) ++ calls.map(call => ToolMessage("recorded", call.id))
      )
      val second = client.complete(turn2, options)
      client.close()

      // What is asserted is that OpenAI accepted the request; the model may answer or call the tool again.
      acceptedReply(model, second)
    }
  }

  private def lowOptions: CompletionOptions =
    CompletionOptions(maxTokens = Some(2048)).withTools(Seq(submitAnswerTool)).withReasoning(ReasoningEffort.Low)

  // What is asserted is that OpenAI accepted the request: the model may answer, call the tool again or say nothing.
  // The info line is what tells an empty reply from a dropped one.
  private def acceptedReply(model: String, second: Result[Completion]): Unit = {
    info(
      s"$model turn 2: " + second.fold(
        e => s"rejected: $e",
        r =>
          s"accepted, finish=${r.finishReason}, usage=${r.usage}, content.length=${r.content.length}, " +
            s"tool calls=${r.message.toolCalls.size}, thinking=${r.message.thinking.map(_.getClass.getSimpleName)}"
      )
    )
    withClue(s"Responses turn 2 on $model was rejected: ${second.swap.toOption}: ")(second.isRight shouldBe true)
  }

  for (model <- Seq("gpt-6.1-terra", "gpt-5.6-luna")) {
    it should s"finish a tool loop that sends its encrypted reasoning back on the Responses API on $model" in {
      Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

      val client  = OpenAIClient(config(apiKey.get, model)).toOption.get
      val first   = client.complete(Conversation(Seq(calculationMessage)), lowOptions)
      val firstOk = withClue(s"Responses turn 1 on $model failed: ${first.swap.toOption}: ")(first.value)
      val calls   = firstOk.message.toolCalls
      if (calls.isEmpty) {
        client.close()
        cancel(s"$model made no tool call on turn 1, content: ${firstOk.content}")
      }
      val opaque = firstOk.message.thinking.collect {
        case ThinkingBlock.Opaque(provider, _) if provider == ProviderId("openai") => provider
      }
      val kinds = firstOk.message.thinking.map(_.getClass.getSimpleName)
      info(s"$model turn 1: ${calls.size} tool call(s), ${opaque.size} opaque openai block(s), blocks=$kinds")
      if (opaque.isEmpty) {
        client.close()
        cancel(s"$model returned no reasoning item on turn 1 (thinking blocks: $kinds), so none could be sent back")
      }
      withClue(s"$model turn 1 thinking blocks: $kinds: ")(firstOk.message.hasSealedThinking shouldBe true)

      val turn2 =
        Conversation(Seq(calculationMessage, firstOk.message) ++ calls.map(c => ToolMessage("recorded", c.id)))
      val second = client.complete(turn2, lowOptions)
      client.close()
      acceptedReply(model, second)
    }

    it should s"finish the same loop after the turn was edited, without its reasoning, on $model" in {
      Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

      val client  = OpenAIClient(config(apiKey.get, model)).toOption.get
      val first   = client.complete(Conversation(Seq(calculationMessage)), lowOptions)
      val firstOk = withClue(s"Responses turn 1 on $model failed: ${first.swap.toOption}: ")(first.value)
      val calls   = firstOk.message.toolCalls
      if (calls.isEmpty) {
        client.close()
        cancel(s"$model made no tool call on turn 1, content: ${firstOk.content}")
      }
      val edited = firstOk.message.withContent(firstOk.message.content + " ")
      val turn2  = Conversation(Seq(calculationMessage, edited) ++ calls.map(c => ToolMessage("recorded", c.id)))
      val second = client.complete(turn2, lowOptions)
      client.close()
      acceptedReply(model, second)
    }

    it should s"report reasoning summary text when reasoningSummary is auto on $model" in {
      Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

      val cfg    = config(apiKey.get, model).withReasoningSummary(Some(OpenAIReasoningSummary.Auto))
      val client = OpenAIClient(cfg).toOption.get
      val result = client.complete(Conversation(Seq(calculationMessage)), lowOptions)
      client.close()
      result.left.foreach { e =>
        if e.message.toLowerCase.contains("verif") then cancel(s"$model summary request refused: ${e.message}")
      }
      val completion = withClue(s"Responses summary request on $model failed: ${result.swap.toOption}: ")(result.value)
      info(s"$model summary: ${completion.thinking.map(_.take(80))}")
      withClue(s"$model returned no summary text, blocks=${completion.message.thinking}: ") {
        completion.thinking.exists(_.nonEmpty) shouldBe true
      }
    }
  }

  // ---- streamComplete on the Responses API (#2900): deltas as they arrive, the Completion from the terminal event ----

  private def streamed(
    client: LLMClient,
    conversation: Conversation,
    options: CompletionOptions
  ): (Result[Completion], Vector[StreamedChunk]) = {
    val collected = Vector.newBuilder[StreamedChunk]
    val result    = client.streamComplete(conversation, options)(collected += _)
    (result, collected.result())
  }

  // One line per chunk: which delta kinds it carried and its finish reason. Goes in a failure's clue.
  private def chunkShape(chunks: Seq[StreamedChunk]): String =
    chunks.zipWithIndex
      .map { case (c, i) =>
        val kinds = Seq(
          c.contentDelta.filter(_.nonEmpty).map(_ => "content"),
          c.toolCallDelta.map(t => s"tool(${t.id})"),
          c.thinkingDelta.filter(_.nonEmpty).map(_ => "thinking")
        ).flatten
        s"$i:[${kinds.mkString(",")}]${c.finishReason.fold("")(f => s" finish=$f")}"
      }
      .mkString("; ")

  private def logStream(model: String, result: Result[Completion], chunks: Seq[StreamedChunk]): Unit =
    info(
      s"$model streamed: ${chunks.size} chunks, ${chunks.count(_.hasContent)} content, " +
        s"${chunks.count(_.toolCallDelta.isDefined)} tool-call, ${chunks.count(_.hasThinking)} thinking, " +
        result.fold(
          e => s"failed: $e",
          r => s"finish=${r.finishReason}, usage=${r.usage}"
        )
    )

  it should "stream text on the Responses API by default on gpt-4.1-mini" in {
    Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

    val model  = "gpt-4.1-mini"
    val client = OpenAIClient(config(apiKey.get, model)).toOption.get
    val ask =
      Conversation(Seq(UserMessage("In four or five sentences, explain what a hash map is and why lookups are fast.")))
    val (result, chunks) = streamed(client, ask, CompletionOptions())
    client.close()

    logStream(model, result, chunks)
    withClue(s"$model stream failed: ${result.swap.toOption}; chunks: ${chunkShape(chunks)}: ") {
      val completion = result.value
      withClue(s"chunks: ${chunkShape(chunks)}: ") {
        chunks.flatMap(_.contentDelta).mkString shouldBe completion.content
        chunks.count(_.hasContent) should be > 1
        chunks.init.forall(_.finishReason.isEmpty) shouldBe true
        chunks.last.finishReason shouldBe Some(completion.finishReason)
      }
    }
  }

  for (model <- Seq("gpt-6.1-terra", "gpt-5.6-luna")) {
    it should s"stream a tool call with reasoning on the Responses API on $model" in {
      Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

      val client           = OpenAIClient(config(apiKey.get, model)).toOption.get
      val (result, chunks) = streamed(client, Conversation(Seq(calculationMessage)), lowOptions)
      client.close()

      logStream(model, result, chunks)
      withClue(s"$model stream failed: ${result.swap.toOption}; chunks: ${chunkShape(chunks)}: ")(
        result.isRight shouldBe true
      )
      val completion = result.value
      val calls      = completion.message.toolCalls
      if (calls.isEmpty) cancel(s"$model made no tool call, content: ${completion.content}")
      withClue(s"$model chunks: ${chunkShape(chunks)}: ") {
        val toolDeltas = chunks.flatMap(_.toolCallDelta)
        toolDeltas should not be empty
        toolDeltas.forall(_.id.asString.nonEmpty) shouldBe true
        toolDeltas.map(_.id).toSet shouldBe calls.map(_.id).toSet
        chunks.flatMap(_.contentDelta).mkString shouldBe completion.content
        chunks.init.forall(_.finishReason.isEmpty) shouldBe true
        chunks.last.finishReason shouldBe Some(completion.finishReason)
      }
      val reasoningTokens = completion.usage.flatMap(_.thinkingTokens).exists(_ > 0)
      if (!reasoningTokens || !completion.message.hasSealedThinking)
        cancel(
          s"$model made tool calls but returned reasoning tokens=$reasoningTokens, " +
            s"sealed reasoning block=${completion.message.hasSealedThinking}"
        )
    }

    it should s"stream a tool loop that sends its reasoning back on the Responses API on $model" in {
      Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

      val client           = OpenAIClient(config(apiKey.get, model)).toOption.get
      val (first, chunks1) = streamed(client, Conversation(Seq(calculationMessage)), lowOptions)
      logStream(model, first, chunks1)
      val firstOk =
        withClue(
          s"Responses streamed turn 1 on $model failed: ${first.swap.toOption}; chunks: ${chunkShape(chunks1)}: "
        )(
          first.value
        )
      val calls = firstOk.message.toolCalls
      if (calls.isEmpty) {
        client.close()
        cancel(s"$model made no tool call on turn 1, content: ${firstOk.content}")
      }
      val opaque = firstOk.message.thinking.collect {
        case ThinkingBlock.Opaque(provider, _) if provider == ProviderId("openai") => provider
      }
      val kinds = firstOk.message.thinking.map(_.getClass.getSimpleName)
      info(s"$model streamed turn 1: ${calls.size} tool call(s), ${opaque.size} opaque openai block(s), blocks=$kinds")
      if (opaque.isEmpty) {
        client.close()
        cancel(s"$model returned no reasoning item on turn 1 (thinking blocks: $kinds), so none could be sent back")
      }

      val turn2 =
        Conversation(Seq(calculationMessage, firstOk.message) ++ calls.map(c => ToolMessage("recorded", c.id)))
      val (second, chunks2) = streamed(client, turn2, lowOptions)
      client.close()
      logStream(model, second, chunks2)
      acceptedReply(model, second)
    }
  }

  it should "stream reasoning summary text when reasoningSummary is auto on gpt-5.6-luna" in {
    Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

    val model            = "gpt-5.6-luna"
    val cfg              = config(apiKey.get, model).withReasoningSummary(Some(OpenAIReasoningSummary.Auto))
    val client           = OpenAIClient(cfg).toOption.get
    val (result, chunks) = streamed(client, Conversation(Seq(calculationMessage)), lowOptions)
    client.close()

    logStream(model, result, chunks)
    result.left.foreach { e =>
      if {
        val m = e.message.toLowerCase
        m.contains("verif") && m.contains("organi")
      } then cancel(s"$model summary request refused: ${e.message}")
    }
    withClue(s"$model stream failed: ${result.swap.toOption}; chunks: ${chunkShape(chunks)}: ")(
      result.isRight shouldBe true
    )
    withClue(s"$model chunks: ${chunkShape(chunks)}: ") {
      chunks.flatMap(_.thinkingDelta).mkString should not be empty
      chunks.init.forall(_.finishReason.isEmpty) shouldBe true
      chunks.last.finishReason shouldBe Some(result.value.finishReason)
    }
  }

  it should "complete a plain request on the Responses API by default on a non-reasoning model" in {
    Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

    val client = OpenAIClient(config(apiKey.get, "gpt-4.1-mini")).toOption.get
    val result = client.complete(conversation, CompletionOptions())
    client.close()

    withClue(s"Responses plain completion failed: ${result.swap.toOption}: ") {
      result.isRight shouldBe true
    }
    result.value.content should not be empty
  }

  // ---- the default API (#2900): decided when the client is built, from the model registry ----

  // The request body tells the API: Responses sends `input`, Chat Completions `messages`.
  private def recordingClient(
    cfg: OpenAIConfig
  ): (LLMClient, java.util.concurrent.ConcurrentLinkedQueue[ProviderExchange]) = {
    val exchanges = new java.util.concurrent.ConcurrentLinkedQueue[ProviderExchange]()
    val sink    = new ProviderExchangeSink { override def record(e: ProviderExchange): Unit = { exchanges.add(e); () } }
    val options = LlmClientOptions(exchangeLogging = ProviderExchangeLogging.Enabled(sink))
    (OpenAIClient(cfg, options).toOption.get, exchanges)
  }

  private def apiOf(exchanges: java.util.concurrent.ConcurrentLinkedQueue[ProviderExchange]): String = {
    val bodies = exchanges.asScala.toSeq.map(_.requestBody)
    if bodies.nonEmpty && bodies.forall(b => b.contains("\"input\"") && !b.contains("\"messages\"")) then "responses"
    else if bodies.nonEmpty && bodies.forall(b => b.contains("\"messages\"") && !b.contains("\"input\"")) then
      "chat-completions"
    else s"unclear (${bodies.size} exchange(s))"
  }

  // gpt-4o-mini is listed for /v1/responses by an llm4s patch (OpenAI served it there on 2026-11-26). No model this
  // account can call is known to the registry and unlisted, so the "known but unlisted stays on Chat Completions"
  // side is covered by OpenAIApiRoutingSpec and OpenAIApiRoutingGoldenSpec, not live.
  it should "use the Responses API by default for listed models and Chat Completions when told to" in {
    Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

    val key = apiKey.get
    val cases: Seq[(String, String, OpenAIConfig, CompletionOptions, Conversation)] = Seq(
      (
        "gpt-6.1-terra, no api",
        "responses",
        config(key, "gpt-6.1-terra"),
        lowOptions,
        Conversation(Seq(calculationMessage))
      ),
      (
        "gpt-6.1-terra, api = chat-completions",
        "chat-completions",
        config(key, "gpt-6.1-terra").withApi(OpenAIApi.ChatCompletions),
        lowOptions,
        Conversation(Seq(calculationMessage))
      ),
      (
        "gpt-4o-mini (listed by patch), no api",
        "responses",
        config(key, "gpt-4o-mini"),
        CompletionOptions(),
        conversation
      )
    )
    cases.foreach { case (label, expected, cfg, options, ask) =>
      val (client, exchanges) = recordingClient(cfg)
      val result              = client.complete(ask, options)
      client.close()
      info(
        s"[#2900] $label: used ${apiOf(exchanges)} (body lengths ${exchanges.asScala.toSeq
            .map(_.requestBody.length)}; request body has ${if expected == "responses" then "input" else "messages"}?), " +
          result.fold(
            e => s"failed: $e",
            r =>
              s"ok, reasoning tokens=${r.usage.flatMap(_.thinkingTokens)}, " +
                s"adjustments=${r.adjustments.map(_.getClass.getSimpleName)}, tool calls=${r.message.toolCalls.size}"
          )
      )
      withClue(s"$label failed: ${result.swap.toOption}: ")(result.isRight shouldBe true)
      withClue(s"$label used the wrong API: ")(apiOf(exchanges) shouldBe expected)
    }
  }

  // OpenAI refuses the search-preview models on /v1/responses; the registry knows them without that endpoint, so the
  // default should keep them on Chat Completions. OpenAI has been retiring them: when it answers that the model is
  // deprecated or does not exist, the case cancels and shows nothing about routing. Any other failure (an answer
  // about the Responses API, a 401, 429, 5xx or timeout) fails the case with OpenAI's message; a success checks the
  // API used.
  for (model <- Seq("gpt-4o-search-preview", "gpt-4o-mini-search-preview"))
    it should s"not send $model to the Responses API by default (cancels when OpenAI has retired the model)" in {
      Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

      val (client, exchanges) = recordingClient(config(apiKey.get, model))
      val result              = client.complete(conversation, CompletionOptions())
      client.close()
      info(
        s"[#2900] $model, no api: ${exchanges.size} exchange(s), " + result.fold(
          e => s"failed: ${e.getClass.getSimpleName}: ${e.message.take(200)}",
          r => s"ok, api=${apiOf(exchanges)}, content.length=${r.content.length}"
        )
      )
      result.left.foreach { e =>
        val m = e.message.toLowerCase
        withClue(s"$model failed with an answer about the Responses API (wrong routing?): ${e.message}: ") {
          Seq("responses api", "/v1/responses", "not supported with the responses").exists(m.contains) shouldBe false
        }
        val retired = e match
          case _: ModelNotFoundError => true
          case _                     => Seq("deprecated", "does not exist").exists(m.contains)
        if retired then cancel(s"$model is not served by OpenAI (${e.getClass.getSimpleName}): ${e.message.take(160)}")
        else fail(s"$model failed (${e.getClass.getSimpleName}): ${e.message}")
      }
      withClue(s"$model used the wrong API: ")(apiOf(exchanges) shouldBe "chat-completions")
    }

  it should "finish a tool loop with keepReasoning false on gpt-6.1-terra" in {
    Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

    val model   = "gpt-6.1-terra"
    val client  = OpenAIClient(config(apiKey.get, model).withKeepReasoning(false)).toOption.get
    val first   = client.complete(Conversation(Seq(calculationMessage)), lowOptions)
    val firstOk = withClue(s"turn 1 on $model failed: ${first.swap.toOption}: ")(first.value)
    val calls   = firstOk.message.toolCalls
    if (calls.isEmpty) {
      client.close()
      cancel(s"$model made no tool call on turn 1, content: ${firstOk.content}")
    }
    withClue(s"$model kept sealed thinking with keepReasoning false, blocks=${firstOk.message.thinking}: ")(
      firstOk.message.hasSealedThinking shouldBe false
    )
    val turn2  = Conversation(Seq(calculationMessage, firstOk.message) ++ calls.map(c => ToolMessage("recorded", c.id)))
    val second = client.complete(turn2, lowOptions)
    client.close()
    acceptedReply(model, second)
  }

  // ---- the comparison: does replaying the reasoning change how often turn 2 comes back empty? (#2900) ----

  // One arm's state: its client and the turn-2 results so far; `skipped` counts loops not counted (no tool call).
  final private case class ArmRun(
    label: String,
    client: LLMClient,
    done: Vector[Result[Completion]],
    skipped: Int
  ) {
    def active: Boolean = done.size < OpenAISmokeSpec.LoopsPerArm && skipped <= OpenAISmokeSpec.ReplacementsPerArm
  }

  // One attempt of one arm: turn 1, and when it made a tool call, turn 2.
  private def step(model: String, arm: ArmRun): ArmRun = {
    val first = arm.client.complete(Conversation(Seq(calculationMessage)), lowOptions)
    first match {
      case Left(e) =>
        info(s"[#2900] $model ${arm.label}: turn 1 failed: $e")
        arm.copy(done = arm.done :+ first)
      case Right(firstOk) if firstOk.message.toolCalls.isEmpty =>
        info(s"[#2900] $model ${arm.label}: turn 1 made no tool call, loop not counted")
        arm.copy(skipped = arm.skipped + 1)
      case Right(firstOk) =>
        val calls = firstOk.message.toolCalls
        val turn2 =
          Conversation(Seq(calculationMessage, firstOk.message) ++ calls.map(c => ToolMessage("recorded", c.id)))
        val second = arm.client.complete(turn2, lowOptions)
        info(
          s"[#2900] $model ${arm.label} loop ${arm.done.size + 1}: " + second.fold(
            e => s"rejected: $e",
            r =>
              s"finish=${r.finishReason}, content.length=${r.content.length} code points=${r.content
                  .take(3)
                  .map(_.toInt)
                  .mkString("[", ",", "]")}, tool calls=${r.message.toolCalls.size}, " +
                s"reasoning tokens turn 1=${firstOk.usage.flatMap(_.thinkingTokens)}, turn 2=${r.usage.flatMap(_.thinkingTokens)}, " +
                s"turn 1 sealed=${firstOk.message.hasSealedThinking}"
          )
        )
        arm.copy(done = arm.done :+ second)
    }
  }

  // The two arms alternate, loop by loop, so drift in OpenAI's behaviour over the run is not confounded with the arm.
  private def runArms(model: String): (OpenAISmokeSpec.Arm, OpenAISmokeSpec.Arm) = {
    def fresh(label: String, keepReasoning: Boolean) =
      ArmRun(
        label,
        OpenAIClient(config(apiKey.get, model).withKeepReasoning(keepReasoning)).toOption.get,
        Vector.empty,
        0
      )

    @scala.annotation.tailrec
    def go(replay: ArmRun, noReplay: ArmRun): (ArmRun, ArmRun) =
      if !replay.active && !noReplay.active then (replay, noReplay)
      else
        go(
          if replay.active then step(model, replay) else replay,
          if noReplay.active then step(model, noReplay) else noReplay
        )
    val (r, n) = go(fresh("replay", keepReasoning = true), fresh("no-replay", keepReasoning = false))
    r.client.close()
    n.client.close()
    (OpenAISmokeSpec.Arm(r.done.size, r.done), OpenAISmokeSpec.Arm(n.done.size, n.done))
  }

  for (model <- Seq("gpt-5.6-luna", "gpt-6.1-terra"))
    it should s"answer after a tool result, with and without the reasoning replayed, on $model" in {
      Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

      val (replay, noReplay) = runArms(model)
      info(
        s"[#2900] $model replay arm: ${replay.loops} loops, ${replay.accepted} accepted, ${replay.empty} empty, ${replay.blank} blank (whitespace only, counting the empty)"
      )
      info(
        s"[#2900] $model no-replay arm: ${noReplay.loops} loops, ${noReplay.accepted} accepted, ${noReplay.empty} empty, ${noReplay.blank} blank (whitespace only, counting the empty)"
      )
      // The counts are data; what is asserted is that OpenAI accepted every turn 2.
      withClue(s"$model replay arm: ")(replay.results.forall(_.isRight) shouldBe true)
      withClue(s"$model no-replay arm: ")(noReplay.results.forall(_.isRight) shouldBe true)
    }

  // ---- an in-stream error: one request that OpenAI may reject after the stream opens; observed, not asserted ----

  it should "report what a streamed request that OpenAI rejects looks like on the wire" in {
    Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

    val model = "gpt-4.1-mini"
    val image = ContentPart
      .image(MediaSource.Url(java.net.URI.create("https://example.invalid/none.png")), MediaType.Png)
      .value
    val ask                 = Conversation(Seq(UserMessage(Seq(ContentPart.Text("Describe this image."), image))))
    val (client, exchanges) = recordingClient(config(apiKey.get, model))
    val (result, chunks)    = streamed(client, ask, CompletionOptions())
    client.close()

    val seen = exchanges.asScala.toSeq
    info(
      s"[#2900] in-stream error probe on $model: api=${apiOf(exchanges)}, ${chunks.size} chunk(s) delivered, " +
        s"${seen.size} exchange(s), outcomes=${seen.map(_.outcome)}, result=" +
        result.fold(
          e => s"Left(${e.getClass.getSimpleName}: ${e.message.take(300)})",
          r => s"Right(finish=${r.finishReason})"
        )
    )
    seen.foreach { e =>
      info(s"[#2900] probe exchange: outcome=${e.outcome}, error=${e.errorMessage
          .map(_.take(300))}, response=${e.responseBody.map(_.take(300))}")
    }
    (result.isLeft || result.isRight) shouldBe true
  }

  it should "return AuthenticationError for invalid key" in {
    val client = OpenAIClient(config("sk-invalid-key-for-testing")).toOption.get
    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[AuthenticationError]
  }

  // ---- the shared capability contract (issue #1212): see ProviderSmokeContract ----

  override protected def providerLabel: String                          = "OpenAI"
  override protected def apiKeyEnvVar: String                           = "OPENAI_API_KEY"
  override protected def contractKey: Option[String]                    = apiKey
  override protected def contractClient(key: String): Result[LLMClient] = OpenAIClient(config(key))
  // gpt-5.6-luna reasons. OpenAI reports reasoning tokens, not the reasoning text. max_completion_tokens counts the
  // reasoning too, so the cap leaves room for the answer after it. The model skips reasoning for a greeting
  // (reasoning_tokens 0 at every effort, #2945), so the prompt is a calculation that needs some.
  override protected def reasoningSetup: Option[String => Result[ReasoningSetup]] = Some { key =>
    OpenAIClient(config(key, ReasoningModel)).map { client =>
      ReasoningSetup(
        client,
        CompletionOptions(maxTokens = Some(2048)).withReasoning(ReasoningEffort.Low),
        expectsThinkingText = false,
        prompt = "What is the product of the decimal digits of 7^13?"
      )
    }
  }

  registerCapabilityContract()

  // ---- validate(): GET /models/<model> (#2855) ----
  override protected def invalidKeyClient: Option[Result[LLMClient]] =
    Some(OpenAIClient(config("sk-invalid-key-for-testing")))
  override protected def unknownModelClient(key: String): Option[Result[LLMClient]] =
    Some(OpenAIClient(config(key, "llm4s-no-such-model")))

  registerValidationContract()
}

private object OpenAISmokeSpec {
  val LoopsPerArm: Int        = 5
  val ReplacementsPerArm: Int = 3

  final case class Arm(loops: Int, results: Seq[org.llm4s.types.Result[org.llm4s.llmconnect.model.Completion]]) {
    def accepted: Int = results.count(_.isRight)
    def empty: Int    = results.count(_.exists(r => r.content.isEmpty && r.message.toolCalls.isEmpty))
    def blank: Int    = results.count(_.exists(r => r.content.trim.isEmpty && r.message.toolCalls.isEmpty))
  }
}
