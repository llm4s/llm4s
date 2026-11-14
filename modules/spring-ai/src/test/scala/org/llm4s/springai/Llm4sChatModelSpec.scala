package org.llm4s.springai

import org.llm4s.error.{ AuthenticationError, RateLimitError, ValidationError }
import org.llm4s.javaapi.{ FakeLlmClient, LlmErrorKind, LlmException }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  CompletionOptions,
  Conversation,
  StreamedChunk,
  SystemMessage,
  TokenUsage,
  ToolCall,
  ToolMessage,
  UserMessage
}
import org.llm4s.types.Result
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.SafeParameterExtractor
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.{
  AssistantMessage as SpringAssistantMessage,
  SystemMessage as SpringSystemMessage,
  ToolResponseMessage,
  UserMessage as SpringUserMessage
}
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.prompt.{ ChatOptions, Prompt }
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.locks.LockSupport
import scala.jdk.CollectionConverters.*

class Llm4sChatModelSpec extends AnyFlatSpec with Matchers {

  private def model(replies: Reply*): (ScriptedLLMClient, Llm4sChatModel) = {
    val client = ScriptedLLMClient.sequence(replies*)
    (client, new Llm4sChatModel(client))
  }

  private val weatherSchema =
    """{"type":"object","properties":{
      |  "city":{"type":"string","description":"The city"},
      |  "unit":{"type":"string","enum":["C","F"]},
      |  "days":{"type":"integer"},
      |  "threshold":{"type":"number"},
      |  "alerts":{"type":"boolean"},
      |  "tags":{"type":"array","items":{"type":"string"}},
      |  "hours":{"type":"array"},
      |  "where":{"type":"object","description":"A place","properties":{"lat":{"type":"number"}},"required":["lat"]}
      |},"required":["city"]}""".stripMargin

  final private class RecordingTool(name: String, schema: String) extends ToolCallback {
    @volatile var inputs: Vector[String] = Vector.empty
    override def getToolDefinition: ToolDefinition =
      ToolDefinition.builder().name(name).description(s"The $name tool").inputSchema(schema).build()
    override def call(toolInput: String): String = {
      inputs = inputs :+ toolInput
      if (toolInput.contains("boom")) throw new IllegalStateException("tool failed") else "sunny"
    }
  }

  // `toolCallbacks` is deprecated in Spring AI 2.0 in favour of `tools`, which reads @Tool methods off an object and
  // takes no ToolCallback; a ToolCallback still goes through `toolCallbacks`.
  @annotation.nowarn("cat=deprecation")
  private def askWithTool(chatClient: ChatClient, tool: ToolCallback): String =
    chatClient.prompt().user("Weather?").toolCallbacks(tool).call().content()

  "call" should "answer a plain-text prompt with the llm4s client's reply" in {
    val (client, chat) = model(Reply.text("4"))
    chat.call("What is 2+2?") shouldBe "4"
    client.lastCall.flatMap(_.lastUserText) shouldBe Some("What is 2+2?")
  }

  it should "send system and user messages in order and report the model, id and usage" in {
    val usage = TokenUsage(
      promptTokens = 12,
      completionTokens = 3,
      totalTokens = 15,
      cachedTokens = Some(4),
      cacheCreationTokens = Some(2)
    )
    val (client, chat) = model(Reply.text("Bonjour").withModel("fixture-model").withUsage(usage))
    val response = chat.call(
      new Prompt(java.util.List.of(new SpringSystemMessage("Answer in French"), new SpringUserMessage("Hello")))
    )

    client.lastCall.map(_.messages) shouldBe Some(Seq(SystemMessage("Answer in French"), UserMessage("Hello")))
    response.getResult.getOutput.getText shouldBe "Bonjour"
    response.getResult.getMetadata.getFinishReason shouldBe "STOP"
    val metadata: ChatResponseMetadata = response.getMetadata
    metadata.getModel shouldBe "fixture-model"
    metadata.getId shouldBe "scripted-1"
    metadata.getUsage.getPromptTokens shouldBe 12
    metadata.getUsage.getCompletionTokens shouldBe 3
    metadata.getUsage.getTotalTokens shouldBe 15
    metadata.getUsage.getCacheReadInputTokens shouldBe 4L
    metadata.getUsage.getCacheWriteInputTokens shouldBe 2L
    metadata.getUsage.getNativeUsage shouldBe usage
  }

