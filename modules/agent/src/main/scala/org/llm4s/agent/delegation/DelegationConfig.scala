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
 *    across handoffs (every agent of the family's calls in the turn count), so a call is refused (a tool error) exactly when the calls before it, its own included, are over the bound,
 *    whichever order the calls run in and however often they are replayed.
 *  - `maxDepth`: how deep delegation nests; 1, the default, lets the agent delegate and its children not.
 *  - `maxReportBytes`: the most any `task` tool message may be, in UTF-8: a child's report, as the JSON the parent
 *    model reads (a larger one is a tool error), and every tool error, its reason cut to fit, so the parent's
 *    checkpoint never holds more. At least [[DelegationConfig.MinReportBytes]], so a cut error still fits.
 *  - `childRetention`: settled child threads each parent thread keeps. A run's `RunConfig.childRetention` bounds it too:
 *    the smaller of the two applies.
 *
 * Every bound must be positive, and `maxReportBytes` at least `MinReportBytes`: `AgentBuilder.build()` refuses one
 * that is not.
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
    ).collect {
      case (name, n) if n < 1 => s"$name must be positive, was $n"
      case ("maxReportBytes", n) if n < DelegationConfig.MinReportBytes =>
        s"maxReportBytes must be at least ${DelegationConfig.MinReportBytes}, was $n"
    }.toList match
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

  /**
   * The least `maxReportBytes`: room for the tool error envelope the loop stores (`{"error":"..."}`, 12 bytes) and a
   * cut reason with its `(truncated)` marker.
   */
  val MinReportBytes: Int = 64

  /** The defaults: 4 concurrent children, 16 tasks a turn, depth 1, 64 KiB reports, 64 settled children kept. */
  val default: DelegationConfig = DelegationConfig()
