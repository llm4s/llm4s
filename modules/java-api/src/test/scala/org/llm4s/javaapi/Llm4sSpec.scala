package org.llm4s.javaapi

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class Llm4sSpec extends AnyFlatSpec with Matchers {

  private val stubClient: LLMClient = new LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      Right(Completion("id", 0L, "ok", "test-model", AssistantMessage("ok")))
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  "createAgent" should "return a JAgent wrapping the given client" in {
    val jClient = Llm4s.wrapClient(stubClient)
    val agent   = Llm4s.createAgent(jClient)
    agent shouldBe a[JAgent]
  }

  it should "produce an agent that can run a query" in {
    val jClient = Llm4s.wrapClient(stubClient)
    val agent   = Llm4s.createAgent(jClient)
    val result  = agent.run("hello")
    result.isSuccess shouldBe true
  }

  "createDefaultClient" should "not throw, and surface any config failure as a failed LlmResult" in {
    // Whether credentials exist depends on the environment, so assert the invariant that holds
    // either way: the call never throws and the result is exactly one of success or failure.
    noException should be thrownBy Llm4s.createDefaultClient()
    val result = Llm4s.createDefaultClient()
    result.isSuccess should not be result.isFailure
  }

  "createClient" should "return a successful result for a valid OpenAI config" in {
    import org.llm4s.llmconnect.config.OpenAIConfig

    val config = OpenAIConfig(
      apiKey = "sk-test-key",
      model = "gpt-4o",
      organization = None,
      baseUrl = "https://api.openai.com/v1",
      contextWindow = 128000,
      reserveCompletion = 4096
    )
    val result = Llm4s.createClient(config)
    result.isSuccess shouldBe true
    result.get() shouldBe a[JLlmClient]
  }

  "createClient with a MetricsCollector" should "report the client's calls to that collector" in {
    import com.sun.net.httpserver.HttpServer
    import org.llm4s.llmconnect.config.OllamaConfig
    import org.llm4s.metrics.{ MetricsCollector, Outcome }

    import java.net.InetSocketAddress
    import java.nio.charset.StandardCharsets
    import scala.concurrent.duration.FiniteDuration

    // An Ollama `/api/chat` reply, so the real provider client runs end to end against a loopback server.
    val reply =
      """{"model":"llama3.2","message":{"role":"assistant","content":"hi"},"done":true,"prompt_eval_count":5,"eval_count":3}"""
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/api/chat",
      exchange => {
        val bytes = reply.getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.length.toLong)
        exchange.getResponseBody.write(bytes)
        exchange.close()
      }
    )
    server.start()

    val requests = new java.util.concurrent.ConcurrentLinkedQueue[(String, String, Outcome)]()
    val tokens   = new java.util.concurrent.ConcurrentLinkedQueue[(Long, Long)]()
    val metrics = new MetricsCollector {
      override def observeRequest(provider: String, model: String, outcome: Outcome, duration: FiniteDuration): Unit =
        requests.add((provider, model, outcome))
      override def addTokens(provider: String, model: String, inputTokens: Long, outputTokens: Long): Unit =
        tokens.add((inputTokens, outputTokens))
      override def recordCost(provider: String, model: String, costUsd: Double): Unit = ()
    }

    val config = OllamaConfig("llama3.2", s"http://127.0.0.1:${server.getAddress.getPort}")
    val answer = FacadeInternals.createClient(config, metrics).get().complete("hello")
    server.stop(0)

    answer.get() shouldBe "hi"
    requests.size() shouldBe 1
    requests.peek() shouldBe (("ollama", "llama3.2", Outcome.Success))
    tokens.peek() shouldBe ((5L, 3L))
  }

  it should "refuse a null config and treat a null collector as none" in {
    import org.llm4s.llmconnect.config.OllamaConfig
    FacadeInternals.createClient(null, org.llm4s.metrics.MetricsCollector.noop).isFailure shouldBe true
    FacadeInternals.createClient(OllamaConfig("llama3.2", "http://127.0.0.1:1"), null).isSuccess shouldBe true
  }

  "wrapClient" should "wrap a Scala LLMClient the caller built, its calls and close going to it (#1666)" in {
    val closed = new java.util.concurrent.atomic.AtomicBoolean(false)
    val own = new LLMClient {
      override def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
        Right(Completion("id", 0L, "mine", "m", AssistantMessage("mine")))
      override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
        complete(c, o)
      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 512
      override def close(): Unit               = closed.set(true)
    }
    val client = Llm4s.wrapClient(own)
    client.underlying shouldBe theSameInstanceAs(own)
    client.complete("hi").get() shouldBe "mine"
    client.close()
    closed.get shouldBe true
  }

  it should "refuse a null client at once" in {
    (the[NullPointerException] thrownBy Llm4s.wrapClient(null) should have).message("client must not be null")
  }
}
