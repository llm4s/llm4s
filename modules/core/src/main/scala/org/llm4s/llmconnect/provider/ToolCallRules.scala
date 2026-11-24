package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.model.{ Completion, FinishReason, StreamedChunk, ToolCall }
import org.llm4s.types.Result
import org.llm4s.util.BoundedJson

/**
 * The rules every chat client applies to the tool calls of a reply, on `complete` and on a stream alike (RFC 0009,
 * Part 3):
 *
 *  1. A call whose arguments are not a complete JSON object is a `Left`: never `{}`, never dropped, never passed on as
 *     a raw string. Arguments nested more than `BoundedJson.MaxDepth` levels deep stay unparsed, and fail the same
 *     way (#1562).
 *  1. Absent or blank arguments are `{}` (a call to a tool with no parameters) only when the reply ended cleanly,
 *     with `ToolCalls` or `Stop`.
 *  1. A tool call in a reply cut by the output limit (`FinishReason.Length`) is a `Left`: a later call may be missing,
 *     or a call's arguments a valid prefix of what the model meant.
 *
 * The `Left` is the malformed-tool-call [[org.llm4s.error.ProcessingError ProcessingError]] the clients already
 * returned for a call they could not read.
 *
 * A provider module applies them with [[checked]] to a whole reply, [[parseArguments]] to a call's argument text
 * and [[wholeCall]] to a chunk that carries a whole call; `StreamingAccumulator.toCompletion` applies them to a
 * stream.
 */
@Stable
object ToolCallRules {

  /** The operation a shared malformed-tool-call error names; a client with its own names its provider instead. */
  private[llm4s] val Operation: String = "tool-calls"

  /** The malformed-tool-call error, `ProcessingError(operation, "malformed tool call: <detail>")`. */
  private[llm4s] def malformed(detail: String, operation: String = Operation): ProcessingError =
    ProcessingError(operation, s"malformed tool call: $detail")

  /** The malformed-tool-call error for the call named `name`: `"malformed tool call '<name>': <detail>"`. */
  private[llm4s] def malformedCall(name: String, detail: String): ProcessingError =
    ProcessingError(Operation, s"malformed tool call '$name': $detail")

  /** Whether a reply that ended with `reason` ended cleanly, so blank arguments mean "no arguments" (rule 2). */
  private[llm4s] def cleanEnd(reason: FinishReason): Boolean =
    reason == FinishReason.ToolCalls || reason == FinishReason.Stop

  /**
   * A call's arguments, as the text the provider sent, read as a JSON object (rule 1); blank is `{}` (rule 2: the
   * caller checks the reply ended cleanly). The error names the call and says why, without the arguments
   * themselves: they are model output, possibly large or deep.
   */
  def parseArguments(name: String, raw: String): Result[ujson.Value] =
    readObject(raw).left.map(detail => malformedCall(name, detail))

  /** [[parseArguments]] for a call not yet named. */
  def parseArguments(raw: String): Result[ujson.Value] = readObject(raw).left.map(malformed(_))

  private def readObject(raw: String): Either[String, ujson.Value] =
    if (raw.trim.isEmpty) Right(ujson.Obj())
    else
      BoundedJson.read(raw) match {
        case Right(obj: ujson.Obj) => Right(obj)
        case Right(other)          => Left(s"the arguments are ${kind(other)}, not a JSON object")
        case Left(BoundedJson.TooDeep()) =>
          Left(s"the arguments are nested more than ${BoundedJson.MaxDepth} levels deep")
        case Left(e) => Left(s"the arguments are not valid JSON: ${e.message}")
      }

  /** Rule 1 on parsed arguments: a JSON object, or a `Left` naming the call. */
  private[llm4s] def requireObject(name: String, arguments: ujson.Value): Result[ujson.Value] = arguments match {
    case obj: ujson.Obj => Right(obj)
    case other          => Left(notAnObject(name, other))
  }

  /** Rule 2: a call with blank arguments in a reply that did not end cleanly is a `Left`. */
  private[llm4s] def blankArguments(name: String, reason: FinishReason): Result[Unit] =
    if (cleanEnd(reason)) Right(())
    else
      Left(
        malformedCall(name, s"it has no arguments, and the reply ended with ${describe(reason)}, not ToolCalls or Stop")
      )

  /**
   * Rule 3, and rule 1 on each call's parsed arguments: a reply with a tool call that was cut by the output limit,
   * or whose arguments are not a JSON object, is a `Left`; any other reply is unchanged. Reads the calls from
   * `Completion.toolCalls`, else from the message.
   */
  def checked(completion: Completion): Result[Completion] = {
    val calls = callsOf(completion)
    if (calls.nonEmpty && completion.finishReason == FinishReason.Length) Left(cutByLimit(calls.map(_.name)))
    else
      calls
        .collectFirst { case c if c.arguments.objOpt.isEmpty => notAnObject(c.name, c.arguments) }
        .toLeft(completion)
  }

  /** Rule 3's error: the output limit cut a reply that made the calls named `names`. */
  private[llm4s] def cutByLimit(names: Seq[String]): ProcessingError =
    malformed(
      "the output limit cut the reply (FinishReason.Length) while it made tool calls " +
        names.map(n => s"'$n'").mkString(", ") + "; none is run"
    )

  /**
   * A chunk from a provider that streams each tool call whole (Gemini, Vertex AI, Ollama, watsonx), as the
   * accumulator is to take it: the call's object arguments as their JSON text. The accumulator reads an empty
   * `ujson.Obj()` as a placeholder, as Anthropic and Bedrock send one before the fragments, so an explicit `{}` from
   * these providers would otherwise read as no arguments, which rule 2 refuses after any finish but `ToolCalls` or
   * `Stop`. The caller's `onChunk` still gets the chunk unchanged.
   */
  def wholeCall(chunk: StreamedChunk): StreamedChunk =
    chunk.toolCall match {
      case Some(call) if call.arguments.objOpt.isDefined =>
        chunk.withToolCall(ToolCall(call.id, call.name, ujson.Str(call.arguments.render())))
      case _ => chunk
    }

  private def callsOf(completion: Completion): Seq[ToolCall] =
    if (completion.toolCalls.nonEmpty) completion.toolCalls else completion.message.toolCalls

  private def notAnObject(name: String, value: ujson.Value): ProcessingError =
    malformedCall(name, s"the arguments are ${kind(value)}, not a JSON object")

  private def describe(reason: FinishReason): String = reason match {
    case FinishReason.Other("") => "no finish reason"
    case FinishReason.Other(r)  => s"'$r'"
    case r                      => r.toString
  }

  private def kind(value: ujson.Value): String = value match {
    case _: ujson.Arr  => "an array"
    case _: ujson.Str  => "a string"
    case _: ujson.Num  => "a number"
    case _: ujson.Bool => "a boolean"
    case ujson.Null    => "null"
    case _: ujson.Obj  => "an object"
  }
}
