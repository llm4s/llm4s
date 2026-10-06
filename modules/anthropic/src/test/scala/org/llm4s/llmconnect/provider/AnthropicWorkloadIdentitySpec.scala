package org.llm4s.llmconnect.provider

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ AnthropicConfig, AnthropicWorkloadIdentity, ContextWindowResolver }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.testkit.{ FakeTokenExchangeServer, ProviderModuleChecks, ProviderTestConfig, TestJwt }
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{ Files, Path }

class AnthropicWorkloadIdentitySpec extends AnyWordSpec with Matchers with EitherValues with ProviderModuleChecks:

  private given ProviderRegistry = ProviderRegistry.default

  private def sectionResult(body: String): Result[NamedProviderConfig] =
    ProviderTestConfig.loadSection("main", s"llm4s.providers.main {\n$body\n}")

  private def sectionOf(body: String): NamedProviderConfig =
    sectionResult(body).fold(error => fail(error.message), identity)

  private val conversation = Conversation(Seq(UserMessage("hi")))

  // A Windows path has backslashes, which a quoted HOCON string reads as escapes (`\U` is not one).
  private def hoconEscaped(value: String): String = value.replace("\\", "\\\\")

  private def section(baseUrl: String, svid: String, idTokenKey: String = "identityTokenFile") =
    s"""provider = "anthropic"
       |model = "claude-test"
       |baseUrl = "$baseUrl"
       |auth { $idTokenKey = "${hoconEscaped(
        svid
      )}", federationRuleId = "fdrl_1", organizationId = "org_1", workspaceId = "wrkspc_1" }""".stripMargin

  // On POSIX the name gets a backslash, as every Windows path has, so the HOCON escaping is exercised everywhere.
  private val svidPrefix = if java.io.File.separatorChar == '/' then "svid\\Ufile" else "svid"

  private def svidFile(jwt: String) =
    val file = Files.createTempFile(svidPrefix, ".jwt")
    file.toFile.deleteOnExit()
    Files.writeString(file, jwt)

  "an anthropic section with auth" should {
    "exchange the SVID with the jwt-bearer grant and call messages with the access token" in FakeTokenExchangeServer
      .withServer { fake =>
        val jwt    = TestJwt.es256("spiffe://llm4s.test/app", "https://api.anthropic.com")
        val file   = svidFile(jwt)
        val client = assertBuildsClient(AnthropicProvider, sectionOf(section(fake.baseUrl, file.toString)))
        client.complete(conversation, CompletionOptions()).isRight shouldBe true
        val grant = fake.exchanges.head
        grant("grant_type") shouldBe "urn:ietf:params:oauth:grant-type:jwt-bearer"
        grant("assertion") shouldBe jwt
        grant("federation_rule_id") shouldBe "fdrl_1"
        grant("organization_id") shouldBe "org_1"
        fake.apiAuthorizations.last shouldBe "Bearer t1"
      }

    "refresh after a 401" in FakeTokenExchangeServer.withServer { fake =>
      val file   = svidFile(TestJwt.es256("spiffe://llm4s.test/app", "https://api.anthropic.com"))
      val client = assertBuildsClient(AnthropicProvider, sectionOf(section(fake.baseUrl, file.toString)))
      client.complete(conversation, CompletionOptions()).isRight shouldBe true
      fake.rejectNextApiCalls(1)
      val second = client.complete(conversation, CompletionOptions())
      info(
        s"second call after a forced 401: ${second.fold(_.toString.take(120), _ => "Right")}; tokens issued: ${fake.issuedTokens}; authorizations: ${fake.apiAuthorizations}"
      )
      second.isRight shouldBe true
      fake.issuedTokens.size should be >= 2
    }

    "reject a literal identityToken, since the SDK reads only a file" in {
      val body   = section("https://api.anthropic.com", "eyJ.x.y", "identityToken")
      val result = ProviderTestConfig.loadProvider("main", s"llm4s.providers.main {\n$body\n}")
      result.left.value.message should include("identityTokenFile")
    }

    "refuse a plain-http baseUrl, since the SDK posts the identity token there" in {
      val body   = section("http://api.example", "/s")
      val result = ProviderTestConfig.loadProvider("main", s"llm4s.providers.main {\n$body\n}")
      result.left.value shouldBe a[ConfigurationError]
      result.left.value.message should (include("llm4s.providers.main.baseUrl").and(include("https")))
    }

    "accept an https baseUrl, and plain http only to a loopback host" in {
      for url <- Seq("https://api.anthropic.com", "http://127.0.0.1:9", "http://localhost:9") do
        val body = section(url, "/s")
        ProviderTestConfig.loadProvider("main", s"llm4s.providers.main {\n$body\n}").isRight shouldBe true
    }

    "keep the federation ids out of toString" in {
      val body   = section("https://api.anthropic.com", "/s")
      val config = ProviderTestConfig.loadProvider("main", s"llm4s.providers.main {\n$body\n}").value
      config.toString should include("workloadIdentity=Some(AnthropicWorkloadIdentity(")
      for id <- Seq("fdrl_1", "org_1", "wrkspc_1") do (config.toString should not).include(id)
    }

    "refuse to list models, naming the reason, rather than fail for a missing apiKey" in {
      val error = org.llm4s.config.AnthropicModelLister
        .listModels(sectionOf(section("https://api.anthropic.com", "/s")), org.llm4s.http.Llm4sHttpClient.create())
        .left
        .value
      error shouldBe a[ConfigurationError]
      error.message should include("model listing is not supported with workload identity auth for anthropic")
    }

    "reject a missing federationRuleId" in {
      sectionResult(
        section("https://api.anthropic.com", "/s").replace("federationRuleId = \"fdrl_1\", ", "")
      ).isLeft shouldBe true
    }
  }

  "an AnthropicConfig with workload identity built without a named section" should {
    given org.llm4s.model.ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
    given ContextWindowResolver                = ContextWindowResolver(summon[org.llm4s.model.ModelRegistryService])

    val identity = AnthropicWorkloadIdentity(Path.of("/var/run/svid.jwt"), "fdrl_1", "org_1")

    def fromValues(baseUrl: String, apiKey: String = "") =
      AnthropicConfig.fromValues("claude-test", apiKey, baseUrl, Some(identity))

    "be refused by fromValues with a plain-http baseUrl to a non-loopback host" in {
      val error = fromValues("http://api.example").left.value
      error shouldBe a[ConfigurationError]
      error.message should (include("baseUrl").and(include("https")))
    }

    "be accepted by fromValues over https, and over plain http only to a loopback host" in {
      for url <- Seq("https://api.anthropic.com", "http://127.0.0.1:9", "http://localhost:9") do
        fromValues(url).isRight shouldBe true
    }

    "be refused by fromValues with an apiKey as well" in {
      fromValues("https://api.anthropic.com", apiKey = "sk-ant").left.value shouldBe a[ConfigurationError]
    }

    "leave a plain-http baseUrl alone without workload identity" in {
      AnthropicConfig.fromValues("claude-test", "sk-ant", "http://api.example").isRight shouldBe true
    }

    "be refused by AnthropicClient when built with the constructor or copy" in {
      val valid = fromValues("https://api.anthropic.com").value
      val bad = Seq(
        valid.copy(baseUrl = "http://api.example"),
        valid.copy(apiKey = "sk-ant"),
        AnthropicConfig("", "claude-test", "http://localhost@api.example", 200000, 4096, Some(identity))
      )
      for config <- bad do
        AnthropicClient(config).left.value shouldBe a[ConfigurationError]
        an[IllegalArgumentException] should be thrownBy new AnthropicClient(config)
      AnthropicClient(valid).value.close()
    }
  }
