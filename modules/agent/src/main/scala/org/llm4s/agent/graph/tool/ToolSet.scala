package org.llm4s.agent.graph.tool

import org.llm4s.error.ValidationError
import org.llm4s.toolapi.ToolFunction
import org.llm4s.types.Result

/**
 * The tools an agent loop offers, in order, with the validator that checks their arguments. Built
 * only through [[ToolSet.of]], so every name is valid and unique, every argument schema is an
 * object, and every schema keyword is one the validator checks.
 */
final class ToolSet private (
  val tools: Vector[AgentTool[?]],
  val validator: ToolArgumentValidator,
  /** Tools checked by another validator than [[validator]], by name: a subagent's granted tools (design 4.45). */
  private[agent] val byTool: Map[String, ToolArgumentValidator] = Map.empty
):
  private val byName: Map[String, AgentTool[?]] = tools.map(t => t.spec.name -> t).toMap

  /** The validator that checks the arguments of tool `name`. */
  private[agent] def validatorOf(name: String): ToolArgumentValidator = byTool.getOrElse(name, validator)

  def get(name: String): Option[AgentTool[?]] = byName.get(name)

  /**
   * The tool definitions in OpenAI's strict format, in order, for callers that send definitions
   * themselves; core's clients receive [[toolFunctions]] instead.
   */
  def definitions: Vector[ujson.Value] = tools.map(_.spec.toolDefinition)

  /**
   * The tools as core `ToolFunction`s, in order, for `CompletionOptions.tools`. A tool made by
   * [[AgentTool.fromToolFunction]] is its original function; any other is a stand-in with the
   * spec's name, description and schema whose handler refuses to run, since `ToolLoop` executes
   * agent tools itself.
   */
  def toolFunctions: Seq[ToolFunction[?, ?]] = tools.map(ToolSet.toolFunction)

object ToolSet:
  private def toolFunction(tool: AgentTool[?]): ToolFunction[?, ?] = tool match
    case adapted: AgentTool.FromToolFunction => adapted.function
    case other                               => standIn(other.spec)

  private def standIn[A](spec: AgentToolSpec[A]): ToolFunction[A, ujson.Value] =
    ToolFunction[A, ujson.Value](
      spec.name,
      spec.description,
      spec.schema,
      _ => Left(s"Tool '${spec.name}' is executed by ToolLoop, not directly")
    )

  val empty: ToolSet = new ToolSet(Vector.empty, ToolArgumentValidator.default)

  /** A set checked with [[ToolArgumentValidator.default]]. */
  def of(tools: AgentTool[?]*): Result[ToolSet] = of(ToolArgumentValidator.default, tools*)

  /**
   * Refuses, with one `ValidationError` listing every problem one per line: an invalid tool name,
   * a duplicate name, an argument schema whose root is not `type: object` (core's clients send
   * every tool's parameters as an object), or a keyword in a tool's
   * [[AgentToolSpec.argumentSchema]] that `validator` does not support.
   */
  def of(validator: ToolArgumentValidator, tools: AgentTool[?]*): Result[ToolSet] = of(validator, Map.empty, tools*)

  /** [[of]], with the tools `byTool` names checked by their own validator rather than `validator` (design 4.45). */
  private[agent] def of(
    validator: ToolArgumentValidator,
    byTool: Map[String, ToolArgumentValidator],
    tools: AgentTool[?]*
  ): Result[ToolSet] =
    val all   = tools.toVector
    val names = all.map(_.spec.name)
    val invalid = names.distinct.collect {
      case n if !AgentToolSpec.isValidName(n) => s"tool '$n': invalid tool name; must match [a-zA-Z0-9_-]{1,64}"
    }
    val duplicates = names.distinct.collect {
      case n if names.count(_ == n) > 1 => s"tool '$n': duplicate tool name"
    }
    val notObject = all.collect {
      case t if !t.spec.argumentSchema.objOpt.flatMap(_.get("type")).contains(ujson.Str("object")) =>
        s"tool '${t.spec.name}': argument schema must be an object (type: object)"
    }
    val unsupported = all.flatMap { t =>
      byTool
        .getOrElse(t.spec.name, validator)
        .unsupported(t.spec.argumentSchema)
        .map(path => s"tool '${t.spec.name}': unsupported schema keyword at $path")
    }
    invalid ++ duplicates ++ notObject ++ unsupported match
      // kept whole: the loop adds the middleware's tools to the set later, a granted middleware's among them
      case Vector() => Right(new ToolSet(all, validator, byTool))
      case problems => Left(ValidationError("tool set", problems.toList))