  it should "report no usage when the llm4s client reported none" in {
    val (_, chat) = model(Reply.text("ok"))
    chat.call(new Prompt("hi")).getMetadata.getUsage.getTotalTokens shouldBe 0
  }

  it should "send temperature, top-p, max tokens and the penalties, and not the model" in {
    val (client, chat) = model(Reply.text("ok"))
    val options = ChatOptions
      .builder()
      .model("not-sent")
      .temperature(0.2)
      .topP(0.9)
      .maxTokens(64)
      .presencePenalty(0.5)
      .frequencyPenalty(0.25)
      .build()
    chat.call(new Prompt("hi", options))

    val sent = client.lastCall.map(_.options).get
    sent.temperature shouldBe 0.2
    sent.topP shouldBe 0.9
    sent.maxTokens shouldBe Some(64)
    sent.presencePenalty shouldBe 0.5
    sent.frequencyPenalty shouldBe 0.25
    sent.tools shouldBe empty
  }

  it should "keep llm4s's defaults for options a prompt leaves unset, or for a prompt with no options" in {
    val (client, chat) = model(Reply.text("a"), Reply.text("b"))
    chat.call(new Prompt("hi", ChatOptions.builder().build()))
    chat.call(new Prompt(java.util.List.of(new SpringUserMessage("hi")), null))
    client.calls.map(_.options) shouldBe Vector(
      org.llm4s.llmconnect.model.CompletionOptions(),
      org.llm4s.llmconnect.model.CompletionOptions()
    )
  }

  it should "refuse stop sequences and top-k, which no llm4s client sends, without calling the client" in {
    val (client, chat) = model()
    val stop = intercept[LlmException](
      chat.call(new Prompt("hi", ChatOptions.builder().stopSequences(java.util.List.of("END")).build()))
    )
    stop.getKind shouldBe LlmErrorKind.VALIDATION
    stop.getMessage should include("stop sequences")
    intercept[LlmException](
      chat.call(new Prompt("hi", ChatOptions.builder().topK(5).build()))
    ).getMessage should include(
      "top-k"
    )
    client.callCount shouldBe 0
  }

  it should "send the prompt's tools as llm4s tool definitions, and return the model's tool call" in {
    val tool           = new RecordingTool("weather", weatherSchema)
    val (client, chat) = model(Reply.toolCall("weather", """{"city":"Paris"}""", Some("call-7")))
    val response =
      chat.call(new Prompt("Weather in Paris?", ToolCallingChatOptions.builder().toolCallbacks(tool).build()))

    val sent = client.lastCall.get.options.tools
    sent.map(_.name) shouldBe Seq("weather")
    sent.head.description shouldBe "The weather tool"
    val schema = sent.head.schema.toJsonSchema(false)
    schema("required").arr.map(_.str) shouldBe Seq("city")
    schema("properties")("city")("description").str shouldBe "The city"
    schema("properties")("unit")("enum").arr.map(_.str) shouldBe Seq("C", "F")
    schema("properties")("days")("type").str shouldBe "integer"
    schema("properties")("threshold")("type").str shouldBe "number"
    schema("properties")("alerts")("type").str shouldBe "boolean"
    schema("properties")("tags")("items")("type").str shouldBe "string"
    schema("properties")("hours")("items")("type").str shouldBe "string"
    schema("properties")("where")("description").str shouldBe "A place"
    schema("properties")("where")("required").arr.map(_.str) shouldBe Seq("lat")

    response.hasToolCalls shouldBe true
    response.getResult.getMetadata.getFinishReason shouldBe "TOOL_CALLS"
    val call = response.getResult.getOutput.getToolCalls.asScala.head
    (call.id, call.`type`, call.name, ujson.read(call.arguments)) shouldBe ("call-7", "function", "weather", ujson.Obj(
      "city" -> "Paris"
    ))
    tool.inputs shouldBe empty
  }

  it should "run the Spring AI tool when the llm4s side calls the tool definition it was given" in {
    val tool           = new RecordingTool("weather", """{"type":"object","properties":{}}""")
    val (client, chat) = model(Reply.text("ok"))
    chat.call(new Prompt("hi", ToolCallingChatOptions.builder().toolCallbacks(tool).build()))
    val function = client.lastCall.get.options.tools.head
    function.schema.toJsonSchema(false)("description").str shouldBe "Parameters for weather"

    function.execute(ujson.Obj("city" -> "Oslo")) shouldBe Right(ujson.Str("sunny"))
    tool.inputs shouldBe Vector("""{"city":"Oslo"}""")
    function.handler(SafeParameterExtractor(ujson.Obj("city" -> "boom"))) shouldBe Left("tool failed")
  }

