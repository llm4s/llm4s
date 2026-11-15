package org.llm4s.springai

import org.llm4s.error.ValidationError
import org.llm4s.javaapi.LlmException
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  CompletionOptions,
  Conversation,
  Message,
  SystemMessage,
  TokenUsage,
  ToolCall,
  ToolMessage,
  UserMessage
}
import org.llm4s.toolapi.{
  ArraySchema,
  BooleanSchema,
  IntegerSchema,
  NumberSchema,
  ObjectSchema,
  PropertyDefinition,
  SchemaDefinition,
  StringSchema,
  ToolFunction
}
import org.llm4s.types.{ Result, TryOps }
import org.springframework.ai.chat.messages.{
  AssistantMessage as SpringAssistantMessage,
  Message as SpringMessage,
  MessageType,
  SystemMessage as SpringSystemMessage,
  ToolResponseMessage,
  UserMessage as SpringUserMessage
}
import org.springframework.ai.chat.metadata.{ ChatGenerationMetadata, ChatResponseMetadata, DefaultUsage, Usage }
import org.springframework.ai.chat.model.{ ChatResponse, Generation }
import org.springframework.ai.chat.prompt.{ ChatOptions, Prompt }
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition

import java.time.Instant
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * The conversions between Spring AI's chat types and llm4s's, in both directions. What has no counterpart on the other
 * side is refused (stop sequences and top-k, which no llm4s client sends) or left out (media, which `docs/guide/spring-ai.md`
 * lists), never silently changed.
 */
