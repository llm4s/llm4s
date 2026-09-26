package org.llm4s.trace

import org.llm4s.agent.{ Agent, AgentContext, AgentStatus }
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

import java.util.Base64
import scala.collection.mutable

/**
 * Integration tests for LangfuseTracing.
 *
 * The "mock-backed" tests run unconditionally (no external service required)
 * and verify that a complete agent turn produces the correct Langfuse batch
 * payloads: span structure, tool call presence, model and token-usage fields.
 *
 * The "real Langfuse" smoke tests (marked with `assume` guards) require the
 * `LANGFUSE_PUBLIC_KEY` and `LANGFUSE_SECRET_KEY` environment variables.
 * When they are absent the tests skip gracefully via ScalaTest's `assume()`.
 */
class LangfuseTracingIntegrationSpec extends AnyFlatSpec with Matchers {

  // -------------------------------------------------------------------------
  // Env-var guards – read once at construction; never call System.getenv inside test body
  // -------------------------------------------------------------------------

  private val langfusePublicKey: Option[String] =
    Option(System.getenv("LANGFUSE_PUBLIC_KEY")).filter(_.nonEmpty)

  private val langfuseSecretKey: Option[String] =
    Option(System.getenv("LANGFUSE_SECRET_KEY")).filter(_.nonEmpty)

  private val langfuseBaseUrl: String =
    Option(System.getenv("LANGFUSE_BASE_URL"))
      .filter(_.nonEmpty)
      .getOrElse("https://cloud.langfuse.com")

  // -------------------------------------------------------------------------
  // Local minimal HTTP client mock – keeps the IT module self-contained
  // -------------------------------------------------------------------------

  /**
   * A simple capturing HTTP client that records all POST calls and
   * returns a configurable sequence of responses.
   */
  private class CapturingHttpClient(responses: Seq[HttpResponse]) extends Llm4sHttpClient {
    private val responseQueue: mutable.Queue[HttpResponse] = mutable.Queue(responses*)
    val postedBodies: mutable.Buffer[String]               = mutable.Buffer.empty
    val postedHeaders: mutable.Buffer[Map[String, String]] = mutable.Buffer.empty
    val postedUrls: mutable.Buffer[String]                 = mutable.Buffer.empty

    private def nextResponse: HttpResponse =
      if (responseQueue.nonEmpty) responseQueue.dequeue() else responses.lastOption.getOrElse(HttpResponse(200, "{}"))

    override def post(url: String, headers: Map[String, String], body: String, timeout: Int): HttpResponse = {
      postedBodies.append(body)
      postedHeaders.append(headers)
      postedUrls.append(url)
      nextResponse
    }

    override def get(
      url: String,
      headers: Map[String, String],
      params: Map[String, String],
      timeout: Int
    ): HttpResponse = HttpResponse(200, "{}")

    override def postBytes(
      url: String,
      headers: Map[String, String],
      data: Array[Byte],
      timeout: Int
    ): HttpResponse = HttpResponse(200, "{}")

    override def postMultipart(
      url: String,
      headers: Map[String, String],
      parts: Seq[org.llm4s.http.MultipartPart],
      timeout: Int
    ): HttpResponse = HttpResponse(200, "{}")

    override def put(url: String, headers: Map[String, String], body: String, timeout: Int): HttpResponse =
      HttpResponse(200, "{}")

    override def delete(url: String, headers: Map[String, String], timeout: Int): HttpResponse =
      HttpResponse(200, "{}")

    override def postRaw(
      url: String,
      headers: Map[String, String],
      body: String,
      timeout: Int
    ): org.llm4s.http.HttpRawResponse =
      org.llm4s.http.HttpRawResponse(200, Array.emptyByteArray)

    override def postStream(
      url: String,
      headers: Map[String, String],
      body: String,
      timeout: Int
    ): org.llm4s.http.StreamingHttpResponse =
      org.llm4s.http.StreamingHttpResponse(200, new java.io.ByteArrayInputStream(Array.emptyByteArray))
  }

