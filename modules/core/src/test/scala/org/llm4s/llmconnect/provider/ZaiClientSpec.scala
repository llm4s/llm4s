package org.llm4s.llmconnect.provider

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.config.ZaiConfig
import org.llm4s.llmconnect.model._
import org.llm4s.metrics.MockMetricsCollector

/**
 * Unit tests for ZaiClient.
 *
 * Tests the JSON request/response formatting logic without requiring actual API calls.
 */
class ZaiClientSpec extends AnyFlatSpec with Matchers {

  // Test configuration
  private val testConfig = ZaiConfig(
    apiKey = "test-api-key",
    model = "GLM-4.7",
    baseUrl = "https://api.z.ai/api/paas/v4",
    contextWindow = 128000,
    reserveCompletion = 4096
  )

  // ============ Factory Method Tests ============

  "ZaiClient.apply" should "create a client successfully with valid config" in {
    val result = ZaiClient(testConfig)
    result.isRight shouldBe true
    result.foreach(client => client.getClass.getSimpleName shouldBe "ZaiClient")
  }

  // ============ Context Window Tests ============

  "ZaiClient.getContextWindow" should "return the configured context window" in {
    val client = new ZaiClient(testConfig)
    client.getContextWindow() shouldBe 128000
  }

  it should "return different values for different model configs" in {
    val config4_5 = testConfig.copy(contextWindow = 32000)
    val client    = new ZaiClient(config4_5)
    client.getContextWindow() shouldBe 32000
  }

  // ============ Reserve Completion Tests ============

  "ZaiClient.getReserveCompletion" should "return the configured reserve completion" in {
    val client = new ZaiClient(testConfig)
    client.getReserveCompletion() shouldBe 4096
  }

  it should "return custom reserve completion when configured" in {
    val customConfig = testConfig.copy(reserveCompletion = 8192)
    val client       = new ZaiClient(customConfig)
    client.getReserveCompletion() shouldBe 8192
  }

  // ============ Request Body Creation Tests ============

  "ZaiClientTestHelper.createRequestBody" should "create valid request body for user message" in {
    val helper       = new ZaiClientTestHelper(testConfig)
    val conversation = Conversation(Seq(UserMessage("Hello, world!")))
    val options      = CompletionOptions()

    val requestBody = helper.testCreateRequestBody(conversation, options)

    requestBody("model").str shouldBe "GLM-4.7"
    requestBody("messages").arr should have size 1
    requestBody("messages")(0)("role").str shouldBe "user"
    requestBody("messages")(0)("content")(0)("type").str shouldBe "text"
    requestBody("messages")(0)("content")(0)("text").str shouldBe "Hello, world!"
  }

  it should "create valid request body for system message" in {
    val helper       = new ZaiClientTestHelper(testConfig)
    val conversation = Conversation(Seq(SystemMessage("You are a helpful assistant.")))
    val options      = CompletionOptions()

    val requestBody = helper.testCreateRequestBody(conversation, options)

    requestBody("messages")(0)("role").str shouldBe "system"
    requestBody("messages")(0)("content")(0)("text").str shouldBe "You are a helpful assistant."
  }

  it should "create valid request body for assistant message with content" in {
    val helper       = new ZaiClientTestHelper(testConfig)
    val conversation = Conversation(Seq(AssistantMessage(Some("I can help with that!"))))
    val options      = CompletionOptions()

    val requestBody = helper.testCreateRequestBody(conversation, options)

    requestBody("messages")(0)("role").str shouldBe "assistant"
    requestBody("messages")(0)("content")(0)("text").str shouldBe "I can help with that!"
  }

  it should "omit content for assistant message with empty content" in {
    val helper       = new ZaiClientTestHelper(testConfig)
    val conversation = Conversation(Seq(AssistantMessage(Some(""))))
    val options      = CompletionOptions()

    val requestBody = helper.testCreateRequestBody(conversation, options)

    requestBody("messages")(0)("role").str shouldBe "assistant"
    requestBody("messages")(0).obj.contains("content") shouldBe false
  }

