package org.llm4s.agent.graph.middleware

import org.llm4s.agent.graph.{ GraphError, StateKey, StateUpdate }
import org.llm4s.agent.graph.tool.{ AgentTool, ToolContext, ToolOutcome }
import org.llm4s.error.{ CancelledError, LLMError, ValidationError }
import org.llm4s.types.Result

import java.util.concurrent.ConcurrentLinkedQueue
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*
import scala.util.{ Failure, Success, Try }

/**
 * Middleware in the order they run, built and checked whole by [[MiddlewareStack.of]].
 *
 * `ordered(0)` is the outermost wrapper: its `next` calls `ordered(1)`'s hook, and the last one's
 * `next` calls the innermost function. `beforeAgent` runs in stack order and `afterAgent` in
 * reverse; each stops at the first `Left`. Every hook invocation is guarded on its own: a throw
 * becomes `GraphError.MiddlewareFailed` naming that middleware, and a thrown cancellation a
 * `CancelledError`, restoring the interrupt flag at once, which each enclosing wrapper sees as its
 * `next`'s result. The innermost function is not guarded; its caller guards it.
 */
final class MiddlewareStack private (val ordered: Vector[AgentMiddleware]):
  import MiddlewareStack.{ Hooked, ToolChainResult, WriteCheck }

  /** Every middleware's contributed tools, in stack order. */
  val tools: Vector[AgentTool[?]] = ordered.flatMap(_.tools)

  /** The union of every middleware's `writes`. */
  val writes: Set[StateKey[?, ?]] = ordered.flatMap(_.writes).toSet

  /**
   * Runs each `beforeAgent` in stack order, threading the input; the first `Left` stops. Each middleware's
   * update is kept with its id, in the order the hooks ran, and checked by `check` as its hook returns: an
   * undeclared write stops the fold too, and is in `check` whatever the caller makes of the `Left`.
   */
  private[graph] def beforeAgent(input: String, context: ModelContext, check: WriteCheck): Result[Hooked] =
    boundary(ordered, input, check)((m, text) => m.beforeAgent(text, context))

  /** Runs each `afterAgent` in reverse stack order, threading the answer, as [[beforeAgent]] does. */
  private[graph] def afterAgent(answer: String, context: ModelContext, check: WriteCheck): Result[Hooked] =
    boundary(ordered.reverse, answer, check)((m, text) => m.afterAgent(text, context))

  private def boundary(in: Vector[AgentMiddleware], text: String, check: WriteCheck)(
    hook: (AgentMiddleware, String) => Result[BoundaryResult]
  ): Result[Hooked] =
    in.foldLeft[Result[Hooked]](Right(Hooked(text, Vector.empty)))((acc, m) =>
      acc.flatMap(done =>
        guarded(m)(hook(m, done.text)).flatMap { r =>
          if check(m, r.update) then Right(Hooked(r.text, done.updates :+ (m.id -> r.update)))
          else Left(check.failure.getOrElse(ValidationError("middleware update", "undeclared write")))
        }
      )
    )

  /** Every middleware's prompt sections, in stack order, each with the id of the middleware that gave it. */
  private[graph] def promptSections(context: ModelContext): Result[Vector[(MiddlewareId, PromptSection)]] =
    ordered.foldLeft[Result[Vector[(MiddlewareId, PromptSection)]]](Right(Vector.empty))((acc, m) =>
      acc.flatMap(done => guarded(m)(m.promptSections(context)).map(found => done ++ found.map(m.id -> _)))
    )

  /**
   * Runs the model call through every `wrapModelCall`, the first outermost, with `innermost` at the centre. The
   * update parts a wrapper adds are attributed to it as it returns, and `check` checks them against its own
   * `writes` then - so an undeclared write is found also in a response an outer wrapper discards, or turns into a
   * `Left` or a question.
   */
  private[graph] def wrapModelCall(request: ModelRequest, context: ModelContext, check: WriteCheck)(
    innermost: ModelRequest => Result[ModelResponse]
  ): Result[ModelResponse] =
    val chain = ordered.foldRight(innermost) { (m, next) => (req: ModelRequest) =>
      guarded(m)(m.wrapModelCall(req, context)(next)).map { r =>
        r.parts.collect { case (None, u) => u }.foreach(check(m, _))
        r.claimedBy(m.id)
      }
    }
    chain(request)

  /**
   * Runs the tool call through every `wrapToolCall`, the first outermost, with `innermost` at the
   * centre, and says who raised a resulting `NeedsApproval` or `Ask`.
   *
   * A layer raised it when its own result is `NeedsApproval` or `Ask` and none of the results its `next`
   * returned - however often it was called - is that same value (by reference); otherwise that inner
   * result's attribution stands, and the innermost function's own is attributed to the tool.
   */
  private[graph] def wrapToolCall(request: ToolCallRequest, context: ToolContext)(
    innermost: () => ToolOutcome
  ): ToolChainResult =
    def layer(index: Int): ToolChainResult =
      if index == ordered.size then ToolChainResult(innermost(), None)
      else
        val m = ordered(index)
        // every result next returned, so an earlier one the wrapper hands back keeps its attribution
        val inner = new ConcurrentLinkedQueue[ToolChainResult]()
        val next: () => ToolOutcome = () =>
          val result = layer(index + 1)
          inner.add(result)
          result.outcome
        guardedTool(m)(m.wrapToolCall(request, context)(next)) match
          case raised @ (_: ToolOutcome.NeedsApproval | _: ToolOutcome.Ask[?]) =>
            inner.asScala.find(_.outcome.eq(raised)).getOrElse(ToolChainResult(raised, Some(m.id)))
          case other => ToolChainResult(other, None)
    layer(0)

  /** Runs one boundary or model hook; a throw is `Left`, and a cancellation also restores the interrupt flag. */
  private def guarded[A](m: AgentMiddleware)(run: => Result[A]): Result[A] =
    MiddlewareStack.attempt(run) match
      case Right(result) => result
      case Left(thrown) =>
        CancelledError.fromThrowable(thrown, MiddlewareStack.operation(m)) match
          case Some(cancellation) =>
            Thread.currentThread().interrupt()
            Left(cancellation)
          case None => Left(GraphError.MiddlewareFailed(m.id.value, thrown))

  /**
   * Runs one tool hook; a throw is `Fatal`, which the loop turns into a failed or cancelled task. A
   * thrown cancellation restores the interrupt flag at once, so no enclosing wrapper that retries
   * runs with it clear.
   */
  private def guardedTool(m: AgentMiddleware)(run: => ToolOutcome): ToolOutcome =
    MiddlewareStack.attempt(run) match
      case Right(outcome) => outcome
      case Left(thrown) =>
        val error: LLMError = CancelledError.fromThrowable(thrown, MiddlewareStack.operation(m)) match
          case Some(cancellation) =>
            Thread.currentThread().interrupt()
            cancellation
          case None => GraphError.MiddlewareFailed(m.id.value, thrown)
        ToolOutcome.Fatal(error)

