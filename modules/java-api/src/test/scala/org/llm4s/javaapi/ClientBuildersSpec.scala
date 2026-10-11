package org.llm4s.javaapi

import com.sun.net.httpserver.HttpExchange
import org.llm4s.javaapi.usage.ClientBuilderCheck
import org.llm4s.testkit.LocalProviderTestServer.{ anthropicMessage, openAICompletion, sendJsonResponse, withServer }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/**
 * The Java client builders of `Llm4s` (#1489): each provider's client made in code, with no `application.conf`, its
 * key and model reaching the provider's request; a missing or out-of-range field as a failed result naming it; and a
 * `toString` that never shows a key.
 */
class ClientBuildersSpec extends AnyFlatSpec with Matchers {

  /** One request a provider client sent: its path and query, its headers (lower-cased names) and its JSON body. */
  final private case class Seen(path: String, query: String, headers: Map[String, String], body: ujson.Value)

  private val geminiReply =
    """{"candidates":[{"content":{"role":"model","parts":[{"text":"hi from gemini"}]},"finishReason":"STOP"}],
      |"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":4,"totalTokenCount":7}}""".stripMargin

  private val ollamaReply =
    """{"model":"llama3.2","message":{"role":"assistant","content":"hi from ollama"},"done":true,"prompt_eval_count":5,"eval_count":3}"""

  /** A stand-in for every provider at once, answering each in its own format by the path it calls. */
  private def provider(seen: ConcurrentLinkedQueue[Seen])(exchange: HttpExchange): Unit = {
    val uri  = exchange.getRequestURI
    val text = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
    val headers =
      exchange.getRequestHeaders.asScala.map((name, values) => name.toLowerCase -> values.asScala.mkString(",")).toMap
    seen.add(Seen(uri.getPath, Option(uri.getRawQuery).getOrElse(""), headers, ujson.read(text)))
    val path = uri.getPath
    val reply =
      if (path.endsWith("/v1/messages")) anthropicMessage("hi from anthropic")
      else if (path.contains(":generateContent")) geminiReply
      else if (path == "/api/chat") ollamaReply
      else if (path.contains("/openai/deployments/")) openAICompletion("hi from azure")
      else if (path == "/v1/chat/completions") openAICompletion("hi from an OpenAI-format server")
      else """{"error":"unexpected path"}"""
    sendJsonResponse(exchange, 200, reply)
  }

  "every builder" should "make a client with no application.conf whose key and model reach the provider" in {
    val seen    = new ConcurrentLinkedQueue[Seen]()
    var answers = List.empty[String]
    withServer("/")(provider(seen))(baseUrl => answers = ClientBuilderCheck.askEach(baseUrl).asScala.toList)
    answers shouldBe List(
      "openai: hi from an OpenAI-format server",
      "anthropic: hi from anthropic",
      "gemini: hi from gemini",
      "ollama: hi from ollama",
      "openai-compatible: hi from an OpenAI-format server",
      "azure: hi from azure"
    )
    val List(openAi, anthropic, gemini, ollama, compatible, azure) = seen.asScala.toList: @unchecked

    (openAi.path, openAi.headers("authorization"), openAi.body("model").str) shouldBe
      (("/v1/chat/completions", "Bearer sk-openai-java", "gpt-4o-mini"))
    openAi.headers("openai-organization") shouldBe "org-java"

    (anthropic.headers("x-api-key"), anthropic.body("model").str) shouldBe
      (("sk-ant-java", "claude-sonnet-4-20250514"))

    // Gemini names the model in the path and takes the key as a query parameter
    gemini.path shouldBe "/v1beta/models/gemini-2.0-flash:generateContent"
    gemini.query should include("key=gemini-java-key")

    (ollama.path, ollama.body("model").str) shouldBe (("/api/chat", "llama3.2"))

    (compatible.headers("authorization"), compatible.headers("x-team-id"), compatible.body("model").str) shouldBe
      (("Bearer sk-local-java", "team-7", "local-model"))

    // Azure names the deployment in the path and sends the key as `api-key`
    azure.path should include("/openai/deployments/my-deployment/")
    azure.headers("api-key") shouldBe "azure-java-key"
  }

  it should "send nothing until asked, and name a missing field as VALIDATION, read from Java" in {
    ClientBuilderCheck.whatIsMissing().asScala.toList shouldBe List(
      "VALIDATION: Field 'apiKey' is required",
      "VALIDATION: Field 'apiKey' is required",
      "VALIDATION: Field 'model' is required",
      "VALIDATION: Field 'model' is required",
      "VALIDATION: Field 'baseUrl' is required",
      "VALIDATION: Field 'endpoint' is required",
      "VALIDATION: Field 'apiKey' is required",
      "VALIDATION: Field 'contextWindow' is invalid: must be at least 1, got 0"
    )
  }