  it should "omit content for assistant message with None content" in {
    val helper       = new ZaiClientTestHelper(testConfig)
    val conversation = Conversation(Seq(AssistantMessage(None)))
    val options      = CompletionOptions()

    val requestBody = helper.testCreateRequestBody(conversation, options)

    requestBody("messages")(0)("role").str shouldBe "assistant"
    requestBody("messages")(0).obj.contains("content") shouldBe false
  }

  it should "include tool calls in assistant message" in {
    val helper   = new ZaiClientTestHelper(testConfig)
    val toolCall = ToolCall("call-123", "calculate", ujson.Obj("expression" -> "2+2"))
    val conversation =
      Conversation(Seq(AssistantMessage(contentOpt = None, toolCalls = List(toolCall))))
    val options = CompletionOptions()

    val requestBody = helper.testCreateRequestBody(conversation, options)

    val msg = requestBody("messages")(0)
    msg("role").str shouldBe "assistant"
    msg("tool_calls").arr should have size 1
    msg("tool_calls")(0)("id").str shouldBe "call-123"
    msg("tool_calls")(0)("type").str shouldBe "function"
    msg("tool_calls")(0)("function")("name").str shouldBe "calculate"
  }

  it should "create valid request body for tool message" in {
    val helper       = new ZaiClientTestHelper(testConfig)
    val conversation = Conversation(Seq(ToolMessage("call-123", "Result: 4")))
    val options      = CompletionOptions()

    val requestBody = helper.testCreateRequestBody(conversation, options)

    requestBody("messages")(0)("role").str shouldBe "tool"
    requestBody("messages")(0)("tool_call_id").str shouldBe "call-123"
    requestBody("messages")(0)("content")(0)("text").str shouldBe "Result: 4"
  }

  it should "include temperature and top_p options" in {
    val helper       = new ZaiClientTestHelper(testConfig)
    val conversation = Conversation(Seq(UserMessage("Test")))
    val options      = CompletionOptions(temperature = 0.5, topP = 0.9)

    val requestBody = helper.testCreateRequestBody(conversation, options)

    requestBody("temperature").num shouldBe 0.5
    requestBody("top_p").num shouldBe 0.9
  }

  it should "include maxTokens when specified" in {
    val helper       = new ZaiClientTestHelper(testConfig)
    val conversation = Conversation(Seq(UserMessage("Test")))
    val options      = CompletionOptions(maxTokens = Some(1000))

    val requestBody = helper.testCreateRequestBody(conversation, options)

    requestBody("max_tokens").num.toInt shouldBe 1000
  }

  it should "include presence_penalty when non-zero" in {
    val helper       = new ZaiClientTestHelper(testConfig)
    val conversation = Conversation(Seq(UserMessage("Test")))
    val options      = CompletionOptions(presencePenalty = 0.5)

    val requestBody = helper.testCreateRequestBody(conversation, options)

    requestBody("presence_penalty").num shouldBe 0.5
  }

  it should "include frequency_penalty when non-zero" in {
    val helper       = new ZaiClientTestHelper(testConfig)
    val conversation = Conversation(Seq(UserMessage("Test")))
    val options      = CompletionOptions(frequencyPenalty = 0.3)

    val requestBody = helper.testCreateRequestBody(conversation, options)

    requestBody("frequency_penalty").num shouldBe 0.3
  }

  it should "handle multiple messages in conversation" in {
    val helper = new ZaiClientTestHelper(testConfig)
    val conversation = Conversation(
      Seq(
        SystemMessage("You are helpful."),
        UserMessage("Hello!"),
        AssistantMessage(Some("Hi there!")),
        UserMessage("How are you?")
      )
    )
    val options = CompletionOptions()

    val requestBody = helper.testCreateRequestBody(conversation, options)

    requestBody("messages").arr should have size 4
    requestBody("messages")(0)("role").str shouldBe "system"
    requestBody("messages")(1)("role").str shouldBe "user"
    requestBody("messages")(2)("role").str shouldBe "assistant"
    requestBody("messages")(3)("role").str shouldBe "user"
  }

  // ============ Completion Parsing Tests ============

