package org.llm4s.springai

import org.llm4s.error.{ AuthenticationError, LLMError, ValidationError }
import org.llm4s.javaapi.{ LlmException, Llm4s }
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  CompletionOptions,
  Conversation,
  StreamedChunk,
  SystemMessage,
  TokenUsage,
  ToolCall,
  ToolMessage,
  UserMessage
}
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.{ Schema, ToolBuilder }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.springframework.ai.chat.messages.{
  AssistantMessage as SpringAssistantMessage,
  MessageType,
  SystemMessage as SpringSystemMessage,
  ToolResponseMessage,
  UserMessage as SpringUserMessage
}
import org.springframework.ai.chat.metadata.{ ChatGenerationMetadata, ChatResponseMetadata, DefaultUsage }
import org.springframework.ai.chat.model.{ ChatModel, ChatResponse, Generation }
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import reactor.core.publisher.Flux

import scala.jdk.CollectionConverters.*

class SpringAiLlmClientSpec extends AnyFlatSpec with Matchers with EitherValues {

  /** A Spring AI model that answers from a function and records every prompt it was given. */
  final private class FakeChatModel(
    answer: Prompt => ChatResponse,
    streaming: Prompt => Flux[ChatResponse] = _ => Flux.error(new UnsupportedOperationException("no streaming"))
  ) extends ChatModel {
    @volatile var prompts: Vector[Prompt] = Vector.empty
    override def call(prompt: Prompt): ChatResponse = {
      prompts = prompts :+ prompt
      answer(prompt)
    }
    override def stream(prompt: Prompt): Flux[ChatResponse] = {
      prompts = prompts :+ prompt
      streaming(prompt)
    }
  }

  private def reply(
    text: String,
    calls: Seq[SpringAssistantMessage.ToolCall] = Nil,
    id: String = "resp-1",
    model: String = "spring-model",
    usage: Option[DefaultUsage] = None,
    finish: String = "STOP"
  ): ChatResponse = {
    val output   = SpringAssistantMessage.builder().content(text).toolCalls(calls.asJava).build()
    val metadata = ChatResponseMetadata.builder().id(id).model(model)
    usage.foreach(metadata.usage)
    new ChatResponse(
      java.util.List.of(new Generation(output, ChatGenerationMetadata.builder().finishReason(finish).build())),
      metadata.build()
    )
  }

  private val weather = ToolBuilder[Map[String, Any], String](
    "weather",
    "Current weather in a city",
    Schema.`object`[Map[String, Any]]("Weather query").withProperty(Schema.property("city", Schema.string("City name")))
  ).withHandler(params => params.getString("city").map(city => s"sunny in $city")).buildSafe().value

  "complete" should "answer with the Spring AI model's reply, its id, model and usage" in {
    val usage  = new DefaultUsage(10, 2, 12, null, 3L, 1L)
    val model  = new FakeChatModel(_ => reply("4", usage = Some(usage)))
    val client = new SpringAiLlmClient(model)
    val done   = client.complete(Conversation(Seq(UserMessage("What is 2+2?"))), CompletionOptions()).value

    done.content shouldBe "4"
    done.message shouldBe AssistantMessage("4")
    done.id shouldBe "resp-1"
    done.model shouldBe "spring-model"
    done.usage shouldBe Some(TokenUsage(10, 2, 12, cachedTokens = Some(3), cacheCreationTokens = Some(1)))
    model.prompts.head.getUserMessage.getText shouldBe "What is 2+2?"
  }

  it should "send the system and user messages in order" in {
    val model = new FakeChatModel(_ => reply("Bonjour"))
    new SpringAiLlmClient(model)
      .complete(Conversation(Seq(SystemMessage("In French"), UserMessage("Hello"))), CompletionOptions())
    model.prompts.head.getInstructions.asScala.map(m => (m.getMessageType, m.getText)) shouldBe Seq(
      (MessageType.SYSTEM, "In French"),
      (MessageType.USER, "Hello")
    )
  }

  it should "send temperature, top-p, max tokens and the penalties as tool-calling options" in {
    val model = new FakeChatModel(_ => reply("ok"))
    val options = CompletionOptions()
      .withTemperature(0.3)
      .withTopP(0.8)
      .withMaxTokens(100)
      .withPresencePenalty(0.1)
      .withFrequencyPenalty(0.2)
    new SpringAiLlmClient(model).complete(Conversation(Seq(UserMessage("hi"))), options)

    val sent = model.prompts.head.getOptions.asInstanceOf[ToolCallingChatOptions]
    sent.getTemperature shouldBe 0.3
    sent.getTopP shouldBe 0.8
    sent.getMaxTokens shouldBe 100
    sent.getPresencePenalty shouldBe 0.1
    sent.getFrequencyPenalty shouldBe 0.2
    sent.getToolCallbacks shouldBe empty
  }

