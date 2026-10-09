package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, ConfigurationError, NetworkError, RateLimitError, ValidationError }
import org.llm4s.http.{ FailingHttpClient, HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.config.WatsonXConfig
import org.llm4s.llmconnect.model.*
import org.llm4s.model.ModelRegistryService
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

class WatsonXClientSpec extends AnyFunSuite with Matchers:
  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val config = WatsonXConfig(
    apiKey = "secret key&=",
    projectId = "project-1",
    spaceId = None,
    model = "ibm/granite-13b-instruct-v2",
    baseUrl = "https://wx.example.com",
    apiVersion = "2024-05-31",
    iamUrl = "https://iam.example.com/identity/token",
    contextWindow = 8192,
    reserveCompletion = 1024
  )

  private val iam = HttpResponse(200, """{"access_token":"tok-1","expires_in":3600}""")
  private val generation = HttpResponse(
    200,
    """{"id":"g-1","choices":[{"index":0,"message":{"role":"assistant","content":"Hello"},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":2,"total_tokens":9}}"""
  )
  private val hi = Conversation(Seq(SystemMessage("be brief"), UserMessage("Hi")))

  private def client(
    http: org.llm4s.http.Llm4sHttpClient,
    cfg: WatsonXConfig = config,
    now: () => Long = () => 1000L
  ) = new WatsonXClient(cfg, httpClient = http, nowSeconds = now)

  test("complete exchanges the key at the IAM endpoint, then calls text/chat with the bearer token") {
    val http   = new MockHttpClient(Seq(iam, generation))
    val result = client(http).complete(hi, CompletionOptions(maxTokens = Some(64)))

    val completion = result.getOrElse(fail(s"expected success, got $result"))
    completion.content shouldBe "Hello"
    completion.id shouldBe "g-1"
    completion.usage.map(u => (u.promptTokens, u.completionTokens, u.totalTokens)) shouldBe Some((7, 2, 9))

    http.lastUrl shouldBe Some("https://wx.example.com/ml/v1/text/chat?version=2024-05-31")
    http.lastHeaders.flatMap(_.get("Authorization")) shouldBe Some("Bearer tok-1")
    http.postCallCount shouldBe 2
  }

  test("the IAM request carries the url-encoded api key and the apikey grant type") {
    val http = new MockHttpClient(Seq(iam, generation))
    val c    = client(http)
    c.bearerToken() shouldBe Right("tok-1")
    http.lastUrl shouldBe Some(config.iamUrl)
    http.lastHeaders.flatMap(_.get("Content-Type")) shouldBe Some("application/x-www-form-urlencoded")
    http.lastBody shouldBe Some(
      "grant_type=urn%3Aibm%3Aparams%3Aoauth%3Agrant-type%3Aapikey&apikey=secret+key%26%3D"
    )
  }

  test("the IAM token is cached until five minutes before it expires") {
    val http = new MockHttpClient(Seq(iam, generation, generation, iam, generation))
    var now  = 1000L
    val c    = client(http, now = () => now)

    c.complete(hi, CompletionOptions()).isRight shouldBe true
    now = 1000L + 3600L - 301L // 301s left: still outside the 300s buffer
    c.complete(hi, CompletionOptions()).isRight shouldBe true
    http.postCallCount shouldBe 3 // one IAM exchange, two chat calls

    now = 1000L + 3600L - 299L // inside the buffer: refresh
    c.complete(hi, CompletionOptions()).isRight shouldBe true
    http.postCallCount shouldBe 5
  }

  test("a failed IAM exchange is an AuthenticationError and no model call is made") {
    val http   = new MockHttpClient(Seq(HttpResponse(400, """{"errorMessage":"bad key"}"""), generation))
    val result = client(http).complete(hi, CompletionOptions())
    result.left.toOption.exists(_.isInstanceOf[AuthenticationError]) shouldBe true
    http.postCallCount shouldBe 1
  }

  test("an IAM response without an access_token is an AuthenticationError") {
    val result =
      client(new MockHttpClient(HttpResponse(200, """{"expires_in":3600}"""))).complete(hi, CompletionOptions())
    result.left.toOption.exists(_.isInstanceOf[AuthenticationError]) shouldBe true
  }

  test("a transport failure is returned, not thrown") {
    val result = client(new FailingHttpClient(NetworkError("down", None, "https://iam.example.com")))
      .complete(hi, CompletionOptions())
    result.left.toOption.exists(_.isInstanceOf[NetworkError]) shouldBe true
  }

  test("HTTP error statuses map to typed errors") {
    val limited = client(new MockHttpClient(Seq(iam, HttpResponse(429, """{"errors":[{"message":"slow down"}]}"""))))
    limited.complete(hi, CompletionOptions()).left.toOption.exists(_.isInstanceOf[RateLimitError]) shouldBe true

    val denied = client(new MockHttpClient(Seq(iam, HttpResponse(401, """{"errors":[{"message":"nope"}]}"""))))
    denied.complete(hi, CompletionOptions()).left.toOption.exists(_.isInstanceOf[AuthenticationError]) shouldBe true
  }

  test("a response without choices is a ValidationError") {
    val result =
      client(new MockHttpClient(Seq(iam, HttpResponse(200, """{"id":"x"}""")))).complete(hi, CompletionOptions())
    result.left.toOption.exists(_.isInstanceOf[ValidationError]) shouldBe true
  }

  test("the request body names the project, the model and sends the conversation as role messages") {
    val conversation = Conversation(
      Seq(
        SystemMessage("be brief"),
        UserMessage("Hi"),
        AssistantMessage("Hello"),
        ToolMessage("42", "call-7"),
        UserMessage("Thanks")
      )
    )
    val body = client(new MockHttpClient(iam)).createRequestBody(conversation, CompletionOptions(maxTokens = Some(32)))

    body("model_id").str shouldBe "ibm/granite-13b-instruct-v2"
    body("project_id").str shouldBe "project-1"
    body.obj.contains("space_id") shouldBe false
    body("max_tokens").num shouldBe 32
    body("messages").arr.map(m => (m("role").str, m("content").str)) shouldBe Seq(
      ("system", "be brief"),
      ("user", "Hi"),
      ("assistant", "Hello"),
      ("tool", "42"),
      ("user", "Thanks")
    )
  }

  test("a space id replaces the project id, and top_p is sent only when it is not the default") {
    val spaced = config.copy(spaceId = Some("space-9"))
    val body   = client(new MockHttpClient(iam), spaced).createRequestBody(hi, CompletionOptions(topP = 0.5))
    body("space_id").str shouldBe "space-9"
    body.obj.contains("project_id") shouldBe false
    body("top_p").num shouldBe 0.5

    client(new MockHttpClient(iam))
      .createRequestBody(hi, CompletionOptions())
      .obj
      .contains("top_p") shouldBe false
  }

  test("streamComplete posts to chat_stream and accumulates text, finish and usage") {
    val events = Seq(
      """{"choices":[{"delta":{"content":"Hel"},"finish_reason":null}],"usage":{"prompt_tokens":7,"completion_tokens":1,"total_tokens":8}}""",
      """{"choices":[{"delta":{"content":"lo"},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":2,"total_tokens":9}}"""
    ).map(data => s"id: 1\nevent: message\ndata: $data\n").mkString("\n")
    val http   = new MockHttpClient(Seq(iam, HttpResponse(200, events)))
    val chunks = ListBuffer.empty[StreamedChunk]

    val completion = client(http)
      .streamComplete(hi, CompletionOptions(), chunks += _)
      .getOrElse(fail("expected a completion"))

    http.lastUrl shouldBe Some("https://wx.example.com/ml/v1/text/chat_stream?version=2024-05-31")
    chunks.flatMap(_.content).mkString shouldBe "Hello"
    chunks.map(_.finishReason) shouldBe Seq(None, None, Some("stop"))
    completion.content shouldBe "Hello"
    completion.model shouldBe "ibm/granite-13b-instruct-v2"
    completion.usage.map(u => (u.promptTokens, u.completionTokens)) shouldBe Some((7, 2))
  }

  test("a streaming error status is mapped, not parsed as events") {
    val http   = new MockHttpClient(Seq(iam, HttpResponse(429, """{"errors":[{"message":"slow down"}]}""")))
    val result = client(http).streamComplete(hi, CompletionOptions(), _ => ())
    result.left.toOption.exists(_.isInstanceOf[RateLimitError]) shouldBe true
  }

  test("a closed client refuses calls") {
    val c = client(new MockHttpClient(Seq(iam, generation)))
    c.close()
    c.complete(hi, CompletionOptions()).left.toOption.exists(_.isInstanceOf[ConfigurationError]) shouldBe true
  }

  test("context window and completion reserve come from the config") {
    val c = client(new MockHttpClient(iam))
    c.getContextWindow() shouldBe 8192
    c.getReserveCompletion() shouldBe 1024
  }
