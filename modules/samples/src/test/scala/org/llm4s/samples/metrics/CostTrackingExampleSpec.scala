package org.llm4s.samples.metrics

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.model.{ ModelCapabilities, ModelMetadata, ModelMode, ModelPricing, ModelRegistryService }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ ConcurrentLinkedQueue, Executors }
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * The sample, run for real: the real client, the real `Agent` and its calculator tool, against a fake OpenAI-format
 * endpoint that reports fixed token counts, so every cost below is arithmetic on known numbers.
 *
 * The fake provider answers by what the request carries:
 *  - a request with tools and no tool result yet asks for the calculator (50 in, 20 out);
 *  - a request carrying the tool result is the final answer (70 in, 15 out);
 *  - any other request is plain, the first (20 in, 10 out) and the second (30 in, 12 out).
 */
class CostTrackingExampleSpec extends AnyFlatSpec with Matchers {

  private val Model = "stub-model"

  // Token counts the fake provider reports.
  private val Plain      = Seq((20, 10), (30, 12))
  private val ToolCall   = (50, 20)
  private val FinalReply = (70, 15)

  // The registry's price for the model: $1 per million input tokens, $2 per million output.
  private val RegistryIn  = 1.0e-6
  private val RegistryOut = 2.0e-6

  private val tolerance = 1.0e-12

  final private class FakeProvider {
    val requests: ConcurrentLinkedQueue[ujson.Value] = new ConcurrentLinkedQueue[ujson.Value]()
    private val plainServed                          = new AtomicInteger(0)

    def seen: List[ujson.Value] = requests.asScala.toList

    def handle(exchange: HttpExchange, model: String): Unit = {
      val body = ujson.read(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
      requests.add(body)
      val hasTools      = body.obj.contains("tools")
      val hasToolResult = body("messages").arr.exists(_.obj.get("role").exists(_.str == "tool"))
      val reply =
        if (hasTools && !hasToolResult) completion(model, calculatorCall, "tool_calls", ToolCall)
        else if (hasTools) completion(model, assistant("17 times 23 is 391. Today is a day."), "stop", FinalReply)
        else
          completion(
            model,
            assistant("A token is a chunk of text."),
            "stop",
            Plain(plainServed.getAndIncrement() % Plain.size)
          )
      val bytes = ujson.write(reply).getBytes(StandardCharsets.UTF_8)
      exchange.getResponseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(200, bytes.length.toLong)
      exchange.getResponseBody.write(bytes)
      exchange.getResponseBody.close()
    }

    private def assistant(content: String): ujson.Obj = ujson.Obj("role" -> "assistant", "content" -> content)

    private val calculatorCall: ujson.Obj = ujson.Obj(
      "role"    -> "assistant",
      "content" -> ujson.Null,
      "tool_calls" -> ujson.Arr(
        ujson.Obj(
          "id"   -> "call_1",
          "type" -> "function",
          "function" -> ujson.Obj(
            "name"      -> "calculator",
            "arguments" -> """{"operation":"multiply","a":17,"b":23}"""
          )
        )
      )
    )

    private def completion(model: String, message: ujson.Obj, finish: String, usage: (Int, Int)): ujson.Obj =
      ujson.Obj(
        "id"      -> "chatcmpl-1",
        "object"  -> "chat.completion",
        "created" -> 1,
        "model"   -> model,
        "choices" -> ujson.Arr(ujson.Obj("index" -> 0, "message" -> message, "finish_reason" -> finish)),
        "usage" -> ujson.Obj(
          "prompt_tokens"     -> usage._1,
          "completion_tokens" -> usage._2,
          "total_tokens"      -> (usage._1 + usage._2)
        )
      )
  }

  /** Runs `test` against a fake provider that reports `model`, with a config pointing at it. */
  private def withProvider(model: String = Model)(test: (ProviderConfig, FakeProvider) => Any): Unit = {
    val server   = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    val provider = new FakeProvider
    val handlers = Executors.newVirtualThreadPerTaskExecutor()
    server.createContext("/", exchange => provider.handle(exchange, model))
    server.setExecutor(handlers)
    server.start()

    val outcome = Try {
      val hocon =
        s"""llm4s.providers {
           |  provider = "stub"
           |  stub {
           |    provider = "openai-compatible"
           |    baseUrl  = "http://localhost:${server.getAddress.getPort}/v1"
           |    model    = "$model"
           |    apiKey   = "test-key"
           |  }
           |}""".stripMargin
      val config =
        Llm4sConfig.provider(ConfigSource.string(hocon), "stub").fold(error => fail(error.formatted), identity)
      test(config, provider)
    }
    server.stop(0)
    handlers.shutdownNow(): Unit
    outcome.fold(error => throw error, _ => ())
  }

