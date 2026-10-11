package org.llm4s.llmconnect.provider

import com.openai.auth.SubjectTokenType
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.error.{ AuthenticationError, ConfigurationError }
import org.llm4s.llmconnect.auth.IdentitySource
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig, OpenAIWorkloadIdentity }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.testkit.{ FakeTokenExchangeServer, ProviderModuleChecks, ProviderTestConfig }
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{ Files, Path }
import java.util.concurrent.CompletionException
import scala.util.Try

class OpenAIWorkloadIdentitySpec
    extends AnyWordSpec
    with Matchers
    with EitherValues
    with OptionValues
    with ProviderModuleChecks:

  private given ProviderRegistry = ProviderRegistry.default

  private def sectionResult(body: String): Result[NamedProviderConfig] =
    ProviderTestConfig.loadSection("main", s"llm4s.providers.main {\n$body\n}")

  private def sectionOf(body: String): NamedProviderConfig =
    sectionResult(body).fold(error => fail(error.message), identity)

  private val section =
    """provider = "openai"
      |model = "gpt-4o-mini"
      |auth { identityTokenFile = "/var/run/svid", identityProviderId = "idp_1", serviceAccountId = "sa_1", clientId = "c_1" }""".stripMargin

  "an openai section with auth" should {
    "build an OpenAIConfig carrying the workload identity and no key" in {
      val config = ProviderTestConfig.loadProvider("main", s"llm4s.providers.main {\n$section\n}").value
      val openai = config.asInstanceOf[OpenAIConfig]
      openai.apiKey shouldBe ""
      openai.workloadIdentity.value shouldBe OpenAIWorkloadIdentity(
        IdentitySource.File(Path.of("/var/run/svid").toAbsolutePath),
        "idp_1",
        "sa_1",
        Some("c_1")
      )
      openai.toString should include("workloadIdentity=Some(OpenAIWorkloadIdentity(")
      for id <- Seq("idp_1", "sa_1", "c_1") do (openai.toString should not).include(id)
    }

    "build a client" in {
      assertBuildsClient(OpenAIProvider, sectionOf(section))
    }

    "map to the SDK's WorkloadIdentity with a JWT subject token read from the file" in {
      val file = Files.createTempFile("svid", ".jwt")
      Files.writeString(file, "eyJ.svid.sig\n")
      val sdk = OpenAIClientTransport.sdkWorkloadIdentity(
        OpenAIWorkloadIdentity(IdentitySource.File(file), "idp_1", "sa_1", Some("c_1"))
      )
      sdk.identityProviderId() shouldBe "idp_1"
      sdk.serviceAccountId() shouldBe "sa_1"
      sdk.clientId() shouldBe "c_1"
      sdk.provider().tokenType() shouldBe SubjectTokenType.JWT
      sdk.provider().getToken(null, null) shouldBe "eyJ.svid.sig"
    }

    "reread the file on each call, so a rotated SVID is picked up" in {
      val file = Files.createTempFile("svid", ".jwt")
      Files.writeString(file, "first")
      val provider = OpenAIClientTransport
        .sdkWorkloadIdentity(OpenAIWorkloadIdentity(IdentitySource.File(file), "i", "s"))
        .provider()
      provider.getToken(null, null) shouldBe "first"
      Files.writeString(file, "second")
      provider.getToken(null, null) shouldBe "second"
    }

    "fail a request, without touching the network, when the identity token file is missing" in {
      val missing = Files.createTempFile("svid", ".jwt")
      Files.delete(missing)
      // Forward slashes: a Windows path's backslashes would be read as escapes inside the quoted HOCON string.
      val body  = section.replace("/var/run/svid", missing.toString.replace('\\', '/'))
      val c     = assertBuildsClient(OpenAIProvider, sectionOf(body))
      val error = c.complete(Conversation(Seq(UserMessage("hi"))), CompletionOptions()).left.value
      error shouldBe an[AuthenticationError]
      error.message should include("workload-identity")
    }

    "keep a missing identity token an AuthenticationError through the SDK's async path and its wrapping" in {
      val missing = Files.createTempFile("svid", ".jwt")
      Files.delete(missing)
      val provider = OpenAIClientTransport
        .sdkWorkloadIdentity(OpenAIWorkloadIdentity(IdentitySource.File(missing), "i", "s"))
        .provider()
      val failure = Try(provider.getTokenAsync(null, null).join()).failed.get
      failure shouldBe a[CompletionException]
      OpenAIClient.mapError(new IllegalStateException("token refresh failed", failure), "openai") shouldBe
        an[AuthenticationError]
    }

    "redact the token exchange's reply, and the configured ids, from an SDK error" in {
      val config = OpenAIConfig(
        apiKey = "",
        model = "gpt-4o-mini",
        organization = None,
        baseUrl = "https://api.openai.com/v1",
        contextWindow = 8192,
        reserveCompletion = 1024,
        workloadIdentity = Some(
          OpenAIWorkloadIdentity(IdentitySource.Literal("opaque-svid-literal-7"), "idp_secret_1", "sa_secret_1")
            .withClientId("client_secret_id_1")
        )
      )
      val secrets = OpenAIClient.credentialSecrets(config)
      val sdkMessages = Seq(
        // What the SDK's exchange reports for a reply without an access token.
        "Token exchange response missing 'access_token' field. Response: " +
          """{"error":"bad","client_id":"client_secret_id_1","service_account_id":"sa_secret_1",""" +
          """"identity_provider_id":"idp_secret_1","subject_token":"opaque-svid-literal-7",""" +
          """"refresh_token":"opaque-refresh-token-1"}"""
      )
      for message <- sdkMessages do
        val error = OpenAIClient.mapError(new com.openai.errors.OpenAIInvalidDataException(message), "openai", secrets)
        error.message should include("Token exchange response")
        for secret <- Seq(
            "client_secret_id_1",
            "sa_secret_1",
            "idp_secret_1",
            "opaque-svid-literal-7",
            "opaque-refresh-token-1"
          )
        do (error.message should not).include(secret)
    }

    "scrub a short literal identity token, but not short ids that would garble every error body" in {
      val config = OpenAIConfig(
        apiKey = "",
        model = "gpt-4o-mini",
        organization = None,
        baseUrl = "https://api.openai.com/v1",
        contextWindow = 8192,
        reserveCompletion = 1024,
        workloadIdentity = Some(
          OpenAIWorkloadIdentity(IdentitySource.Literal("tok"), "idp", "prod").withClientId("app")
        )
      )
      OpenAIClient.credentialSecrets(config) shouldBe Seq("tok")
      val error = OpenAIClient.mapError(
        new com.openai.errors.OpenAIInvalidDataException("app in prod rejected tok"),
        "openai",
        OpenAIClient.credentialSecrets(config)
      )
      error.message should include("app in prod rejected")
      (error.message should not).include("tok")
    }

    "read the identity token for the async path off the common pool" in {
      val file = Files.createTempFile("svid", ".jwt")
      Files.writeString(file, "eyJ.svid.sig")
      val provider = OpenAIClientTransport
        .sdkWorkloadIdentity(OpenAIWorkloadIdentity(IdentitySource.File(file), "i", "s"))
        .provider()
      provider.getTokenAsync(null, null).join() shouldBe "eyJ.svid.sig"
    }

    "refuse to list models, naming the reason, rather than fail for a missing apiKey" in {
      val error = org.llm4s.config.OpenAIModelLister
        .listModels(sectionOf(section), org.llm4s.http.Llm4sHttpClient.create())
        .left
        .value
      error shouldBe a[ConfigurationError]
      error.message should include("model listing is not supported with workload identity auth for openai")
    }

    // `refusedBaseUrl` reaches the fake, whose OpenAI-format chat endpoint records every Authorization it is
    // sent, so a refusal that came after any API call would show there. (The SDK exchanges the identity token
    // with OpenAI's own token endpoint, not the baseUrl; the baseUrl receives the exchanged token.)
    "be refused on every entry point, before any request, for a baseUrl that is not an OpenAI API host" in
      FakeTokenExchangeServer.withServer { fake =>
        given org.llm4s.model.ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
        val baseUrl                                = s"${fake.refusedBaseUrl}/v1"
        val body                                   = s"$section\nbaseUrl = \"$baseUrl\""
        val named                                  = sectionOf(body)
        // Chat from a section: buildConfig, as Llm4sConfig.provider, LLMConnect and the testkit use.
        ProviderModuleChecks.buildClient(OpenAIProvider, named).left.value shouldBe a[ConfigurationError]
        ProviderTestConfig.loadProvider("main", s"llm4s.providers.main {\n$body\n}").left.value shouldBe
          a[ConfigurationError]
        // Chat from a config built by hand: the companion apply, LLMConnect and the constructor.
        val byHand = OpenAIConfig(
          "",
          "gpt-4o-mini",
          None,
          baseUrl,
          128000,
          4096,
          workloadIdentity = Some(OpenAIWorkloadIdentity(IdentitySource.Literal("eyJ.svid.sig"), "idp_1", "sa_1"))
        )
        OpenAIClient(byHand).left.value shouldBe a[ConfigurationError]
        org.llm4s.llmconnect.LLMConnect.getClient(byHand).left.value shouldBe a[ConfigurationError]
        an[IllegalArgumentException] should be thrownBy new OpenAIClient(
          byHand,
          org.llm4s.metrics.MetricsCollector.noop
        )
        // Model listing refuses workload identity outright.
        org.llm4s.config.OpenAIModelLister
          .listModels(named, org.llm4s.http.Llm4sHttpClient.create())
          .left
          .value shouldBe a[ConfigurationError]
        fake.exchanges shouldBe empty
        fake.apiAuthorizations shouldBe empty
      }

    "reject a missing serviceAccountId" in {
      sectionResult(section.replace("serviceAccountId = \"sa_1\", ", "")).isLeft shouldBe true
    }

    "reject a blank identityProviderId or serviceAccountId, naming the auth key" in {
      for key <- Seq("identityProviderId", "serviceAccountId") do
        val blanked = section.replaceAll(s"""$key = "[^"]*"""", s"""$key = " """")
        blanked should not be section
        sectionResult(blanked).left.value.message should include(s"auth.$key")
    }

    "reject neither apiKey nor auth as before" in {
      sectionResult(
        """provider = "openai"
                      |model = "m"
                      |apiKey = "sk-x"
                      |auth { identityTokenFile = "/s", identityProviderId = "i", serviceAccountId = "s" }""".stripMargin
      ).isLeft shouldBe true
    }
  }

  "auth on azure or requesty" should {
    "be rejected" in {
      for provider <- Seq("azure", "requesty") do
        sectionResult(
          s"""provider = "$provider"
             |model = "m"
             |auth { identityTokenFile = "/s" }""".stripMargin
        ).isLeft shouldBe true
    }
  }

  "an OpenAIConfig with workload identity built without a named section" should {
    given org.llm4s.model.ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
    given ContextWindowResolver                = ContextWindowResolver(summon[org.llm4s.model.ModelRegistryService])

    val identity = OpenAIWorkloadIdentity(IdentitySource.File(Path.of("/var/run/svid")), "idp_1", "sa_1")

    def fromValues(
      apiKey: String = "",
      baseUrl: String = OpenAIProvider.DEFAULT_BASE_URL,
      providerId: Option[org.llm4s.types.ProviderModelTypes.ProviderId] = None
    ) =
      OpenAIConfig.fromValues("gpt-4o-mini", apiKey, None, baseUrl, providerId, Some(identity))

    "be accepted by fromValues for openai, with no key" in {
      fromValues().value.workloadIdentity.value shouldBe identity
    }

    "keep the workload identity and the timeouts independently" in {
      import scala.concurrent.duration.DurationInt
      val timeouts = org.llm4s.llmconnect.config.ProviderTimeouts(Some(45.seconds), Some(5.minutes))
      val config   = fromValues().value.withTimeouts(timeouts)
      config.timeouts shouldBe timeouts
      config.workloadIdentity.value shouldBe identity
      config.withWorkloadIdentity(None).timeouts shouldBe timeouts
      OpenAIConfig.validate(config).isRight shouldBe true
    }

    "be refused by fromValues with an apiKey as well" in {
      fromValues(apiKey = "sk-x").left.value shouldBe a[ConfigurationError]
    }

    "be refused by fromValues for a config belonging to another provider, which would receive the OpenAI token" in {
      val refused = Seq(
        fromValues(providerId = Some(org.llm4s.types.ProviderModelTypes.ProviderId("requesty"))),
        fromValues(baseUrl = "https://openrouter.ai/api/v1")
      )
      for result <- refused do
        val error = result.left.value
        error shouldBe a[ConfigurationError]
        error.message should include("provider openai only")
    }

    "be accepted by fromValues for OpenAI's API hosts, including the data-residency regions" in {
      for url <- Seq(
          "https://api.openai.com/v1",
          "https://us.api.openai.com/v1",
          "https://eu.api.openai.com/v1",
          "https://ae.api.openai.com/v1",
          "HTTPS://API.OPENAI.COM/v1",
          "http://127.0.0.1:9/v1",
          "http://localhost:9/v1"
        )
      do withClue(url)(fromValues(baseUrl = url).value.baseUrl shouldBe url)
    }

    "be refused by fromValues for a baseUrl that is not an OpenAI API host, though the config still reports openai" in {
      for url <- Seq(
          "https://attacker.example/v1",
          "http://attacker.example/v1",
          "http://api.openai.com/v1",
          "https://api.openai.com.evil.example/v1",
          "https://evilapi.openai.com/v1",
          "https://openai.com/v1",
          "https://api.openai.com./v1",
          "https://u:pw@api.openai.com/v1",
          "https://api.openai.com@attacker.example/v1",
          "http://localhost@attacker.example/v1"
        )
      do
        withClue(url) {
          OpenAIConfig(
            "",
            "gpt-4o-mini",
            None,
            url,
            8192,
            4096,
            None,
            Some(identity)
          ).providerId.asString shouldBe "openai"
          val error = fromValues(baseUrl = url).left.value
          error shouldBe a[ConfigurationError]
          error.message should (include("baseUrl").and(include("api.openai.com")))
        }
    }

    "leave a custom baseUrl alone with an apiKey" in {
      OpenAIConfig.fromValues("gpt-4o-mini", "sk-x", None, "http://proxy.example/v1").isRight shouldBe true
    }

    "be refused by OpenAIClient and OpenRouterClient when built with apply or changed with a with* setter" in {
      val valid = fromValues().value
      val bad = Seq(
        valid.withApiKey("sk-x"),
        valid.withExplicitProviderId(Some(org.llm4s.types.ProviderModelTypes.ProviderId("requesty"))),
        valid.withBaseUrl("https://openrouter.ai/api/v1"),
        valid.withBaseUrl("https://attacker.example/v1"),
        valid.withBaseUrl("http://api.openai.com/v1")
      )
      for config <- bad do
        OpenAIClient(config).left.value shouldBe a[ConfigurationError]
        an[IllegalArgumentException] should be thrownBy new OpenAIClient(
          config,
          org.llm4s.metrics.MetricsCollector.noop
        )
      OpenRouterClient(valid.withBaseUrl("https://openrouter.ai/api/v1")).left.value shouldBe a[ConfigurationError]
      OpenAIClient(valid).value.close()
    }

    "be refused by OpenRouterClient even when OpenAIConfig.validate accepts it, rather than send an empty bearer" in {
      val valid = fromValues().value
      OpenRouterClient(valid).left.value.message should include("workloadIdentity")
      an[IllegalArgumentException] should be thrownBy new OpenRouterClient(valid)
    }

    "be refused by fromValues, OpenAIClient and the with* setters for a blank required id, token or clientId" in {
      val blanks = Seq(
        "workloadIdentity.identityProviderId" -> identity.withIdentityProviderId(" "),
        "workloadIdentity.serviceAccountId"   -> identity.withServiceAccountId(""),
        "workloadIdentity.clientId"           -> identity.withClientId(" "),
        "workloadIdentity.identityToken"      -> identity.withIdentityToken(IdentitySource.Literal("")),
        "workloadIdentity.identityTokenFile"  -> identity.withIdentityToken(IdentitySource.File(Path.of("")))
      )
      for (field, blank) <- blanks do
        withClue(field) {
          val error = OpenAIConfig
            .fromValues("gpt-4o-mini", "", None, OpenAIProvider.DEFAULT_BASE_URL, None, Some(blank))
            .left
            .value
          error shouldBe a[ConfigurationError]
          error.asInstanceOf[ConfigurationError].missingKeys shouldBe List(field)
          error.message should include(field)
          OpenAIClient(fromValues().value.withWorkloadIdentity(Some(blank))).left.value shouldBe a[
            ConfigurationError
          ]
        }
    }

    "leave OpenAIClient refusing a blank apiKey once the identity is removed, rather than send an empty bearer" in {
      val keyless = fromValues().value.withWorkloadIdentity(None)
      Seq("", "   ").foreach { key =>
        val error = OpenAIClient(keyless.withApiKey(key)).left.value
        error shouldBe a[ConfigurationError]
        error.message should include("apiKey")
        an[IllegalArgumentException] should be thrownBy new OpenAIClient(
          keyless.withApiKey(key),
          org.llm4s.metrics.MetricsCollector.noop
        )
      }
    }

    "apply the same rules to the Java and Kotlin path: the short apply and withWorkloadIdentity" in {
      // the short apply with a blank key and no identity would send an empty bearer
      Seq("", "  ").foreach { key =>
        OpenAIClient(OpenAIConfig(key, "gpt-4o-mini")).left.value.message should include("apiKey")
      }
      // the short apply's default base URL is an OpenAI host, so a keyless config with an identity is valid
      val federated = OpenAIConfig("", "gpt-4o-mini").withWorkloadIdentity(identity)
      OpenAIConfig.validate(federated) shouldBe Right(federated)
      OpenAIClient(federated).value.close()
      val bad = Seq(
        OpenAIConfig("sk-x", "gpt-4o-mini").withWorkloadIdentity(identity),
        OpenAIConfig("sk-x", "gpt-4o-mini").withWorkloadIdentity(Some(identity)),
        federated.withApiKey("sk-x"),
        federated.withBaseUrl("https://proxy.example/v1"),
        federated.withExplicitProviderId(org.llm4s.types.ProviderModelTypes.ProviderId("requesty")),
        federated.withExplicitProviderId(Some(org.llm4s.types.ProviderModelTypes.ProviderId("openrouter"))),
        federated.withWorkloadIdentity(identity.withServiceAccountId(" ")),
        federated.withWorkloadIdentity(None)
      )
      for config <- bad do OpenAIClient(config).left.value shouldBe a[ConfigurationError]
    }
  }