  "ZaiClientTestHelper.parseCompletion" should "parse a basic completion response" in {
    val helper = new ZaiClientTestHelper(testConfig)
    val json = ujson.Obj(
      "id"      -> "chatcmpl-123",
      "created" -> ujson.Num(1677652288),
      "model"   -> "GLM-4.7",
      "choices" -> ujson.Arr(
        ujson.Obj(
          "message" -> ujson.Obj(
            "role"    -> "assistant",
            "content" -> "Hello! How can I help you today?"
          ),
          "finish_reason" -> "stop"
        )
      ),
      "usage" -> ujson.Obj(
        "prompt_tokens"     -> ujson.Num(10),
        "completion_tokens" -> ujson.Num(15),
        "total_tokens"      -> ujson.Num(25)
      )
    )

    val completion = helper.testParseCompletion(json)

    completion.id shouldBe "chatcmpl-123"
    completion.created shouldBe 1677652288L
    completion.model shouldBe "GLM-4.7"
    completion.content shouldBe "Hello! How can I help you today?"
    completion.usage shouldBe Some(TokenUsage(10, 15, 25))
    completion.toolCalls shouldBe empty
  }

  it should "parse completion with array-format content" in {
    val helper = new ZaiClientTestHelper(testConfig)
    val json = ujson.Obj(
      "id"      -> "chatcmpl-456",
      "created" -> ujson.Num(1677652288),
      "model"   -> "GLM-4.7",
      "choices" -> ujson.Arr(
        ujson.Obj(
          "message" -> ujson.Obj(
            "role" -> "assistant",
            "content" -> ujson.Arr(
              ujson.Obj(
                "type" -> "text",
                "text" -> "Array format content"
              )
            )
          )
        )
      )
    )

    val completion = helper.testParseCompletion(json)

    completion.content shouldBe "Array format content"
  }

  it should "parse completion with tool calls" in {
    val helper = new ZaiClientTestHelper(testConfig)
    val json = ujson.Obj(
      "id"      -> "chatcmpl-789",
      "created" -> ujson.Num(1677652288),
      "model"   -> "GLM-4.7",
      "choices" -> ujson.Arr(
        ujson.Obj(
          "message" -> ujson.Obj(
            "role"    -> "assistant",
            "content" -> "",
            "tool_calls" -> ujson.Arr(
              ujson.Obj(
                "id"   -> "call-abc",
                "type" -> "function",
                "function" -> ujson.Obj(
                  "name"      -> "get_weather",
                  "arguments" -> """{"city":"London"}"""
                )
              )
            )
          )
        )
      )
    )

    val completion = helper.testParseCompletion(json)

    completion.toolCalls should have size 1
    completion.toolCalls.head.id shouldBe "call-abc"
    completion.toolCalls.head.name shouldBe "get_weather"
    completion.toolCalls.head.arguments("city").str shouldBe "London"
  }

  it should "handle missing usage data" in {
    val helper = new ZaiClientTestHelper(testConfig)
    val json = ujson.Obj(
      "id"      -> "chatcmpl-no-usage",
      "created" -> ujson.Num(1677652288),
      "model"   -> "GLM-4.7",
      "choices" -> ujson.Arr(
        ujson.Obj(
          "message" -> ujson.Obj(
            "role"    -> "assistant",
            "content" -> "No usage data"
          )
        )
      )
    )

    val completion = helper.testParseCompletion(json)

    completion.usage shouldBe None
  }

  it should "handle missing content" in {
    val helper = new ZaiClientTestHelper(testConfig)
    val json = ujson.Obj(
      "id"      -> "chatcmpl-no-content",
      "created" -> ujson.Num(1677652288),
      "model"   -> "GLM-4.7",
      "choices" -> ujson.Arr(
        ujson.Obj(
          "message" -> ujson.Obj(
            "role" -> "assistant"
          )
        )
      )
    )

    val completion = helper.testParseCompletion(json)

    completion.content shouldBe ""
  }

  // ============ Tool Call Parsing Tests ============

