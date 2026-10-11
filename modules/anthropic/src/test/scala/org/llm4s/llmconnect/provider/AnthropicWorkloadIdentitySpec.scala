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

    "exchange the SVID and stream messages with the access token" in FakeTokenExchangeServer.withServer { fake =>
      val file   = svidFile(TestJwt.es256("spiffe://llm4s.test/app", "https://api.anthropic.com"))
      val client = assertBuildsClient(AnthropicProvider, sectionOf(section(fake.baseUrl, file.toString)))
      assertStreams(client)
      fake.exchanges should have size 1
      fake.apiAuthorizations shouldBe Seq("Bearer t1")
    }

    "refresh after a 401 while streaming" in FakeTokenExchangeServer.withServer { fake =>
      val file   = svidFile(TestJwt.es256("spiffe://llm4s.test/app", "https://api.anthropic.com"))
      val client = assertBuildsClient(AnthropicProvider, sectionOf(section(fake.baseUrl, file.toString)))
      assertStreams(client)
      fake.rejectNextApiCalls(1)
      assertStreams(client)
      fake.issuedTokens.size should be >= 2
      fake.apiAuthorizations.last shouldBe s"Bearer ${fake.issuedTokens.last}"
    }

    "exchange again once the token has expired" in FakeTokenExchangeServer.withServer { fake =>
      fake.setExpiresIn(0)
      val file   = svidFile(TestJwt.es256("spiffe://llm4s.test/app", "https://api.anthropic.com"))
      val client = assertBuildsClient(AnthropicProvider, sectionOf(section(fake.baseUrl, file.toString)))
      client.complete(conversation, CompletionOptions()).isRight shouldBe true
      client.complete(conversation, CompletionOptions()).isRight shouldBe true
      fake.issuedTokens.size should be >= 2
      fake.apiAuthorizations.last shouldBe s"Bearer ${fake.issuedTokens.last}"
    }

    "present the rotated SVID on the next exchange" in FakeTokenExchangeServer.withServer { fake =>
      fake.setExpiresIn(0)
      val first  = TestJwt.es256("spiffe://llm4s.test/app", "https://api.anthropic.com")
      val file   = svidFile(first)
      val client = assertBuildsClient(AnthropicProvider, sectionOf(section(fake.baseUrl, file.toString)))
      client.complete(conversation, CompletionOptions()).isRight shouldBe true
      val second = TestJwt.es256("spiffe://llm4s.test/app", "https://api.anthropic.com")
      Files.writeString(file, second)
      client.complete(conversation, CompletionOptions()).isRight shouldBe true
      fake.exchanges.map(_("assertion")).distinct shouldBe Seq(first, second)
    }

    "make one exchange for concurrent calls" in FakeTokenExchangeServer.withServer { fake =>
      import scala.jdk.CollectionConverters.*
      val file   = svidFile(TestJwt.es256("spiffe://llm4s.test/app", "https://api.anthropic.com"))
      val client = assertBuildsClient(AnthropicProvider, sectionOf(section(fake.baseUrl, file.toString)))
      val pool   = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
      val calls = (1 to 10).map(_ =>
        (
          () => client.complete(conversation, CompletionOptions()).fold(_.toString, _ => "ok")
        ): java.util.concurrent.Callable[String]
      )
      try pool.invokeAll(calls.asJava).asScala.map(_.get()).toList shouldBe List.fill(10)("ok")
      finally pool.shutdown()
      fake.issuedTokens shouldBe Seq("t1")
      fake.apiAuthorizations shouldBe Seq.fill(10)("Bearer t1")
    }

    // `refusedBaseUrl` reaches the fake, where the SDK would post the jwt-bearer grant, so a refusal that came
    // after any request would show in its records.
    "be refused on every entry point, before the SDK posts the grant, for a baseUrl the rules refuse" in
      FakeTokenExchangeServer.withServer { fake =>
        given org.llm4s.model.ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
        val file = svidFile(TestJwt.es256("spiffe://llm4s.test/app", "https://api.anthropic.com"))
        val body = section(fake.refusedBaseUrl, file.toString)
        // Section validation leaves the baseUrl to the provider, so the section itself loads.
        val named = sectionOf(body)
        // Chat from a section: buildConfig, as Llm4sConfig.provider, LLMConnect and the testkit use.
        ProviderModuleChecks.buildClient(AnthropicProvider, named).left.value shouldBe a[ConfigurationError]
        ProviderTestConfig.loadProvider("main", s"llm4s.providers.main {\n$body\n}").left.value shouldBe
          a[ConfigurationError]
        // Chat from a config built by hand: the companion apply, LLMConnect and the constructor.
        val byHand = AnthropicConfig(
          "",
          "claude-test",
          fake.refusedBaseUrl,
          200000,
          4096,
          Some(AnthropicWorkloadIdentity(file, "fdrl_1", "org_1"))
        )
        AnthropicClient(byHand).left.value shouldBe a[ConfigurationError]
        org.llm4s.llmconnect.LLMConnect.getClient(byHand).left.value shouldBe a[ConfigurationError]
        an[IllegalArgumentException] should be thrownBy new AnthropicClient(byHand)
        // Model listing refuses workload identity outright.
        org.llm4s.config.AnthropicModelLister
          .listModels(named, org.llm4s.http.Llm4sHttpClient.create())
          .left
          .value shouldBe a[ConfigurationError]
        fake.exchanges shouldBe empty
        fake.apiAuthorizations shouldBe empty
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

    "reject a blank federationRuleId or organizationId, naming the auth key" in {
      val valid = section("https://api.anthropic.com", "/s")
      for (key, value) <- Seq("federationRuleId" -> "fdrl_1", "organizationId" -> "org_1") do
        val blanked = valid.replace(s"""$key = "$value"""", s"""$key = " """")
        blanked should not be valid
        sectionResult(blanked).left.value.message should include(s"auth.$key")
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

    "keep the workload identity and the timeouts independently" in {
      import scala.concurrent.duration.DurationInt
      val timeouts = org.llm4s.llmconnect.config.ProviderTimeouts(Some(45.seconds), Some(5.minutes))
      val config   = fromValues("https://api.anthropic.com").value.withTimeouts(timeouts)
      config.timeouts shouldBe timeouts
      config.workloadIdentity shouldBe Some(identity)
      config.withWorkloadIdentity(None).timeouts shouldBe timeouts
      AnthropicConfig.validate(config).isRight shouldBe true
    }

    "be refused by fromValues with a plain-http baseUrl to a non-loopback host" in {
      val error = fromValues("http://api.example").left.value
      error shouldBe a[ConfigurationError]
      error.message should (include("baseUrl").and(include("https")))
    }

    "be accepted by fromValues over https, and over plain http only to a loopback host" in {
      for url <- Seq("https://api.anthropic.com", "http://127.0.0.1:9", "http://localhost:9") do
        fromValues(url).isRight shouldBe true
    }

    "be refused by fromValues for an https baseUrl that is not api.anthropic.com" in {
      for url <- Seq(
          "https://gateway.example",
          "https://api.anthropic.com.evil.example",
          "https://evilapi.anthropic.com",
          "https://anthropic.com",
          "https://api.anthropic.com.",
          "https://u:pw@api.anthropic.com",
          "https://api.anthropic.com@gateway.example"
        )
      do
        withClue(url) {
          val error = fromValues(url).left.value
          error shouldBe a[ConfigurationError]
          error.message should (include("baseUrl").and(include("api.anthropic.com")))
        }
      fromValues("HTTPS://API.ANTHROPIC.COM").isRight shouldBe true
    }

    "be refused by fromValues with an apiKey as well" in {
      fromValues("https://api.anthropic.com", apiKey = "sk-ant").left.value shouldBe a[ConfigurationError]
    }

    "leave a plain-http baseUrl alone without workload identity" in {
      AnthropicConfig.fromValues("claude-test", "sk-ant", "http://api.example").isRight shouldBe true
    }

    "be refused by AnthropicClient when built with apply or changed with a with* setter" in {
      val valid = fromValues("https://api.anthropic.com").value
      val bad = Seq(
        valid.withBaseUrl("http://api.example"),
        valid.withBaseUrl("https://gateway.example"),
        valid.withApiKey("sk-ant"),
        AnthropicConfig("", "claude-test", "http://localhost@api.example", 200000, 4096, Some(identity))
      )
      for config <- bad do
        AnthropicClient(config).left.value shouldBe a[ConfigurationError]
        an[IllegalArgumentException] should be thrownBy new AnthropicClient(config)
      AnthropicClient(valid).value.close()
    }

    "be refused by fromValues, AnthropicClient and the with* setters for a blank required id or optional id" in {
      val blanks = Seq(
        "workloadIdentity.federationRuleId"  -> identity.withFederationRuleId(" "),
        "workloadIdentity.organizationId"    -> identity.withOrganizationId(""),
        "workloadIdentity.serviceAccountId"  -> identity.withServiceAccountId(" "),
        "workloadIdentity.workspaceId"       -> identity.withWorkspaceId(""),
        "workloadIdentity.identityTokenFile" -> identity.withIdentityTokenFile(Path.of(""))
      )
      for (field, blank) <- blanks do
        withClue(field) {
          val error = AnthropicConfig.fromValues("claude-test", "", "https://api.anthropic.com", Some(blank)).left.value
          error shouldBe a[ConfigurationError]
          error.asInstanceOf[ConfigurationError].missingKeys shouldBe List(field)
          error.message should include(field)
          val built = fromValues("https://api.anthropic.com").value.withWorkloadIdentity(Some(blank))
          AnthropicClient(built).left.value shouldBe a[ConfigurationError]
          an[IllegalArgumentException] should be thrownBy new AnthropicClient(built)
        }
    }

    "leave AnthropicClient refusing a blank apiKey once the identity is removed, rather than send no credential" in {
      val keyless = fromValues("https://api.anthropic.com").value.withWorkloadIdentity(None)
      for key <- Seq("", "  ") do
        val error = AnthropicClient(keyless.withApiKey(key)).left.value
        error shouldBe a[ConfigurationError]
        error.message should include("apiKey")
        an[IllegalArgumentException] should be thrownBy new AnthropicClient(keyless.withApiKey(key))
    }

    "apply the same rules to the Java and Kotlin path: the short apply and withWorkloadIdentity" in {
      // the short apply with a blank key and no identity would authenticate as nobody
      for key <- Seq("", "  ") do
        AnthropicClient(AnthropicConfig(key, "claude-test")).left.value.message should include("apiKey")
      // the short apply's default base URL is api.anthropic.com, so a keyless config with an identity is valid
      val federated = AnthropicConfig("", "claude-test").withWorkloadIdentity(identity)
      AnthropicConfig.validate(federated) shouldBe Right(federated)
      val bad = Seq(
        AnthropicConfig("sk-ant", "claude-test").withWorkloadIdentity(identity),
        AnthropicConfig("sk-ant", "claude-test").withWorkloadIdentity(Some(identity)),
        federated.withApiKey("sk-ant"),
        federated.withBaseUrl("https://gateway.example"),
        federated.withWorkloadIdentity(identity.withOrganizationId("")),
        federated.withWorkloadIdentity(None)
      )
      for config <- bad do AnthropicClient(config).left.value shouldBe a[ConfigurationError]
    }
  }

  "AnthropicClient.mapError" should {
    val config = AnthropicConfig(
      "",
      "claude-test",
      "https://api.anthropic.com",
      200000,
      4096,
      Some(
        AnthropicWorkloadIdentity(
          Path.of("/var/run/svid.jwt"),
          "fdrl_secret_1",
          "org_secret_1",
          serviceAccountId = Some("svac_secret_1"),
          workspaceId = Some("wrkspc_secret_1")
        )
      )
    )
    val secrets = AnthropicClient.credentialSecrets(config)
    val echoed  = Seq("fdrl_secret_1", "org_secret_1", "svac_secret_1", "wrkspc_secret_1", "opaque-access-tok-1")
    val body =
      """{"error":"federation rejected","federation_rule_id":"fdrl_secret_1","organization_id":"org_secret_1",""" +
        """"service_account_id":"svac_secret_1","workspace_id":"wrkspc_secret_1","access_token":"opaque-access-tok-1"}"""

    "redact the federation exchange's reply and the configured ids from any SDK failure" in {
      val failures = Seq(
        new com.anthropic.errors.AnthropicInvalidDataException(s"invalid token response: $body"),
        new IllegalStateException(s"token exchange failed: $body")
      )
      for failure <- failures do
        val error = AnthropicClient.mapError(failure, secrets)
        error.message should include("federation rejected")
        for secret <- echoed do (error.message should not).include(secret)
    }

    "not scrub ids too short to scrub without garbling the body, but always an API key" in {
      val short = config.withWorkloadIdentity(
        AnthropicWorkloadIdentity(Path.of("/var/run/svid.jwt"), "rule", "org", workspaceId = Some("prod"))
      )
      AnthropicClient.credentialSecrets(short) shouldBe Nil
      AnthropicClient.credentialSecrets(config.withApiKey("k").withWorkloadIdentity(None)) shouldBe Seq("k")
      val error = AnthropicClient.mapError(
        new IllegalStateException("org prod rejected"),
        AnthropicClient.credentialSecrets(short)
      )
      error.message should include("org prod rejected")
    }

    "redact an API key echoed by a failure when no workload identity is configured" in {
      val keyed = config.withApiKey("opaque-anthropic-key-1").withWorkloadIdentity(None)
      val error = AnthropicClient.mapError(
        new IllegalStateException("rejected key opaque-anthropic-key-1"),
        AnthropicClient.credentialSecrets(keyed)
      )
      (error.message should not).include("opaque-anthropic-key-1")
    }
  }