  it should "read a null or blank value as not given, and check every required field" in {
    def missing(result: LlmResult[JLlmClient]): String = result.getError().getMessage
    missing(Llm4s.openAi().apiKey("  ").model("gpt-4o").build()) shouldBe "Field 'apiKey' is required"
    missing(Llm4s.openAi().apiKey("k").model(null).build()) shouldBe "Field 'model' is required"
    missing(Llm4s.anthropic().apiKey("k").build()) shouldBe "Field 'model' is required"
    missing(Llm4s.gemini().model("gemini-2.0-flash").build()) shouldBe "Field 'apiKey' is required"
    missing(Llm4s.openAiCompatible().baseUrl("http://localhost:8000/v1").build()) shouldBe "Field 'model' is required"
    missing(Llm4s.azureOpenAi().endpoint("https://r.openai.azure.com").model("d").build()) shouldBe
      "Field 'apiKey' is required"
    missing(Llm4s.azureOpenAi().endpoint("https://r.openai.azure.com").apiKey("k").build()) shouldBe
      "Field 'model' is required"
  }

  it should "refuse a context window, reply reserve or timeout out of range, naming the field" in {
    def error(result: LlmResult[JLlmClient]): (LlmErrorKind, String) =
      (result.getError().getKind, result.getError().getMessage)
    val ollama = Llm4s.ollama().model("llama3.2")
    error(ollama.reserveCompletion(-1).build()) shouldBe
      ((LlmErrorKind.VALIDATION, "Field 'reserveCompletion' is invalid: must be at least 0, got -1"))
    error(ollama.contextWindow(4096).reserveCompletion(4096).build()) shouldBe
      (
        (
          LlmErrorKind.VALIDATION,
          "Field 'reserveCompletion' is invalid: the reply's reserve, 4096, must be less than the context window, 4096"
        )
      )
    // a window smaller than the provider's own reserve for the model names the window, the field the caller set
    error(ollama.contextWindow(10).build())._2 should startWith("Field 'contextWindow' is invalid: the reply's reserve")
    error(ollama.requestTimeout(Duration.ZERO).build()) shouldBe
      ((LlmErrorKind.VALIDATION, "Field 'requestTimeout' is invalid: must be more than zero, got PT0S"))
    error(ollama.streamTimeout(Duration.ofSeconds(-1)).build()) shouldBe
      ((LlmErrorKind.VALIDATION, "Field 'streamTimeout' is invalid: must be more than zero, got PT-1S"))
  }

  it should "build with only the required fields, every default the provider's own" in {
    // no request is sent: each is a client, ready for a call
    List(
      Llm4s.openAi().apiKey("k").model("gpt-4o-mini").build(),
      Llm4s.anthropic().apiKey("k").model("claude-sonnet-4-20250514").build(),
      Llm4s.gemini().apiKey("k").model("gemini-2.0-flash").build(),
      Llm4s.ollama().model("llama3.2").build(),
      Llm4s.openAiCompatible().baseUrl("http://localhost:8000/v1").model("m").build(),
      Llm4s.azureOpenAi().endpoint("https://r.openai.azure.com").apiKey("k").model("d").build()
    ).foreach(result => withClue(String.valueOf(result.getError()))(result.isSuccess shouldBe true))
  }

  "a builder" should "be immutable: a setter returns a new builder and leaves the one it was called on alone" in {
    val base   = Llm4s.openAi().apiKey("k")
    val gpt4o  = base.model("gpt-4o")
    val gpt4oM = base.model("gpt-4o-mini")
    (base.toString, gpt4o.toString, gpt4oM.toString) shouldBe
      (
        (
          "OpenAiClientBuilder(apiKey=***)",
          "OpenAiClientBuilder(model=gpt-4o, apiKey=***)",
          "OpenAiClientBuilder(model=gpt-4o-mini, apiKey=***)"
        )
      )
    // clearing: null leaves a field unset again, and removes a header
    base.model("gpt-4o").model(null).toString shouldBe "OpenAiClientBuilder(apiKey=***)"
    Llm4s.openAiCompatible().header("X-A", "1").header("X-A", null).header(null, "ignored").toString shouldBe
      "OpenAiCompatibleClientBuilder()"
  }

  it should "never show a key or a header's value in toString" in {
    val described = ClientBuilderCheck.described().asScala.toList
    described shouldBe List(
      "OpenAiClientBuilder(model=gpt-4o-mini, apiKey=***)",
      "AnthropicClientBuilder(model=claude, apiKey=***)",
      "GeminiClientBuilder(model=gemini-2.0-flash, apiKey=***)",
      "OllamaClientBuilder(model=llama3.2, baseUrl=http://gpu-box:11434)",
      "OpenAiCompatibleClientBuilder(model=m, apiKey=***, baseUrl=http://localhost:8000/v1, headers=[Authorization-Extra])",
      "AzureOpenAiClientBuilder(model=d, apiKey=***, endpoint=https://r.openai.azure.com, apiVersion=2024-10-21)"
    )
    described.foreach(text => (text should not).include("sk-secret"))
    Llm4s
      .openAi()
      .model("m")
      .organization("org")
      .contextWindow(1000)
      .reserveCompletion(100)
      .requestTimeout(Duration.ofSeconds(5))
      .streamTimeout(Duration.ofSeconds(9))
      .toString shouldBe
      "OpenAiClientBuilder(model=m, organization=org, contextWindow=1000, reserveCompletion=100, requestTimeout=PT5S, " +
      "streamTimeout=PT9S)"
  }
}
