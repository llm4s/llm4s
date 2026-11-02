package org.llm4s.llmconnect.smoke

import org.llm4s.error.AuthenticationError
import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.{ ContextWindowResolver, VertexAIConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.provider.VertexAIClient
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Cloud smoke tests for Gemini on Google Cloud Vertex AI (`llm4s-gemini`), ported from #1049.
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with `sbt testSmoke`.
 *
 * Requires:
 *  - `GOOGLE_CLOUD_PROJECT` - the GCP project with the Vertex AI API enabled;
 *  - credentials the client's Application Default Credentials chain finds: `GOOGLE_ACCESS_TOKEN` (e.g. from
 *    `gcloud auth print-access-token`) or `GOOGLE_APPLICATION_CREDENTIALS` (a service-account or authorized-user
 *    JSON file).
 *
 * Optional: `GOOGLE_CLOUD_LOCATION` (default `us-central1`) and `VERTEX_MODEL` (default `gemini-2.5-flash-lite`).
 *
 * Tier: `@Cloud` - `sbt testSmoke`. Assertions are structural; generated text is never compared.
 *
 * Claude on Vertex (`VERTEX_ANTHROPIC_MODEL` in #1049) is not covered: `VertexAIClient` calls Google's publisher
 * models only, and Anthropic's publisher endpoint is #1017.
 */
@Cloud
class VertexAISmokeSpec extends AnyFlatSpec with Matchers with EitherValues with ProviderSmokeContract {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private def env(name: String): Option[String] = Option(System.getenv(name)).map(_.trim).filter(_.nonEmpty)

  private val project: Option[String] = env("GOOGLE_CLOUD_PROJECT")
  private val location: String        = env("GOOGLE_CLOUD_LOCATION").getOrElse(VertexAIConfig.DEFAULT_LOCATION)
  private val model: String           = env("VERTEX_MODEL").getOrElse("gemini-2.5-flash-lite")
  private val accessToken             = env("GOOGLE_ACCESS_TOKEN")
  private val hasCredentials: Boolean = accessToken.isDefined || env("GOOGLE_APPLICATION_CREDENTIALS").isDefined

  private val Required = "GOOGLE_CLOUD_PROJECT and GOOGLE_ACCESS_TOKEN or GOOGLE_APPLICATION_CREDENTIALS"

  /** The project, when credentials are there too; the contract runs only then. */
  private val credentials: Option[String] = project.filter(_ => hasCredentials)

  private def config(projectId: String, credentialFile: Option[String] = None): VertexAIConfig =
    VertexAIConfig.fromValues(model, projectId, location, credentialFile).value

  private def conversation: Conversation = Conversation(Seq(UserMessage("Say hi in one word")))

  private val options: CompletionOptions = CompletionOptions(maxTokens = Some(64))

  "Vertex AI Gemini" should "complete a basic request and report its usage" in {
    Tier.require(credentials.isDefined, s"$Required not set")

    val client     = VertexAIClient(config(credentials.get)).value
    val completion = client.complete(conversation, options)
    client.close()

    withClue(s"Completion failed: ${completion.swap.toOption}") {
      completion.isRight shouldBe true
    }
    completion.value.content should not be empty
    completion.value.usage shouldBe defined
    completion.value.usage.get.promptTokens should be > 0
    completion.value.usage.get.completionTokens should be > 0
  }

  it should "stream a basic request" in {
    Tier.require(credentials.isDefined, s"$Required not set")

    val client = VertexAIClient(config(credentials.get)).value
    val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conversation, options, c => chunks += c)
    client.close()

    withClue(s"Streaming failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    val streamed = chunks.flatMap(_.content).mkString
    streamed should not be empty
    streamed shouldBe result.value.content
  }

  it should "return AuthenticationError for invalid credentials" in {
    // GOOGLE_ACCESS_TOKEN takes precedence over any credential file, so the bad one would never be read.
    if (accessToken.isDefined) cancel("GOOGLE_ACCESS_TOKEN is set and would replace the invalid credentials")

    // An authorized-user file whose refresh token Google's token endpoint rejects.
    val file = Files.createTempFile("llm4s-vertex-invalid", ".json")
    Files.write(
      file,
      ujson
        .Obj(
          "type"          -> "authorized_user",
          "client_id"     -> "invalid-client-for-smoke-test.apps.googleusercontent.com",
          "client_secret" -> "invalid-secret-for-smoke-test",
          "refresh_token" -> "invalid-refresh-token-for-smoke-test"
        )
        .render()
        .getBytes(StandardCharsets.UTF_8)
    )
    val result =
      VertexAIClient(config(project.getOrElse("llm4s-smoke-invalid"), Some(file.toString))).flatMap { client =>
        val completion = client.complete(conversation, options)
        client.close()
        completion
      }
    Files.deleteIfExists(file)

    result.swap.value shouldBe an[AuthenticationError]
  }

  // ---- the shared capability contract (issue #1212): see ProviderSmokeContract ----

  override protected def providerLabel: String                                = "Vertex AI"
  override protected def apiKeyEnvVar: String                                 = Required
  override protected def contractKey: Option[String]                          = credentials
  override protected def contractClient(projectId: String): Result[LLMClient] = VertexAIClient(config(projectId))

  registerCapabilityContract()
}
