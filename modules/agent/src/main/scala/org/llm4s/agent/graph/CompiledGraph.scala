package org.llm4s.agent.graph

import org.llm4s.error.{ CancelledError, LLMError }
import org.llm4s.types.{ Result, TryOps }

import scala.util.Try

/**
 * An immutable, validated graph, run in supersteps.
 *
 * Each superstep runs every ready task against the same committed [[ThreadState]], then commits
 * their updates and schedules the next frontier together:
 *
 *  1. Tasks run in frontier order (possibly concurrently); none sees another's writes.
 *  1. If any task fails, the superstep commits nothing and the run fails with the first failure
 *     in frontier order.
 *  1. Each command is checked: every update is to a key in the node's declared write set, every
 *     route targets a node and join of this graph, and a task fans out to a join at most once.
 *  1. Updates apply task by task in frontier order, and in emission order within a task.
 *  1. The next frontier is, for each task in order, its static edges (declaration order) then
 *     its routes (route order, fan-out payloads in item order); then the targets of joins this
 *     commit released - static joins in declaration order, then dynamic activations in the order
 *     they opened.
 *
 * A task that returns [[NodeResult.Suspend]] parks a continuation; its update commits with the
 * superstep, and the run pauses after that superstep ([[RunResult.Suspended]]). A continuation
 * stands in for the suspended task: when it completes it makes that task's join arrivals. The
 * suspended task itself makes no arrival and fires none of its node's static edges.
 *
 * When the frontier is empty the run completes - unless a join is still waiting. A join waiting
 * only for arrivals that parked continuations can make keeps the run suspended; a join waiting for
 * an arrival nothing can make fails the run with [[GraphError.UnsatisfiedJoin]].
 */