object MiddlewareStack:

  /** What a boundary hook's stack returned: the threaded text, and each middleware's update in the order the hooks ran. */
  final private[graph] case class Hooked(text: String, updates: Vector[(MiddlewareId, StateUpdate)]):
    def update: StateUpdate = updates.foldLeft(StateUpdate.empty)((acc, u) => acc.combine(u._2))

  /**
   * The undeclared writes a step's hooks returned, recorded as each hook returns - whatever the step's outcome, so
   * neither a later hook's `Left` or question nor a wrapper that discards a response hides one. Thread-safe.
   */
  final private[graph] class WriteCheck:
    private val problems = new ConcurrentLinkedQueue[String]()

    /** Whether `update` touches only `m`'s `writes`; records each key it does not declare. */
    def apply(m: AgentMiddleware, update: StateUpdate): Boolean =
      val undeclared =
        update.operations.map(_.key.id).filterNot(k => m.writes.exists(_.id == k)).distinct
      undeclared.foreach(k =>
        problems.add(s"middleware '${m.id.value}' updated '${k.value}', which it does not declare"): Unit
      )
      undeclared.isEmpty

    /** Every undeclared write recorded, as one `ValidationError`; `None` when there was none. */
    def failure: Option[ValidationError] =
      val found = problems.asScala.toList.distinct
      Option.when(found.nonEmpty)(ValidationError("middleware update", found))

  /** What a tool call's chain returned; for a `NeedsApproval` or an `Ask`, `raisedBy` says who: `None` = the tool, `Some(id)` = that middleware. */
  final private[graph] case class ToolChainResult(outcome: ToolOutcome, raisedBy: Option[MiddlewareId])

  /** No middleware: every chain is its innermost function. */
  val empty: MiddlewareStack = new MiddlewareStack(Vector.empty)

  private val IdPattern = "[a-zA-Z0-9_-]{1,64}".r

  private def operation(m: AgentMiddleware): String = s"middleware ${m.id.value}"

  /**
   * Runs one hook, returning what it throws as `Left`: a NonFatal exception, or a bare
   * `InterruptedException`, which `Try` alone rethrows past every enclosing wrapper with the flag clear.
   */
  private def attempt[A](run: => A): Either[Throwable, A] =
    CancelledError.catchInterrupt(Try(run)) match
      case Right(Success(value))  => Right(value)
      case Right(Failure(thrown)) => Left(thrown)
      case Left(interrupted)      => Left(interrupted)

  /**
   * Orders `middleware` topologically by `runsBefore` / `runsAfter` (`a.runsBefore(b)` and
   * `b.runsAfter(a)` are the same edge, `a` outside `b`), breaking ties by registration order.
   * One `ValidationError` lists every invalid id, duplicate id, constraint naming an id that is not
   * registered, cycle, and tool name contributed twice by one middleware or by more than one; when there is none, the
   * order is checked against every middleware's [[AgentMiddleware.placement]], and one `ValidationError` lists each
   * middleware that runs inside one it must run outside.
   */
  def of(middleware: AgentMiddleware*): Result[MiddlewareStack] =
    val all   = middleware.toVector
    val nodes = all.map(_.id).distinct
    val known = nodes.toSet

    val invalid = nodes
      .filterNot(id => IdPattern.matches(id.value))
      .map(id => s"invalid middleware id '${id.value}': must match [a-zA-Z0-9_-]{1,64}")

    val duplicates = nodes
      .filter(id => all.count(_.id == id) > 1)
      .map(id => s"duplicate middleware id '${id.value}'")

    val unknown = all.flatMap { m =>
      def check(kind: String, ids: Set[MiddlewareId]): Vector[String] =
        ids.toVector
          .filterNot(known.contains)
          .map(_.value)
          .sorted
          .map(u => s"middleware '${m.id.value}': $kind names unknown middleware '$u'")
      check("runsAfter", m.runsAfter) ++ check("runsBefore", m.runsBefore)
    }.distinct

    val edges: Set[(MiddlewareId, MiddlewareId)] = all.flatMap { m =>
      m.runsBefore.filter(known.contains).map(m.id -> _) ++ m.runsAfter.filter(known.contains).map(_ -> m.id)
    }.toSet
    val successors: Map[MiddlewareId, Vector[MiddlewareId]] =
      nodes.map(n => n -> nodes.filter(s => edges.contains(n -> s))).toMap

    // Kahn's algorithm, taking the earliest-registered ready node each step
    @tailrec
    def kahn(
      done: Vector[MiddlewareId],
      remaining: Vector[MiddlewareId]
    ): (Vector[MiddlewareId], Vector[MiddlewareId]) =
      remaining.find(n => !remaining.exists(from => edges.contains(from -> n))) match
        case Some(n) => kahn(done :+ n, remaining.filterNot(_ == n))
        case None    => (done, remaining)
    val (order, blocked) = kahn(Vector.empty, nodes)

    val cycles = cyclesIn(blocked, successors).map(c => s"middleware cycle: ${c.map(_.value).mkString(" -> ")}")

    val contributed = all.flatMap(m => m.tools.map(t => t.spec.name -> m.id.value))
    val clashes = contributed.map(_._1).distinct.flatMap { name =>
      val owners   = contributed.collect { case (`name`, owner) => owner }
      val distinct = owners.distinct
      val twice = distinct
        .filter(owner => owners.count(_ == owner) > 1)
        .map(owner => s"tool '$name' is contributed twice by middleware '$owner'")
      twice ++ Option.when(distinct.size > 1)(
        s"tool '$name' is contributed by more than one middleware: ${distinct.mkString(", ")}"
      )
    }

    val problems = (invalid ++ duplicates ++ unknown ++ cycles ++ clashes).toList
    if problems.nonEmpty then Left(ValidationError("middleware stack", problems))
    else
      val byId    = all.map(m => m.id -> m).toMap
      val ordered = order.map(byId)
      misplaced(ordered).toList match
        case Nil       => Right(new MiddlewareStack(ordered))
        case misplaced => Left(ValidationError("middleware stack", misplaced))

  /**
   * Every pair of `ordered` (outermost first) where a middleware's [[AgentMiddleware.placement]] requires it to run
   * outside another that runs outside it, described with the fix.
   */
  private def misplaced(ordered: Vector[AgentMiddleware]): Vector[String] =
    for
      (inner, at) <- ordered.zipWithIndex
      outer       <- ordered.take(at)
      required    <- inner.placement(outer).toVector
    yield s"middleware '${inner.id.value}' must run outside '${outer.id.value}' (${required.reason}), but runs " +
      s"inside it: register '${inner.id.value}' before '${outer.id.value}'"

  /**
   * One cycle per strongly connected component of `blocked` that has one, starting and ending at
   * its earliest-registered member; the shortest such path, preferring earlier registrations.
   */
  private def cyclesIn(
    blocked: Vector[MiddlewareId],
    successors: Map[MiddlewareId, Vector[MiddlewareId]]
  ): Vector[Vector[MiddlewareId]] =
    val inBlocked = blocked.toSet

    def reachable(from: MiddlewareId): Set[MiddlewareId] =
      @tailrec
      def go(frontier: Vector[MiddlewareId], seen: Set[MiddlewareId]): Set[MiddlewareId] =
        frontier.headOption match
          case None => seen
          case Some(n) =>
            val fresh = successors(n).filter(s => inBlocked.contains(s) && !seen.contains(s))
            go(frontier.tail ++ fresh, seen ++ fresh)
      go(Vector(from), Set.empty)

    val reach = blocked.map(n => n -> reachable(n)).toMap

    def cycleFrom(start: MiddlewareId, component: Set[MiddlewareId]): Vector[MiddlewareId] =
      @tailrec
      def go(queue: Vector[Vector[MiddlewareId]], seen: Set[MiddlewareId]): Vector[MiddlewareId] =
        val path  = queue.head
        val nexts = successors(path.last).filter(component.contains)
        if nexts.contains(start) then path :+ start
        else
          val fresh = nexts.filterNot(seen.contains)
          go(queue.tail ++ fresh.map(path :+ _), seen ++ fresh)
      go(Vector(Vector(start)), Set(start))

    blocked
      .foldLeft((Vector.empty[Vector[MiddlewareId]], Set.empty[MiddlewareId])) { case ((found, covered), n) =>
        if covered.contains(n) || !reach(n).contains(n) then (found, covered)
        else
          val component = blocked.filter(u => u == n || (reach(n).contains(u) && reach(u).contains(n))).toSet
          (found :+ cycleFrom(n, component), covered ++ component)
      }
      ._1
