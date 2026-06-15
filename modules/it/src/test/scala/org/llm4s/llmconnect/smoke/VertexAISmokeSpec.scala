package org.llm4s.llmconnect.smoke

import org.llm4s.llmconnect.config.{ ContextWindowResolver, VertexAIConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.provider.VertexAIClient
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Real-endpoint smoke tests for the Vertex AI provider.
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with `sbt "it/testOnly org.llm4s.llmconnect.smoke.*"`
 * or the `sbt testSmoke` alias.
 *
 * == Required environment variables ==
 *
 *  - `GOOGLE_CLOUD_PROJECT`            — GCP project ID
 *  - `GOOGLE_CLOUD_LOCATION`           — GCP region (e.g. `us-central1`)
 *  - `VERTEX_ACCESS_TOKEN`             — OAuth2 bearer token obtained from ADC
 *                                        (`gcloud auth print-access-token`)
 *
 * All tests are skipped gracefully when any of these variables is absent.
 *
 * == Optional: Anthropic on Vertex ==
 *
 * Tests against `claude-3-haiku@20240307` are also skipped unless
 * `VERTEX_ANTHROPIC_MODEL` is set (e.g. `claude-3-haiku@20240307`).
 * Claude access on Vertex requires explicit model enablement in your GCP
 * project.
 *
 * @see [[org.llm4s.llmconnect.provider.VertexAIClient]]
 * @see [[org.llm4s.llmconnect.smoke.GeminiSmokeSpec]] for the public Gemini API smoke tests
 */
class VertexAISmokeSpec extends AnyFlatSpec with Matchers {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver    = ContextWindowResolver(mrs)

  // Environment variables
  private val project: Option[String]  = Option(System.getenv("GOOGLE_CLOUD_PROJECT")).filter(_.nonEmpty)
  private val location: Option[String] = Option(System.getenv("GOOGLE_CLOUD_LOCATION")).filter(_.nonEmpty)
  private val token: Option[String]    = Option(System.getenv("VERTEX_ACCESS_TOKEN")).filter(_.nonEmpty)

  // Optional Anthropic model (Claude on Vertex)
  private val anthropicModel: Option[String] =
    Option(System.getenv("VERTEX_ANTHROPIC_MODEL")).filter(_.nonEmpty)

  /** True only when all required GCP env vars are present. */
  private def gcpCredentialsPresent: Boolean =
    project.isDefined && location.isDefined && token.isDefined

  private def geminiConfig: VertexAIConfig =
    VertexAIConfig.fromValues(
      project = project.get,
      location = location.get,
      modelName = "gemini-1.5-flash",
      accessToken = token.get,
    )

  private def claudeConfig(model: String): VertexAIConfig =
    VertexAIConfig.fromValues(
      project = project.get,
      location = location.get,
      modelName = model,
      accessToken = token.get,
    )

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in one word")))

  // -----------------------------------------------------------------------
  // Gemini-on-Vertex smoke tests
  // -----------------------------------------------------------------------

  "Vertex AI Gemini" should "complete a basic request" in {
    assume(gcpCredentialsPresent, "GOOGLE_CLOUD_PROJECT, GOOGLE_CLOUD_LOCATION, or VERTEX_ACCESS_TOKEN not set")

    val clientResult = VertexAIClient(geminiConfig)
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

  it should "populate token usage in the response" in {
    assume(gcpCredentialsPresent, "GOOGLE_CLOUD_PROJECT, GOOGLE_CLOUD_LOCATION, or VERTEX_ACCESS_TOKEN not set")

    val client     = VertexAIClient(geminiConfig).toOption.get
    val completion = client.complete(conversation, CompletionOptions())

    withClue(s"Completion failed: ${completion.swap.toOption}") {
      completion.isRight shouldBe true
    }
    val usage = completion.toOption.get.usage
    usage shouldBe defined
    usage.foreach { u =>
      u.promptTokens should be > 0
      u.completionTokens should be > 0
    }
  }

  it should "stream a response and emit at least one chunk" in {
    assume(gcpCredentialsPresent, "GOOGLE_CLOUD_PROJECT, GOOGLE_CLOUD_LOCATION, or VERTEX_ACCESS_TOKEN not set")

    val client = VertexAIClient(geminiConfig).toOption.get
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c)

    withClue(s"Streaming failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    result.toOption.get.content should not be empty
    chunks should not be empty
  }

  it should "return an authentication error for an invalid token" in {
    assume(gcpCredentialsPresent, "GOOGLE_CLOUD_PROJECT, GOOGLE_CLOUD_LOCATION, or VERTEX_ACCESS_TOKEN not set")

    val badConfig = VertexAIConfig.fromValues(
      project = project.get,
      location = location.get,
      modelName = "gemini-1.5-flash",
      accessToken = "invalid-token-for-testing",
    )
    val client = VertexAIClient(badConfig).toOption.get
    val result = client.complete(conversation, CompletionOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe an[org.llm4s.error.AuthenticationError]
  }

  // -----------------------------------------------------------------------
  // Claude-on-Vertex smoke tests (optional — requires explicit access)
  // -----------------------------------------------------------------------

  "Vertex AI Claude (Anthropic on Vertex)" should "complete a basic request" in {
    assume(gcpCredentialsPresent, "GOOGLE_CLOUD_PROJECT, GOOGLE_CLOUD_LOCATION, or VERTEX_ACCESS_TOKEN not set")
    assume(anthropicModel.isDefined, "VERTEX_ANTHROPIC_MODEL not set; skipping Anthropic-on-Vertex tests")

    val clientResult = VertexAIClient(claudeConfig(anthropicModel.get))
    withClue(s"Client creation failed: ${clientResult.swap.toOption}") {
      clientResult.isRight shouldBe true
    }

    val client     = clientResult.toOption.get
    val completion = client.complete(conversation, CompletionOptions())

    withClue(s"Claude-on-Vertex completion failed: ${completion.swap.toOption}") {
      completion.isRight shouldBe true
    }
    completion.toOption.get.content should not be empty
  }

  it should "populate token usage in the Claude response" in {
    assume(gcpCredentialsPresent, "GOOGLE_CLOUD_PROJECT, GOOGLE_CLOUD_LOCATION, or VERTEX_ACCESS_TOKEN not set")
    assume(anthropicModel.isDefined, "VERTEX_ANTHROPIC_MODEL not set; skipping Anthropic-on-Vertex tests")

    val client     = VertexAIClient(claudeConfig(anthropicModel.get)).toOption.get
    val completion = client.complete(conversation, CompletionOptions())

    withClue(s"Claude-on-Vertex completion failed: ${completion.swap.toOption}") {
      completion.isRight shouldBe true
    }
    val usage = completion.toOption.get.usage
    usage shouldBe defined
    usage.foreach { u =>
      u.promptTokens should be > 0
      u.completionTokens should be > 0
    }
  }
}