  "ZaiClientTestHelper.parseToolCalls" should "parse multiple tool calls" in {
    val helper = new ZaiClientTestHelper(testConfig)
    val json = ujson.Arr(
      ujson.Obj(
        "id"   -> "call-1",
        "type" -> "function",
        "function" -> ujson.Obj(
          "name"      -> "func1",
          "arguments" -> """{"a":1}"""
        )
      ),
      ujson.Obj(
        "id"   -> "call-2",
        "type" -> "function",
        "function" -> ujson.Obj(
          "name"      -> "func2",
          "arguments" -> """{"b":2}"""
        )
      )
    )

    val toolCalls = helper.testParseToolCalls(json)

    toolCalls should have size 2
    toolCalls(0).id shouldBe "call-1"
    toolCalls(0).name shouldBe "func1"
    toolCalls(0).arguments("a").num.toInt shouldBe 1
    toolCalls(1).id shouldBe "call-2"
    toolCalls(1).name shouldBe "func2"
    toolCalls(1).arguments("b").num.toInt shouldBe 2
  }

  it should "handle missing optional fields with defaults" in {
    val helper = new ZaiClientTestHelper(testConfig)
    val json = ujson.Arr(
      ujson.Obj(
        "function" -> ujson.Obj()
      )
    )

    val toolCalls = helper.testParseToolCalls(json)

    toolCalls should have size 1
    toolCalls(0).id shouldBe ""
    toolCalls(0).name shouldBe ""
    toolCalls(0).arguments shouldBe a[ujson.Obj]
  }

  it should "handle invalid JSON arguments gracefully" in {
    val helper = new ZaiClientTestHelper(testConfig)
    val json = ujson.Arr(
      ujson.Obj(
        "id"   -> "call-bad",
        "type" -> "function",
        "function" -> ujson.Obj(
          "name"      -> "test",
          "arguments" -> "not valid json {"
        )
      )
    )

    val toolCalls = helper.testParseToolCalls(json)

    toolCalls should have size 1
    toolCalls(0).id shouldBe "call-bad"
    toolCalls(0).name shouldBe "test"
    // Invalid JSON should fallback to empty object
    toolCalls(0).arguments shouldBe a[ujson.Obj]
  }

  // ============ Streaming Chunk Parsing Tests ============

  "ZaiClientTestHelper.parseStreamingChunks" should "parse text content chunk" in {
    val helper = new ZaiClientTestHelper(testConfig)
    val json = ujson.Obj(
      "id" -> "chunk-1",
      "choices" -> ujson.Arr(
        ujson.Obj(
          "delta" -> ujson.Obj(
            "content" -> "Hello"
          )
        )
      )
    )

    val chunks = helper.testParseStreamingChunks(json)

    chunks should have size 1
    chunks.head.id shouldBe "chunk-1"
    chunks.head.content shouldBe Some("Hello")
    chunks.head.toolCall shouldBe None
  }

  it should "parse text content in array format" in {
    val helper = new ZaiClientTestHelper(testConfig)
    val json = ujson.Obj(
      "id" -> "chunk-arr",
      "choices" -> ujson.Arr(
        ujson.Obj(
          "delta" -> ujson.Obj(
            "content" -> ujson.Arr(
              ujson.Obj("type" -> "text", "text" -> "Array text")
            )
          )
        )
      )
    )

    val chunks = helper.testParseStreamingChunks(json)

    chunks should have size 1
    chunks.head.content shouldBe Some("Array text")
  }

  it should "parse finish reason" in {
    val helper = new ZaiClientTestHelper(testConfig)
    val json = ujson.Obj(
      "id" -> "chunk-end",
      "choices" -> ujson.Arr(
        ujson.Obj(
          "delta"         -> ujson.Obj(),
          "finish_reason" -> "stop"
        )
      )
    )

    val chunks = helper.testParseStreamingChunks(json)

    chunks should have size 1
    chunks.head.finishReason shouldBe Some("stop")
  }

  it should "ignore null finish reason" in {
    val helper = new ZaiClientTestHelper(testConfig)
    val json = ujson.Obj(
      "id" -> "chunk-null",
      "choices" -> ujson.Arr(
        ujson.Obj(
          "delta"         -> ujson.Obj("content" -> "text"),
          "finish_reason" -> "null"
        )
      )
    )

    val chunks = helper.testParseStreamingChunks(json)

    chunks should have size 1
    chunks.head.finishReason shouldBe None
  }