  it should "leave max tokens unset when llm4s sets none" in {
    val model = new FakeChatModel(_ => reply("ok"))
    new SpringAiLlmClient(model).complete(Conversation(Seq(UserMessage("hi"))), CompletionOptions())
    model.prompts.head.getOptions.getMaxTokens shouldBe null
  }

  it should "send llm4s tools as Spring AI tool definitions, and return the model's tool calls" in {
    val call  = new SpringAssistantMessage.ToolCall("call-1", "function", "weather", """{"city":"Paris"}""")
    val model = new FakeChatModel(_ => reply("", calls = Seq(call), finish = "TOOL_CALLS"))
    val done = new SpringAiLlmClient(model)
      .complete(Conversation(Seq(UserMessage("Weather in Paris?"))), CompletionOptions().withTools(Seq(weather)))
      .value

    done.toolCalls shouldBe List(ToolCall("call-1", "weather", ujson.Obj("city" -> "Paris")))
    done.message.toolCalls shouldBe done.toolCalls
    done.message.contentOpt shouldBe None
    val callback = model.prompts.head.getOptions.asInstanceOf[ToolCallingChatOptions].getToolCallbacks.asScala.head
    callback.getToolDefinition.name shouldBe "weather"
    callback.getToolDefinition.description shouldBe "Current weather in a city"
    ujson.read(callback.getToolDefinition.inputSchema)("properties")("city")("type").str shouldBe "string"
    callback.call("""{"city":"Oslo"}""") shouldBe "\"sunny in Oslo\""
    intercept[LlmException](callback.call("{}")).getMessage should include("city")
  }

  it should "send a tool call and its results back as one tool-response message" in {
    val model = new FakeChatModel(_ => reply("Sunny in Paris and Oslo"))
    val conversation = Conversation(
      Seq(
        UserMessage("Weather?"),
        AssistantMessage(
          contentOpt = None,
          toolCalls =
            Seq(ToolCall("c1", "weather", ujson.Obj("city" -> "Paris")), ToolCall("c2", "weather", ujson.Obj()))
        ),
        ToolMessage("sunny in Paris", "c1"),
        ToolMessage("sunny in Oslo", "c2"),
        ToolMessage("orphan", "c9")
      )
    )
    new SpringAiLlmClient(model)
      .complete(conversation, CompletionOptions())
      .value
      .content shouldBe "Sunny in Paris and Oslo"

    val sent = model.prompts.head.getInstructions.asScala.toList
    sent.map(_.getMessageType) shouldBe List(MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL)
    sent(1)
      .asInstanceOf[SpringAssistantMessage]
      .getToolCalls
      .asScala
      .map(c => (c.id, c.name, c.arguments)) shouldBe Seq(
      ("c1", "weather", """{"city":"Paris"}"""),
      ("c2", "weather", "{}")
    )
    sent(2)
      .asInstanceOf[ToolResponseMessage]
      .getResponses
      .asScala
      .map(r => (r.id, r.name, r.responseData)) shouldBe Seq(
      ("c1", "weather", "sunny in Paris"),
      ("c2", "weather", "sunny in Oslo"),
      ("c9", "", "orphan")
    )
  }

  it should "report no usage when the model reported none, and make up an id when it gave none" in {
    val model = new FakeChatModel(_ => reply("ok", id = ""))
    val done  = new SpringAiLlmClient(model).complete(Conversation(Seq(UserMessage("hi"))), CompletionOptions()).value
    done.usage shouldBe None
    done.id should not be empty
  }

  it should "refuse a reply with no generation, and a tool call whose arguments are not JSON" in {
    val empty = new FakeChatModel(_ => new ChatResponse(java.util.List.of()))
    new SpringAiLlmClient(empty)
      .complete(Conversation(Seq(UserMessage("hi"))), CompletionOptions())
      .left
      .value shouldBe a[
      ValidationError
    ]
    val bad = new FakeChatModel(_ =>
      reply("", calls = Seq(new SpringAssistantMessage.ToolCall("c", "function", "weather", "{x")))
    )
    new SpringAiLlmClient(bad)
      .complete(Conversation(Seq(UserMessage("hi"))), CompletionOptions())
      .left
      .value
      .message should include("not JSON")
  }

  it should "return the model's exception as a Left" in {
    val model = new FakeChatModel(_ => throw new IllegalStateException("model down"))
    val error =
      new SpringAiLlmClient(model).complete(Conversation(Seq(UserMessage("hi"))), CompletionOptions()).left.value
    error.message should include("model down")
  }

