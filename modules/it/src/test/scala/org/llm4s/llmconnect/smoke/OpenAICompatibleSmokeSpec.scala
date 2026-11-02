package org.llm4s.llmconnect.smoke

import org.llm4s.error.AuthenticationError
import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.OpenAICompatibleConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.provider.OpenAICompatibleClient
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for the generic `openai-compatible` provider (`llm4s-openai-compatible`): the standard
 * dialect, with no vendor of its own, pointed at a real compatible endpoint.
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with `sbt testSmoke`.
 *
 * Requires: `GROQ_API_KEY` - the hosted endpoint is Groq's (`https://api.groq.com/openai/v1`), as in the providers
 * guide's recipe. `GROQ_MODEL` overrides the model (default `llama-3.1-8b-instant`, a cheap one).
 *
 * Optional: `OPENAI_COMPATIBLE_BASE_URL` and `OPENAI_COMPATIBLE_MODEL` - a local server that needs no key (Ollama's
 * `http://localhost:11434/v1`, vLLM, LM Studio, llama.cpp), for the keyless run. Its test is cancelled when they
 * are unset, even under `LLM4S_IT_STRICT`, since the cloud job has no local server.
 *
 * Tier: `@Cloud` - `sbt testSmoke`. Assertions are structural; generated text is never compared, and latency is
 * not asserted.
 */
@Cloud
class OpenAICompatibleSmokeSpec extends AnyFlatSpec with Matchers with EitherValues with ProviderSmokeContract {

  private given ModelRegistryService = ModelRegistryService.default().toOption.get

  private def env(name: String): Option[String] = Option(System.getenv(name)).map(_.trim).filter(_.nonEmpty)

  private val apiKey: Option[String] = env("GROQ_API_KEY")
  private val model: String          = env("GROQ_MODEL").getOrElse("llama-3.1-8b-instant")

  private val GroqBaseUrl = "https://api.groq.com/openai/v1"

  private val localBaseUrl: Option[String] = env("OPENAI_COMPATIBLE_BASE_URL")
  private val localModel: Option[String]   = env("OPENAI_COMPATIBLE_MODEL")

  private def config(key: Option[String], baseUrl: String = GroqBaseUrl, modelName: String = model) =
    OpenAICompatibleConfig.fromValues(model = modelName, baseUrl = baseUrl, apiKey = key)

  private def client(key: Option[String]): Result[LLMClient] = config(key).flatMap(OpenAICompatibleClient(_))

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in one word")))

  private val options: CompletionOptions = CompletionOptions(maxTokens = Some(16))

  "The generic openai-compatible provider" should "complete a basic request against a hosted endpoint" in {
    Tier.require(apiKey.isDefined, "GROQ_API_KEY not set")

    val llm        = client(apiKey).value
    val completion = llm.complete(conversation, options)
    llm.close()

    withClue(s"Completion failed: ${completion.swap.toOption}") {
      completion.isRight shouldBe true
    }
    completion.value.content should not be empty
  }

  it should "stream a basic request against a hosted endpoint" in {
    Tier.require(apiKey.isDefined, "GROQ_API_KEY not set")

    val llm    = client(apiKey).value
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    val result = llm.streamComplete(conversation, options, c => chunks += c)
    llm.close()

    withClue(s"Streaming failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    val streamed = chunks.flatMap(_.content).mkString
    streamed should not be empty
    streamed shouldBe result.value.content
  }

  it should "complete a request with no API key against a local server" in {
    if (localBaseUrl.isEmpty || localModel.isEmpty)
      cancel("OPENAI_COMPATIBLE_BASE_URL and OPENAI_COMPATIBLE_MODEL not set (optional: a local keyless server)")

    val llm        = config(None, localBaseUrl.get, localModel.get).flatMap(OpenAICompatibleClient(_)).value
    val completion = llm.complete(conversation, options)
    llm.close()

    withClue(s"Keyless completion against ${localBaseUrl.get} failed: ${completion.swap.toOption}") {
      completion.isRight shouldBe true
    }
    completion.value.content should not be empty
  }

  it should "return AuthenticationError for an invalid API key" in {
    val llm    = client(Some("invalid-key-for-testing")).value
    val result = llm.complete(conversation, options)
    llm.close()

    result.swap.value shouldBe an[AuthenticationError]
  }

  // ---- the shared capability contract (issue #1212): see ProviderSmokeContract ----

  override protected def providerLabel: String                          = "openai-compatible (Groq)"
  override protected def apiKeyEnvVar: String                           = "GROQ_API_KEY"
  override protected def contractKey: Option[String]                    = apiKey
  override protected def contractClient(key: String): Result[LLMClient] = client(Some(key))
  // Groq serves strict json_schema response formats on a few models only (gpt-oss, Kimi K2, Llama 4), not on the
  // cheap default; point GROQ_MODEL at one of those to check it.
  override protected def notApplicable: Map[Capability, String] =
    if (env("GROQ_MODEL").isDefined) Map.empty
    else Map(Capability.StructuredOutput -> s"Groq's $model takes json_object, not a json_schema response format")

  registerCapabilityContract()
}
