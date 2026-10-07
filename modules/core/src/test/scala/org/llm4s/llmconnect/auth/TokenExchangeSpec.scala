package org.llm4s.llmconnect.auth

import org.llm4s.error.{ AuthenticationError, ConfigurationError, ServiceError }
import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant

class TokenExchangeSpec extends AnyWordSpec with Matchers with EitherValues with OptionValues:

  private val now   = Instant.parse("2026-10-04T12:00:00Z")
  private val clock = MutableClock(now)
  private val jwt   = "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJzcGlmZmUifQ.sig"
  private val ok    = HttpResponse(200, """{"access_token":"dbx-1","token_type":"Bearer","expires_in":3600}""")

  private def config(
    clientId: Option[String] = None,
    scope: Option[String] = None,
    audience: Option[String] = None
  ) = TokenExchangeConfig(IdentitySource.Literal(jwt), "https://ws.example/oidc/v1/token", clientId, scope, audience)

  private def form(body: String): Map[String, String] =
    body
      .split('&')
      .toSeq
      .map { pair =>
        val Array(k, v) = pair.split("=", 2)
        URLDecoder.decode(k, StandardCharsets.UTF_8) -> URLDecoder.decode(v, StandardCharsets.UTF_8)
      }
      .toMap

  "TokenExchange.rfc8693" should {
    "post the RFC 8693 form and return the access token with its expiry" in {
      val http  = MockHttpClient(Seq(ok))
      val token = TokenExchange.rfc8693(config(Some("sp-uuid"), Some("all-apis")), http, clock)().value
      token shouldBe AccessToken("dbx-1", now.plusSeconds(3600))
      http.lastUrl shouldBe Some("https://ws.example/oidc/v1/token")
      http.lastHeaders.value("Content-Type") shouldBe "application/x-www-form-urlencoded"
      form(http.lastBody.value) shouldBe Map(
        "grant_type"         -> TokenExchange.GrantType,
        "subject_token"      -> jwt,
        "subject_token_type" -> TokenExchange.JwtTokenType,
        "client_id"          -> "sp-uuid",
        "scope"              -> "all-apis"
      )
    }

    "omit optional fields that are not set" in {
      val http = MockHttpClient(Seq(ok))
      TokenExchange.rfc8693(config(), http, clock)().value
      form(http.lastBody.value).keySet shouldBe Set("grant_type", "subject_token", "subject_token_type")
    }

    "send audience when set" in {
      val http = MockHttpClient(Seq(ok))
      TokenExchange.rfc8693(config(audience = Some("aud-x")), http, clock)().value
      form(http.lastBody.value)("audience") shouldBe "aud-x"
    }

    "accept expires_in given as a string" in {
      val http = MockHttpClient(Seq(HttpResponse(200, """{"access_token":"a","expires_in":"60"}""")))
      TokenExchange.rfc8693(config(), http, clock)().value.expiresAt shouldBe now.plusSeconds(60)
    }

    "clamp an absurdly long lifetime to the documented maximum instead of overflowing" in {
      for body <- Seq(
          """{"access_token":"a","expires_in":1e30}""",
          """{"access_token":"a","expires_in":"1e30"}""",
          """{"access_token":"a","expires_in":9223372036854775807}"""
        )
      do
        val http = MockHttpClient(Seq(HttpResponse(200, body)))
        TokenExchange.rfc8693(config(), http, clock)().value.expiresAt shouldBe
          now.plusSeconds(TokenExchange.MaxLifetime.toSeconds)
    }

    "reject a lifetime that is not a finite, non-negative number, as AuthenticationError and never by throwing" in {
      for value <- Seq(""""Infinity"""", """"-Infinity"""", """"NaN"""", "-1", "-0.5", """"-60"""")
      do
        val http  = MockHttpClient(Seq(HttpResponse(200, s"""{"access_token":"a","expires_in":$value}""")))
        val error = TokenExchange.rfc8693(config(), http, clock)().left.value
        withClue(s"expires_in=$value: ")(error shouldBe an[AuthenticationError])
        error.message should include("expires_in")
    }

    "accept a zero or fractional lifetime, truncated to whole seconds" in {
      val zero = MockHttpClient(Seq(HttpResponse(200, """{"access_token":"a","expires_in":0}""")))
      TokenExchange.rfc8693(config(), zero, clock)().value.expiresAt shouldBe now
      val fraction = MockHttpClient(Seq(HttpResponse(200, """{"access_token":"a","expires_in":59.9}""")))
      TokenExchange.rfc8693(config(), fraction, clock)().value.expiresAt shouldBe now.plusSeconds(59)
    }

    "map 400, 401 and 403 from the token endpoint to AuthenticationError" in {
      for status <- Seq(400, 401, 403) do
        val http = MockHttpClient(Seq(HttpResponse(status, """{"error":"invalid_grant"}""")))
        TokenExchange.rfc8693(config(), http, clock)().left.value shouldBe an[AuthenticationError]
    }

    "map a 5xx to a retryable ServiceError" in {
      val http  = MockHttpClient(Seq(HttpResponse(503, "busy")))
      val error = TokenExchange.rfc8693(config(), http, clock)().left.value
      error shouldBe a[ServiceError]
      error.asInstanceOf[ServiceError].httpStatus shouldBe 503
    }

    "fail on a malformed body or a missing access_token" in {
      for body <- Seq(
          "not json",
          """{"expires_in":60}""",
          """{"access_token":"","expires_in":1}"""
        )
      do
        val http = MockHttpClient(Seq(HttpResponse(200, body)))
        TokenExchange.rfc8693(config(), http, clock)().left.value shouldBe an[AuthenticationError]
    }

    "never echoes the subject token in an error" in {
      val http  = MockHttpClient(Seq(HttpResponse(400, s"""{"error":"invalid_grant","assertion":"$jwt"}""")))
      val error = TokenExchange.rfc8693(config(), http, clock)().left.value
      (error.message should not).include(jwt)
    }

    "never echo the client id, an opaque subject token or a credential field in a 400/401/403 error" in {
      // Opaque values, so it is the exact-match scrub that removes them, not the general JWT pattern.
      val subject  = "opaque-subject-token-7f3a9c"
      val clientId = "sp-client-4d2e81"
      val cfg      = config(Some(clientId)).withIdentityToken(IdentitySource.Literal(subject))
      for status <- Seq(400, 401, 403) do
        val body =
          s"""{"error":"invalid_client","error_description":"client $clientId rejected subject $subject",""" +
            s""""access_token":"leaked-access-9b1c","refresh_token":"leaked-refresh-77aa",""" +
            s""""echo":"grant_type=x&subject_token=${java.net.URLEncoder.encode(subject, StandardCharsets.UTF_8)}"}"""
        val http    = MockHttpClient(Seq(HttpResponse(status, body)))
        val message = TokenExchange.rfc8693(cfg, http, clock)().left.value.message
        message should include(s"HTTP $status")
        message should include("invalid_client")
        for secret <- Seq(subject, clientId, "leaked-access-9b1c", "leaked-refresh-77aa") do
          (message should not).include(secret)
    }

    "never echo a credential value repeated outside its field, or the client id, in a 5xx error" in {
      val clientId = "sp-client-4d2e81"
      val body     = s"""{"message":"token leaked-access-9b1c for $clientId","access_token":"leaked-access-9b1c"}"""
      val http     = MockHttpClient(Seq(HttpResponse(503, body)))
      val message  = TokenExchange.rfc8693(config(Some(clientId)), http, clock)().left.value.message
      message should include("token")
      for secret <- Seq(clientId, "leaked-access-9b1c") do (message should not).include(secret)
    }

    "truncate a long error body" in {
      val http    = MockHttpClient(Seq(HttpResponse(400, "x" * 10000)))
      val message = TokenExchange.rfc8693(config(), http, clock)().left.value.message
      message.length should be < 1000
      message should include("truncated")
    }

    "fail without calling the endpoint when the identity token is missing" in {
      val http = MockHttpClient(Seq(ok))
      val cfg  = config().withIdentityToken(IdentitySource.File(java.nio.file.Path.of("/no/such/svid")))
      TokenExchange.rfc8693(cfg, http, clock)().left.value shouldBe an[AuthenticationError]
      http.postCallCount shouldBe 0
    }

    "refuse a blank identity token, tokenUrl, clientId, scope or audience without calling the endpoint" in {
      val blanks = Seq(
        "identityToken"     -> config().withIdentityToken(IdentitySource.Literal("  ")),
        "identityTokenFile" -> config().withIdentityToken(IdentitySource.File(java.nio.file.Path.of(""))),
        "tokenUrl"          -> config().withTokenUrl(" "),
        "clientId"          -> config(clientId = Some("")),
        "scope"             -> config(scope = Some(" ")),
        "audience"          -> config(audience = Some(""))
      )
      for (field, cfg) <- blanks do
        val http  = MockHttpClient(Seq(ok))
        val error = TokenExchange.rfc8693(cfg, http, clock)().left.value
        error shouldBe a[ConfigurationError]
        error.asInstanceOf[ConfigurationError].missingKeys shouldBe List(field)
        error.message should include(field)
        http.postCallCount shouldBe 0
        TokenExchangeConfig.validate(cfg).isLeft shouldBe true
      TokenExchangeConfig.validate(config(Some("c"), Some("s"), Some("a"))).isRight shouldBe true
    }

    "keep a literal identity token out of TokenExchangeConfig.toString" in {
      (config().toString should not).include(jwt)
    }

    "take the expiry from the access token's exp claim when expires_in is absent and the token is a JWT" in {
      val exp     = now.plusSeconds(900).getEpochSecond
      val payload = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(s"""{"exp":$exp}""".getBytes)
      val access  = s"eyJhbGciOiJSUzI1NiJ9.$payload.sig"
      val http    = MockHttpClient(Seq(HttpResponse(200, s"""{"access_token":"$access"}""")))
      TokenExchange.rfc8693(config(), http, clock)().value shouldBe AccessToken(access, now.plusSeconds(900))
    }

    "clamp a JWT exp claim to the documented maximum" in {
      val payload = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString("""{"exp":1e300}""".getBytes)
      val http    = MockHttpClient(Seq(HttpResponse(200, s"""{"access_token":"h.$payload.s"}""")))
      TokenExchange.rfc8693(config(), http, clock)().value.expiresAt shouldBe
        now.plusSeconds(TokenExchange.MaxLifetime.toSeconds)
    }

    "assume the default lifetime when expires_in is absent and the token is opaque" in {
      for access <- Seq("opaque-token", "a.not-base64!.c", "a.e30.c") do
        val http = MockHttpClient(Seq(HttpResponse(200, s"""{"access_token":"$access"}""")))
        TokenExchange.rfc8693(config(), http, clock)().value.expiresAt shouldBe
          now.plusSeconds(TokenExchange.DefaultLifetime.toSeconds)
    }

    "keep the token URL's userinfo and query, and the client id, out of TokenExchangeConfig.toString" in {
      val cfg = TokenExchangeConfig(
        IdentitySource.Literal(jwt),
        "https://user:hunter2@ws.example:8443/oidc/v1/token?sig=s3cr3t#frag",
        clientId = Some("sp-uuid-secret"),
        scope = Some("all-apis")
      )
      val shown = cfg.toString
      shown should include("https://***@ws.example:8443/oidc/v1/token?***#***")
      shown should include("scope=Some(all-apis)")
      for secret <- Seq("hunter2", "user:", "s3cr3t", "frag", "sp-uuid-secret") do (shown should not).include(secret)
    }

    "build a config with the with* setters" in {
      val cfg = config()
        .withTokenUrl("https://other.example/t")
        .withClientId("c")
        .withScope("s")
        .withAudience(Some("a"))
      (cfg.tokenUrl, cfg.clientId, cfg.scope, cfg.audience) shouldBe
        ("https://other.example/t", Some("c"), Some("s"), Some("a"))
      cfg.withClientId(None).clientId shouldBe None
    }
  }

  "TokenExchange's token URL" should {
    def post(url: String) =
      val http = MockHttpClient(Seq(ok))
      val cfg  = config().withTokenUrl(url)
      (TokenExchange.rfc8693(cfg, http, clock)(), http.postCallCount)

    "be https, or http only for a loopback host" in {
      for url <- Seq(
          "https://ws.example/oidc/v1/token",
          "HTTPS://ws.example/oidc/v1/token",
          "http://localhost/t",
          "http://LOCALHOST:8080/t",
          "http://127.0.0.1:9999/t",
          "http://127.1.2.3/t",
          "http://[::1]:9/t"
        )
      do
        val (result, calls) = post(url)
        withClue(url)(result.isRight shouldBe true)
        calls shouldBe 1
    }

    "refuse to send the identity token anywhere else, before any request is made" in {
      for url <- Seq(
          "http://ws.example/oidc/v1/token",
          "HTTP://evil.example/t",
          "http://localhost@evil.example/t", // the real host is evil.example
          "http://127.0.0.1@evil.example/t",
          "http://127.0.0.1.evil.example/t",
          "http://localhost.evil.example/t",
          "http://128.0.0.1/t",
          "http://127.0.0.256/t",
          "https:///nohost",
          "ftp://ws.example/t",
          "ws.example/t",
          "not a url",
          ""
        )
      do
        val (result, calls) = post(url)
        withClue(url)(result.left.value shouldBe a[ConfigurationError])
        calls shouldBe 0
    }

    "never appear in the refusal, since a URL can carry credentials" in {
      val (result, _) = post("http://user:hunter2@evil.example/t")
      (result.left.value.message should not).include("hunter2")
    }
  }

  "TokenExchange.requireTrustedHost" should {
    val trusted            = Set("api.vendor.example", "eu.api.vendor.example")
    def check(url: String) = TokenExchange.requireTrustedHost(url, "baseUrl", "Vendor", trusted)

    "accept https to an allow-listed host, whatever its case, port or path" in {
      for url <- Seq(
          "https://api.vendor.example/v1",
          "https://eu.api.vendor.example/v1",
          "HTTPS://API.Vendor.Example/v1",
          "https://api.vendor.example:443/v1",
          "  https://api.vendor.example  "
        )
      do withClue(url)(check(url) shouldBe Right(()))
    }

    "accept a loopback host over http or https, for test servers" in {
      for url <- Seq("http://127.0.0.1:9/v1", "http://localhost:9", "https://localhost:9", "http://[::1]:9/v1") do
        withClue(url)(check(url) shouldBe Right(()))
    }

    "refuse every other host, lookalike, scheme or userinfo, without echoing the URL" in {
      for url <- Seq(
          "https://attacker.example/v1",
          "http://api.vendor.example/v1",
          "ftp://api.vendor.example/v1",
          "https://api.vendor.example.evil.example/v1",
          "https://evilapi.vendor.example/v1",
          "https://us.api.vendor.example/v1",
          "https://vendor.example/v1",
          "https://api.vendor.example./v1",
          "https://user:s3cr3t@api.vendor.example/v1",
          "https://api.vendor.example@attacker.example/v1",
          "http://localhost@attacker.example/v1",
          "https://api.vendor.example\\@attacker.example/v1",
          "https://10.0.0.1/v1",
          "api.vendor.example/v1",
          "https:///v1",
          ""
        )
      do
        withClue(url) {
          val error = check(url).left.value
          error shouldBe a[ConfigurationError]
          error.message should (include("baseUrl").and(include("api.vendor.example")).and(include("Vendor")))
          (error.message should not).include("s3cr3t")
          (error.message should not).include("attacker")
        }
    }
  }

  "TokenExchange.provider" should {
    "cache the exchanged token between calls" in {
      val http     = MockHttpClient(Seq(ok))
      val provider = TokenExchange.provider(config(), http)
      provider.token() shouldBe Right("dbx-1")
      provider.token() shouldBe Right("dbx-1")
      http.postCallCount shouldBe 1
    }
  }