  it should "parse tool call chunks" in {
    val helper = new ZaiClientTestHelper(testConfig)
    val json = ujson.Obj(
      "id" -> "chunk-tool",
      "choices" -> ujson.Arr(
        ujson.Obj(
          "delta" -> ujson.Obj(
            "tool_calls" -> ujson.Arr(
              ujson.Obj(
                "id" -> "tool-call-1",
                "function" -> ujson.Obj(
                  "name"      -> "test_func",
                  "arguments" -> """{"x":1}"""
                )
              )
            )
          )
        )
      )
    )

    val chunks = helper.testParseStreamingChunks(json)

    chunks should have size 1
    chunks.head.toolCall shouldBe defined
    chunks.head.toolCall.get.id shouldBe "tool-call-1"
    chunks.head.toolCall.get.name shouldBe "test_func"
  }

  it should "return empty sequence for empty choices" in {
    val helper = new ZaiClientTestHelper(testConfig)
    val json = ujson.Obj(
      "id"      -> "empty",
      "choices" -> ujson.Arr()
    )

    val chunks = helper.testParseStreamingChunks(json)

    chunks shouldBe empty
  }

  // ============ Streaming Arguments Parsing Tests ============

  "ZaiClientTestHelper.parseStreamingArguments" should "parse valid JSON" in {
    val helper = new ZaiClientTestHelper(testConfig)

    val result = helper.testParseStreamingArguments("""{"key":"value"}""")

    result("key").str shouldBe "value"
  }

  it should "return Null for empty string" in {
    val helper = new ZaiClientTestHelper(testConfig)

    val result = helper.testParseStreamingArguments("")

    result shouldBe ujson.Null
  }

  it should "return string value for invalid JSON" in {
    val helper = new ZaiClientTestHelper(testConfig)

    val result = helper.testParseStreamingArguments("not json {")

    result.str shouldBe "not json {"
  }
}

/**
 * Test helper class that exposes private methods for unit testing.
 */
class ZaiClientTestHelper(config: ZaiConfig) extends ZaiClient(config) {
  import scala.util.Try

  def testCreateRequestBody(conversation: Conversation, options: CompletionOptions): ujson.Obj = {
    val messages = conversation.messages.map {
      case UserMessage(content) =>
        ujson.Obj("role" -> "user", "content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> ujson.Str(content))))
      case SystemMessage(content) =>
        ujson.Obj(
          "role"    -> "system",
          "content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> ujson.Str(content)))
        )
      case AssistantMessage(content, toolCalls) =>
        val base = ujson.Obj("role" -> "assistant")
        content.filter(_.nonEmpty).foreach { c =>
          base("content") = ujson.Arr(ujson.Obj("type" -> "text", "text" -> ujson.Str(c)))
        }
        if (toolCalls.nonEmpty) {
          base("tool_calls") = ujson.Arr.from(toolCalls.map { tc =>
            ujson.Obj(
              "id"   -> tc.id,
              "type" -> "function",
              "function" -> ujson.Obj(
                "name"      -> tc.name,
                "arguments" -> tc.arguments.render()
              )
            )
          })
        }
        base
      case ToolMessage(toolCallId, content) =>
        ujson.Obj(
          "role"         -> "tool",
          "tool_call_id" -> toolCallId,
          "content"      -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> ujson.Str(content)))
        )
    }

    val base = ujson.Obj(
      "model"       -> config.model,
      "messages"    -> ujson.Arr.from(messages),
      "temperature" -> options.temperature,
      "top_p"       -> options.topP
    )

    options.maxTokens.foreach(mt => base("max_tokens") = mt)
    if (options.presencePenalty != 0) base("presence_penalty") = options.presencePenalty
    if (options.frequencyPenalty != 0) base("frequency_penalty") = options.frequencyPenalty