  /** Convenience – builds a CapturingHttpClient returning the same response each time. */
  private def captureClient(statusCode: Int = 200, body: String = """{"successes":1}"""): CapturingHttpClient =
    new CapturingHttpClient(Seq.fill(20)(HttpResponse(statusCode, body)))

  // -------------------------------------------------------------------------
  // Shared helpers
  // -------------------------------------------------------------------------

  private def buildTracing(
    httpClient: Llm4sHttpClient,
    publicKey: String = "pk-lf-test",
    secretKey: String = "sk-lf-test"
  ): LangfuseTracing =
    new LangfuseTracing(
      langfuseUrl = langfuseBaseUrl,
      publicKey = publicKey,
      secretKey = secretKey,
      environment = "test",
      release = "v0.0.0",
      version = "1.0.0",
      httpClient = httpClient
    )

  /** A mock LLM client that cycles through the supplied completions. */
  private class SequencedMockClient(completions: Seq[Completion]) extends LLMClient {
    private var idx = 0

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      val c = completions(idx % completions.size)
      idx += 1
      Right(c)
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  case class EchoResult(echo: String)
  object EchoResult {
    implicit val rw: ReadWriter[EchoResult] = macroRW
  }

  private def buildEchoTool(): Result[ToolFunction[Map[String, Any], EchoResult]] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Echo parameters")
      .withRequiredField("message", Schema.string("Message to echo"))

    ToolBuilder[Map[String, Any], EchoResult](
      "echo",
      "Echoes the supplied message",
      schema
    ).withHandler { extractor =>
      extractor.getString("message").map(EchoResult(_))
    }.buildSafe()
  }

  private def toolCallCompletion(toolCallId: String, argument: String): Completion = {
    val tc  = ToolCall(toolCallId, "echo", ujson.Obj("message" -> argument))
    val msg = AssistantMessage("Calling echo.", Seq(tc))
    Completion(
      id = "turn-1",
      created = System.currentTimeMillis(),
      content = "Calling echo.",
      model = "test-model",
      message = msg,
      toolCalls = List(tc),
      usage = Some(TokenUsage(promptTokens = 20, completionTokens = 10, totalTokens = 30))
    )
  }

  private def finalCompletion(): Completion = {
    val msg = AssistantMessage("Done.", Seq.empty)
    Completion(
      id = "turn-2",
      created = System.currentTimeMillis(),
      content = "Done.",
      model = "test-model",
      message = msg,
      usage = Some(TokenUsage(promptTokens = 30, completionTokens = 5, totalTokens = 35))
    )
  }

  // -------------------------------------------------------------------------
  // Mock-backed tests – run in every CI environment (no external service needed)
  // -------------------------------------------------------------------------

  "LangfuseTracingIntegration (mock-backed)" should
    "send a batch containing a span-create event for a ToolExecuted trace event" in {

    val http    = captureClient()
    val tracing = buildTracing(http)

    val result = tracing.traceEvent(
      TraceEvent.ToolExecuted("echo", """{"message":"hi"}""", "hi", 25L, success = true)
    )

    result.isRight shouldBe true
    http.postedBodies should have size 1

    val batch = ujson.read(http.postedBodies.head).obj("batch").arr
    batch should have size 1
    batch.head.obj("type").str shouldBe "span-create"
    batch.head.obj("body").obj("name").str should include("echo")
  }

  it should "send a batch containing a generation-create event for a CompletionReceived trace event" in {
    val http    = captureClient()
    val tracing = buildTracing(http)

    tracing.traceEvent(
      TraceEvent.CompletionReceived(id = "comp-1", model = "test-model", toolCalls = 0, content = "Hi!")
    )

    val batch = ujson.read(http.postedBodies.head).obj("batch").arr
    batch should have size 1
    batch.head.obj("type").str shouldBe "generation-create"
    batch.head.obj("body").obj("model").str shouldBe "test-model"
  }

  it should "send a trace-create event with name 'LLM4S Agent Run' for AgentInitialized" in {
    val http    = captureClient()
    val tracing = buildTracing(http)

    tracing.traceEvent(TraceEvent.AgentInitialized("Run echo test", Vector("echo")))

    val batch        = ujson.read(http.postedBodies.head).obj("batch").arr
    val traceCreates = batch.filter(_.obj("type").str == "trace-create")
    traceCreates should not be empty
    traceCreates.head.obj("body").obj("name").str shouldBe "LLM4S Agent Run"
    traceCreates.head.obj("body").obj("input").str shouldBe "Run echo test"
  }

