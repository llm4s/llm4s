package org.llm4s.llmconnect.smoke

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.streaming.StreamingAccumulator
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  CompletionOptions,
  Conversation,
  ResponseFormat,
  StreamedChunk,
  SystemMessage,
  TokenUsage,
  ToolCall,
  ToolMessage,
  UserMessage
}
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction }

import scala.collection.mutable.ListBuffer
import scala.util.Try

/**
 * What a provider's smoke spec can be asked to prove (issue #1212).
 *
 * The minimum the old smoke specs checked - one user message, the same streamed, and an invalid key - leaves
 * out exactly the parts most likely to differ between a mock server and the real API. Each capability here is
 * one such part, checked by [[SmokeChecks]].
 */
enum Capability(val label: String) {
  case SystemPrompt        extends Capability("system message")
  case MultiTurn           extends Capability("multi-turn history")
  case ToolCalling         extends Capability("tool call")
  case StreamedToolCalling extends Capability("streamed tool call")
  case StructuredOutput    extends Capability("structured output")
  case Usage               extends Capability("usage")
  case StreamedUsage       extends Capability("streamed usage")
  case Reasoning           extends Capability("reasoning")
}

/** Whether a provider is expected to have a capability: a provider that cannot says why, in the matrix. */
sealed trait Applicability

object Applicability {

  /** The capability is checked against the provider. */
  case object Supported extends Applicability

  /** The provider has no such capability (or the spec has no model for it); `reason` is shown in the matrix. */
  final case class NotApplicable(reason: String) extends Applicability
}

/** The result of one capability for one provider, as the matrix shows it. */
sealed trait Outcome

object Outcome {

  /** The check ran and the provider did what the capability requires. */
  case object Held extends Outcome

  /** The check ran and the provider did not; `message` starts with the capability's label and says what differed. */
  final case class Failed(message: String) extends Outcome

  /** The capability does not apply to this provider (declared by its spec). */
  final case class NotApplicable(reason: String) extends Outcome

  /** The check could not run here, typically because the provider's key is not set. */
  final case class Skipped(reason: String) extends Outcome
}

/**
 * The model and options a provider's `Reasoning` check runs against: a model that reasons, which is not the cheap
 * default model the other checks use.
 *
 * @param client               a client for the reasoning model
 * @param options              options that switch reasoning on and leave room for the answer after the thinking
 * @param expectsThinkingText  true when the provider returns the reasoning text itself (`Completion.thinking`);
 *                             false when it only reports reasoning tokens
 */
final case class ReasoningSetup(client: LLMClient, options: CompletionOptions, expectsThinkingText: Boolean)

/** An answer shaped like the structured-output schema the contract asks for. */
final case class Verdict(color: String, count: Int)

object Verdict {
  given rw: upickle.default.ReadWriter[Verdict] = upickle.default.macroRW
}

/**
 * The capability checks, as pure functions of a client.
 *
 * They are not ScalaTest assertions on purpose: a check returns an [[Outcome]], so the same logic runs both
 * against a real provider (from [[ProviderSmokeContract]]) and against local fake servers (from
 * `SmokeContractOfflineSpec`), where it is shown to pass for a well-behaved server and to fail, naming the
 * capability, for each way a server can misbehave. A check nobody can run against a failing server proves
 * nothing, which is why they are built this way.
 *
 * Every check is cheap (tiny prompts, a few tens of tokens) and asserts structure and invariants, never a
 * model's exact wording.
 */
object SmokeChecks {

  /** What the contract's tool returns; a final answer that carries it proves the tool result got back. */
  val SecretCode = "ZX-4417"

  private val ToolName = "get_secret_code"

  private val ToolPrompt =
    "Use the get_secret_code tool with the topic 'vault', then tell me the code it returns."

  private val FavouriteNumber = "7342"

  /**
   * Asks for the values without prescribing a shape: the JSON must come from the response format alone, so a client
   * that drops it gets prose back and fails.
   */
  val StructuredPrompt = "Report the colour blue and the count 3."

  private def small: CompletionOptions = CompletionOptions(temperature = 0.0, maxTokens = Some(24))

  private def fail(capability: Capability, detail: String): Outcome =
    Outcome.Failed(s"[${capability.label}] $detail")

  private def snippet(text: String): String = {
    val flat = text.replaceAll("\\s+", " ").trim
    if (flat.length <= 80) s"\"$flat\"" else s"\"${flat.take(80)}...\""
  }

  private def outcome(capability: Capability, result: Either[String, Unit]): Outcome =
    result.fold(detail => fail(capability, detail), _ => Outcome.Held)

