package org.llm4s.llmconnect.smoke

import org.llm4s.types.ProviderModelTypes.BaseUrl
import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.security.Secret
import org.scalatest.EitherValues
import org.llm4s.error.AuthenticationError
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIApi, OpenAIConfig }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  CompletionOptions,
  Conversation,
  ReasoningEffort,
  StreamedChunk,
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

  // #2940: on Chat Completions these models call a function only at reasoning_effort none, which the client sends
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

    val client   = OpenAIClient(config(apiKey.get, model)).toOption.get
    val sync     = client.complete(ask, options)
    val streamed = client.streamComplete(ask, options)(_ => ())
    client.close()

    Seq("complete" -> sync, "streamComplete" -> streamed).foreach { case (call, result) =>
      withClue(s"$call on $model failed: ${result.swap.toOption}: ") {
        result.value.message.toolCalls.map(_.name) shouldBe Seq("get_weather")
      }
    }
  }

  // ---- the Responses API (#2900): /v1/responses, store false, reasoning items never sent back ----

  private def responsesConfig(key: String, model: String): OpenAIConfig =
    config(key, model).withApi(OpenAIApi.Responses)

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
      val client = OpenAIClient(responsesConfig(apiKey.get, model)).toOption.get
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
      val client  = OpenAIClient(responsesConfig(apiKey.get, model)).toOption.get
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
      val reply = withClue(s"Responses turn 2 on $model was rejected: ${second.swap.toOption}: ")(second.value)
      withClue(
        s"$model replied with neither content nor a tool call: content=${reply.content}, tool calls=${reply.message.toolCalls}: "
      ) {
        (reply.content.nonEmpty || reply.message.toolCalls.nonEmpty) shouldBe true
      }
    }
  }

  it should "complete a plain request on the Responses API on a non-reasoning model" in {
    Tier.require(apiKey.isDefined, "OPENAI_API_KEY not set")

    val client = OpenAIClient(responsesConfig(apiKey.get, "gpt-4o-mini")).toOption.get
    val result = client.complete(conversation, CompletionOptions())
    client.close()

    withClue(s"Responses plain completion failed: ${result.swap.toOption}: ") {
      result.isRight shouldBe true
    }
    result.value.content should not be empty
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
  // reasoning too, so the cap leaves room for the answer after it.
  override protected def reasoningSetup: Option[String => Result[ReasoningSetup]] = Some { key =>
    OpenAIClient(config(key, ReasoningModel)).map { client =>
      ReasoningSetup(
        client,
        CompletionOptions(maxTokens = Some(1024)).withReasoning(ReasoningEffort.Low),
        expectsThinkingText = false
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