  it should "include correct Basic auth credentials in the Authorization header" in {
    val http    = captureClient()
    val tracing = buildTracing(http, publicKey = "pk-lf-TESTKEY", secretKey = "sk-lf-TESTSECRET")

    tracing.traceEvent(TraceEvent.AgentInitialized("query", Vector.empty))

    val auth    = http.postedHeaders.head.getOrElse("Authorization", fail("Missing Authorization header"))
    val encoded = auth.stripPrefix("Basic ")
    val decoded = new String(Base64.getDecoder.decode(encoded))
    decoded shouldBe "pk-lf-TESTKEY:sk-lf-TESTSECRET"
  }

  it should "post to the Langfuse ingestion endpoint URL" in {
    val http    = captureClient()
    val tracing = buildTracing(http)

    tracing.traceEvent(TraceEvent.AgentInitialized("query", Vector.empty))

    http.postedUrls.head should endWith("/api/public/ingestion")
  }

  it should "include token usage counts in a TokenUsageRecorded batch event output" in {
    val http    = captureClient()
    val tracing = buildTracing(http)

    tracing.traceTokenUsage(TokenUsage(promptTokens = 100, completionTokens = 50, totalTokens = 150), "model-x", "op")

    val event  = ujson.read(http.postedBodies.head).obj("batch").arr.head.obj
    event("type").str shouldBe "event-create"
    val output = event("body").obj("output").obj
    output("prompt_tokens").num.toInt shouldBe 100
    output("completion_tokens").num.toInt shouldBe 50
    output("total_tokens").num.toInt shouldBe 150
  }

  it should "send a trace-create plus child span-create events via traceAgentState" in {
    val http    = captureClient()
    val tracing = buildTracing(http)

    val conversation = Conversation(
      Seq(
        UserMessage("What is echo?"),
        AssistantMessage("Echo repeats your message.", Seq.empty)
      )
    )
    val state = org.llm4s.agent.AgentState(
      conversation = conversation,
      tools = ToolRegistry.empty,
      status = AgentStatus.Complete
    )

    tracing.traceAgentState(state)

    http.postedBodies should have size 1
    val batch       = ujson.read(http.postedBodies.head).obj("batch").arr
    val traceEvents = batch.filter(_.obj("type").str == "trace-create")
    val spanEvents  = batch.filter(_.obj("type").str == "span-create")

    traceEvents should not be empty
    spanEvents should not be empty

    // Child spans must reference the main trace id
    val mainTraceId = traceEvents.head.obj("body").obj("id").str
    spanEvents.foreach { s =>
      s.obj("body").obj("traceId").str shouldBe mainTraceId
    }
  }

  it should "emit trace events during a full agent run with a mock tool call" in {
    val echoTool = buildEchoTool().getOrElse(fail("echo tool build failed"))
    val registry = new ToolRegistry(Seq(echoTool))

    val http    = captureClient()
    val tracing = buildTracing(http)
    val client  = new SequencedMockClient(Seq(toolCallCompletion("call-001", "hello"), finalCompletion()))
    val agent   = new Agent(client)
    val ctx     = AgentContext(tracing = Some(tracing))

    val result = agent.run(query = "Echo hello", tools = registry, context = ctx)

    result.isRight shouldBe true
    result.map(_.status shouldBe AgentStatus.Complete)

    // At least one batch was posted to Langfuse during the run
    http.postedBodies should not be empty

    // The last batch should be a valid JSON object containing a batch array
    val lastBatch = ujson.read(http.postedBodies.last)
    lastBatch.obj.contains("batch") shouldBe true
  }

  it should "skip export silently when public key is empty and return Right" in {
    val http    = captureClient()
    val tracing = buildTracing(http, publicKey = "")

    val result = tracing.traceEvent(TraceEvent.AgentInitialized("q", Vector.empty))

    result.isRight shouldBe true
    http.postedBodies shouldBe empty
  }

