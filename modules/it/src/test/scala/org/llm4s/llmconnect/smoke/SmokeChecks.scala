package org.llm4s.llmconnect.smoke

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
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction }

import scala.collection.mutable.ListBuffer

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

  private val StructuredPrompt =
    "Answer with a JSON object whose color is \"blue\" and whose count is 3."

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

  /** The system message is honoured: a model told to answer with one word, whatever it is asked, does. */
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
          completion.content.toUpperCase.contains("PINEAPPLE"),
          (),
          s"the reply ignored the system message (it should have been PINEAPPLE): ${snippet(completion.content)}"
        )
      } yield ()
    )
  }

  /** An assistant turn in the history reaches the model: it answers from what the earlier turns said. */
  def multiTurn(client: LLMClient): Outcome = {
    val conversation = Conversation(
      Seq(
        UserMessage(s"My favourite number is $FavouriteNumber."),
        AssistantMessage(contentOpt = Some(s"Noted. Your favourite number is $FavouriteNumber.")),
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
          s"the reply did not use the earlier turns (it should contain $FavouriteNumber): ${snippet(completion.content)}"
        )
      } yield ()
    )
  }

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
   * fit the tool's schema. Returns the call and what the tool returns for it.
   */
  private def calledTool(tool: ToolFunction[_, _], completion: Completion): Either[String, (ToolCall, String)] =
    for {
      call <- completion.toolCalls.headOption.toRight(
        s"the model did not call the tool; it said ${snippet(completion.content)}"
      )
      _ <- Either.cond(call.name == tool.name, (), s"it called '${call.name}' instead of '${tool.name}'")
      _ <- Either.cond(call.id.nonEmpty, (), "the tool call has an empty id, so its result cannot be sent back")
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
   * Whether a provider splits the arguments at all is its choice; this checks the result of reassembling them.
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
      } yield ()
    )

  /** A JSON-schema `responseFormat` yields JSON that parses and matches the schema, with the values asked for. */
  def structuredOutput(client: LLMClient): Outcome = {
    val schema = Schema
      .`object`[Verdict]("A verdict")
      .withProperty(Schema.property("color", Schema.string("A colour name")))
      .withProperty(Schema.property("count", Schema.integer("A whole number")))
    outcome(
      Capability.StructuredOutput,
      for {
        verdict <- client
          .completeStructured[Verdict](
            Conversation(Seq(UserMessage(StructuredPrompt))),
            schema,
            CompletionOptions(temperature = 0.0, maxTokens = Some(64))
          )
          .left
          .map(e => s"the response did not parse as the schema: ${e.message}")
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
