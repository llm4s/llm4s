package org.llm4s.llmconnect.smoke

import org.llm4s.error.AuthenticationError
import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.{ ContextWindowResolver, MistralConfig }
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  CompletionOptions,
  Conversation,
  StreamedChunk,
  ToolCall,
  ToolMessage,
  UserMessage
}
import org.llm4s.llmconnect.provider.MistralClient
import org.llm4s.model.ModelRegistryService
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction }
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for Mistral.
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with `sbt testSmoke`.
 *
 * Requires: `MISTRAL_API_KEY` environment variable.
 * Tier: `@Cloud` - `sbt testSmoke`.
 *
 * `MistralClient` is the Mistral dialect of `llm4s-openai-compatible` (#1210), so streaming
 * goes through the shared client. Assertions are structural; generated text is never compared.
 */
@Cloud
class MistralSmokeSpec extends AnyFlatSpec with Matchers with EitherValues with ProviderSmokeContract {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val apiKey: Option[String] = Option(System.getenv("MISTRAL_API_KEY")).filter(_.nonEmpty)

  /** A reasoning model: it returns its reasoning as `thinking` content chunks. */
  private val ReasoningModel = "magistral-small-latest"

  private def config(key: String, model: String = "mistral-small-latest"): MistralConfig =
    MistralConfig
      .fromValues(
        modelName = model,
        apiKey = key,
        baseUrl = MistralConfig.DEFAULT_BASE_URL
      )
      .value

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in one word")))

  private val options: CompletionOptions = CompletionOptions(maxTokens = Some(16))

  private val NineCharacterId = "[A-Za-z0-9]{9}"

  private def secretCodeTool: ToolFunction[_, _] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Secret code parameters")
      .withProperty(Schema.property("topic", Schema.string("What the code is for")))
    ToolBuilder[Map[String, Any], String]("get_secret_code", "Looks up the secret code for a topic", schema)
      .withHandler(params => params.getString("topic").map(_ => "ZX-4417"))
      .buildSafe()
      .value
  }

  "Mistral" should "complete a basic request" in {
    Tier.require(apiKey.isDefined, "MISTRAL_API_KEY not set")

    val client     = MistralClient(config(apiKey.get)).value
    val completion = client.complete(conversation, options).value

    completion.usage shouldBe defined
    completion.usage.get.promptTokens should be > 0
    completion.usage.get.completionTokens should be > 0
  }

  it should "stream a basic request" in {
    Tier.require(apiKey.isDefined, "MISTRAL_API_KEY not set")

    val client = MistralClient(config(apiKey.get)).value
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conversation, options, c => chunks += c).value

    // The first delta carries `content: ""` (the role chunk), so `isDefined` alone would pass for a
    // stream with no text at all; require real text, and that it is what the call returned.
    val streamed = chunks.flatMap(_.content).mkString
    streamed should not be empty
    streamed shouldBe result.content
  }

  it should "return AuthenticationError for an invalid API key" in {
    val client = MistralClient(config("invalid-key-for-testing")).value
    val result = client.complete(conversation, options)

    result.swap.value shouldBe an[AuthenticationError]
  }

  it should "return tool calls with nine-character ids" in {
    Tier.require(apiKey.isDefined, "MISTRAL_API_KEY not set")

    val client = MistralClient(config(apiKey.get)).value
    val completion = client
      .complete(
        Conversation(Seq(UserMessage("Use the get_secret_code tool with the topic 'vault'."))),
        CompletionOptions(temperature = 0.0, maxTokens = Some(64)).withTools(Seq(secretCodeTool))
      )
      .value
    client.close()

    completion.toolCalls should not be empty
    completion.toolCalls.foreach(_.id should fullyMatch regex NineCharacterId)
  }

  it should "accept a history whose tool-call id is not nine characters (from another provider)" in {
    Tier.require(apiKey.isDefined, "MISTRAL_API_KEY not set")

    // Mistral rejects such an id itself; the dialect maps it to a stable nine-character one, on the call and the
    // tool message alike, so the two still match.
    val foreignId = "call_0123456789abcdefFOREIGN"
    val history = Conversation(
      Seq(
        UserMessage("Use the get_secret_code tool with the topic 'vault', then tell me the code."),
        AssistantMessage(toolCalls = Seq(ToolCall(foreignId, "get_secret_code", ujson.Obj("topic" -> "vault")))),
        ToolMessage("ZX-4417", foreignId)
      )
    )

    val client = MistralClient(config(apiKey.get)).value
    val result = client.complete(
      history,
      CompletionOptions(temperature = 0.0, maxTokens = Some(64)).withTools(Seq(secretCodeTool))
    )
    client.close()

    withClue(s"A history with a foreign tool-call id was rejected: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
  }

  it should "accept a history with an empty assistant turn" in {
    Tier.require(apiKey.isDefined, "MISTRAL_API_KEY not set")

    // Mistral rejects an assistant message with neither content nor tool calls; the dialect leaves it out.
    val history = Conversation(
      Seq(UserMessage("Hello"), AssistantMessage(contentOpt = None), UserMessage("Say hi in one word"))
    )

    val client = MistralClient(config(apiKey.get)).value
    val result = client.complete(history, options)
    client.close()

    withClue(s"A history with an empty assistant turn was rejected: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    result.value.content should not be empty
  }

  it should "stream a Magistral model's thinking chunks as thinking deltas, apart from the answer" in {
    Tier.require(apiKey.isDefined, "MISTRAL_API_KEY not set")

    val client = MistralClient(config(apiKey.get, ReasoningModel)).value
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conversation, CompletionOptions(maxTokens = Some(2048)), c => chunks += c)
    client.close()

    withClue(s"Streaming from $ReasoningModel failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    chunks.flatMap(_.thinkingDelta).mkString should not be empty
    result.value.content should not be empty
  }

  // ---- the shared capability contract (issue #1212): see ProviderSmokeContract ----

  override protected def providerLabel: String                          = "Mistral"
  override protected def apiKeyEnvVar: String                           = "MISTRAL_API_KEY"
  override protected def contractKey: Option[String]                    = apiKey
  override protected def contractClient(key: String): Result[LLMClient] = MistralClient(config(key))
  // Magistral reasons whatever the options say and returns its reasoning as `thinking` content chunks, which the
  // dialect reads back as Completion.thinking. The cap leaves room for the answer after the reasoning.
  override protected def reasoningSetup: Option[String => Result[ReasoningSetup]] = Some { key =>
    MistralClient(config(key, ReasoningModel)).map { client =>
      ReasoningSetup(client, CompletionOptions(maxTokens = Some(2048)), expectsThinkingText = true)
    }
  }

  registerCapabilityContract()
}
