package org.llm4s.agent.graph.toolloop

import org.llm4s.annotation.Experimental
import org.llm4s.agent.graph.tool.ToolArgumentValidator
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.ResponseFormat
import org.llm4s.toolapi.SchemaDefinition
import org.llm4s.types.Result
import org.llm4s.util.BoundedJson
import upickle.default.ReadWriter

import scala.util.Try

/**
 * The response format of an agent's answer (design 4.26): the JSON schema its final answer must match, and
 * the type it must read as. Every model call of the agent carries the schema as its `responseFormat`, the
 * system prompt states it, and an answer is checked inside the model step: an invalid one is retried once,
 * and a second ends the turn with [[org.llm4s.agent.graph.GraphError.OutputInvalid]]. Build one with
 * [[OutputFormat.of]]; `AgentBuilder.withOutput` does.
 */
@Experimental
final class OutputFormat private (val schema: ujson.Value, reads: ujson.Value => Option[String]):

  /** The `responseFormat` every model call of the agent is made with. */
  def responseFormat: ResponseFormat.JsonSchema = ResponseFormat.JsonSchema(schema)

  /** The system prompt section that states the schema, after the agent's own prompt. */
  private[agent] def promptSection: String =
    s"${OutputFormat.Instruction}\n${ujson.write(schema)}"

  /** The schema's problems for `validator`, as a build refuses them: empty when the format is usable. */
  private[toolloop] def problems(validator: ToolArgumentValidator): Vector[String] =
    val rendered = ujson.write(schema)
    val notObject = schema.objOpt.flatMap(_.get("type")) match
      case Some(ujson.Str("object")) => Vector.empty
      case _                         => Vector("its schema's root type must be \"object\"")
    val tooLong =
      if rendered.length <= OutputFormat.MaxSchemaChars then Vector.empty
      else Vector(s"its schema is ${rendered.length} characters, over ${OutputFormat.MaxSchemaChars}")
    val unsupported = validator.unsupported(schema).map(path => s"its schema keyword $path cannot be checked")
    notObject ++ tooLong ++ unsupported

  /**
   * `answer` checked: its JSON - with a surrounding code fence or prose stripped - when it parses, is not
   * `null`, matches the schema and reads as the output type; otherwise its problems, at most
   * [[OutputFormat.MaxErrors]], each cut to [[OutputFormat.MaxErrorChars]].
   */
  private[toolloop] def check(answer: String, validator: ToolArgumentValidator): Either[Vector[String], ujson.Value] =
    OutputFormat
      .parse(answer)
      .left
      .map(Vector(_))
      .flatMap { json =>
        val problems = validator.validate(schema, json) match
          case none if none.isEmpty => reads(json).toVector
          case some                 => some
        Either.cond(problems.isEmpty, json, problems)
      }
      .left
      .map(OutputFormat.bounded)

object OutputFormat:

  /** Most problems an answer is reported with: to the model in the corrective request, and in `OutputInvalid`. */
  val MaxErrors: Int = 20

  /** Longest a reported problem is; a longer one is cut. */
  val MaxErrorChars: Int = 300

  /** Longest a schema may render to: it is sent in the system prompt of every model call. */
  val MaxSchemaChars: Int = 32768

  /** Longest the invalid answer is when it is sent back to the model in the corrective request. */
  val MaxEchoedAnswerChars: Int = 16000

  private val Instruction =
    "When you give your final answer, reply with only a JSON value matching this JSON Schema, with no other text:"

  /**
   * The format of `O`: `definition`'s strict JSON schema, and `O`'s reader. A schema derived with
   * `llm4s-schema-derivation` is `SchemaOf[O].definition`.
   */
  def of[O: ReadWriter](definition: SchemaDefinition[O]): OutputFormat =
    new OutputFormat(
      definition.toJsonSchema(strict = true),
      json =>
        Try(upickle.default.read[O](json)).failed.toOption.map(e =>
          s"$$: does not read as the output type: ${Option(e.getMessage).getOrElse(e.toString)}"
        )
    )

  /** The JSON of `answer`, with a code fence or prose round it stripped; `null` and non-JSON are refused. */
  private[agent] def parse(answer: String): Either[String, ujson.Value] =
    BoundedJson.read(LLMClient.extractJson(answer)) match
      case Left(e)           => Left(s"$$: the answer is not JSON: ${e.message}")
      case Right(ujson.Null) => Left("$: the answer is JSON null")
      case Right(json)       => Right(json)

  /** `answer` decoded as `O`, for `AgentResult.decodeOutput`. */
  private[agent] def decode[O: ReadWriter](answer: String): Result[O] =
    parse(answer)
      .flatMap(json => Try(upickle.default.read[O](json)).toEither.left.map(e => s"$$: ${e.getMessage}"))
      .left
      .map(problem => ValidationError("output", truncate(problem)))

  /** The corrective request's user message: the answer's problems, and the request to answer again. */
  private[toolloop] def correction(problems: Vector[String]): String =
    s"Your answer did not match the required JSON Schema:\n${problems.map("- " + _).mkString("\n")}\n" +
      "Reply again with only a JSON value that matches the schema."

  /** The invalid answer as it is sent back to the model: cut to [[MaxEchoedAnswerChars]]. */
  private[toolloop] def echoed(answer: String): String = answer.take(MaxEchoedAnswerChars)

  private def bounded(problems: Vector[String]): Vector[String] = problems.take(MaxErrors).map(truncate)

  private def truncate(problem: String): String =
    if problem.length <= MaxErrorChars then problem else problem.take(MaxErrorChars - 3) + "..."
