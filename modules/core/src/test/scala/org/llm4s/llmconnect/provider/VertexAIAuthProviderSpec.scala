package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, LLMError, NetworkError }
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient }
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import VertexAIAuthProvider._

/**
 * Unit tests for [[VertexAIAuthProvider]].
 *
 * All three credential paths are tested by injecting a mock HTTP client and
 * controlling the `envReader` / `fileReader` to simulate different environments
 * without touching the filesystem or real OAuth2 endpoints.
 */
class VertexAIAuthProviderSpec extends AnyFlatSpec with Matchers with MockFactory {

  private val tokenResponseBody = """{"access_token":"ya29.test-token","expires_in":3600}"""

  "VertexAIAuthProvider.getAccessToken()" should "return token from GOOGLE_ACCESS_TOKEN env var" in {
    val mockHttp = stub[Llm4sHttpClient]
    val provider = new VertexAIAuthProvider(
      credentialFilePath = None,
      httpClient = mockHttp,
      envReader = {
        case "GOOGLE_ACCESS_TOKEN" => Some("direct-token-from-env")
        case _                     => None
      }
    )

    val result = provider.getAccessToken()

    result.isRight shouldBe true
    result.toOption.get shouldBe "direct-token-from-env"
  }

  it should "cache the token and not call HTTP again" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.get _).when(*, *, *, *).returns(HttpResponse(200, tokenResponseBody, Map.empty))

    val provider = new VertexAIAuthProvider(
      credentialFilePath = None,
      httpClient = mockHttp,
      envReader = _ => None
    )

    val r1 = provider.getAccessToken()
    val r2 = provider.getAccessToken()

    r1.isRight shouldBe true
    r2.isRight shouldBe true
    r1.toOption.get shouldBe r2.toOption.get
  }

  it should "return AuthenticationError on metadata server non-200" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.get _).when(*, *, *, *).returns(HttpResponse(404, "Not Found", Map.empty))

    val provider = new VertexAIAuthProvider(
      credentialFilePath = None,
      httpClient = mockHttp,
      envReader = _ => None
    )

    val result = provider.getAccessToken()

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[AuthenticationError]
  }

  it should "fetch token from authorized_user credential file via refresh flow" in {
    val credFileContent =
      """{
        |  "type": "authorized_user",
        |  "client_id": "test-client-id",
        |  "client_secret": "test-secret",
        |  "refresh_token": "test-refresh-token"
        |}""".stripMargin

    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(HttpResponse(200, tokenResponseBody, Map.empty))

    val provider = new VertexAIAuthProvider(
      credentialFilePath = Some("/fake/path/creds.json"),
      httpClient = mockHttp,
      envReader = _ => None,
      fileReader = _ => Right(credFileContent)
    )

    val result = provider.getAccessToken()

    result.isRight shouldBe true
    result.toOption.get shouldBe "ya29.test-token"
  }

  it should "return AuthenticationError when refresh token endpoint returns non-200" in {
    val credFileContent =
      """{
        |  "type": "authorized_user",
        |  "client_id": "test-client-id",
        |  "client_secret": "test-secret",
        |  "refresh_token": "test-refresh-token"
        |}""".stripMargin

    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(HttpResponse(401, "Unauthorized", Map.empty))

    val provider = new VertexAIAuthProvider(
      credentialFilePath = Some("/fake/path/creds.json"),
      httpClient = mockHttp,
      envReader = _ => None,
      fileReader = _ => Right(credFileContent)
    )

    val result = provider.getAccessToken()

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[AuthenticationError]
    result.left.toOption.get.message should include("Token refresh failed")
  }

  it should "return AuthenticationError for unsupported credential type" in {
    val credFileContent = """{"type": "external_account"}"""
    val mockHttp        = stub[Llm4sHttpClient]

    val provider = new VertexAIAuthProvider(
      credentialFilePath = Some("/fake/path/creds.json"),
      httpClient = mockHttp,
      envReader = _ => None,
      fileReader = _ => Right(credFileContent)
    )

    val result = provider.getAccessToken()

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[AuthenticationError]
    result.left.toOption.get.message should include("Unsupported credential type")
  }

  it should "return error for invalid JSON in credential file" in {
    val mockHttp = stub[Llm4sHttpClient]

    val provider = new VertexAIAuthProvider(
      credentialFilePath = Some("/fake/path/creds.json"),
      httpClient = mockHttp,
      envReader = _ => None,
      fileReader = _ => Right("{not valid json}")
    )

    val result = provider.getAccessToken()

    result.isLeft shouldBe true
  }

  it should "fall back to metadata server when no credentials are configured" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.get _).when(*, *, *, *).returns(HttpResponse(200, tokenResponseBody, Map.empty))

    val provider = new VertexAIAuthProvider(
      credentialFilePath = None,
      httpClient = mockHttp,
      envReader = _ => None
    )

    val result = provider.getAccessToken()

    result.isRight shouldBe true
    result.toOption.get shouldBe "ya29.test-token"
  }

  it should "return AuthenticationError when metadata server is not available" in {
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.get _).when(*, *, *, *).returns(HttpResponse(503, "Service Unavailable", Map.empty))

    val provider = new VertexAIAuthProvider(
      credentialFilePath = None,
      httpClient = mockHttp,
      envReader = _ => None
    )

    val result = provider.getAccessToken()

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[AuthenticationError]
  }

  it should "return actionable no-credentials guidance when the metadata host does not resolve" in {
    // Off-GCP the metadata host does not resolve, so the HTTP call throws UnknownHostException.
    // That signals "not on GCP" → the user should get the helpful no-credentials message.
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.get _).when(*, *, *, *).throws(new java.net.UnknownHostException("metadata.google.internal"))

    val provider = new VertexAIAuthProvider(
      credentialFilePath = None,
      httpClient = mockHttp,
      envReader = _ => None
    )

    val result = provider.getAccessToken()

    result.isLeft shouldBe true
    val err = result.left.toOption.get
    err shouldBe a[AuthenticationError]
    err.message should include("No credentials found")
  }

  it should "preserve a transient metadata-server failure as a recoverable error" in {
    // On GCE/GKE the host resolves but may momentarily time out; that must stay retryable
    // (RecoverableError) rather than being reported as missing credentials.
    val mockHttp = stub[Llm4sHttpClient]
    (mockHttp.get _).when(*, *, *, *).throws(new java.net.SocketTimeoutException("metadata timeout"))

    val provider = new VertexAIAuthProvider(
      credentialFilePath = None,
      httpClient = mockHttp,
      envReader = _ => None
    )

    val result = provider.getAccessToken()

    result.isLeft shouldBe true
    val err = result.left.toOption.get
    err shouldBe a[NetworkError]
    LLMError.isRecoverable(err) shouldBe true
  }

  "CachedToken" should "not be expired when expiresAtMillis is in the future" in {
    val future = System.currentTimeMillis() + 60000
    val token  = CachedToken("tok", future)

    token.isExpired shouldBe false
  }

  it should "be expired when expiresAtMillis is in the past" in {
    val past  = System.currentTimeMillis() - 1
    val token = CachedToken("tok", past)

    token.isExpired shouldBe true
  }

  it should "never expire when expiresAtMillis is Long.MaxValue" in {
    val token = CachedToken("tok", Long.MaxValue)

    token.isExpired shouldBe false
  }
}
