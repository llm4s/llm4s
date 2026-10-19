package org.llm4s.agent.delegation

import org.llm4s.annotation.Experimental
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

/**
 * The bounds on an agent's delegated tasks (design 4.44), each enforced on its own:
 *
 *  - `maxConcurrentChildren`: child runs one parent run has live at once. A `task` call over it waits for a slot; it
 *    is not refused.
 *  - `maxTasksPerTurn`: `task` calls one parent turn may make, counted in the order of the turn's assistant messages,
 *    so a call is refused (a tool error) exactly when the calls before it, its own included, are over the bound,
 *    whichever order the calls run in and however often they are replayed.
 *  - `maxDepth`: how deep delegation nests; 1, the default, lets the agent delegate and its children not.
 *  - `maxReportBytes`: the most a child's report may be, as the JSON the parent model reads; a larger one is a tool
 *    error, so the parent's checkpoint never holds more.
 *  - `childRetention`: settled child threads each parent thread keeps (`RunConfig.withChildRetention`), applied to
 *    every run of the agent whose config leaves it at `RunConfig.DefaultChildRetention`.
 *
 * Every bound must be positive: `AgentBuilder.build()` refuses one that is not.
 */
@Experimental
final case class DelegationConfig private (
  maxConcurrentChildren: Int,
  maxTasksPerTurn: Int,
  maxDepth: Int,
  maxReportBytes: Int,
  childRetention: Int
):
  def withMaxConcurrentChildren(n: Int): DelegationConfig = copy(maxConcurrentChildren = n)
  def withMaxTasksPerTurn(n: Int): DelegationConfig       = copy(maxTasksPerTurn = n)
  def withMaxDepth(n: Int): DelegationConfig              = copy(maxDepth = n)
  def withMaxReportBytes(n: Int): DelegationConfig        = copy(maxReportBytes = n)
  def withChildRetention(n: Int): DelegationConfig        = copy(childRetention = n)

  /** Every bound positive, else a `ValidationError` naming each one that is not. */
  private[agent] def validated: Result[DelegationConfig] =
    Vector(
      "maxConcurrentChildren" -> maxConcurrentChildren,
      "maxTasksPerTurn"       -> maxTasksPerTurn,
      "maxDepth"              -> maxDepth,
      "maxReportBytes"        -> maxReportBytes,
      "childRetention"        -> childRetention
    ).collect { case (name, n) if n < 1 => s"$name must be positive, was $n" }.toList match
      case Nil      => Right(this)
      case problems => Left(ValidationError("delegation", problems))

object DelegationConfig:
  def apply(
    maxConcurrentChildren: Int = 4,
    maxTasksPerTurn: Int = 16,
    maxDepth: Int = 1,
    maxReportBytes: Int = 64 * 1024,
    childRetention: Int = 64
  ): DelegationConfig =
    new DelegationConfig(maxConcurrentChildren, maxTasksPerTurn, maxDepth, maxReportBytes, childRetention)

  /** The defaults: 4 concurrent children, 16 tasks a turn, depth 1, 64 KiB reports, 64 settled children kept. */
  val default: DelegationConfig = DelegationConfig()