final class CompiledGraph[I, O] private[graph] (
  val id: String,
  val version: String,
  val fingerprint: String,
  owner: GraphOwner,
  entry: NodeRef[I],
  nodes: Map[NodeId, NodeDef[?]],
  edges: Map[NodeId, Vector[NodeId]],
  staticJoins: Vector[StaticJoin],
  dynamicJoins: Map[JoinId, DynamicJoin],
  resumes: Map[NodeId, ResumeRef[?, ?]],
  keys: Map[StateKeyId, StateKey[?, ?]],
  output: ThreadState => Result[O],
  executorOverride: Option[TaskExecutor]
):

  /** An execution with only the entry task ready, before any superstep has run. */
  def start(input: I): Execution = startAt(0, ThreadState.empty(keys), input)

  /**
   * Runs one superstep, or finishes or suspends the run if `execution` cannot advance. The superstep
   * runs at most `config.budgets.maxConcurrency` tasks at a time. No superstep limit is checked:
   * a caller driving `step` owns its loop. Outside a [[GraphRuntime]], `emit` and `progress` are
   * no-ops and each task's `checkpointId` is `""`.
   */
  def step(threadId: ThreadId, execution: Execution, config: RunConfig): Step[O] =
    if execution.owner ne owner then Step.Done(RunResult.Failed(execution.state, GraphError.ForeignExecution(id)))
    else if execution.paused then Step.Done(suspended(execution))
    else if execution.isQuiescent then Step.Done(complete(execution))
    else
      CancelledError.catchInterrupt(superstep(threadId, execution, config)) match
        case Left(_)                        => Step.Done(cancelled(execution))
        case Right(Left(_: CancelledError)) => Step.Done(cancelled(execution))
        case Right(Left(error))             => Step.Done(RunResult.Failed(execution.state, error))
        case Right(Right((next, None)))     => Step.Next(next)
        // a blocked task ends the run once the superstep is committed: the state keeps its update
        case Right(Right((next, Some(error)))) => Step.Done(RunResult.Failed(next.state, error))

  /** A run cancelled during a superstep: nothing from it commits, and the interrupt flag is set. */
  private def cancelled(execution: Execution): RunResult[O] =
    Thread.currentThread().interrupt()
    RunResult.Failed(execution.state, GraphError.Cancelled(None, None))

  /**
   * Answers some of `execution`'s parked continuations. Each answer is decoded with its resume
   * node's answer codec; each answered continuation is scheduled after the ready frontier, in the
   * order it was parked, with its suspended task's join slot. Unanswered continuations stay
   * parked. Fails without changing anything if `answers` is empty, names an interrupt that is not
   * parked, or holds an answer that does not decode.
   */
  def resume(execution: Execution, answers: Map[InterruptId, ujson.Value]): Result[Execution] =
    if execution.owner ne owner then Left(GraphError.ForeignExecution(id))
    else if answers.isEmpty then Left(GraphError.InvalidResume(id, List("no answers were given")))
    else
      val parkedIds = execution.parked.map(_.interrupt).toSet
      val unknown   = answers.keys.filterNot(parkedIds.contains).map(i => s"interrupt '${i.value}' is not pending")
      val decoded = execution.parked.filter(p => answers.contains(p.interrupt)).map { p =>
        resumes(p.resumeNode)
          .decodeAnswer(answers(p.interrupt))
          .left
          .map(e => s"the answer to interrupt '${p.interrupt.value}' does not decode: ${e.message}")
          .map(answer => p -> answer)
      }
      val problems = unknown.toVector.sorted ++ decoded.collect { case Left(problem) => problem }
      if problems.nonEmpty then Left(GraphError.InvalidResume(id, problems.toList))
      else
        val continuations =
          decoded.collect { case Right((p, answer)) => p -> answer }.zipWithIndex.map { case ((p, answer), i) =>
            Task(
              TaskId(s"${execution.superstep}.${execution.frontier.size + i}"),
              p.resumeNode,
              Resumed(p.question, answer),
              p.slot,
              Some(p.origin)
            )
          }
        Right(
          execution.copy(
            frontier = execution.frontier ++ continuations,
            parked = execution.parked.filterNot(p => answers.contains(p.interrupt)),
            paused = false
          )
        )

  /** Encodes `execution` as data, using each key's, node's and resume node's codec. */
  def snapshot(execution: Execution): Result[GraphSnapshot] =
    if execution.owner ne owner then Left(GraphError.ForeignExecution(id))
    else
      Try(
        GraphSnapshot(
          graphId = id,
          graphVersion = version,
          fingerprint = fingerprint,
          superstep = execution.superstep,
          state = execution.state.values.map((key, value) => key.value -> keys(key).encode(value)),
          frontier = execution.frontier.map { task =>
            GraphSnapshot.PendingTask(
              task.id.value,
              task.node.value,
              nodes(task.node).encode(task.input),
              task.slot.map(_.join.value),
              task.slot.map(_.fanOutTask.value),
              task.origin.map(_.task.value),
              task.origin.map(_.node.value)
            )
          },
          staticJoins = execution.staticArrivals.toVector
            .sortBy(_._1.value)
            .map((join, arrived) => GraphSnapshot.StaticArrivals(join.value, arrived.map(_.value).toVector.sorted)),
          dynamicJoins = execution.dynamicActivations.map { a =>
            GraphSnapshot.Activation(
              a.join.value,
              a.fanOutTask.value,
              a.expected.map(_.value),
              a.arrived.map(_.value).toVector.sorted
            )
          },
          parked = execution.parked.map { p =>
            GraphSnapshot.ParkedContinuation(
              p.interrupt.value,
              p.resumeNode.value,
              resumes(p.resumeNode).encodeQuestion(p.question),
              p.origin.task.value,
              p.origin.node.value,
              p.slot.map(_.join.value),
              p.slot.map(_.fanOutTask.value)
            )
          },
          paused = execution.paused
        )
      ).toResult

  /**
   * Rebuilds an execution from a snapshot, checking it against this graph first: the graph id,
   * version and structural fingerprint; that every state value, pending input and parked question
   * decodes with this graph's codec for it; that every join exists with the right kind and is
   * partially arrived; that every parked continuation names a resume node; and that every arrival
   * a dynamic join still expects will be made by a pending task or a parked continuation in its
   * fan-out. Every problem is reported at once.
   */
  def restore(snapshot: GraphSnapshot): Result[Execution] =
    val header = Vector(
      Option.when(snapshot.graphId != id)(s"snapshot is of graph '${snapshot.graphId}'"),
      Option.when(snapshot.graphVersion != version)(
        s"snapshot is of version '${snapshot.graphVersion}', this graph is version '$version'"
      ),
      Option.when(snapshot.fingerprint != fingerprint)("snapshot's graph structure differs from this graph's"),
      Option.when(snapshot.superstep < 0)(s"superstep ${snapshot.superstep} is negative")
    ).flatten
    val (stateProblems, values)      = snapshot.state.toVector.sortBy(_._1).partitionMap(restoreValue)
    val (taskProblems, tasks)        = snapshot.frontier.partitionMap(restoreTask)
    val (parkedProblems, parked)     = snapshot.parked.partitionMap(restoreParked)
    val (staticProblems, arrivals)   = snapshot.staticJoins.partitionMap(restoreStaticArrivals)
    val (dynamicProblems, activated) = snapshot.dynamicJoins.partitionMap(restoreActivation(_, snapshot))
    val duplicateTasks = snapshot.frontier.map(_.taskId).groupBy(identity).collect {
      case (taskId, copies) if copies.size > 1 => s"task $taskId is pending more than once"
    }
    val duplicateInterrupts = snapshot.parked.map(_.interruptId).groupBy(identity).collect {
      case (interruptId, copies) if copies.size > 1 => s"interrupt $interruptId is parked more than once"
    }
    val duplicateActivations = activated.groupBy(a => (a.join, a.fanOutTask)).collect {
      case ((join, task), copies) if copies.size > 1 =>
        s"dynamic join '${join.value}' has more than one activation for fan-out ${task.value}"
    }
    val savedSlots = snapshot.dynamicJoins.flatMap(a => a.expected.map((a.joinId, a.fanOutTask, _))).toSet
    val orphanTasks = tasks.collect {
      case task @ Task(_, _, _, Some(slot), _)
          if !savedSlots.contains((slot.join.value, slot.fanOutTask.value, task.arrivalId.value)) =>
        s"pending task ${task.id.value} belongs to no open activation of dynamic join '${slot.join.value}'"
    }
    val orphanParked = parked.collect {
      case p @ Parked(_, _, _, origin, Some(slot))
          if !savedSlots.contains((slot.join.value, slot.fanOutTask.value, origin.task.value)) =>
        s"interrupt ${p.interrupt.value} belongs to no open activation of dynamic join '${slot.join.value}'"
    }
    val problems = header ++ stateProblems ++ taskProblems ++ parkedProblems ++ staticProblems ++
      dynamicProblems ++ duplicateTasks.toVector.sorted ++ duplicateInterrupts.toVector.sorted ++
      duplicateActivations.toVector.sorted ++ orphanTasks ++ orphanParked
    if problems.nonEmpty then Left(GraphError.RestoreRejected(id, problems.toList))
    else
      Right(
        new Execution(
          owner,
          snapshot.superstep,
          new ThreadState(keys, values.toMap),
          tasks,
          arrivals.toMap,
          activated,
          parked,
          snapshot.paused
        )
      )

  private[graph] def withExecutor(next: TaskExecutor): CompiledGraph[I, O] =
    new CompiledGraph(
      id,
      version,
      fingerprint,
      owner,
      entry,
      nodes,
      edges,
      staticJoins,
      dynamicJoins,
      resumes,
      keys,
      output,
      Some(next)
    )

  /** The executor a run with `budgets` uses: a test override, else bounded by `maxConcurrency`. */
  private[graph] def executorFor(budgets: RunBudgets): TaskExecutor =
    executorOverride.getOrElse(TaskExecutor.bounded(budgets.maxConcurrency))

  /** An execution over `state` with nothing ready: a finished thread, for [[GraphRuntime.seed]]. */
  private[graph] def seeded(state: ThreadState): Execution =
    new Execution(owner, 0, state, Vector.empty, Map.empty, Vector.empty, Vector.empty, paused = false)

  /** The graph's initial state: every registered key at its initial value, for [[GraphRuntime.seed]]. */
  private[graph] def initialState: ThreadState = ThreadState.empty(keys)

  /** An execution at `superstep` over `state` with only the entry task ready. */
  private[graph] def startAt(superstep: Int, state: ThreadState, input: I): Execution =
    new Execution(
      owner,
      superstep,
      state,
      Vector(Task(TaskId(s"$superstep.0"), entry.id, input, None)),
      Map.empty,
      Vector.empty,
      Vector.empty,
      paused = false
    )

  private def superstep(
    threadId: ThreadId,
    execution: Execution,
    config: RunConfig
  ): Result[(Execution, Option[LLMError])] =
    val outcomes = executorFor(config.budgets).runAll(execution.frontier.map { task => () =>
      val position = RunPosition(threadId, config.runId, "", task.id, task.node, execution.superstep)
      executeTask(task, execution, new RunContext(config, position, NodeEventSink.none)).map(task -> _)
    })
    // A cancelled task wins over an earlier task's failure: the run was cancelled, not failed.
    outcomes
      .collectFirst { case Left(c: CancelledError) => c }
      .fold(
        sequence(outcomes).flatMap(done => commitSuperstep(execution, done).map(next => next -> blockedBy(done)))
      )(Left(_))

  /**
   * The error of the first blocked task in `completed`, in frontier order: the run ends once the superstep is
   * committed, and the caller receives this error.
   */
  private[graph] def blockedBy(completed: Vector[(Task, TaskResult)]): Option[LLMError] =
    completed.collectFirst { case (_, TaskResult.Blocked(_, error)) => error }

  /**
   * Runs one task against the committed snapshot and checks what it returned. A task whose node
   * throws `InterruptedException`, or whose thread is interrupted when its node returns, is
   * cancelled: `Left(CancelledError)` with the flag set, whatever the node returned.
   */
  private[graph] def executeTask(task: Task, execution: Execution, context: RunContext): Result[TaskResult] =
    nodes.get(task.node) match
      case None => Left(GraphError.InvalidRoute(task.node, task.id, "node is not part of this graph"))
      case Some(node) =>
        val operation = s"task ${task.id.value}"
        CancelledError.attempt(operation)(Try(node.run(task.input, execution.state, context)).toResult) match
          case Left(cancelled: CancelledError)                  => Left(cancelled)
          case Right(_) if Thread.currentThread().isInterrupted => Left(CancelledError(operation))
          case Left(thrown)                  => Left(GraphError.NodeFailed(task.node, task.id, thrown))
          case Right(NodeResult.Fail(error)) => Left(GraphError.NodeFailed(task.node, task.id, error))
          case Right(NodeResult.Block(update, error)) =>
            undeclaredWrite(task, update).toLeft(TaskResult.Blocked(update, error))
          case Right(NodeResult.Continue(command)) =>
            validate(task, command).map(_ => TaskResult.Done(command))
          case Right(NodeResult.Suspend(update, question, resumeAt)) =>
            validateSuspension(task, update, resumeAt).map(_ => TaskResult.Parked(update, question, resumeAt))

  /** Checks a result - such as one decoded from a pending write - as if `task` had just returned it. */
  private[graph] def checkResult(task: Task, result: TaskResult): Result[Unit] =
    result match
      case TaskResult.Done(command)               => validate(task, command)
      case TaskResult.Parked(update, _, resumeAt) => validateSuspension(task, update, resumeAt)
      case TaskResult.Blocked(update, _)          => undeclaredWrite(task, update).toLeft(())

  /**
   * Applies every task's checked result in frontier order and schedules the next frontier.
   * `completed` holds a result for every frontier task, in frontier order.
   */
  private[graph] def commitSuperstep(execution: Execution, completed: Vector[(Task, TaskResult)]): Result[Execution] =
    completed
      .foldLeft[Result[ThreadState]](Right(execution.state))((state, tr) => state.flatMap(_.applyUpdate(tr._2.update)))
      .map(schedule(execution, completed, _))

  /** Completes, suspends or fails an execution with an empty frontier. */
  private[graph] def finish(execution: Execution): RunResult[O] =
    if execution.paused then suspended(execution) else complete(execution)

  /** The interrupts parked in `execution`, with their questions as JSON. */
  private[graph] def pendingInterrupts(execution: Execution): Vector[PendingInterrupt] =
    execution.parked.map(p =>
      PendingInterrupt(p.interrupt, p.resumeNode, resumes(p.resumeNode).encodeQuestion(p.question).value)
    )

  /** A completed or suspended task's result as data, for a checkpoint's pending writes. */
  private[graph] def encodeWrite(checkpointId: String, task: Task, result: TaskResult): Result[PendingWrite] =
    Try {
      val operations = result.update.operations.map {
        case update: StateOperation.Update[?, ?] =>
          EncodedOperation.Update(update.key.id.value, update.key.encodeUpdate(update.value))
        case StateOperation.Remove(key) => EncodedOperation.Remove(key.id.value)
      }
      result match
        case TaskResult.Done(command) =>
          val routes = command.routes.toVector.map {
            case Route.Goto(to)       => EncodedRoute.Goto(to.id.value)
            case Route.Send(to, data) => EncodedRoute.Send(to.id.value, nodes(to.id).encode(data))
            case Route.FanOut(join, to, payloads) =>
              EncodedRoute.FanOut(join.id.value, to.id.value, payloads.map(nodes(to.id).encode))
          }
          PendingWrite(checkpointId, task.id.value, task.node.value, operations, routes)
        case TaskResult.Parked(_, question, resume) =>
          PendingWrite(
            checkpointId,
            task.id.value,
            task.node.value,
            operations,
            Vector.empty,
            Some(EncodedSuspension(resume.node.id.value, resume.encodeQuestion(question)))
          )
        // a blocked task is never written: the runtime ends the run, and recovery runs the task again
        case TaskResult.Blocked(_, _) =>
          throw new IllegalStateException(s"task ${task.id.value} is blocked and has no pending write")
    }.toResult

  /** Rebinds a pending write to this graph's keys, nodes and joins, migrating encoded values. */
  private[graph] def decodeWrite(write: PendingWrite): Result[TaskResult] =
    def problem(reason: String) =
      GraphError.RestoreRejected(id, List(s"pending write for task ${write.taskId}: $reason"))
    def node(nodeId: String) = nodes.get(NodeId(nodeId)).toRight(problem(s"unknown node '$nodeId'"))
    def key(keyId: String)   = keys.get(StateKeyId(keyId)).toRight(problem(s"unknown state key '$keyId'"))
    val operations = sequence(write.operations.map {
      case EncodedOperation.Update(keyId, update) =>
        key(keyId).flatMap { k =>
          k.decodeUpdate(update)
            .left
            .map(e => problem(s"update to '$keyId' does not decode: ${e.message}"))
            .map(u => StateOperation.Update(k.asInstanceOf[StateKey[Any, Any]], u): StateOperation)
        }
      case EncodedOperation.Remove(keyId) => key(keyId).map(StateOperation.Remove(_))
    })
    def payload(target: NodeDef[?], json: VersionedJson) =
      target.decode(json).left.map(e => problem(s"input for '${target.ref.id.value}' does not decode: ${e.message}"))
    val routes = sequence(write.routes.map {
      case EncodedRoute.Goto(to) => node(to).map(n => Route.Goto(n.ref.asInstanceOf[NodeRef[Unit]]): Route)
      case EncodedRoute.Send(to, data) =>
        node(to).flatMap(n => payload(n, data).map(p => Route.Send(n.ref.asInstanceOf[NodeRef[Any]], p)))
      case EncodedRoute.FanOut(joinId, to, payloads) =>
        for
          join <- dynamicJoins.get(JoinId(joinId)).toRight(problem(s"unknown dynamic join '$joinId'"))
          n    <- node(to)
          ps   <- sequence(payloads.map(payload(n, _)))
        yield Route.FanOut(join, n.ref.asInstanceOf[NodeRef[Any]], ps)
    })
    for
      ops <- operations
      rs  <- routes
      result <- write.suspension match
        case None => Right(TaskResult.Done(Command(new StateUpdate(ops), rs.toList)))
        case Some(EncodedSuspension(resumeNode, question)) =>
          for
            resume <- resumes.get(NodeId(resumeNode)).toRight(problem(s"'$resumeNode' is not a resume node"))
            q <- resume
              .decodeQuestion(question)
              .left
              .map(e => problem(s"question for '$resumeNode' does not decode: ${e.message}"))
          yield TaskResult.Parked(new StateUpdate(ops), q, resume)
    yield result

  private def sequence[A](results: Vector[Result[A]]): Result[Vector[A]] =
    results.foldLeft[Result[Vector[A]]](Right(Vector.empty))((acc, r) => acc.flatMap(as => r.map(as :+ _)))

  private def undeclaredWrite(task: Task, update: StateUpdate): Option[GraphError] =
    val writes = nodes.get(task.node).fold(Set.empty[StateKey[?, ?]])(_.writes)
    update.operations
      .find(op => !writes.contains(op.key))
      .map(op => GraphError.UndeclaredWrite(task.node, task.id, op.key.id))

  private def validate(task: Task, command: Command): Result[Unit] =
    val joins = command.routes.collect { case Route.FanOut(join, _, _) => join.id }
    val routeProblem = command.routes.iterator
      .flatMap(routeProblemOf)
      .nextOption()
      .orElse(
        joins.diff(joins.distinct).headOption.map(join => s"it fans out to join '${join.value}' more than once")
      )
      .map(GraphError.InvalidRoute(task.node, task.id, _))
    undeclaredWrite(task, command.update).orElse(routeProblem).toLeft(())

  private def validateSuspension(task: Task, update: StateUpdate, resumeAt: ResumeRef[?, ?]): Result[Unit] =
    val notOurs = Option.unless((resumeAt.node.owner eq owner) && resumes.contains(resumeAt.node.id))(
      GraphError.InvalidRoute(task.node, task.id, s"resume node '${resumeAt.node.id.value}' is not part of this graph")
    )
    undeclaredWrite(task, update).orElse(notOurs).toLeft(())

  private def routeProblemOf(route: Route): Option[String] =
    def target(ref: NodeRef[?]): Option[String] =
      Option.unless((ref.owner eq owner) && nodes.contains(ref.id))(s"node '${ref.id.value}' is not part of this graph")
    route match
      case Route.Goto(to)    => target(to)
      case Route.Send(to, _) => target(to)
      case Route.FanOut(join, to, _) =>
        if (join.owner ne owner) || !dynamicJoins.contains(join.id) then
          Some(s"dynamic join '${join.id.value}' is not part of this graph")
        else target(to)

  private def schedule(execution: Execution, results: Vector[(Task, TaskResult)], state: ThreadState): Execution =
    val next      = execution.superstep + 1
    val completed = results.collect { case (task, TaskResult.Done(command)) => task -> command }
    val routed = completed.flatMap { (task, command) =>
      edges.getOrElse(task.node, Vector.empty).map(to => (to, (): Any, Option.empty[JoinSlot])) ++
        command.routes.flatMap {
          case Route.Goto(to)       => Vector((to.id, (): Any, None))
          case Route.Send(to, data) => Vector((to.id, data: Any, None))
          case Route.FanOut(join, to, payloads) =>
            payloads.map(data => (to.id, data: Any, Some(JoinSlot(join.id, task.id))))
        }
    }
    val routedTasks = routed.zipWithIndex.map { case ((node, input, slot), i) =>
      Task(TaskId(s"$next.$i"), node, input, slot)
    }

    val arrivedNodes = completed.map(_._1.arrivalNode).toSet
    val (staticReleased, staticArrivals) =
      staticJoins.foldLeft((Vector.empty[NodeId], execution.staticArrivals)) { case ((released, arrivals), join) =>
        val now = arrivals.getOrElse(join.id, Set.empty) ++ join.sources.intersect(arrivedNodes)
        if now == join.sources then (released :+ join.target.id, arrivals - join.id)
        else if now.isEmpty then (released, arrivals)
        else (released, arrivals.updated(join.id, now))
      }

    val arrivedBySlot = completed.flatMap((task, _) => task.slot.map(_ -> task.arrivalId)).groupMap(_._1)(_._2)
    val advanced = execution.dynamicActivations.map { a =>
      a.copy(arrived = a.arrived ++ arrivedBySlot.getOrElse(JoinSlot(a.join, a.fanOutTask), Vector.empty))
    }
    val opened = completed.flatMap { (task, command) =>
      command.routes.collect { case Route.FanOut(join, _, _) =>
        val slot = JoinSlot(join.id, task.id)
        DynamicActivation(join.id, task.id, routedTasks.filter(_.slot.contains(slot)).map(_.id), Set.empty)
      }
    }
    val (released, stillOpen) = (advanced ++ opened).partition(_.isComplete)

    val releasedTasks = (staticReleased ++ released.map(a => dynamicJoins(a.join).target.id)).zipWithIndex.map {
      (node, i) => Task(TaskId(s"$next.${routedTasks.size + i}"), node, (), None)
    }
    val newlyParked = results.collect { case (task, TaskResult.Parked(_, question, resume)) =>
      Parked(
        InterruptId(task.id.value),
        resume.node.id,
        question,
        task.origin.getOrElse(Origin(task.id, task.node)),
        task.slot
      )
    }
    new Execution(
      owner,
      next,
      state,
      routedTasks ++ releasedTasks,
      staticArrivals,
      stillOpen,
      execution.parked ++ newlyParked,
      paused = newlyParked.nonEmpty
    )

  private def suspended(execution: Execution): RunResult[O] =
    RunResult.Suspended(execution.state, pendingInterrupts(execution), execution)

  private def complete(execution: Execution): RunResult[O] =
    def parkedFor(node: NodeId) = execution.parked.exists(_.origin.node == node)
    def parkedIn(slot: JoinSlot, task: TaskId) =
      execution.parked.exists(p => p.slot.contains(slot) && p.origin.task == task)
    val unsatisfiedStatic = staticJoins.flatMap { join =>
      execution.staticArrivals.get(join.id).flatMap { arrived =>
        val missing = (join.sources -- arrived).filterNot(parkedFor)
        Option.when(missing.nonEmpty)(
          GraphError.UnsatisfiedJoin(join.id, missing.map(n => s"node '${n.value}'").toList.sorted)
        )
      }
    }
    val unsatisfiedDynamic = execution.dynamicActivations.flatMap { a =>
      val slot    = JoinSlot(a.join, a.fanOutTask)
      val missing = a.expected.filterNot(a.arrived.contains).filterNot(parkedIn(slot, _))
      Option.when(missing.nonEmpty)(GraphError.UnsatisfiedJoin(a.join, missing.map(t => s"task ${t.value}").toList))
    }
    (unsatisfiedStatic ++ unsatisfiedDynamic).headOption match
      case Some(unsatisfied)                 => RunResult.Failed(execution.state, unsatisfied)
      case None if execution.parked.nonEmpty => suspended(execution)
      case None =>
        Try(output(execution.state)).toResult.flatten
          .fold(RunResult.Failed(execution.state, _), RunResult.Completed(execution.state, _, execution.superstep))

  private def restoreValue(entry: (String, VersionedJson)): Either[String, (StateKeyId, Any)] =
    val (keyId, json) = entry
    keys.get(StateKeyId(keyId)) match
      case None => Left(s"state key '$keyId' is not registered with this graph")
      case Some(key) =>
        key
          .decode(json)
          .left
          .map(e => s"state key '$keyId' does not decode: ${e.message}")
          .map(key.id -> _)

  private def restoreSlot(owner: String, joinId: Option[String], fanOutTask: Option[String]) =
    (joinId, fanOutTask) match
      case (None, None) => Right(None)
      case (Some(join), Some(fanOut)) if dynamicJoins.contains(JoinId(join)) =>
        Right(Some(JoinSlot(JoinId(join), TaskId(fanOut))))
      case (Some(join), Some(_)) => Left(s"$owner belongs to unknown dynamic join '$join'")
      case _                     => Left(s"$owner has half a join slot")

  private def restoreTask(pending: GraphSnapshot.PendingTask): Either[String, Task] =
    val owner = s"pending task ${pending.taskId}"
    for
      node <- nodes
        .get(NodeId(pending.nodeId))
        .toRight(s"$owner targets unknown node '${pending.nodeId}'")
      input <- node.decode(pending.input).left.map { e =>
        s"$owner input does not decode for node '${pending.nodeId}': ${e.message}"
      }
      slot <- restoreSlot(owner, pending.joinId, pending.fanOutTask)
      origin <- (pending.originTask, pending.originNode) match
        case (None, None) => Right(None)
        case (Some(task), Some(originNode)) if nodes.contains(NodeId(originNode)) =>
          Right(Some(Origin(TaskId(task), NodeId(originNode))))
        case (Some(_), Some(originNode)) => Left(s"$owner continues unknown node '$originNode'")
        case _                           => Left(s"$owner has half an origin")
    yield Task(TaskId(pending.taskId), node.ref.id, input, slot, origin)

  private def restoreParked(saved: GraphSnapshot.ParkedContinuation): Either[String, Parked] =
    val owner = s"interrupt ${saved.interruptId}"
    for
      resume <- resumes
        .get(NodeId(saved.resumeNode))
        .toRight(s"$owner resumes at '${saved.resumeNode}', not a resume node")
      question <- resume.decodeQuestion(saved.question).left.map { e =>
        s"$owner question does not decode for '${saved.resumeNode}': ${e.message}"
      }
      _ <- Either.cond(
        nodes.contains(NodeId(saved.originNode)),
        (),
        s"$owner continues unknown node '${saved.originNode}'"
      )
      slot <- restoreSlot(owner, saved.joinId, saved.fanOutTask)
    yield Parked(
      InterruptId(saved.interruptId),
      resume.node.id,
      question,
      Origin(TaskId(saved.originTask), NodeId(saved.originNode)),
      slot
    )

  private def restoreStaticArrivals(saved: GraphSnapshot.StaticArrivals): Either[String, (JoinId, Set[NodeId])] =
    staticJoins.find(_.id == JoinId(saved.joinId)) match
      case None => Left(s"static join '${saved.joinId}' is not part of this graph")
      case Some(join) =>
        val arrived = saved.arrived.map(NodeId(_)).toSet
        val strays  = (arrived -- join.sources).map(_.value).toVector.sorted
        if strays.nonEmpty then
          Left(s"static join '${saved.joinId}' records arrivals from non-sources ${strays.mkString(", ")}")
        // the scheduler keeps only partial activations: none is not open, all would have released
        else if arrived.isEmpty then Left(s"static join '${saved.joinId}' records no arrivals")
        else if arrived == join.sources then
          Left(s"static join '${saved.joinId}' records every source, so it has already released")
        else Right(join.id -> arrived)

  private def restoreActivation(
    saved: GraphSnapshot.Activation,
    snapshot: GraphSnapshot
  ): Either[String, DynamicActivation] =
    val join = JoinId(saved.joinId)
    if !dynamicJoins.contains(join) then Left(s"dynamic join '${saved.joinId}' is not part of this graph")
    else
      val activation =
        DynamicActivation(
          join,
          TaskId(saved.fanOutTask),
          saved.expected.map(TaskId(_)),
          saved.arrived.map(TaskId(_)).toSet
        )
      def inSlot(joinId: Option[String], fanOutTask: Option[String]) =
        joinId.contains(saved.joinId) && fanOutTask.contains(saved.fanOutTask)
      val unexpected = activation.arrived.filterNot(activation.expected.contains)
      val notPending = activation.expected.filterNot(activation.arrived.contains).filterNot { id =>
        snapshot.frontier.exists(p => p.originTask.getOrElse(p.taskId) == id.value && inSlot(p.joinId, p.fanOutTask)) ||
        snapshot.parked.exists(p => p.originTask == id.value && inSlot(p.joinId, p.fanOutTask))
      }
      if activation.isComplete then
        Left(
          s"dynamic join '${saved.joinId}' (fan-out ${saved.fanOutTask}) has every arrival, so it has already released"
        )
      else if unexpected.nonEmpty then
        Left(
          s"dynamic join '${saved.joinId}' records unexpected arrivals ${unexpected.map(_.value).toVector.sorted.mkString(", ")}"
        )
      else if notPending.nonEmpty then
        Left(
          s"dynamic join '${saved.joinId}' (fan-out ${saved.fanOutTask}) waits for ${notPending.map(_.value).mkString(", ")}, which are not pending"
        )
      else Right(activation)

/** What a task produced, once checked: a command, a suspension with its update, or a block that ends the run. */
private[graph] enum TaskResult:
  case Done(command: Command)
  case Parked(suspendedUpdate: StateUpdate, question: Any, resume: ResumeRef[?, ?])
  case Blocked(blockedUpdate: StateUpdate, error: LLMError)

  def update: StateUpdate = this match
    case Done(command)        => command.update
    case Parked(update, _, _) => update
    case Blocked(update, _)   => update