  it should "send no tools when the options carry none" in {
    val (client, chat) = model(Reply.text("ok"))
    chat.call(new Prompt("hi", ToolCallingChatOptions.builder().build()))
    client.lastCall.get.options.tools shouldBe empty
  }

  it should "refuse a tool whose input schema is not JSON" in {
    val (client, chat) = model()
    val error = intercept[LlmException](
      chat.call(
        new Prompt("hi", ToolCallingChatOptions.builder().toolCallbacks(new RecordingTool("bad", "{nope")).build())
      )
    )
    error.getMessage should include("input schema of tool 'bad'")
    client.callCount shouldBe 0
  }

  it should "carry a tool call and its result back to the llm4s client" in {
    val (client, chat) = model(Reply.text("It is sunny in Paris"))
    val assistant = SpringAssistantMessage
      .builder()
      .content("")
      .toolCalls(
        java.util.List.of(
          new SpringAssistantMessage.ToolCall("call-1", "function", "weather", """{"city":"Paris"}"""),
          new SpringAssistantMessage.ToolCall("call-2", "function", "time", "")
        )
      )
      .build()
    val results = ToolResponseMessage
      .builder()
      .responses(
        java.util.List.of(
          new ToolResponseMessage.ToolResponse("call-1", "weather", "sunny"),
          new ToolResponseMessage.ToolResponse("call-2", "time", null)
        )
      )
      .build()
    val answer = chat.call(new Prompt(java.util.List.of(new SpringUserMessage("Weather?"), assistant, results)))

    answer.getResult.getOutput.getText shouldBe "It is sunny in Paris"
    client.lastCall.map(_.messages) shouldBe Some(
      Seq(
        UserMessage("Weather?"),
        AssistantMessage(
          contentOpt = None,
          toolCalls =
            Seq(ToolCall("call-1", "weather", ujson.Obj("city" -> "Paris")), ToolCall("call-2", "time", ujson.Obj()))
        ),
        ToolMessage("sunny", "call-1"),
        ToolMessage("", "call-2")
      )
    )
  }

  it should "refuse a tool call whose arguments are not JSON" in {
    val (client, chat) = model()
    val assistant = SpringAssistantMessage
      .builder()
      .content("")
      .toolCalls(java.util.List.of(new SpringAssistantMessage.ToolCall("c", "function", "weather", "{oops")))
      .build()
    intercept[LlmException](chat.call(new Prompt(java.util.List.of(assistant)))).getMessage should include(
      "tool 'weather' are not JSON"
    )
    client.callCount shouldBe 0
  }

  it should "throw an LlmException carrying the llm4s error" in {
    val (_, chat) = model(Reply.failure(AuthenticationError("fixture", "bad key")))
    val error     = intercept[LlmException](chat.call("hi"))
    error.getKind shouldBe LlmErrorKind.AUTHENTICATION
    error.error shouldBe AuthenticationError("fixture", "bad key")
  }

  "stream" should "emit each streamed piece of text, then the finish reason, model and usage" in {
    val usage     = TokenUsage(promptTokens = 5, completionTokens = 4, totalTokens = 9)
    val client    = ScriptedLLMClient.sequence(Reply.text("Hello there").withUsage(usage)).withChunkSize(5)
    val responses = new Llm4sChatModel(client).stream(new Prompt("hi")).collectList().block().asScala.toList

    responses.map(_.getResult.getOutput.getText) shouldBe List("Hello", " ther", "e", "")
    val last = responses.last
    last.getResult.getMetadata.getFinishReason shouldBe "STOP"
    last.getMetadata.getModel shouldBe "scripted-model"
    last.getMetadata.getUsage.getTotalTokens shouldBe 9
    client.lastCall.map(_.streamed) shouldBe Some(true)
  }

  it should "deliver the tool calls in its last response" in {
    val client = ScriptedLLMClient.sequence(Reply.toolCall("weather", """{"city":"Paris"}""", Some("c1")))
    val last   = new Llm4sChatModel(client).stream(new Prompt("hi")).blockLast()
    last.getResult.getMetadata.getFinishReason shouldBe "TOOL_CALLS"
    last.getResult.getOutput.getToolCalls.asScala.map(_.name) shouldBe Seq("weather")
  }

