package org.llm4s.agent.graph.tool

import org.llm4s.error.ValidationError
import org.llm4s.types.Result

/**
 * The tools an agent loop offers, in order, with the validator that checks their arguments. Built
 * only through [[ToolSet.of]], so every name is valid and unique and every schema keyword is one
 * the validator checks.
 */
final class ToolSet private (val tools: Vector[AgentTool[?]], val validator: ToolArgumentValidator):
  private val byName: Map[String, AgentTool[?]] = tools.map(t => t.spec.name -> t).toMap

  def get(name: String): Option[AgentTool[?]] = byName.get(name)

  /** The provider-facing tool definitions, in order. */
  def definitions: Vector[ujson.Value] = tools.map(_.spec.toolDefinition)

object ToolSet:
  val empty: ToolSet = new ToolSet(Vector.empty, ToolArgumentValidator.default)

  /** A set checked with [[ToolArgumentValidator.default]]. */
  def of(tools: AgentTool[?]*): Result[ToolSet] = of(ToolArgumentValidator.default, tools*)

  /**
   * Refuses, with one `ValidationError` listing every problem one per line: an invalid tool name,
   * a duplicate name, or a keyword in a tool's provider schema that `validator` does not support.
   */
  def of(validator: ToolArgumentValidator, tools: AgentTool[?]*): Result[ToolSet] =
    val all   = tools.toVector
    val names = all.map(_.spec.name)
    val invalid = names.distinct.collect {
      case n if !AgentToolSpec.isValidName(n) => s"tool '$n': invalid tool name; must match [a-zA-Z0-9_-]{1,64}"
    }
    val duplicates = names.distinct.collect {
      case n if names.count(_ == n) > 1 => s"tool '$n': duplicate tool name"
    }
    val unsupported = all.flatMap { t =>
      validator
        .unsupported(t.spec.providerSchema)
        .map(path => s"tool '${t.spec.name}': unsupported schema keyword at $path")
    }
    invalid ++ duplicates ++ unsupported match
      case Vector() => Right(new ToolSet(all, validator))
      case problems => Left(ValidationError("tool set", problems.mkString("\n")))
