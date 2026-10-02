package org.llm4s.agent.graph

import org.llm4s.types.{ Result, TryOps }

import scala.annotation.tailrec
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
 * The run completes when the frontier is empty and no join is waiting; a join still waiting at
 * that point can never release and fails the run with [[GraphError.UnsatisfiedJoin]].
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
  keys: Map[StateKeyId, StateKey[?, ?]],
  output: ThreadState => Result[O],
  val maxSupersteps: Int,
  executor: TaskExecutor
):

  /** Runs the graph from `input` to completion or failure. */
  def run(input: I): RunResult[O] = runFrom(start(input))

  /** An execution with only the entry task ready, before any superstep has run. */
  def start(input: I): Execution = startAt(0, ThreadState.empty(keys), input)

  /** Runs supersteps from `execution` to completion or failure. */
  @tailrec def runFrom(execution: Execution): RunResult[O] =
    step(execution) match
      case Step.Next(next)   => runFrom(next)
      case Step.Done(result) => result

  /** Runs one superstep, or finishes the run if `execution` is quiescent. */
  def step(execution: Execution): Step[O] =
    if execution.owner ne owner then Step.Done(RunResult.Failed(execution.state, GraphError.ForeignExecution(id)))
    else if execution.isQuiescent then Step.Done(complete(execution))
    else if execution.superstep >= maxSupersteps then
      Step.Done(RunResult.Failed(execution.state, GraphError.SuperstepLimitExceeded(maxSupersteps)))
    else superstep(execution).fold(error => Step.Done(RunResult.Failed(execution.state, error)), Step.Next(_))

  /** Encodes `execution` as data, using each key's and node's codec. */
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
              task.slot.map(_.fanOutTask.value)
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
          }
        )
      ).toResult

  /**
   * Rebuilds an execution from a snapshot, checking it against this graph first: the graph id,
   * version and structural fingerprint; that every state value and pending input decodes with
   * this graph's codec for that key or node; that every join exists with the right kind; and that
   * every arrival a dynamic join still expects is a pending task in its fan-out. Every problem is
   * reported at once.
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
    val (staticProblems, arrivals)   = snapshot.staticJoins.partitionMap(restoreStaticArrivals)
    val (dynamicProblems, activated) = snapshot.dynamicJoins.partitionMap(restoreActivation(_, snapshot.frontier))
    val duplicateTasks = snapshot.frontier.map(_.taskId).groupBy(identity).collect {
      case (taskId, copies) if copies.size > 1 => s"task $taskId is pending more than once"
    }
    val duplicateActivations = activated.groupBy(a => (a.join, a.fanOutTask)).collect {
      case ((join, task), copies) if copies.size > 1 =>
        s"dynamic join '${join.value}' has more than one activation for fan-out ${task.value}"
    }
    val savedSlots = snapshot.dynamicJoins.flatMap(a => a.expected.map((a.joinId, a.fanOutTask, _))).toSet
    val orphans = tasks.collect {
      case task @ Task(_, _, _, Some(slot))
          if !savedSlots.contains((slot.join.value, slot.fanOutTask.value, task.id.value)) =>
        s"pending task ${task.id.value} belongs to no open activation of dynamic join '${slot.join.value}'"
    }
    val problems = header ++ stateProblems ++ taskProblems ++ staticProblems ++ dynamicProblems ++
      duplicateTasks.toVector.sorted ++ duplicateActivations.toVector.sorted ++ orphans
    if problems.nonEmpty then Left(GraphError.RestoreRejected(id, problems.toList))
    else
      Right(
        new Execution(owner, snapshot.superstep, new ThreadState(keys, values.toMap), tasks, arrivals.toMap, activated)
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
      keys,
      output,
      maxSupersteps,
      next
    )

  private[graph] def taskExecutor: TaskExecutor = executor

  private[graph] def isOwnerOf(execution: Execution): Boolean = execution.owner eq owner

  /** An execution at `superstep` over `state` with only the entry task ready. */
  private[graph] def startAt(superstep: Int, state: ThreadState, input: I): Execution =
    new Execution(
      owner,
      superstep,
      state,
      Vector(Task(TaskId(s"$superstep.0"), entry.id, input, None)),
      Map.empty,
      Vector.empty
    )

  private def superstep(execution: Execution): Result[Execution] =
    val outcomes = executor.runAll(execution.frontier.map { task => () =>
      executeTask(task, execution, NodeEventSink.none).map(task -> _)
    })
    outcomes
      .foldLeft[Result[Vector[(Task, Command)]]](Right(Vector.empty))((done, outcome) =>
        done.flatMap(cs => outcome.map(cs :+ _))
      )
      .flatMap(commitSuperstep(execution, _))

  /** Runs one task against the committed snapshot and checks its command. */
  private[graph] def executeTask(task: Task, execution: Execution, sink: NodeEventSink): Result[Command] =
    nodes.get(task.node) match
      case None => Left(GraphError.InvalidRoute(task.node, task.id, "node is not part of this graph"))
      case Some(node) =>
        val context = new NodeContext(task.id, task.node, execution.superstep, sink)
        Try(node.run(task.input, execution.state, context)).toResult match
          case Left(thrown)                        => Left(GraphError.NodeFailed(task.node, task.id, thrown))
          case Right(NodeResult.Fail(error))       => Left(GraphError.NodeFailed(task.node, task.id, error))
          case Right(NodeResult.Continue(command)) => validate(task, command).map(_ => command)

  /**
   * Applies every task's checked command in frontier order and schedules the next frontier.
   * `completed` holds a command for every frontier task, in frontier order.
   */
  private[graph] def commitSuperstep(execution: Execution, completed: Vector[(Task, Command)]): Result[Execution] =
    completed
      .foldLeft[Result[ThreadState]](Right(execution.state))((state, tc) => state.flatMap(_.applyUpdate(tc._2.update)))
      .map(schedule(execution, completed, _))

  /** Completes a quiescent execution: checks joins and projects the output. */
  private[graph] def finish(execution: Execution): RunResult[O] = complete(execution)

  /** A completed task's command as data, for a checkpoint's pending writes. */
  private[graph] def encodeWrite(checkpointId: String, task: Task, command: Command): Result[PendingWrite] =
    Try(
      PendingWrite(
        checkpointId,
        task.id.value,
        task.node.value,
        command.update.operations.map {
          case update: StateOperation.Update[?, ?] =>
            EncodedOperation.Update(update.key.id.value, update.key.encodeUpdate(update.value))
          case StateOperation.Remove(key) => EncodedOperation.Remove(key.id.value)
        },
        command.routes.toVector.map {
          case Route.Goto(to)       => EncodedRoute.Goto(to.id.value)
          case Route.Send(to, data) => EncodedRoute.Send(to.id.value, nodes(to.id).encode(data))
          case Route.FanOut(join, to, payloads) =>
            EncodedRoute.FanOut(join.id.value, to.id.value, payloads.map(nodes(to.id).encode))
        }
      )
    ).toResult

  /** Rebinds a pending write to this graph's keys, nodes and joins, migrating encoded values. */
  private[graph] def decodeWrite(write: PendingWrite): Result[Command] =
    def problem(reason: String) =
      GraphError.RestoreRejected(id, List(s"pending write for task ${write.taskId}: $reason"))
    def node(nodeId: String) = nodes.get(NodeId(nodeId)).toRight(problem(s"unknown node '$nodeId'"))
    def key(keyId: String)   = keys.get(StateKeyId(keyId)).toRight(problem(s"unknown state key '$keyId'"))
    def sequence[A](results: Vector[Result[A]]): Result[Vector[A]] =
      results.foldLeft[Result[Vector[A]]](Right(Vector.empty))((acc, r) => acc.flatMap(as => r.map(as :+ _)))
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
    yield Command(new StateUpdate(ops), rs.toList)

  private def validate(task: Task, command: Command): Result[Unit] =
    val writes = nodes.get(task.node).fold(Set.empty[StateKey[?, ?]])(_.writes)
    val undeclared = command.update.operations
      .find(op => !writes.contains(op.key))
      .map(op => GraphError.UndeclaredWrite(task.node, task.id, op.key.id))
    val joins = command.routes.collect { case Route.FanOut(join, _, _) => join.id }
    val routeProblem = command.routes.iterator
      .flatMap(routeProblemOf)
      .nextOption()
      .orElse(
        joins.diff(joins.distinct).headOption.map(join => s"it fans out to join '${join.value}' more than once")
      )
      .map(GraphError.InvalidRoute(task.node, task.id, _))
    undeclared.orElse(routeProblem).toLeft(())

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

  private def schedule(execution: Execution, completed: Vector[(Task, Command)], state: ThreadState): Execution =
    val next = execution.superstep + 1
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

    val completedNodes = completed.map(_._1.node).toSet
    val (staticReleased, staticArrivals) =
      staticJoins.foldLeft((Vector.empty[NodeId], execution.staticArrivals)) { case ((released, arrivals), join) =>
        val now = arrivals.getOrElse(join.id, Set.empty) ++ join.sources.intersect(completedNodes)
        if now == join.sources then (released :+ join.target.id, arrivals - join.id)
        else if now.isEmpty then (released, arrivals)
        else (released, arrivals.updated(join.id, now))
      }

    val arrivedBySlot = completed.flatMap((task, _) => task.slot.map(_ -> task.id)).groupMap(_._1)(_._2)
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
    new Execution(owner, next, state, routedTasks ++ releasedTasks, staticArrivals, stillOpen)

  private def complete(execution: Execution): RunResult[O] =
    val waitingStatic = staticJoins.flatMap { join =>
      execution.staticArrivals.get(join.id).map { arrived =>
        GraphError.UnsatisfiedJoin(join.id, (join.sources -- arrived).map(n => s"node '${n.value}'").toList.sorted)
      }
    }
    val waitingDynamic = execution.dynamicActivations.map { a =>
      GraphError.UnsatisfiedJoin(a.join, a.expected.filterNot(a.arrived.contains).map(t => s"task ${t.value}").toList)
    }
    (waitingStatic ++ waitingDynamic).headOption match
      case Some(unsatisfied) => RunResult.Failed(execution.state, unsatisfied)
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

  private def restoreTask(pending: GraphSnapshot.PendingTask): Either[String, Task] =
    for
      node <- nodes
        .get(NodeId(pending.nodeId))
        .toRight(s"pending task ${pending.taskId} targets unknown node '${pending.nodeId}'")
      input <- node.decode(pending.input).left.map { e =>
        s"pending task ${pending.taskId} input does not decode for node '${pending.nodeId}': ${e.message}"
      }
      slot <- (pending.joinId, pending.fanOutTask) match
        case (None, None) => Right(None)
        case (Some(join), Some(fanOut)) if dynamicJoins.contains(JoinId(join)) =>
          Right(Some(JoinSlot(JoinId(join), TaskId(fanOut))))
        case (Some(join), Some(_)) => Left(s"pending task ${pending.taskId} belongs to unknown dynamic join '$join'")
        case _                     => Left(s"pending task ${pending.taskId} has half a join slot")
    yield Task(TaskId(pending.taskId), node.ref.id, input, slot)

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
    frontier: Vector[GraphSnapshot.PendingTask]
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
      val unexpected = activation.arrived.filterNot(activation.expected.contains)
      val notPending = activation.expected.filterNot(activation.arrived.contains).filterNot { id =>
        frontier.exists { pending =>
          pending.taskId == id.value && pending.joinId.contains(saved.joinId) && pending.fanOutTask
            .contains(saved.fanOutTask)
        }
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