  it should "signal the llm4s error as the Flux's error" in {
    val (_, chat) = model(Reply.failure(RateLimitError("fixture")))
    intercept[LlmException](chat.stream(new Prompt("hi")).blockLast()).getKind shouldBe LlmErrorKind.RATE_LIMIT
  }

  it should "signal a prompt it cannot send as the Flux's error, without calling the client" in {
    val (client, chat) = model()
    intercept[LlmException](
      chat.stream(new Prompt("hi", ChatOptions.builder().topK(1).build())).blockLast()
    ).getKind shouldBe LlmErrorKind.VALIDATION
    client.callCount shouldBe 0
  }

  it should "end the Flux with the error when the llm4s client throws instead of returning a Left" in {
    val throwing = new LLMClient {
      override def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
        Left(ValidationError("x", "unused"))
      override def streamComplete(
        c: Conversation,
        o: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = throw new IllegalStateException("client bug")
      override def getContextWindow(): Int     = 1000
      override def getReserveCompletion(): Int = 100
    }
    val error = intercept[IllegalStateException](
      new Llm4sChatModel(throwing).stream(new Prompt("hi")).blockLast(java.time.Duration.ofSeconds(30))
    )
    error.getMessage shouldBe "client bug"
  }

  it should "interrupt the llm4s call when the subscriber cancels" in {
    val interrupted = new CountDownLatch(1)
    val blocking = new LLMClient {
      override def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
        Left(ValidationError("x", "unused"))
      override def streamComplete(
        c: Conversation,
        o: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = {
        onChunk(StreamedChunk("s1", Some("first")))
        // Waits until the cancelled subscription interrupts this thread.
        while (!Thread.currentThread().isInterrupted) LockSupport.park()
        interrupted.countDown()
        Left(ValidationError("x", "interrupted"))
      }
      override def getContextWindow(): Int     = 1000
      override def getReserveCompletion(): Int = 100
    }
    val first = new Llm4sChatModel(blocking).stream(new Prompt("hi")).take(1).collectList().block().asScala
    first.map(_.getResult.getOutput.getText) shouldBe Seq("first")
    interrupted.await(30, TimeUnit.SECONDS) shouldBe true
  }

  it should "stream text through a ChatClient" in {
    val client = ScriptedLLMClient.sequence(Reply.text("streamed answer")).withChunkSize(4)
    ChatClient
      .create(new Llm4sChatModel(client))
      .prompt()
      .user("hi")
      .stream()
      .content()
      .collectList()
      .block()
      .asScala
      .mkString shouldBe
      "streamed answer"
  }

  "a ChatClient" should "answer through the llm4s client, with its system prompt" in {
    val (client, chat) = model(Reply.text("Paris"))
    ChatClient.create(chat).prompt().system("Be brief").user("Capital of France?").call().content() shouldBe "Paris"
    client.lastCall.map(_.messages) shouldBe Some(Seq(SystemMessage("Be brief"), UserMessage("Capital of France?")))
  }

  it should "run a tool through its tool-calling loop and send the result back to llm4s" in {
    val tool = new RecordingTool("weather", weatherSchema)
    val (client, chat) =
      model(Reply.toolCall("weather", """{"city":"Paris"}""", Some("c1")), Reply.text("Sunny in Paris"))
    val chatClient = ChatClient.create(chat)
    val content    = askWithTool(chatClient, tool)
    client.calls.head.options.tools.map(_.name) shouldBe Seq("weather")
    client.callCount shouldBe 2
    content shouldBe "Sunny in Paris"
    tool.inputs shouldBe Vector("""{"city":"Paris"}""")
    client.calls.last.messages.last shouldBe ToolMessage("sunny", "c1")
  }

  "Llm4sChatModel.of" should "adapt the Java facade's client" in {
    val fake = FakeLlmClient.replying("from the facade")
    Llm4sChatModel.of(fake.client()).call("hi") shouldBe "from the facade"
    fake.prompts().asScala shouldBe Seq("hi")
  }

  "the model" should "have no default options of its own, and refuse a null client" in {
    val (_, chat) = model()
    val defaults  = chat.getOptions
    defaults shouldBe a[ToolCallingChatOptions]
    defaults.getTemperature shouldBe null
    defaults.getModel shouldBe null
    intercept[NullPointerException](new Llm4sChatModel(null))
    intercept[NullPointerException](Llm4sChatModel.of(null))
  }
}