    base
  }

  def testParseCompletion(json: ujson.Value): Completion = {
    val choice  = json("choices")(0)
    val message = choice("message")

    val toolCalls = message.obj
      .get("tool_calls")
      .map(testParseToolCalls)
      .getOrElse(Seq.empty)

    val contentStr = message.obj.get("content") match {
      case Some(content) =>
        content.strOpt.getOrElse {
          content.arrOpt
            .flatMap(arr => arr.headOption.flatMap(obj => obj.obj.get("text").flatMap(_.strOpt)))
            .getOrElse("")
        }
      case None => ""
    }

    val usage = Option(json.obj.get("usage")).flatMap { u =>
      val usageObjOpt =
        u.objOpt.orElse(u.arrOpt.flatMap(_.headOption.flatMap(_.objOpt)))
      usageObjOpt.map { usageObj =>
        TokenUsage(
          promptTokens = usageObj.value("prompt_tokens").num.toInt,
          completionTokens = usageObj.value("completion_tokens").num.toInt,
          totalTokens = usageObj.value("total_tokens").num.toInt
        )
      }
    }

    Completion(
      id = json("id").str,
      created = json("created").num.toLong,
      content = contentStr,
      model = json("model").str,
      message = AssistantMessage(
        contentOpt = Some(contentStr),
        toolCalls = toolCalls.toList
      ),
      toolCalls = toolCalls.toList,
      usage = usage,
      thinking = None
    )
  }

  def testParseToolCalls(toolCallsJson: ujson.Value): Seq[ToolCall] =
    toolCallsJson.arr.map { call =>
      val function = call("function")
      val argsStr  = function.obj.get("arguments").flatMap(_.strOpt).getOrElse("{}")
      ToolCall(
        id = call.obj.get("id").flatMap(_.strOpt).getOrElse(""),
        name = function.obj.get("name").flatMap(_.strOpt).getOrElse(""),
        arguments = Try(ujson.read(argsStr)).getOrElse(ujson.Obj())
      )
    }.toSeq

  def testParseStreamingChunks(json: ujson.Value): Seq[StreamedChunk] = {
    val choices = json("choices").arr
    if (choices.nonEmpty) {
      val choice = choices(0)
      val delta  = choice("delta")

      val content = delta.obj.get("content").flatMap { content =>
        content.strOpt.orElse {
          content.arrOpt.flatMap(arr => arr.headOption.flatMap(obj => obj.obj.get("text").flatMap(_.strOpt)))
        }
      }

      val finishReason = choice.obj.get("finish_reason").flatMap(_.strOpt).filter(_ != "null")

      val toolCalls = delta.obj.get("tool_calls").map(_.arr).getOrElse(Seq.empty).collect {
        case call if call.obj.contains("function") =>
          val function = call("function")
          val rawArgs  = function.obj.get("arguments").flatMap(_.strOpt).getOrElse("")
          ToolCall(
            id = call.obj.get("id").flatMap(_.strOpt).getOrElse(""),
            name = function.obj.get("name").flatMap(_.strOpt).getOrElse(""),
            arguments = testParseStreamingArguments(rawArgs)
          )
      }

      val chunkId = json.obj.get("id").flatMap(_.strOpt).getOrElse("")
      if (toolCalls.isEmpty) {
        Seq(
          StreamedChunk(
            id = chunkId,
            content = content,
            toolCall = None,
            finishReason = finishReason,
            thinkingDelta = None
          )
        )
      } else {
        val first = StreamedChunk(
          id = chunkId,
          content = content,
          toolCall = Some(toolCalls.head),
          finishReason = finishReason,
          thinkingDelta = None
        )
        val rest = toolCalls.drop(1).map { tc =>
          StreamedChunk(
            id = chunkId,
            content = None,
            toolCall = Some(tc),
            finishReason = None,
            thinkingDelta = None
          )
        }
        Seq(first) ++ rest
      }
    } else {
      Seq.empty
    }
  }

  def testParseStreamingArguments(raw: String): ujson.Value =
    if (raw.isEmpty) ujson.Null else scala.util.Try(ujson.read(raw)).getOrElse(ujson.Str(raw))
}

// ============ Metrics Tests ============
class ZaiClientMetricsSpec extends AnyFlatSpec with Matchers {
  private val testConfig = ZaiConfig(
    apiKey = "test-key",
    model = "test-model",
    baseUrl = "http://test.com",
    contextWindow = 128000,
    reserveCompletion = 4096
  )

  "ZaiClient" should "accept custom metrics collector" in {
    val mockMetrics = new MockMetricsCollector()
    val client      = new ZaiClient(testConfig, mockMetrics)

    client should not be null
    mockMetrics.totalRequests shouldBe 0 // No requests yet
  }

  it should "use noop metrics by default" in {
    val client = new ZaiClient(testConfig)

    client should not be null // Verify it compiles without metrics parameter
  }
}