  private def metadata(model: String, input: Double, output: Double): ModelMetadata =
    metadata(model, ModelPricing(inputCostPerToken = Some(input), outputCostPerToken = Some(output)))

  private def metadata(model: String, pricing: ModelPricing): ModelMetadata =
    ModelMetadata(
      modelId = model,
      provider = "openai-compatible",
      mode = ModelMode.Chat,
      maxInputTokens = None,
      maxOutputTokens = None,
      inputCostPerToken = None,
      outputCostPerToken = None,
      capabilities = ModelCapabilities(),
      pricing = pricing,
      deprecationDate = None
    )

  private def pricedRegistry(model: String): ModelRegistryService =
    ModelRegistryService.fromModels(Seq(metadata(model, RegistryIn, RegistryOut)))

  private val emptyRegistry: ModelRegistryService = ModelRegistryService.fromModels(Nil)

  private def cost(tokens: (Int, Int), in: Double, out: Double): Double = tokens._1 * in + tokens._2 * out

  private def runFor(config: ProviderConfig, registry: ModelRegistryService): CostTrackingExample.CostReport =
    CostTrackingExample.run(config, registry).fold(error => fail(error.formatted), identity)

  "CostTrackingExample" should "measure the cost at every level from the registry's price" in withProvider() {
    (config, _) =>
      val report = runFor(config, pricedRegistry(Model))

      report.priced shouldBe true

      // 1. Per request: the first plain request, 20 in and 10 out.
      report.perRequest.usage.map(u => (u.promptTokens, u.completionTokens)) shouldBe Some(Plain.head)
      report.perRequest.cost
        .getOrElse(fail("no cost")) shouldBe (cost(Plain.head, RegistryIn, RegistryOut) +- tolerance)

      // 2. Agent: the tool call and the final answer, two requests.
      report.agent.requestCount shouldBe 2
      report.agent.inputTokens shouldBe (ToolCall._1 + FinalReply._1).toLong
      report.agent.outputTokens shouldBe (ToolCall._2 + FinalReply._2).toLong
      val agentCost = cost(ToolCall, RegistryIn, RegistryOut) + cost(FinalReply, RegistryIn, RegistryOut)
      report.agent.totalCost.toDouble shouldBe (agentCost +- tolerance)

      // 3. Session: the request and the agent run, through one client.
      report.session.requestCount shouldBe 3
      report.session.inputTokens shouldBe (Plain.head._1 + ToolCall._1 + FinalReply._1).toLong
      val sessionCost = cost(Plain.head, RegistryIn, RegistryOut) + agentCost
      report.session.totalCost.toDouble shouldBe (sessionCost +- tolerance)
  }

  it should "run the agent through its tool loop, not around it" in withProvider() { (config, provider) =>
    runFor(config, pricedRegistry(Model))

    val withTools = provider.seen.filter(_.obj.contains("tools"))
    withTools should have size 2
    // The second agent request carries the calculator's result: the framework ran the tool and went back to the model.
    val toolResults = withTools.last("messages").arr.filter(_.obj.get("role").exists(_.str == "tool"))
    toolResults should have size 1
    toolResults.head("content").str should include("391")
  }

  it should "price with the custom rates in demo 4, and let both composed trackers see that request" in withProvider() {
    (config, _) =>
      val report = runFor(config, pricedRegistry(Model))
      val custom = report.custom

      // The second plain request, 30 in and 12 out, at the example rates: they replace the registry's for the model.
      val expected = cost(
        Plain(1),
        CostTrackingExample.ExampleInputPerMillion / 1.0e6,
        CostTrackingExample.ExampleOutputPerMillion / 1.0e6
      )
      custom.request.cost.getOrElse(fail("no cost")) shouldBe (expected +- tolerance)
      custom.request.cost
        .getOrElse(fail("no cost")) should not be (cost(Plain(1), RegistryIn, RegistryOut) +- tolerance)

      // compose: the demo's own tracker saw only that request; the session tracker saw it with everything else.
      custom.demoTracker.requestCount shouldBe 1
      custom.demoTracker.totalCost.toDouble shouldBe (expected +- tolerance)
      report.sessionTotal.requestCount shouldBe 4
      report.sessionTotal.totalCost.toDouble shouldBe (report.session.totalCost.toDouble + expected +- tolerance)
  }