  /** Runs one capability's check against `client`. `Reasoning` is checked by [[reasoning]], which needs a setup. */
  def run(capability: Capability, client: LLMClient): Outcome = capability match {
    case Capability.SystemPrompt        => systemPrompt(client)
    case Capability.MultiTurn           => multiTurn(client)
    case Capability.ToolCalling         => toolCalling(client)
    case Capability.StreamedToolCalling => streamedToolCalling(client)
    case Capability.StructuredOutput    => structuredOutput(client)
    case Capability.Usage               => usage(client)
    case Capability.StreamedUsage       => streamedUsage(client)
    case Capability.Reasoning =>
      fail(capability, "no reasoning setup was supplied: call SmokeChecks.reasoning with one")
  }

  /**
   * A reply reduced to the word it is: trimmed, without wrapping quotes, backticks or emphasis, and without one
   * trailing full stop. Case and any other words are kept, so `pineapple` or `The answer is PINEAPPLE` stay wrong.
   */
  private def bareWord(text: String): String = {
    val wrapping                  = "\"'`*".toSet
    def unwrap(s: String): String = s.trim.dropWhile(wrapping).reverse.dropWhile(wrapping).reverse.trim
    unwrap(unwrap(text).stripSuffix("."))
  }

  /** The system message is honoured: a model told to answer with one word, whatever it is asked, does, exactly. */
  def systemPrompt(client: LLMClient): Outcome = {
    val conversation = Conversation(
      Seq(
        SystemMessage("Whatever the user asks, reply with the single word PINEAPPLE in capitals and nothing else."),
        UserMessage("What is the capital of France?")
      )
    )
    outcome(
      Capability.SystemPrompt,
      for {
        completion <- client.complete(conversation, small).left.map(e => s"the call failed: ${e.message}")
        _ <- Either.cond(
          bareWord(completion.content) == "PINEAPPLE",
          (),
          s"the reply ignored the system message (it should have been PINEAPPLE alone): ${snippet(completion.content)}"
        )
      } yield ()
    )
  }

  /**
   * An assistant turn in the history reaches the model: it answers from what that turn said. The number is only in
   * the assistant turn, so a client that sent the user turns and dropped the assistant's would fail.
   */
  def multiTurn(client: LLMClient): Outcome = {
    val conversation = Conversation(
      Seq(
        UserMessage("Pick a four-digit favourite number for me and tell me what it is."),
        AssistantMessage(contentOpt = Some(s"Your favourite number is $FavouriteNumber.")),
        UserMessage("What is my favourite number? Reply with the number only.")
      )
    )
    outcome(
      Capability.MultiTurn,
      for {
        completion <- client.complete(conversation, small).left.map(e => s"the call failed: ${e.message}")
        _ <- Either.cond(
          completion.content.contains(FavouriteNumber),
          (),
          s"the reply did not use the assistant turn (it should contain $FavouriteNumber): ${snippet(completion.content)}"
        )
      } yield ()
    )
  }

  /** The properties the contract's tool declares; its object schema allows no others (`additionalProperties`). */
  private val ToolProperties = Set("topic")