  it should "skip export silently when secret key is empty and return Right" in {
    val http    = captureClient()
    val tracing = buildTracing(http, secretKey = "")

    val result = tracing.traceEvent(TraceEvent.AgentInitialized("q", Vector.empty))

    result.isRight shouldBe true
    http.postedBodies shouldBe empty
  }

  // -------------------------------------------------------------------------
  // Real Langfuse smoke tests – skipped when env vars absent
  // -------------------------------------------------------------------------

  "LangfuseTracingIntegration (real Langfuse)" should
    "create a trace and receive a 2xx/207 response for an agent run" in {

    assume(
      langfusePublicKey.isDefined,
      "LANGFUSE_PUBLIC_KEY not set - skipping real Langfuse smoke test"
    )
    assume(
      langfuseSecretKey.isDefined,
      "LANGFUSE_SECRET_KEY not set - skipping real Langfuse smoke test"
    )

    val echoTool = buildEchoTool().getOrElse(fail("echo tool build failed"))
    val registry = new ToolRegistry(Seq(echoTool))

    val realTracing = new LangfuseTracing(
      langfuseUrl = langfuseBaseUrl,
      publicKey = langfusePublicKey.get,
      secretKey = langfuseSecretKey.get,
      environment = "ci-integration-test",
      release = "test",
      version = "1.0.0"
    )

    val client = new SequencedMockClient(Seq(toolCallCompletion("real-call-001", "hello"), finalCompletion()))
    val agent  = new Agent(client)
    val ctx    = AgentContext(tracing = Some(realTracing))

    val result = agent.run(
      query = "Integration test: echo 'hello'",
      tools = registry,
      context = ctx
    )

    result.isRight shouldBe true
    result.map(_.status shouldBe AgentStatus.Complete)
  }

  it should "verify trace creation via Langfuse API after an agent run" in {
    assume(
      langfusePublicKey.isDefined,
      "LANGFUSE_PUBLIC_KEY not set - skipping real Langfuse API verification"
    )
    assume(
      langfuseSecretKey.isDefined,
      "LANGFUSE_SECRET_KEY not set - skipping real Langfuse API verification"
    )

    val publicKey = langfusePublicKey.get
    val secretKey = langfuseSecretKey.get

    // Run an agent turn to generate a trace
    val echoTool = buildEchoTool().getOrElse(fail("echo tool build failed"))
    val registry = new ToolRegistry(Seq(echoTool))

    val realTracing = new LangfuseTracing(
      langfuseUrl = langfuseBaseUrl,
      publicKey = publicKey,
      secretKey = secretKey,
      environment = "ci-integration-test",
      release = "test",
      version = "1.0.0"
    )

    val client = new SequencedMockClient(Seq(toolCallCompletion("verify-call-001", "world"), finalCompletion()))
    val agent  = new Agent(client)
    val ctx    = AgentContext(tracing = Some(realTracing))

    val agentResult = agent.run(
      query = "Verify trace: echo 'world'",
      tools = registry,
      context = ctx
    )

    agentResult.isRight shouldBe true

    // Allow Langfuse a moment to ingest the events
    Thread.sleep(3000)

    // Poll the Langfuse API to confirm the trace was created
    val httpClient   = org.llm4s.http.Llm4sHttpClient.create()
    val credentials  = Base64.getEncoder.encodeToString(s"$publicKey:$secretKey".getBytes("UTF-8"))
    val tracesApiUrl = langfuseBaseUrl.stripSuffix("/") + "/api/public/traces"

    val response = httpClient.get(
      url = tracesApiUrl,
      headers = Map(
        "Authorization" -> s"Basic $credentials",
        "Content-Type"  -> "application/json"
      ),
      params = Map("name" -> "LLM4S Agent Run", "limit" -> "5"),
      timeout = 30000
    )

    response.statusCode should be < 300
    val responseBody = ujson.read(response.body)
    val traces       = responseBody.obj("data").arr
    traces should not be empty
    traces.head.obj("name").str shouldBe "LLM4S Agent Run"
  }
}
