package org.llm4s.testkit

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.http.{ Llm4sHttpClient, MultipartPart }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, ProviderConfig }
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.llmconnect.spi.{ ProviderConfigSpec, ProviderDescriptor, ProviderFeatures }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.fixtures.AcmeProvider
import org.llm4s.types.ProviderModelTypes.*
import org.llm4s.types.Result
import org.scalatest.exceptions.TestFailedException
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*

/**
 * `assertUsesSuppliedHttpClient` (#2341) passes for a provider that sends `complete` and `streamComplete`
 * through the supplied client and leaves it open, fails - saying why - for each way one can get that wrong,
 * and closes the LLM client it built even when it fails. `RecordingHttpClient` records every method.
 */
class SuppliedHttpClientChecksSpec extends AnyWordSpec with Matchers with ProviderModuleChecks:

  private def section(provider: ProviderId) = NamedProviderConfig(
    provider = provider,
    model = ModelName("acme-large"),
    baseUrl = None,
    apiKey = Some(ApiKey("test-key"))
  )

  private def failureOf(check: => Any): String =
    intercept[TestFailedException](check).getMessage

  /** An opted-in provider whose client can be told to skip the supplied client or to close it. */
  final private class HttpProvider(
    name: String,
    completeSends: Boolean = true,
    streamSends: Boolean = true,
    closesSupplied: Boolean = false
  ) extends ProviderDescriptor:
    val id: ProviderId                      = ProviderId(name)
    val configSpec: ProviderConfigSpec      = AcmeProvider.configSpec
    override def features: ProviderFeatures = ProviderFeatures.default.withCustomHttpClient(true)
    val clientClosed: AtomicBoolean         = new AtomicBoolean(false)
    def buildConfig(providerName: String, section: NamedProviderConfig)(using
      ContextWindowResolver
    ): Result[ProviderConfig] = AcmeProvider.buildConfig(providerName, section)
    def buildClient(config: ProviderConfig, options: LlmClientOptions)(using ModelRegistryService): Result[LLMClient] =
      Right(new HttpClientUser(options.httpClient, completeSends, streamSends, closesSupplied, clientClosed))

  final private class HttpClientUser(
    http: Option[Llm4sHttpClient],
    completeSends: Boolean,
    streamSends: Boolean,
    closesSupplied: Boolean,
    closedFlag: AtomicBoolean
  ) extends LLMClient:
    private val answer = Completion("c-1", Instant.EPOCH, "hi", "acme-large", AssistantMessage("hi"))
    private def send(path: String): Unit =
      http.foreach(_.post(s"https://acme.invalid/$path", Map.empty, "{}", 1.second)): Unit
    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      if completeSends then send("complete")
      Right(answer)
    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      if streamSends then send("stream")
      Right(answer)
    def getContextWindow(): Int     = 8192
    def getReserveCompletion(): Int = 1024
    override def close(): Unit =
      closedFlag.set(true)
      if closesSupplied then http.foreach(_.close())

  "assertUsesSuppliedHttpClient" should {

    "pass for a client that sends both calls through the supplied client and leaves it open" in {
      val provider = new HttpProvider("good")
      assertUsesSuppliedHttpClient(provider, section(provider.id))
      provider.clientClosed.get shouldBe true
    }

    "fail for a descriptor that does not opt in with customHttpClient" in {
      failureOf(assertUsesSuppliedHttpClient(AcmeProvider, section(AcmeProvider.id))) should include(
        "acme declares features.customHttpClient = false"
      )
    }

    "fail, and still close the client, when complete bypasses the supplied client" in {
      val provider = new HttpProvider("bypass-complete", completeSends = false)
      failureOf(assertUsesSuppliedHttpClient(provider, section(provider.id))) should include(
        "bypass-complete: complete sent no request through the supplied HTTP client"
      )
      provider.clientClosed.get shouldBe true
    }

    "fail, and still close the client, when streamComplete bypasses the supplied client" in {
      val provider = new HttpProvider("bypass-stream", streamSends = false)
      failureOf(assertUsesSuppliedHttpClient(provider, section(provider.id))) should include(
        "bypass-stream: streamComplete sent no request through the supplied HTTP client"
      )
      provider.clientClosed.get shouldBe true
    }

    "fail for a client that closes the supplied client with itself" in {
      val provider = new HttpProvider("closer", closesSupplied = true)
      failureOf(assertUsesSuppliedHttpClient(provider, section(provider.id))) should include(
        "closer closed the HTTP client its caller supplied"
      )
    }
  }

  "RecordingHttpClient" should {

    "record each method and URL, answer 400 with an error body, and report being closed" in {
      val http = new ProviderModuleChecks.RecordingHttpClient
      val t    = 1.second

      val answers = Seq(
        http.get("https://h/get", Map.empty, Map("q" -> "1"), t),
        http.post("https://h/post", Map.empty, "{}", t),
        http.postBytes("https://h/bytes", Map.empty, Array[Byte](1, 2), t),
        http.postMultipart("https://h/multi", Map.empty, Seq.empty[MultipartPart], t),
        http.put("https://h/put", Map.empty, "{}", t),
        http.delete("https://h/delete", Map.empty, t)
      )
      answers.foreach { answer =>
        answer.map(_.statusCode) shouldBe Right(400)
        answer.map(_.body).toOption.get should include("refused by provider-testkit")
      }

      val raw = http.postRaw("https://h/raw", Map.empty, "{}", t).fold(e => fail(e.message), identity)
      raw.statusCode shouldBe 400
      new String(raw.body, StandardCharsets.UTF_8) should include("refused by provider-testkit")

      val streamed = http.postStream("https://h/stream", Map.empty, "{}", t).fold(e => fail(e.message), identity)
      streamed.statusCode shouldBe 400
      new String(streamed.body.readAllBytes(), StandardCharsets.UTF_8) should include("refused by provider-testkit")

      http.requests shouldBe Seq(
        "GET https://h/get",
        "POST https://h/post",
        "POST https://h/bytes",
        "POST https://h/multi",
        "PUT https://h/put",
        "DELETE https://h/delete",
        "POST https://h/raw",
        "POST https://h/stream"
      )
      http.closed shouldBe false
      http.close()
      http.closed shouldBe true
    }
  }