  it should "price the model the configuration names, not a fixed one" in withProvider("acme-chat-7") { (config, _) =>
    val report = runFor(config, pricedRegistry("acme-chat-7"))

    report.model shouldBe "acme-chat-7"
    report.perRequest.cost.getOrElse(fail("no cost")) shouldBe (cost(Plain.head, RegistryIn, RegistryOut) +- tolerance)
    report.agent.requestCount shouldBe 2
  }

  it should "report an unpriced model as unknown, and never invent a number for it" in withProvider() { (config, _) =>
    val report = runFor(config, emptyRegistry)

    report.priced shouldBe false
    report.perRequest.cost shouldBe None
    // UsageSummary holds 0 for "no price" as for "free": the output has to say which.
    report.agent.totalCost shouldBe BigDecimal(0)

    val lines = CostTrackingExample.render(report)
    lines.exists(_.contains(s"unknown (no price for '$Model' in the registry)")) shouldBe true
    lines.exists(_.contains("means unknown, not free")) shouldBe true
    // The composed totals do not hide that demos 1 to 3 went unpriced.
    lines.exists(l => l.contains("had no price") && l.contains("only the custom-priced one is costed")) shouldBe true
    // Demo 4 is how to fix it: with a registry that prices the model, the cost is known.
    report.custom.request.cost should not be empty
  }

  it should "show a known cost without the 'unknown' note" in withProvider() { (config, _) =>
    val lines = CostTrackingExample.render(runFor(config, pricedRegistry(Model)))

    lines.exists(_.contains("unknown")) shouldBe false
    lines.exists(_.contains("1. Per request")) shouldBe true
    lines.exists(_.contains("2. Per agent run")) shouldBe true
    lines.exists(_.contains("3. Per session")) shouldBe true
    lines.exists(_.contains("4. Custom pricing")) shouldBe true
    lines.exists(_.contains("PrometheusMetricsExample")) shouldBe true
  }

  "CostTrackingExample.withCustomPricing" should "price a model the base registry does not know, and keep what it knows" in {
    val base = pricedRegistry("known-model")
    val result =
      CostTrackingExample.withCustomPricing(base, "my-model", "acme", 3.0, 9.0).fold(e => fail(e.formatted), identity)

    val mine = result.lookup("my-model").fold(e => fail(e.formatted), identity)
    mine.pricing.inputCostPerToken.getOrElse(fail("no price")) shouldBe (3.0e-6 +- tolerance)
    mine.pricing.outputCostPerToken.getOrElse(fail("no price")) shouldBe (9.0e-6 +- tolerance)
    result.lookup("known-model").isRight shouldBe true
  }

  it should "leave the registry it was given as it was" in {
    val base = pricedRegistry("known-model")
    CostTrackingExample.withCustomPricing(base, "my-model", "acme", 3.0, 9.0).isRight shouldBe true

    base.lookup("my-model").isLeft shouldBe true
    base.lookup("known-model").isRight shouldBe true
  }

  it should "replace the price of a model the base registry already has" in {
    val base = pricedRegistry("known-model")
    val result = CostTrackingExample
      .withCustomPricing(base, "known-model", "acme", 5.0, 7.0)
      .fold(e => fail(e.formatted), identity)

    result.lookup("known-model").fold(e => fail(e.formatted), identity).pricing.inputCostPerToken shouldBe Some(5.0e-6)
    base.lookup("known-model").fold(e => fail(e.formatted), identity).pricing.inputCostPerToken shouldBe Some(
      RegistryIn
    )
  }

  "CostTrackingExample.isPriced" should "be true only for a model with an input price" in {
    val registry = ModelRegistryService.fromModels(
      Seq(metadata("priced", 1.0e-6, 2.0e-6), metadata("unpriced", ModelPricing()))
    )

    CostTrackingExample.isPriced(registry, "priced") shouldBe true
    CostTrackingExample.isPriced(registry, "unpriced") shouldBe false
    CostTrackingExample.isPriced(registry, "absent") shouldBe false
  }
}
