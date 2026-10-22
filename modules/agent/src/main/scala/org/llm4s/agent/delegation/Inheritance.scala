package org.llm4s.agent.delegation

import org.llm4s.agent.graph.middleware.MiddlewareId
import org.llm4s.annotation.Experimental
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import scala.concurrent.duration.FiniteDuration

/**
 * What a subagent takes from the agent it is bound to, beyond its own definition (design 4.45). Nothing is taken
 * unless granted (rule 15): [[Inheritance.none]], the default, gives the child its own tools and middleware only.
 *
 *  - `tools`: the parent's tools the child also gets, as the parent's instances, run against the child's own thread.
 *    A tool over a state backend (`StateFileBackend`) therefore sees the child's own empty files, while one over a
 *    host, workspace or store backend shares the parent's. The task tools are never among them: `delegate` governs
 *    those.
 *  - `middleware`: the ids of the parent's middleware the child also runs, as the parent's instances - memory or a
 *    guardrail, say. Granting a `SemanticMemoryMiddleware`'s id shares the parent's memory, under the parent's tenant,
 *    which the child always has. The parent's `delegation` middleware cannot be granted.
 *  - `budget`: the child's limits, each also capped by what its parent has left when the task is called.
 *  - `delegate`: whether the child may delegate to its own subagents (`TypedAgent.withSubagents`), under its parent's
 *    `DelegationConfig`, to its `maxDepth`. Without it a child never has the task tools.
 *
 * Identity is never part of a grant (rule 16): tenant, principal, tracing, the runtime and the checkpointer always come
 * from the parent. Nor are permissions: every ancestor's `PermissionMiddleware` rules always apply to the child, and
 * can only narrow what it may do.
 *
 * A tool or middleware id the parent does not have is refused when the parent is built.
 */
@Experimental
final case class Inheritance private (
  tools: ToolGrant,
  middleware: Set[MiddlewareId],
  budget: TaskBudget,
  delegate: Boolean
):
  def withTools(grant: ToolGrant): Inheritance = copy(tools = grant)

  /** The parent's middleware the child also runs, by id, replacing any granted before. */
  def withMiddleware(ids: Set[MiddlewareId]): Inheritance = copy(middleware = ids)

  /** [[withMiddleware]] with the ids listed. */
  def withMiddleware(ids: MiddlewareId*): Inheritance = copy(middleware = ids.toSet)

  def withBudget(b: TaskBudget): Inheritance  = copy(budget = b)
  def withDelegate(may: Boolean): Inheritance = copy(delegate = may)

object Inheritance:
  def apply(
    tools: ToolGrant = ToolGrant.None,
    middleware: Set[MiddlewareId] = Set.empty,
    budget: TaskBudget = TaskBudget(),
    delegate: Boolean = false
  ): Inheritance = new Inheritance(tools, middleware, budget, delegate)

  /** Nothing of the parent's: the child's own tools and middleware, the parent's limits, and no delegation. */
  val none: Inheritance = Inheritance()

/** Which of its parent's tools a subagent also gets (design 4.45). */
@Experimental
enum ToolGrant:

  /** None of the parent's tools: the default. */
  case None

  /** Exactly the parent's tools of these names. */
  case Only(names: Set[String])

  /** Every tool of the parent's except these - and never the task tools, which `Inheritance.delegate` governs. */
  case AllExcept(names: Set[String])

/**
 * A subagent's limits (design 4.45), each `None` unless set. When a task is called, each is capped by what its parent
 * has left, from the parent's snapshot:
 *
 *  - `maxSteps`: model calls in the child's turn; unset, the parent agent's `maxSteps`. Either way at most the parent
 *    turn's remaining steps (`RunContext.remainingSteps`).
 *  - `timeout`: the child's time, from its start; at most until the parent's deadline.
 *  - `maxTokens`: the child's input and output tokens; at most the parent's `RunBudgets.maxTokens` less what the
 *    parent's run has used.
 *  - `maxConcurrency`: the child's run's `RunBudgets.maxConcurrency`; at most the parent's.
 *
 * Each limit is the child's whole life: a child recovered after a crash or a failed parent has its grant less what its
 * earlier attempts used, and its time runs from its first start. Parallel children each get the same remainder, so
 * together they can overshoot the parent's tokens; the parent's next model call then ends its turn
 * `TokenLimitReached`. Every value set must be positive, which the parent's `build()` checks.
 */
@Experimental
final case class TaskBudget private (
  maxSteps: Option[Int],
  timeout: Option[FiniteDuration],
  maxTokens: Option[Long],
  maxConcurrency: Option[Int]
):
  def withMaxSteps(n: Int): TaskBudget                   = copy(maxSteps = Some(n))
  def withMaxSteps(n: Option[Int]): TaskBudget           = copy(maxSteps = n)
  def withTimeout(t: FiniteDuration): TaskBudget         = copy(timeout = Some(t))
  def withTimeout(t: Option[FiniteDuration]): TaskBudget = copy(timeout = t)
  def withMaxTokens(n: Long): TaskBudget                 = copy(maxTokens = Some(n))
  def withMaxTokens(n: Option[Long]): TaskBudget         = copy(maxTokens = n)
  def withMaxConcurrency(n: Int): TaskBudget             = copy(maxConcurrency = Some(n))
  def withMaxConcurrency(n: Option[Int]): TaskBudget     = copy(maxConcurrency = n)

  /** Every value set positive, else a `ValidationError` naming each one that is not, for subagent `id`. */
  private[agent] def validated(id: String): Result[TaskBudget] =
    List(
      maxSteps.filter(_ < 1).map(n => s"maxSteps must be positive, was $n"),
      timeout.filter(_.length <= 0).map(t => s"timeout must be positive, was $t"),
      maxTokens.filter(_ < 1).map(n => s"maxTokens must be positive, was $n"),
      maxConcurrency.filter(_ < 1).map(n => s"maxConcurrency must be positive, was $n")
    ).flatten match
      case Nil      => Right(this)
      case problems => Left(ValidationError("budget", problems.map(p => s"subagent '$id': $p")))

  override def toString: String =
    val set = Vector(
      maxSteps.map(n => s"maxSteps=$n"),
      timeout.map(t => s"timeout=$t"),
      maxTokens.map(n => s"maxTokens=$n"),
      maxConcurrency.map(n => s"maxConcurrency=$n")
    ).flatten
    if set.isEmpty then "TaskBudget(the parent's)" else set.mkString("TaskBudget(", ", ", ")")

object TaskBudget:
  def apply(
    maxSteps: Option[Int] = None,
    timeout: Option[FiniteDuration] = None,
    maxTokens: Option[Long] = None,
    maxConcurrency: Option[Int] = None
  ): TaskBudget = new TaskBudget(maxSteps, timeout, maxTokens, maxConcurrency)
