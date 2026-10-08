package org.llm4s.llmconnect.provider

import org.llm4s.config.OpenAICompatibleModelLister
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.error.{ AuthenticationError, ConfigurationError }
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient, MockHttpClient }
import org.llm4s.llmconnect.auth.{ AccessTokenProvider, IdentitySource, TokenExchangeConfig }
import org.llm4s.llmconnect.config.OpenAICompatibleConfig
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.testkit.{ FakeTokenExchangeServer, ProviderModuleChecks, ProviderTestConfig, TestJwt }
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{ Files, Path }
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ Callable, Executors }
import scala.jdk.CollectionConverters.*

class OpenAICompatibleWorkloadIdentitySpec
    extends AnyWordSpec
    with Matchers
    with EitherValues
    with ProviderModuleChecks:

  private given ProviderRegistry = ProviderRegistry.default

  private def sectionOf(body: String): NamedProviderConfig =
    ProviderTestConfig
      .loadSection("main", s"llm4s.providers.main {\n$body\n}")
      .fold(error => fail(error.message), identity)

  private val conversation = Conversation(Seq(UserMessage("hi")))

  // On POSIX the name gets a backslash, as every Windows path has, so the HOCON escaping is exercised everywhere.
  private val svidPrefix = if java.io.File.separatorChar == '/' then "svid\\Ufile" else "svid"

  private def svidFile(jwt: String): Path =
    val f = Files.createTempFile(svidPrefix, ".jwt")
    f.toFile.deleteOnExit()
    Files.writeString(f, jwt)

  // A Windows path has backslashes, which a quoted HOCON string reads as escapes (`\U` is not one).
  private def hoconEscaped(path: Path): String = path.toString.replace("\\", "\\\\")

  private def authBlock(fake: FakeTokenExchangeServer, svid: Path): String =
    s"""provider = "openai-compatible"
       |model    = "databricks-model"
       |baseUrl  = "${fake.baseUrl}/serving-endpoints"
       |auth {
       |  identityTokenFile = "${hoconEscaped(svid)}"
       |  tokenUrl = "${fake.baseUrl}${FakeTokenExchangeServer.TokenPath}"
       |  clientId = "sp-uuid"
       |  scope    = "all-apis"
       |}""".stripMargin

  private def client(fake: FakeTokenExchangeServer, svid: Path): LLMClient =
    assertBuildsClient(OpenAICompatibleProvider, sectionOf(authBlock(fake, svid)))

  private def freshSvid: Path = svidFile(TestJwt.es256("spiffe://llm4s.test/app", "databricks"))

  "an openai-compatible section whose tokenUrl is not https" should {
    def load(tokenUrl: String) =
      ProviderTestConfig.loadProvider(
        "main",
        s"""llm4s.providers.main {
           |  provider = "openai-compatible"
           |  model    = "m"
           |  baseUrl  = "https://api.example/v1"
           |  auth { identityTokenFile = "/var/run/svid", tokenUrl = "$tokenUrl" }
           |}""".stripMargin
      )

    "be refused at configuration time, naming the key" in {
      val error = load("http://ws.example/oidc/v1/token").left.value
      error.message should include("llm4s.providers.main.auth.tokenUrl")
      error.message should include("https")
    }

    "be refused when its real host is not the loopback one it starts with" in {
      load("http://localhost@evil.example/t").isLeft shouldBe true
    }

    "be accepted over https, and over http to a loopback host" in {
      load("https://ws.example/oidc/v1/token").isRight shouldBe true
      load("http://127.0.0.1:9/t").isRight shouldBe true
    }
  }

  "an openai-compatible section with a blank auth key" should {
    "be refused, naming the auth key, for a blank tokenUrl" in {
      val error = ProviderTestConfig
        .loadProvider(
          "main",
          """llm4s.providers.main {
            |  provider = "openai-compatible"
            |  model    = "m"
            |  baseUrl  = "https://api.example/v1"
            |  auth { identityTokenFile = "/var/run/svid", tokenUrl = " " }
            |}""".stripMargin
        )
        .left
        .value
      error.message should include("auth.tokenUrl")
    }

    "treat a blank optional key as absent rather than post it empty" in FakeTokenExchangeServer.withServer { fake =>
      val c = assertBuildsClient(
        OpenAICompatibleProvider,
        sectionOf(authBlock(fake, freshSvid).replace("clientId = \"sp-uuid\"", "clientId = \" \""))
      )
      c.complete(conversation, CompletionOptions()).isRight shouldBe true
      fake.exchanges.head.keySet should not contain "client_id"
    }

    "be refused by buildConfig, naming the auth key, for a code-built section whose auth key is blank" in {
      val section = sectionOf(
        """provider = "openai-compatible"
          |model    = "m"
          |baseUrl  = "https://api.example/v1"
          |auth { identityTokenFile = "/var/run/svid", tokenUrl = "https://t.example/token" }""".stripMargin
      )
      val blanked = section.withAuth(section.auth.map(_.withExtras(Map("tokenUrl" -> " "))))
      val error   = ProviderModuleChecks.buildClient(OpenAICompatibleProvider, blanked).left.value
      error.message should include("auth.tokenUrl")
    }
  }

  "an openai-compatible section with auth and timeouts" should {
    "load as a config carrying both the token exchange and the timeouts" in {
      val config = ProviderTestConfig
        .loadProvider(
          "main",
          """llm4s.providers.main {
            |  provider = "openai-compatible"
            |  model    = "m"
            |  baseUrl  = "https://api.example/v1"
            |  timeouts { request = 45s, stream = 5m }
            |  auth { identityToken = "eyJ.x.y", tokenUrl = "https://t.example/token" }
            |}""".stripMargin
        )
        .value
        .asInstanceOf[OpenAICompatibleConfig]
      config.tokenExchange.map(_.tokenUrl) shouldBe Some("https://t.example/token")
      config.timeouts.request shouldBe Some(scala.concurrent.duration.DurationInt(45).seconds)
      config.timeouts.stream shouldBe Some(scala.concurrent.duration.DurationInt(5).minutes)
      val settings = OpenAICompatibleClient.settings(config)
      settings.timeouts shouldBe config.timeouts
      settings.credential shouldBe a[OpenAICompatibleClient.Credential.Exchange]
      config
        .withTimeouts(org.llm4s.llmconnect.config.ProviderTimeouts.default)
        .tokenExchange shouldBe config.tokenExchange
    }
  }

  "an openai-compatible section with auth" should {
    "exchange the SVID and send the access token on complete and stream" in FakeTokenExchangeServer.withServer { fake =>
      val jwt = TestJwt.es256("spiffe://llm4s.test/app", "databricks")
      val c   = client(fake, svidFile(jwt))
      c.complete(conversation, CompletionOptions()).isRight shouldBe true
      assertStreams(c)
      fake.exchanges.head("subject_token") shouldBe jwt
      fake.exchanges.head("client_id") shouldBe "sp-uuid"
      fake.exchanges.head("scope") shouldBe "all-apis"
      fake.apiAuthorizations.distinct shouldBe Seq("Bearer t1")
      fake.exchanges.size shouldBe 1
    }

    "exchange again once the token has expired" in FakeTokenExchangeServer.withServer { fake =>
      fake.setExpiresIn(0)
      val c = client(fake, freshSvid)
      c.complete(conversation, CompletionOptions()).isRight shouldBe true
      c.complete(conversation, CompletionOptions()).isRight shouldBe true
      fake.apiAuthorizations shouldBe Seq("Bearer t1", "Bearer t2")
    }

    "refresh and retry once on a 401" in FakeTokenExchangeServer.withServer { fake =>
      val c = client(fake, freshSvid)
      c.complete(conversation, CompletionOptions()).isRight shouldBe true
      fake.rejectNextApiCalls(1)
      c.complete(conversation, CompletionOptions()).isRight shouldBe true
      fake.apiAuthorizations shouldBe Seq("Bearer t1", "Bearer t1", "Bearer t2")
    }

    "not refresh or retry on a 403: the token is valid, it is the permission that is missing" in
      FakeTokenExchangeServer.withServer { fake =>
        val c = client(fake, freshSvid)
        c.complete(conversation, CompletionOptions()).isRight shouldBe true
        fake.rejectNextApiCalls(1, 403)
        c.complete(conversation, CompletionOptions()).left.value shouldBe an[AuthenticationError]
        fake.apiAuthorizations shouldBe Seq("Bearer t1", "Bearer t1")
        fake.issuedTokens shouldBe Seq("t1")
      }

    "refresh and retry once on a 401 while streaming" in FakeTokenExchangeServer.withServer { fake =>
      val c = client(fake, freshSvid)
      fake.rejectNextApiCalls(1)
      val chunks = new AtomicInteger(0)
      c.streamComplete(conversation, CompletionOptions(), _ => chunks.incrementAndGet(): Unit).isRight shouldBe true
      chunks.get should be > 0
      fake.issuedTokens shouldBe Seq("t1", "t2")
    }

    "fail with AuthenticationError after a second 401, having tried exactly twice" in FakeTokenExchangeServer
      .withServer { fake =>
        val c = client(fake, freshSvid)
        fake.rejectNextApiCalls(2)
        c.complete(conversation, CompletionOptions()).left.value shouldBe an[AuthenticationError]
        fake.apiAuthorizations.size shouldBe 2
      }

    "not reuse the retry's token on the next call when the retry was rejected too" in FakeTokenExchangeServer
      .withServer { fake =>
        val c = client(fake, freshSvid)
        fake.rejectNextApiCalls(2)
        c.complete(conversation, CompletionOptions()).left.value shouldBe an[AuthenticationError]
        c.complete(conversation, CompletionOptions()).isRight shouldBe true
        fake.apiAuthorizations shouldBe Seq("Bearer t1", "Bearer t2", "Bearer t3")
        fake.issuedTokens shouldBe Seq("t1", "t2", "t3")
      }

    "surface a rejected exchange as AuthenticationError without calling the API" in FakeTokenExchangeServer
      .withServer { fake =>
        fake.setSubjectValidator(_ => Left("wrong audience"))
        val c = client(fake, svidFile(TestJwt.es256("spiffe://llm4s.test/app", "other")))
        c.complete(conversation, CompletionOptions()).left.value shouldBe an[AuthenticationError]
        fake.apiAuthorizations shouldBe empty
      }

    "make one exchange for concurrent calls" in FakeTokenExchangeServer.withServer { fake =>
      val c    = client(fake, freshSvid)
      val pool = Executors.newVirtualThreadPerTaskExecutor()
      val calls = (1 to 10).map(_ =>
        (() => c.complete(conversation, CompletionOptions()).fold(_.toString, _ => "ok")): Callable[String]
      )
      pool.invokeAll(calls.asJava).asScala.map(_.get()).toList shouldBe List.fill(10)("ok")
      pool.shutdown()
      fake.issuedTokens shouldBe Seq("t1")
    }

    "present the rotated SVID on the next exchange" in FakeTokenExchangeServer.withServer { fake =>
      fake.setExpiresIn(0)
      val first = TestJwt.es256("spiffe://llm4s.test/app", "databricks")
      val file  = svidFile(first)
      val c     = client(fake, file)
      c.complete(conversation, CompletionOptions()).isRight shouldBe true
      val second = TestJwt.es256("spiffe://llm4s.test/app", "databricks")
      Files.writeString(file, second)
      c.complete(conversation, CompletionOptions()).isRight shouldBe true
      fake.exchanges.map(_("subject_token")) shouldBe Seq(first, second)
    }

    "exchange through the client's own HTTP client, and close it with the client" in {
      given org.llm4s.model.ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
      val http = MockHttpClient(
        Seq(
          HttpResponse(200, """{"access_token":"dbx-1","expires_in":3600}"""),
          HttpResponse(200, """{"id":"x","created":1,"model":"m","choices":[{"message":{"content":"hi"}}]}""")
        )
      )
      val config = OpenAICompatibleConfig(
        model = "m",
        baseUrl = "https://ws.example/serving-endpoints",
        tokenExchange = Some(
          TokenExchangeConfig(IdentitySource.Literal("eyJ.svid.sig"), "https://ws.example/oidc/v1/token")
        )
      )
      val c = new OpenAICompatibleClient(OpenAICompatibleClient.settings(config), OpenAICompatibleDialect.Standard) {
        override protected[provider] val httpClient: Llm4sHttpClient = http
      }
      c.complete(conversation, CompletionOptions()).isRight shouldBe true
      http.posts.map(_._1) shouldBe Seq(
        "https://ws.example/oidc/v1/token",
        "https://ws.example/serving-endpoints/chat/completions"
      )
      http.posts(1)._2("Authorization") shouldBe "Bearer dbx-1"
      http.closed shouldBe false
      c.close()
      http.closed shouldBe true
    }

    "never repeat the bearer, client id or identity token from an API error body, on complete or stream" in {
      given org.llm4s.model.ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
      val bearer                                 = "opaque-dbx-token-1"
      val clientId                               = "sp-client-5e7d"
      val subject                                = "opaque-svid-literal-42"
      val rejected = HttpResponse(
        403,
        s"""{"error":{"message":"token $bearer of $clientId (subject $subject) lacks permission"},""" +
          s""""access_token":"$bearer"}"""
      )
      for streaming <- Seq(false, true) do
        val http = MockHttpClient(
          Seq(HttpResponse(200, s"""{"access_token":"$bearer","expires_in":3600}"""), rejected)
        )
        val config = OpenAICompatibleConfig(
          model = "m",
          baseUrl = "https://ws.example/serving-endpoints",
          tokenExchange = Some(
            TokenExchangeConfig(
              IdentitySource.Literal(subject),
              "https://ws.example/oidc/v1/token",
              clientId = Some(clientId)
            )
          )
        )
        val c = new OpenAICompatibleClient(OpenAICompatibleClient.settings(config), OpenAICompatibleDialect.Standard) {
          override protected[provider] val httpClient: Llm4sHttpClient = http
        }
        val result =
          if streaming then c.streamComplete(conversation, CompletionOptions(), _ => ())
          else c.complete(conversation, CompletionOptions())
        val error = result.left.value
        error shouldBe an[AuthenticationError]
        error.message should include("lacks permission")
        for secret <- Seq(bearer, clientId, subject) do (error.message should not).include(secret)
        c.close()
    }

    "show the exchange's token URL without userinfo or query in the settings and config" in {
      val config = OpenAICompatibleConfig(
        model = "m",
        baseUrl = "https://ws.example/serving-endpoints",
        tokenExchange = Some(
          TokenExchangeConfig(IdentitySource.Literal("eyJ.svid.sig"), "https://u:pw@ws.example/t?sig=s3cr3t")
            .withClientId("sp-secret")
        )
      )
      for shown <- Seq(config.toString, OpenAICompatibleClient.settings(config).toString) do
        shown should include("https://***@ws.example/t?***")
        for secret <- Seq("pw", "s3cr3t", "sp-secret", "eyJ.svid.sig") do (shown should not).include(secret)
    }

    "list models with an exchanged token" in FakeTokenExchangeServer.withServer { fake =>
      val section = sectionOf(authBlock(fake, freshSvid))
      val models: Result[List[org.llm4s.config.DiscoveredModel]] =
        OpenAICompatibleModelLister.listModels(section, Llm4sHttpClient.create())
      models.value.map(_.name.toString) shouldBe List("fake-model")
      fake.apiAuthorizations shouldBe Seq("Bearer t1")
    }
  }

  // Every entry point that can exchange the identity token or send the access token, given a section or config
  // whose `baseUrl` (or `tokenUrl`) would carry a token in plain text. Each must refuse before any request.
  "a workload-identity section whose baseUrl is plain http to a non-loopback host" should {
    // Reaches the fake, but plain http to a host the rules do not accept as loopback: had a request gone out,
    // the fake would have recorded it.
    def insecureBaseUrl(fake: FakeTokenExchangeServer) = s"${fake.refusedBaseUrl}/serving-endpoints"

    def insecureBlock(fake: FakeTokenExchangeServer): String =
      authBlock(fake, freshSvid).replace(s"${fake.baseUrl}/serving-endpoints", insecureBaseUrl(fake))

    def insecureConfig(fake: FakeTokenExchangeServer): OpenAICompatibleConfig =
      OpenAICompatibleConfig(
        model = "m",
        baseUrl = insecureBaseUrl(fake),
        tokenExchange = Some(
          TokenExchangeConfig(
            IdentitySource.File(freshSvid),
            s"${fake.baseUrl}${FakeTokenExchangeServer.TokenPath}"
          )
        )
      )

    "load as a section, since section validation does not judge the baseUrl" in FakeTokenExchangeServer.withServer {
      fake => sectionOf(insecureBlock(fake)).baseUrl.map(_.asUrl) shouldBe Some(insecureBaseUrl(fake))
    }

    "be refused by the model lister before the token exchange" in FakeTokenExchangeServer.withServer { fake =>
      val error = OpenAICompatibleModelLister.listModels(sectionOf(insecureBlock(fake)), Llm4sHttpClient.create())
      error.left.value shouldBe a[ConfigurationError]
      error.left.value.message should (include("baseUrl").and(include("exchanged token")))
      fake.exchanges shouldBe empty
      fake.apiAuthorizations shouldBe empty
    }

    "be refused by the model lister with a plain-http tokenUrl, before the exchange" in FakeTokenExchangeServer
      .withServer { fake =>
        val section = sectionOf(
          authBlock(fake, freshSvid)
            .replace(
              s"${fake.baseUrl}${FakeTokenExchangeServer.TokenPath}",
              s"${fake.refusedBaseUrl}${FakeTokenExchangeServer.TokenPath}"
            )
        )
        OpenAICompatibleModelLister.listModels(section, Llm4sHttpClient.create()).left.value shouldBe
          a[ConfigurationError]
        fake.exchanges shouldBe empty
        fake.apiAuthorizations shouldBe empty
      }

    "be refused when building the chat client from the section (buildConfig, as LLMConnect and the testkit do)" in
      FakeTokenExchangeServer.withServer { fake =>
        val error = ProviderModuleChecks.buildClient(OpenAICompatibleProvider, sectionOf(insecureBlock(fake)))
        error.left.value.message should include("llm4s.providers.provider-testkit.baseUrl")
        fake.exchanges shouldBe empty
      }

    "be refused by LLMConnect.getClient for a config built by hand" in FakeTokenExchangeServer.withServer { fake =>
      given org.llm4s.model.ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
      org.llm4s.llmconnect.LLMConnect.getClient(insecureConfig(fake)).left.value shouldBe a[ConfigurationError]
      fake.exchanges shouldBe empty
    }

    "be refused by the OpenAICompatibleClient constructor for hand-built settings, Exchange or Dynamic" in
      FakeTokenExchangeServer.withServer { fake =>
        given org.llm4s.model.ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
        val fetched                                = new AtomicInteger(0)
        val dynamic = new AccessTokenProvider:
          def token(): Result[String]            = { fetched.incrementAndGet(); Right("t") }
          def invalidate(rejected: String): Unit = ()
        val exchange = OpenAICompatibleClient.settings(insecureConfig(fake).withBaseUrl("https://ws.example/v1"))
        val credentials = Seq(
          exchange.credential,
          OpenAICompatibleClient.Credential.Dynamic(dynamic)
        )
        for credential <- credentials do
          val error = intercept[IllegalArgumentException](
            new OpenAICompatibleClient(
              exchange.copy(baseUrl = insecureBaseUrl(fake), credential = credential),
              OpenAICompatibleDialect.standard(Nil)
            )
          )
          error.getMessage should include("baseUrl")
        // An exchange to a plain-http token endpoint is refused the same way.
        val insecureTokenUrl = OpenAICompatibleClient.Credential.Exchange(
          TokenExchangeConfig(
            IdentitySource.File(freshSvid),
            s"${fake.refusedBaseUrl}${FakeTokenExchangeServer.TokenPath}"
          )
        )
        an[IllegalArgumentException] should be thrownBy
          new OpenAICompatibleClient(
            exchange.copy(credential = insecureTokenUrl),
            OpenAICompatibleDialect.standard(Nil)
          )
        fetched.get shouldBe 0
        fake.exchanges shouldBe empty
        // A static key is the caller's own, as before: a plain-http base URL is still accepted with one.
        new OpenAICompatibleClient(
          exchange.copy(baseUrl = insecureBaseUrl(fake), credential = OpenAICompatibleClient.Credential.Static("k")),
          OpenAICompatibleDialect.standard(Nil)
        ).close()
      }
  }

  "an exchanging OpenAICompatibleConfig built without a named section" should {
    given org.llm4s.model.ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

    val exchange =
      TokenExchangeConfig(IdentitySource.Literal("eyJ.svid.sig"), "https://ws.example/oidc/v1/token")

    def fromValues(
      headers: Map[String, String] = Map.empty,
      apiKey: Option[String] = None,
      tokenExchange: TokenExchangeConfig = exchange,
      baseUrl: String = "https://ws.example/serving-endpoints"
    ) =
      OpenAICompatibleConfig.fromValues(
        model = "m",
        baseUrl = baseUrl,
        apiKey = apiKey,
        headers = headers,
        tokenExchange = Some(tokenExchange)
      )

    "be refused by fromValues with an Authorization header, in any case" in {
      for name <- Seq("Authorization", "authorization", "AUTHORIZATION") do
        val error = fromValues(headers = Map(name -> "Bearer stale")).left.value
        error shouldBe a[ConfigurationError]
        error.message should include("Authorization header")
    }

    "be accepted by fromValues with other headers" in {
      fromValues(headers = Map("X-Gateway" -> "g")).value.headers shouldBe Map("X-Gateway" -> "g")
    }

    "be refused by fromValues with an apiKey as well" in {
      fromValues(apiKey = Some("k")).left.value shouldBe a[ConfigurationError]
    }

    "be refused by fromValues with a plain-http tokenUrl to a non-loopback host" in {
      val error = fromValues(tokenExchange = exchange.withTokenUrl("http://ws.example/oidc/v1/token")).left.value
      error shouldBe a[ConfigurationError]
      error.message should include("https")
      fromValues(tokenExchange = exchange.withTokenUrl("http://127.0.0.1:9/token")).isRight shouldBe true
    }

    "be refused by fromValues with a plain-http baseUrl to a non-loopback host, which would receive the exchanged token" in {
      val error = fromValues(baseUrl = "http://ws.example/serving-endpoints").left.value
      error shouldBe a[ConfigurationError]
      error.message should (include("baseUrl").and(include("exchanged token")))
      fromValues(baseUrl = "http://localhost@evil.example/v1").isLeft shouldBe true
      fromValues(baseUrl = "http://127.0.0.1:9/v1").isRight shouldBe true
    }

    "be refused by fromValues, the client and the with* setters for a blank token, tokenUrl or optional field" in {
      val blanks = Seq(
        "tokenExchange.identityToken"     -> exchange.withIdentityToken(IdentitySource.Literal(" ")),
        "tokenExchange.identityTokenFile" -> exchange.withIdentityToken(IdentitySource.File(Path.of(""))),
        "tokenExchange.tokenUrl"          -> exchange.withTokenUrl(""),
        "tokenExchange.clientId"          -> exchange.withClientId(" "),
        "tokenExchange.scope"             -> exchange.withScope(""),
        "tokenExchange.audience"          -> exchange.withAudience(" ")
      )
      for (field, blank) <- blanks do
        withClue(field) {
          val error = fromValues(tokenExchange = blank).left.value
          error shouldBe a[ConfigurationError]
          error.asInstanceOf[ConfigurationError].missingKeys shouldBe List(field)
          OpenAICompatibleClient(fromValues().value.withTokenExchange(blank)).left.value shouldBe a[ConfigurationError]
        }
    }

    "accept any https baseUrl, since the endpoint is the user's choice" in {
      fromValues(baseUrl = "https://gateway.example/v1").isRight shouldBe true
    }

    "be refused by OpenAICompatibleClient when built with apply or the with* setters" in {
      val valid = fromValues().value
      val bad = Seq(
        valid.withHeaders(Map("authorization" -> "Bearer stale")),
        valid.withApiKey("k"),
        valid.withTokenExchange(exchange.withTokenUrl("http://ws.example/t")),
        valid.withBaseUrl("http://ws.example/v1"),
        OpenAICompatibleConfig(
          model = "m",
          baseUrl = "https://ws.example/v1",
          headers = Map("Authorization" -> "Bearer stale"),
          tokenExchange = Some(exchange)
        )
      )
      for config <- bad do OpenAICompatibleClient(config).left.value shouldBe a[ConfigurationError]
      OpenAICompatibleClient(valid).value.close()
    }

    "be refused when the section path carries an Authorization header past validation" in {
      // Section validation refuses the header; a NamedProviderConfig changed afterwards reaches buildConfig with it.
      val section = sectionOf(
        """provider = "openai-compatible"
          |model    = "m"
          |baseUrl  = "https://ws.example/v1"
          |auth { identityToken = "eyJ.svid.sig", tokenUrl = "https://ws.example/oidc/v1/token" }""".stripMargin
      ).withHeaders(Map("Authorization" -> "Bearer stale"))
      given org.llm4s.llmconnect.config.ContextWindowResolver =
        org.llm4s.llmconnect.config.ContextWindowResolver(summon[org.llm4s.model.ModelRegistryService])
      OpenAICompatibleProvider.buildConfig("main", section).left.value.message should
        (include("llm4s.providers.main.headers").and(include("Authorization header")))
      OpenAICompatibleModelLister.listModels(section, Llm4sHttpClient.create()).left.value shouldBe
        a[ConfigurationError]
    }
  }

  "an OpenAICompatibleClient with a refreshed credential" should {
    given org.llm4s.model.ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

    val dynamic = new AccessTokenProvider:
      def token(): Result[String]            = Right("t")
      def invalidate(rejected: String): Unit = ()

    def settings(credential: OpenAICompatibleClient.Credential) =
      OpenAICompatibleClient.Settings("p", "P", "m", "https://h/v1", credential, 8192, 2048)

    "refuse a dialect that sets Authorization, in any case" in {
      val credentials = Seq(
        OpenAICompatibleClient.Credential.Dynamic(dynamic),
        OpenAICompatibleClient.Credential.Exchange(
          TokenExchangeConfig(IdentitySource.Literal("eyJ.svid.sig"), "https://h/token")
        )
      )
      for
        credential <- credentials
        name       <- Seq("Authorization", "authorization")
      do
        an[IllegalArgumentException] should be thrownBy
          new OpenAICompatibleClient(settings(credential), OpenAICompatibleDialect.standard(Seq(name -> "Bearer x")))
    }

    "accept a static key beside a dialect Authorization header, as before" in {
      new OpenAICompatibleClient(
        settings(OpenAICompatibleClient.Credential.Static("k")),
        OpenAICompatibleDialect.standard(Seq("Authorization" -> "Bearer x"))
      ).close()
    }
  }