private[springai] object SpringAiMapping {

  /** The finish reasons Spring AI's own models report, which its tool-calling advisor reads. */
  val ToolCallsFinish: String = "TOOL_CALLS"
  val StopFinish: String      = "STOP"

  // ---- Spring AI -> llm4s -------------------------------------------------------------------------------------------

  def conversation(prompt: Prompt): Result[Conversation] =
    prompt.getInstructions.asScala.toList
      .foldLeft[Result[Vector[Message]]](Right(Vector.empty)) { (acc, message) =>
        acc.flatMap(messages => toLlm4s(message).map(messages ++ _))
      }
      .map(Conversation(_))

  private def toLlm4s(message: SpringMessage): Result[Seq[Message]] = message.getMessageType match {
    case MessageType.SYSTEM => Right(Seq(SystemMessage(textOf(message))))
    case MessageType.USER   => Right(Seq(UserMessage(textOf(message))))
    case MessageType.ASSISTANT =>
      val assistant = message.asInstanceOf[SpringAssistantMessage]
      toolCalls(assistant).map(calls =>
        Seq(AssistantMessage(contentOpt = Option(assistant.getText).filter(_.nonEmpty), toolCalls = calls))
      )
    case MessageType.TOOL =>
      Right(
        message
          .asInstanceOf[ToolResponseMessage]
          .getResponses
          .asScala
          .toSeq
          .map(response => ToolMessage(Option(response.responseData).getOrElse(""), response.id))
      )
  }

  /** An assistant message's tool calls, with their arguments parsed. */
  def toolCalls(message: SpringAssistantMessage): Result[Vector[ToolCall]] =
    message.getToolCalls.asScala.toList.foldLeft[Result[Vector[ToolCall]]](Right(Vector.empty)) { (acc, call) =>
      acc.flatMap(calls =>
        arguments(call.name, call.arguments).map(args => calls :+ ToolCall(call.id, call.name, args))
      )
    }

  private def textOf(message: SpringMessage): String = Option(message.getText).getOrElse("")

  /** A tool call's arguments, as JSON; blank arguments are an empty object, as a call with no parameters sends. */
  private def arguments(tool: String, json: String): Result[ujson.Value] =
    if (json == null || json.trim.isEmpty) Right(ujson.Obj())
    else
      Try(ujson.read(json)).toResult.left.map(_ =>
        ValidationError("arguments", s"the arguments of the call of tool '$tool' are not JSON: $json")
      )

  /**
   * The request options: temperature, top-p, max tokens, presence and frequency penalty and the tool definitions are
   * carried; the model is the llm4s client's own, so a model named here is not sent. Stop sequences and top-k are
   * refused: no llm4s client sends them, and dropping them would change the answer without saying so.
   */
  def options(chatOptions: ChatOptions): Result[CompletionOptions] =
    Option(chatOptions) match {
      case None => Right(CompletionOptions())
      case Some(o) =>
        val stops = Option(o.getStopSequences).map(_.asScala.toList).getOrElse(Nil)
        if (stops.nonEmpty)
          Left(ValidationError("stopSequences", "llm4s clients do not send stop sequences; leave them unset"))
        else if (o.getTopK != null)
          Left(ValidationError("topK", "llm4s clients do not send top-k; leave it unset"))
        else {
          val base    = CompletionOptions()
          val tuned   = Option(o.getTemperature).fold(base)(t => base.withTemperature(t.doubleValue))
          val topP    = Option(o.getTopP).fold(tuned)(p => tuned.withTopP(p.doubleValue))
          val max     = Option(o.getMaxTokens).fold(topP)(m => topP.withMaxTokens(m.intValue))
          val present = Option(o.getPresencePenalty).fold(max)(p => max.withPresencePenalty(p.doubleValue))
          val freq    = Option(o.getFrequencyPenalty).fold(present)(p => present.withFrequencyPenalty(p.doubleValue))
          o match {
            case tools: ToolCallingChatOptions =>
              val callbacks = Option(tools.getToolCallbacks).map(_.asScala.toSeq).getOrElse(Nil)
              callbacks
                .foldLeft[Result[Vector[ToolFunction[?, ?]]]](Right(Vector.empty)) { (acc, cb) =>
                  acc.flatMap(fs => toolFunction(cb).map(fs :+ _))
                }
                .map(fs => if (fs.isEmpty) freq else freq.withTools(fs))
            case _ => Right(freq)
          }
        }
    }

  /** A Spring AI tool as an llm4s tool definition: its name, description and input schema; calling it calls the tool. */
  private def toolFunction(callback: ToolCallback): Result[ToolFunction[ujson.Value, String]] = {
    val definition = callback.getToolDefinition
    Try(ujson.read(definition.inputSchema)).toResult.left
      .map(_ => ValidationError("inputSchema", s"the input schema of tool '${definition.name}' is not JSON"))
      .map { schema =>
        ToolFunction[ujson.Value, String](
          name = definition.name,
          description = Option(definition.description).getOrElse(""),
          schema = objectSchema(definition.name, schema),
          handler =
            params => Try(callback.call(params.params.render())).toEither.left.map(e => String.valueOf(e.getMessage))
        )
      }
  }

  private def objectSchema(tool: String, json: ujson.Value): ObjectSchema[ujson.Value] = {
    val required = json.obj.get("required").flatMap(_.arrOpt).map(_.value.flatMap(_.strOpt).toSet).getOrElse(Set.empty)
    val properties = json.obj.get("properties").flatMap(_.objOpt).map(_.toSeq).getOrElse(Nil).map { (name, schema) =>
      PropertyDefinition(name, propertySchema(name, schema), required = required.contains(name))
    }
    ObjectSchema[ujson.Value](descriptionOf(json).getOrElse(s"Parameters for $tool"), properties)
  }

  private def descriptionOf(json: ujson.Value): Option[String] = json.obj.get("description").flatMap(_.strOpt)

  /** A property's JSON schema as llm4s's schema types: the type, description, `enum` of strings and array items. */
  private def propertySchema(name: String, json: ujson.Value): SchemaDefinition[?] = {
    val description = descriptionOf(json).getOrElse(name)
    json.obj.get("type").flatMap(_.strOpt) match {
      case Some("object")  => objectSchema(name, json).withDescription(description)
      case Some("integer") => IntegerSchema(description)
      case Some("number")  => NumberSchema(description)
      case Some("boolean") => BooleanSchema(description)
      case Some("array") =>
        ArraySchema(
          description,
          json.obj.get("items").fold(StringSchema("item"): SchemaDefinition[?])(propertySchema("item", _))
        )
      case _ =>
        StringSchema(description, json.obj.get("enum").flatMap(_.arrOpt).map(_.value.flatMap(_.strOpt).toSeq))
    }
  }

  // ---- llm4s -> Spring AI -------------------------------------------------------------------------------------------

  def chatResponse(completion: Completion): ChatResponse =
    response(
      text = completion.content,
      toolCalls = completion.toolCalls,
      id = completion.id,
      model = completion.model,
      usage = completion.usage,
      finish = Some(if (completion.toolCalls.nonEmpty) ToolCallsFinish else StopFinish)
    )

  /** One streamed piece of text, with no metadata yet: the last response of a stream carries it. */
  def textChunk(id: String, text: String): ChatResponse =
    new ChatResponse(java.util.List.of(new Generation(new SpringAssistantMessage(text))), metadata(id, "", None))

  /** The last response of a stream: the tool calls, finish reason, model and usage, and no text (already streamed). */
  def lastChunk(completion: Completion): ChatResponse =
    response(
      text = "",
      toolCalls = completion.toolCalls,
      id = completion.id,
      model = completion.model,
      usage = completion.usage,
      finish = Some(if (completion.toolCalls.nonEmpty) ToolCallsFinish else StopFinish)
    )

  private def response(
    text: String,
    toolCalls: Seq[ToolCall],
    id: String,
    model: String,
    usage: Option[TokenUsage],
    finish: Option[String]
  ): ChatResponse = {
    val output = assistant(text, toolCalls)
    val generationMetadata =
      finish.fold(ChatGenerationMetadata.NULL)(f => ChatGenerationMetadata.builder().finishReason(f).build())
    new ChatResponse(java.util.List.of(new Generation(output, generationMetadata)), metadata(id, model, usage))
  }

  /**
   * A Spring AI assistant message with its tool calls. Spring AI's builders are self-typed (`Builder<B extends
   * Builder<B>>`), and a chain of their calls on the wildcard `builder()` returns is one the coverage instrumentation
   * cannot type; each call on its own statement is the same builder, which every setter returns.
   */
  private def assistant(text: String, toolCalls: Seq[ToolCall]): SpringAssistantMessage = {
    val builder = SpringAssistantMessage.builder()
    val _       = builder.content(text)
    val _ = builder.toolCalls(
      toolCalls.map(c => new SpringAssistantMessage.ToolCall(c.id, "function", c.name, c.arguments.render())).asJava
    )
    builder.build()
  }

  private def metadata(id: String, model: String, usage: Option[TokenUsage]): ChatResponseMetadata = {
    val builder = ChatResponseMetadata.builder().id(id).model(model)
    usage
      .fold(builder) { u =>
        builder.usage(
          new DefaultUsage(
            Integer.valueOf(u.promptTokens),
            Integer.valueOf(u.completionTokens),
            Integer.valueOf(u.totalTokens),
            u,
            u.cachedTokens.map(t => java.lang.Long.valueOf(t.toLong)).orNull,
            u.cacheCreationTokens.map(t => java.lang.Long.valueOf(t.toLong)).orNull
          )
        )
      }
      .build()
  }

  /** The exception a Spring AI caller receives for an llm4s error: it carries the error and its kind. */
  def failure(error: org.llm4s.error.LLMError): LlmException = new LlmException(error)

  // ---- llm4s request -> Spring AI request (SpringAiLlmClient) -------------------------------------------------------

  def prompt(conversation: Conversation, options: CompletionOptions): Prompt =
    new Prompt(messages(conversation.messages).asJava, chatOptions(options))

  private def messages(all: Seq[Message]): List[SpringMessage] = {
    // A tool call's name, by id: Spring AI's tool response names its tool, llm4s's only the call it answers.
    val names = all.collect { case a: AssistantMessage => a.toolCalls }.flatten.map(c => c.id -> c.name).toMap
    all
      .foldLeft(Vector.empty[SpringMessage]) { (out, message) =>
        message match {
          case s: SystemMessage    => out :+ new SpringSystemMessage(s.content)
          case u: UserMessage      => out :+ new SpringUserMessage(u.content)
          case a: AssistantMessage => out :+ assistant(a.content, a.toolCalls)
          case t: ToolMessage =>
            val response =
              new ToolResponseMessage.ToolResponse(t.toolCallId, names.getOrElse(t.toolCallId, ""), t.content)
            // The answers to one assistant message's calls are one Spring AI message, as its models expect.
            out.lastOption match {
              case Some(previous: ToolResponseMessage) =>
                out.init :+ ToolResponseMessage
                  .builder()
                  .responses((previous.getResponses.asScala :+ response).asJava)
                  .build()
              case _ => out :+ ToolResponseMessage.builder().responses(java.util.List.of(response)).build()
            }
        }
      }
      .toList
  }

  private def chatOptions(options: CompletionOptions): ToolCallingChatOptions = {
    val callbacks: List[ToolCallback] = options.tools.toList.map(springTool)
    // One statement per setter, as in `assistant`.
    val builder = ToolCallingChatOptions.builder()
    val _       = builder.temperature(java.lang.Double.valueOf(options.temperature))
    val _       = builder.topP(java.lang.Double.valueOf(options.topP))
    val _       = builder.presencePenalty(java.lang.Double.valueOf(options.presencePenalty))
    val _       = builder.frequencyPenalty(java.lang.Double.valueOf(options.frequencyPenalty))
    val _       = builder.toolCallbacks(callbacks.asJava)
    options.maxTokens.foreach(m => builder.maxTokens(Integer.valueOf(m)))
    builder.build()
  }

  /** An llm4s tool as a Spring AI tool: its definition, and a call that runs it. */
  private def springTool(tool: ToolFunction[?, ?]): ToolCallback = new ToolCallback {
    private val definition = ToolDefinition
      .builder()
      .name(tool.name)
      .description(tool.description)
      .inputSchema(tool.schema.toJsonSchema(false).render())
      .build()
    override def getToolDefinition: ToolDefinition = definition
    override def call(toolInput: String): String =
      arguments(tool.name, toolInput).flatMap(args =>
        tool.execute(args).left.map(e => ValidationError("tool", e.getMessage))
      ) match {
        case Right(result) => result.render()
        case Left(error)   => throw failure(error)
      }
  }

  // ---- Spring AI response -> llm4s completion (SpringAiLlmClient) ---------------------------------------------------

  def completion(response: ChatResponse): Result[Completion] =
    Option(response).flatMap(r => Option(r.getResult)) match {
      case None => Left(ValidationError("response", "the Spring AI model returned no generation"))
      case Some(generation) =>
        val output = generation.getOutput
        toolCalls(output)
          .map { calls =>
            val text = Option(output.getText).getOrElse("")
            val meta = Option(response.getMetadata)
            Completion(
              id = meta.flatMap(m => Option(m.getId)).filter(_.nonEmpty).getOrElse(UUID.randomUUID().toString),
              created = Instant.now(),
              content = text,
              model = meta.flatMap(m => Option(m.getModel)).getOrElse(""),
              message = AssistantMessage(contentOpt = Option(text).filter(_.nonEmpty), toolCalls = calls),
              toolCalls = calls.toList,
              usage = meta.flatMap(m => Option(m.getUsage)).flatMap(tokenUsage)
            )
          }
    }

  /** Spring AI's usage as llm4s's; none when the model reported none (Spring AI's empty usage is all zeros). */
  def tokenUsage(usage: Usage): Option[TokenUsage] = {
    val prompt     = Option(usage.getPromptTokens).map(_.intValue).getOrElse(0)
    val completion = Option(usage.getCompletionTokens).map(_.intValue).getOrElse(0)
    val total      = Option(usage.getTotalTokens).map(_.intValue).getOrElse(prompt + completion)
    Option.when(prompt != 0 || completion != 0 || total != 0)(
      TokenUsage(
        promptTokens = prompt,
        completionTokens = completion,
        totalTokens = total,
        cachedTokens = Option(usage.getCacheReadInputTokens).map(_.intValue),
        cacheCreationTokens = Option(usage.getCacheWriteInputTokens).map(_.intValue)
      )
    )
  }
}