  private def secretCodeTool: Either[String, ToolFunction[_, _]] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Secret code parameters")
      .withProperty(Schema.property("topic", Schema.string("What the code is for")))
    ToolBuilder[Map[String, Any], String](ToolName, "Looks up the secret code for a topic", schema)
      .withHandler(params => params.getString("topic").map(_ => SecretCode))
      .buildSafe()
      .left
      .map(e => s"the contract could not build its own tool: ${e.formatted}")
  }

  /**
   * The model's tool call, checked against the tool: it called the tool, the call carries an id, and its arguments
   * fit the tool's schema - an object with no property the tool does not declare (`ToolFunction.execute` does not
   * validate the schema, and the handler would ignore an extra one), which the handler then accepts. Returns the
   * call and what the tool returns for it.
   */
  private def calledTool(tool: ToolFunction[_, _], completion: Completion): Either[String, (ToolCall, String)] =
    for {
      call <- completion.toolCalls.headOption.toRight(
        s"the model did not call the tool; it said ${snippet(completion.content)}"
      )
      _      <- Either.cond(call.name == tool.name, (), s"it called '${call.name}' instead of '${tool.name}'")
      _      <- Either.cond(call.id.nonEmpty, (), "the tool call has an empty id, so its result cannot be sent back")
      fields <- call.arguments.objOpt.toRight(s"the call's arguments are not a JSON object: ${call.arguments}")
      undeclared = fields.keySet.diff(ToolProperties)
      _ <- Either.cond(
        undeclared.isEmpty,
        (),
        s"the call's arguments do not fit the tool's schema, which does not declare ${undeclared.mkString(", ")}: ${call.arguments}"
      )
      value <- tool
        .execute(call.arguments)
        .left
        .map(e => s"the call's arguments do not fit the tool's schema (${call.arguments}): $e")
      result <- value.strOpt.toRight(s"the tool returned something other than a string: $value")
    } yield (call, result)

  private def toolOptions(tool: ToolFunction[_, _]): CompletionOptions =
    CompletionOptions(temperature = 0.0, maxTokens = Some(64)).withTools(Seq(tool))

  /**
   * A tool call round trip: the model calls the tool, the result goes back as a `ToolMessage`, and the model's
   * answer carries it.
   */
  def toolCalling(client: LLMClient): Outcome =
    outcome(
      Capability.ToolCalling,
      for {
        tool <- secretCodeTool
        options = toolOptions(tool)
        ask     = Conversation(Seq(UserMessage(ToolPrompt)))
        first  <- client.complete(ask, options).left.map(e => s"the request carrying a tool failed: ${e.message}")
        called <- calledTool(tool, first)
        withResult = Conversation(
          ask.messages ++ Seq(
            AssistantMessage(contentOpt = first.message.contentOpt, toolCalls = first.toolCalls),
            ToolMessage(content = called._2, toolCallId = called._1.id)
          )
        )
        second <- client
          .complete(withResult, options)
          .left
          .map(e => s"sending the tool result back failed: ${e.message}")
        _ <- Either.cond(
          second.content.contains(SecretCode),
          (),
          s"the final answer does not carry the tool's result $SecretCode: ${snippet(second.content)}"
        )
      } yield ()
    )

  /**
   * A streamed tool call: the arguments arrive split across deltas and must reassemble into JSON that fits the
   * tool, with every tool-call chunk carrying its call's id (a chunk with none is dropped by the accumulator).
   * Whether a provider splits the arguments at all is its choice; this checks the result of reassembling them,
   * both in the returned completion and from the chunks `onChunk` received - reassembled by a fresh
   * `StreamingAccumulator`, as a consumer of the callback would - so a client that builds a correct completion
   * but hands the callback unusable fragments fails too.
   */
  def streamedToolCalling(client: LLMClient): Outcome =
    outcome(
      Capability.StreamedToolCalling,
      for {
        tool <- secretCodeTool
        chunks = ListBuffer.empty[StreamedChunk]
        completion <- client
          .streamComplete(
            Conversation(Seq(UserMessage(ToolPrompt))),
            toolOptions(tool),
            chunk => { chunks += chunk; () }
          )
          .left
          .map(e => s"the streamed request carrying a tool failed: ${e.message}")
        _ <- calledTool(tool, completion)
        idless = chunks.flatMap(_.toolCall).filter(_.id.isEmpty)
        _ <- Either.cond(
          idless.isEmpty,
          (),
          s"${idless.size} streamed tool-call chunk(s) arrived without their call's id and would be dropped"
        )
        reassembled <- fromChunks(chunks.toSeq)
        _ <- calledTool(tool, reassembled).left.map(detail => s"reassembled from the streamed chunks, $detail")
      } yield ()
    )

  /** The completion a consumer of `onChunk` would rebuild from `chunks`. */
  private def fromChunks(chunks: Seq[StreamedChunk]): Either[String, Completion] = {
    val accumulator = StreamingAccumulator.create()
    chunks.foreach(accumulator.addChunk)
    accumulator.toCompletion
      .map(c => c.withToolCalls(c.message.toolCalls.toList))
      .left
      .map(e => s"the streamed chunks did not reassemble: ${e.message}")
  }

  /** One markdown code fence around a whole reply, which some providers add around a JSON answer. */
  private val Fence = "(?s)```(?:json)?\\s*(.*?)\\s*```".r

  private def unfenced(text: String): String = text.trim match {
    case Fence(inner) => inner
    case other        => other
  }

  /** Whether `value` has the JSON type `property` declares (the contract's schema uses `string` and `integer`). */
  private def hasDeclaredType(property: ujson.Value, value: ujson.Value): Boolean =
    (property.obj.get("type").flatMap(_.strOpt), value) match {
      case (Some("string"), _: ujson.Str)   => true
      case (Some("integer"), n: ujson.Num)  => n.num.isWhole
      case (Some("number"), _: ujson.Num)   => true
      case (Some("boolean"), _: ujson.Bool) => true
      case _                                => false
    }

  /**
   * A JSON-schema `responseFormat` yields JSON that matches the schema, with the values asked for.
   *
   * The prompt does not mention JSON, so only the response format can produce it. The reply must be the JSON
   * document itself (one code fence around it is tolerated; prose around it is not), and the document is checked
   * against the schema here rather than by `completeStructured`, which extracts JSON from prose and ignores
   * undeclared fields: an object with exactly the properties the schema declares (it allows no others, by
   * `additionalProperties`), of the declared types.
   */
  def structuredOutput(client: LLMClient): Outcome = {
    val schema = Schema
      .`object`[Verdict]("A verdict")
      .withProperty(Schema.property("color", Schema.string("A colour name")))
      .withProperty(Schema.property("count", Schema.integer("A whole number")))
    val jsonSchema = schema.toJsonSchema(strict = true)
    val declared   = jsonSchema.obj.get("properties").flatMap(_.objOpt).map(_.keySet.toSet).getOrElse(Set.empty)
    val options =
      CompletionOptions(temperature = 0.0, maxTokens = Some(64))
        .withResponseFormat(ResponseFormat.JsonSchema(jsonSchema))
    outcome(
      Capability.StructuredOutput,
      for {
        completion <- client
          .complete(Conversation(Seq(UserMessage(StructuredPrompt))), options)
          .left
          .map(e => s"the request carrying the response format failed: ${e.message}")
        document <- Try(ujson.read(unfenced(completion.content))).toOption.toRight(
          s"the reply is not a JSON document, so the response format was not honoured: ${snippet(completion.content)}"
        )
        fields <- document.objOpt.toRight(s"the reply is JSON but not an object: ${snippet(completion.content)}")
        undeclared = fields.keySet.toSet.diff(declared)
        missing    = declared.diff(fields.keySet.toSet)
        _ <- Either.cond(
          undeclared.isEmpty,
          (),
          s"the JSON does not fit the schema, which does not declare ${undeclared.toSeq.sorted
              .mkString(", ")}: ${ujson.write(document)}"
        )
        _ <- Either.cond(
          missing.isEmpty,
          (),
          s"the JSON does not fit the schema, which requires ${missing.toSeq.sorted.mkString(", ")}: ${ujson.write(document)}"
        )
        mistyped = declared.filterNot(name => hasDeclaredType(jsonSchema("properties")(name), fields(name)))
        _ <- Either.cond(
          mistyped.isEmpty,
          (),
          s"the JSON does not fit the schema's types for ${mistyped.toSeq.sorted.mkString(", ")}: ${ujson.write(document)}"
        )
        verdict <- Try(upickle.default.read[Verdict](document)).toOption.toRight(
          s"the JSON does not fit the schema's types: ${ujson.write(document)}"
        )
        _ <- Either.cond(
          verdict.color.equalsIgnoreCase("blue") && verdict.count == 3,
          (),
          s"the JSON matched the schema but not the values asked for (blue, 3): $verdict"
        )
      } yield ()
    )
  }

  private def usageProblem(usage: Option[TokenUsage]): Option[String] = usage match {
    case None => Some("the provider reported no usage")
    case Some(u) if u.promptTokens <= 0 || u.completionTokens <= 0 =>
      Some(s"usage is not positive: prompt=${u.promptTokens}, completion=${u.completionTokens}")
    case Some(u) if u.totalTokens < u.promptTokens + u.completionTokens =>
      Some(s"total (${u.totalTokens}) is below prompt + completion (${u.promptTokens} + ${u.completionTokens})")
    case Some(_) => None
  }

  /** Usage is reported on `complete`: positive, and a total that is not below prompt + completion. */
  def usage(client: LLMClient): Outcome =
    outcome(
      Capability.Usage,
      for {
        completion <- client
          .complete(Conversation(Seq(UserMessage("Say hi in one word"))), small)
          .left
          .map(e => s"the call failed: ${e.message}")
        _ <- usageProblem(completion.usage).toLeft(())
      } yield ()
    )

  /** Usage is reported on a streamed completion too (a final chunk with no choices, on OpenAI-style streams). */
  def streamedUsage(client: LLMClient): Outcome =
    outcome(
      Capability.StreamedUsage,
      for {
        completion <- client
          .streamComplete(Conversation(Seq(UserMessage("Say hi in one word"))), small, _ => ())
          .left
          .map(e => s"the streamed call failed: ${e.message}")
        _ <- usageProblem(completion.usage).toLeft(())
      } yield ()
    )

  /**
   * Reasoning shows: the answer is not empty, and the provider reports thinking, as text when it returns text or as
   * a token count when it only counts.
   */
  def reasoning(setup: ReasoningSetup): Outcome =
    outcome(
      Capability.Reasoning,
      for {
        completion <- setup.client
          .complete(Conversation(Seq(UserMessage("Say hi in one word"))), setup.options)
          .left
          .map(e => s"the call failed (was the reasoning option rejected?): ${e.message}")
        _ <- Either.cond(
          completion.content.nonEmpty,
          (),
          "the answer was empty: the reasoning may have used every token"
        )
        _ <-
          if (setup.expectsThinkingText)
            Either.cond(
              completion.thinking.exists(_.nonEmpty),
              (),
              "the provider returned no thinking text (Completion.thinking is empty)"
            )
          else
            Either.cond(
              completion.thinking.exists(_.nonEmpty) || completion.usage.exists(_.thinkingTokens.isDefined),
              (),
              "the provider reported neither thinking text nor reasoning tokens"
            )
      } yield ()
    )
}
