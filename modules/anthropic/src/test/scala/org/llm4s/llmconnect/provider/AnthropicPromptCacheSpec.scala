package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.HttpServer
import org.llm4s.llmconnect.config.AnthropicConfig
import org.llm4s.llmconnect.model.{
  Completion,
  CompletionOptions,
  Conversation,
  PromptCache,
  RequestAdjustment,
  RequestOption,
  SystemMessage,
  UserMessage
}
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.toolapi.{ Schema, ToolBuilder }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/** RFC 0008 PR 6 on Anthropic: the `cache_control` breakpoints `promptCache` puts on the request, and the reports. */
class AnthropicPromptCacheSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def tool(name: String) =
    ToolBuilder[Map[String, Any], String](
      name,
      s"$name tool",
      Schema.`object`[Map[String, Any]]("args").withProperty(Schema.property("city", Schema.string("City")))
    )
      .withHandler(_ => Right("ok"))
      .buildSafe()
      .toOption
      .get

  private def conversation = Conversation(Seq(SystemMessage("Be brief."), UserMessage("Hi")))

  private def reply(usage: String) =
    s"""{"id":"m","type":"message","role":"assistant","model":"x","content":[{"type":"text","text":"ok"}],
       |"stop_reason":"end_turn","stop_sequence":null,"usage":$usage}""".stripMargin

  private def noCacheUsage =
    """{"input_tokens":10,"output_tokens":1,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}"""

  /** The request body `complete` sends, and the completion, against a server answering with `usage`. */
  private def sent(options: CompletionOptions, usage: String = noCacheUsage): (ujson.Value, Completion) = {
    val seen   = new AtomicReference[String]()
    val server = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    server.createContext(
      "/v1/messages",
      exchange => {
        seen.set(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
        val bytes = reply(usage).getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.length)
        exchange.getResponseBody.write(bytes)
        exchange.close()
      }
    )
    server.start()
    try {
      val config = AnthropicConfig(
        apiKey = Secret("test-key"),
        model = "claude-sonnet-4-6",
        baseUrl = s"http://localhost:${server.getAddress.getPort}",
        contextWindow = 200000,
        reserveCompletion = 4096
      )
      val completion = new AnthropicClient(config).complete(conversation, options)
      (ujson.read(seen.get()), completion.toOption.get)
    } finally server.stop(0)
  }

  private def withTools = CompletionOptions(tools = Seq(tool("first"), tool("second")))

  "promptCache" should "change nothing in the request when it is not set" in {
    val (body, completion) = sent(withTools)
    body("system") shouldBe ujson.Str("Be brief.")
    body("tools").arr.foreach(_.obj.keySet should not contain "cache_control")
    body.obj.keySet should not contain "cache_control"
    completion.adjustments shouldBe Nil
  }

  it should "put a breakpoint on the system prompt and the last tool only, by default" in {
    val (body, _) = sent(withTools.withPromptCache(PromptCache.default))
    body("system") shouldBe ujson.Arr(
      ujson.Obj(
        "text"          -> "Be brief.",
        "type"          -> "text",
        "cache_control" -> ujson.Obj("type" -> "ephemeral", "ttl" -> "5m")
      )
    )
    val tools = body("tools").arr
    tools.head.obj.keySet should not contain "cache_control"
    tools.last("cache_control") shouldBe ujson.Obj("type" -> "ephemeral", "ttl" -> "5m")
    body.obj.keySet should not contain "cache_control"
  }

  it should "cache the conversation through the top-level breakpoint, with the long lifetime" in {
    val (body, _) =
      sent(CompletionOptions().withPromptCache(PromptCache(Set(PromptCache.Scope.Conversation), PromptCache.Ttl.Long)))
    body("cache_control") shouldBe ujson.Obj("type" -> "ephemeral", "ttl" -> "1h")
    body("system") shouldBe ujson.Str("Be brief.")
  }

  it should "report nothing when the response read from or wrote to the cache, and count the 1-hour writes" in {
    val usage =
      """{"input_tokens":10,"output_tokens":1,"cache_read_input_tokens":0,"cache_creation_input_tokens":3000,
        |"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":3000}}""".stripMargin
    val (_, completion) = sent(withTools.withPromptCache(PromptCache.default.withTtl(PromptCache.Ttl.Long)), usage)
    completion.adjustments shouldBe Nil
    completion.usage.get.cacheCreationTokens shouldBe Some(3000)
    completion.usage.get.longCacheCreationTokens shouldBe Some(3000)
  }

  it should "report it dropped when nothing was read from or written to the cache" in {
    val (_, completion) = sent(withTools.withPromptCache(PromptCache.default))
    completion.adjustments shouldBe List(
      RequestAdjustment.Dropped(
        RequestOption.PromptCache,
        "nothing was cached: the prefix may be below the model's minimum cacheable length"
      )
    )
  }

  it should "send nothing for a scope that names nothing, and say so" in {
    val (body, completion) = sent(withTools.withPromptCache(PromptCache(Set.empty, PromptCache.Ttl.Short)))
    body("system") shouldBe ujson.Str("Be brief.")
    body.obj.keySet should not contain "cache_control"
    completion.adjustments shouldBe List(
      RequestAdjustment.Dropped(RequestOption.PromptCache, "the prompt cache's scope names nothing to cache")
    )
  }

  "AnthropicPromptCache.answered" should "say nothing without usage, since it cannot tell" in {
    AnthropicPromptCache.answered(CompletionOptions().withPromptCache(PromptCache.default), None) shouldBe Nil
  }
}