  "streamComplete" should "deliver each piece of text as a chunk and put the reply together" in {
    val pieces = Flux.just(
      reply("Hel", finish = ""),
      reply("lo", finish = "STOP"),
      // A last response with the usage alone and no generation, as some models send.
      new ChatResponse(
        java.util.List.of(),
        ChatResponseMetadata.builder().id("resp-1").model("spring-model").usage(new DefaultUsage(3, 2, 5)).build()
      )
    )
    val model  = new FakeChatModel(_ => reply("unused"), _ => pieces)
    val chunks = Vector.newBuilder[StreamedChunk]
    val done = new SpringAiLlmClient(model)
      .streamComplete(Conversation(Seq(UserMessage("hi"))), CompletionOptions(), chunk => chunks += chunk)
      .value

    chunks.result().map(c => (c.content, c.finishReason)) shouldBe Vector(
      (Some("Hel"), None),
      (Some("lo"), Some("STOP"))
    )
    done.content shouldBe "Hello"
    done.model shouldBe "spring-model"
    done.id shouldBe "resp-1"
    done.usage shouldBe Some(TokenUsage(3, 2, 5))
  }

  it should "deliver each tool call once, however many responses repeat it" in {
    val call = new SpringAssistantMessage.ToolCall("c1", "function", "weather", """{"city":"Paris"}""")
    val pieces =
      Flux.just(
        reply("", calls = Seq(call), id = "", finish = ""),
        reply("", calls = Seq(call), id = "", finish = "TOOL_CALLS")
      )
    val model  = new FakeChatModel(_ => reply("unused"), _ => pieces)
    val chunks = Vector.newBuilder[StreamedChunk]
    val done = new SpringAiLlmClient(model)
      .streamComplete(Conversation(Seq(UserMessage("hi"))), CompletionOptions(), chunk => chunks += chunk)
      .value

    chunks.result().flatMap(_.toolCall) shouldBe Vector(ToolCall("c1", "weather", ujson.Obj("city" -> "Paris")))
    done.toolCalls shouldBe List(ToolCall("c1", "weather", ujson.Obj("city" -> "Paris")))
    done.content shouldBe ""
    done.usage shouldBe None
    done.id should not be empty
    done.model shouldBe "spring-model"
  }

  it should "return a streaming error, or a bad tool call in a stream, as a Left" in {
    val failing = new FakeChatModel(_ => reply("unused"))
    new SpringAiLlmClient(failing)
      .streamComplete(Conversation(Seq(UserMessage("hi"))), CompletionOptions(), _ => ())
      .left
      .value
      .message should include("no streaming")

    val bad = new FakeChatModel(
      _ => reply("unused"),
      _ => Flux.just(reply("", calls = Seq(new SpringAssistantMessage.ToolCall("c", "function", "weather", "{x"))))
    )
    new SpringAiLlmClient(bad)
      .streamComplete(Conversation(Seq(UserMessage("hi"))), CompletionOptions(), _ => ())
      .left
      .value shouldBe a[ValidationError]
  }

  "a round trip" should "carry an llm4s client through Spring AI and back, error and all" in {
    val usage = TokenUsage(7, 1, 8)
    val scripted = ScriptedLLMClient.sequence(
      Reply.text("Paris").withModel("fixture-model").withUsage(usage),
      Reply.failure(AuthenticationError("fixture", "bad key")),
      Reply.text("streamed")
    )
    val client       = new SpringAiLlmClient(new Llm4sChatModel(scripted), 32000, 1000)
    val conversation = Conversation(Seq(SystemMessage("Be brief"), UserMessage("Capital of France?")))

    val done = client.complete(conversation, CompletionOptions().withTemperature(0.1)).value
    (done.content, done.model, done.usage) shouldBe ("Paris", "fixture-model", Some(usage))
    scripted.lastCall.map(_.messages) shouldBe Some(conversation.messages)
    scripted.lastCall.map(_.options.temperature) shouldBe Some(0.1)

    client.complete(conversation, CompletionOptions()).left.value shouldBe (AuthenticationError(
      "fixture",
      "bad key"
    ): LLMError)

    val chunks = Vector.newBuilder[String]
    client
      .streamComplete(conversation, CompletionOptions(), c => c.content.foreach(chunks += _))
      .value
      .content shouldBe "streamed"
    chunks.result().mkString shouldBe "streamed"

    (client.getContextWindow(), client.getReserveCompletion()) shouldBe (32000, 1000)
  }

  "the client" should "assume a 128k context window by default, wrap as a JLlmClient, and refuse a null model" in {
    val client = new SpringAiLlmClient(new FakeChatModel(_ => reply("from Spring AI")))
    (client.getContextWindow(), client.getReserveCompletion()) shouldBe (128000, 4096)
    Llm4s.wrapClient(client).complete("hi").get() shouldBe "from Spring AI"
    intercept[NullPointerException](new SpringAiLlmClient(null))
  }

  "SpringSystemMessage" should "map from llm4s's system message" in {
    val model = new FakeChatModel(_ => reply("ok"))
    new SpringAiLlmClient(model).complete(Conversation(Seq(SystemMessage("s"), UserMessage("u"))), CompletionOptions())
    model.prompts.head.getInstructions.get(0) shouldBe new SpringSystemMessage("s")
    model.prompts.head.getInstructions.get(1).getText shouldBe new SpringUserMessage("u").getText
  }
}
