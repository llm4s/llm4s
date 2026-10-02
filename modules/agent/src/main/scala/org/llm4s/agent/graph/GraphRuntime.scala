package org.llm4s.agent.graph

import org.llm4s.error.LLMError
import org.llm4s.types.Result

import java.time.Clock
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ ExecutorService, Executors, TimeUnit }
import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.Try

/**
 * When a run's checkpoints and events become durable. In every mode a durable event is delivered
 * only after the commit that numbered it, so no subscriber sees a sequence number that a crash
 * could later reuse. Suspensions (#1269) will be persisted synchronously in every mode before a
 * suspended result is returned.
 */
enum Durability:
  /** Each task result and superstep is committed before the run moves on. */
  case Sync

  /**
   * Commits are queued and written in order on a background writer while the run continues.
   * After a crash, the work since the last durable checkpoint is redone by `recover`. The run
   * waits for the queue before returning, and fails if any queued commit failed.
   */
  case Async

  /**
   * Nothing is written until the run ends; then the last checkpoint, its pending writes and every
   * buffered event are committed at once. A crash loses the whole run's progress.
   */
  case OnExit

/**
 * Runs compiled graphs on durable threads.
 *
 * `start` begins a run: on a new thread from the graph's entry, on a thread whose latest run
 * completed by applying the input to its committed state. It refuses a thread with an incomplete
 * execution ([[GraphError.IncompleteRun]]). `recover` continues an incomplete execution from its
 * latest checkpoint without new input: tasks with a pending write are not run again, and failed
 * or unstarted tasks run once more (per-node retry policy is Stage 1).
 *
 * `subscribe` replays a thread's committed events after a sequence number and then delivers new
 * ones as their commits succeed, in ascending order with no gaps or duplicates, followed by live
 * progress as it happens. Events are delivered on the committing thread; a dedicated ordered
 * dispatcher with bounded queues is part of the run API (#1271).
 */
final class GraphRuntime(checkpointer: Checkpointer, clock: Clock = Clock.systemUTC()):

  private val hub        = EventHub(checkpointer)
  private val commitLock = new Object

  /** Replays events with `seq > afterSeq`, then delivers new events until cancelled. */
  def subscribe(threadId: ThreadId, afterSeq: Long = 0L)(listener: StreamEvent => Unit): Result[Subscription] =
    hub.subscribe(threadId, afterSeq, listener)

  /** Starts a run on `threadId` with `input`; see the class description. */
  def start[I, O](
    threadId: ThreadId,
    graph: CompiledGraph[I, O],
    input: I,
    runId: RunId,
    durability: Durability = Durability.Sync
  ): Result[RunResult[O]] =
    checkpointer
      .latest(threadId)
      .flatMap {
        case None => Right(graph.start(input) -> None)
        case Some(stored) if stored.checkpoint.status == CheckpointStatus.Running =>
          Left(GraphError.IncompleteRun(threadId.value, stored.checkpoint.id))
        case Some(stored) =>
          graph
            .restore(stored.checkpoint.snapshot)
            .map(done => graph.startAt(done.superstep, done.state, input) -> Some(stored.checkpoint.id))
      }
      .map { (execution, parent) =>
        val run = new Run(graph, threadId, runId, committer(threadId, durability), execution.superstep)
        run.begin(execution, parent)
      }

  /** Continues the incomplete execution on `threadId`; see the class description. */
  def recover[I, O](
    graph: CompiledGraph[I, O],
    threadId: ThreadId,
    runId: RunId,
    durability: Durability = Durability.Sync
  ): Result[RunResult[O]] =
    for
      stored <- checkpointer.latest(threadId).flatMap {
        case Some(stored) if stored.checkpoint.status == CheckpointStatus.Running => Right(stored)
        case _ => Left(GraphError.NothingToRecover(threadId.value))
      }
      execution <- graph.restore(stored.checkpoint.snapshot)
      reused    <- reusableWrites(graph, execution, stored)
    yield
      val run = new Run(graph, threadId, runId, committer(threadId, durability), execution.superstep)
      run.resume(execution, stored.checkpoint.id, reused)

  private def reusableWrites(
    graph: CompiledGraph[?, ?],
    execution: Execution,
    stored: StoredCheckpoint
  ): Result[Map[TaskId, Command]] =
    val frontier = execution.frontier.map(_.id).toSet
    stored.pendingWrites.foldLeft[Result[Map[TaskId, Command]]](Right(Map.empty)) { (acc, write) =>
      acc.flatMap { reused =>
        if !frontier.contains(TaskId(write.taskId)) then
          Left(
            GraphError.RestoreRejected(graph.id, List(s"pending write for task ${write.taskId}, which is not pending"))
          )
        else graph.decodeWrite(write).map(command => reused.updated(TaskId(write.taskId), command))
      }
    }

  private def committer(threadId: ThreadId, durability: Durability): Committer =
    durability match
      case Durability.Sync   => SyncCommitter(threadId)
      case Durability.Async  => AsyncCommitter(threadId)
      case Durability.OnExit => OnExitCommitter(threadId)

  /** Commits and delivers under one lock, so delivery order is commit order. */
  private def commitAndDeliver(threadId: ThreadId, commit: Commit): Result[Unit] =
    commitLock.synchronized {
      checkpointer.commit(threadId, commit).map(hub.durable(threadId, _))
    }

  private trait Committer:
    def submit(commit: Commit): Unit
    def failure: Option[LLMError]

    /** Makes everything submitted durable (or fails), and releases resources. */
    def close(): Unit

  final private class SyncCommitter(threadId: ThreadId) extends Committer:
    private val failed = AtomicReference[Option[LLMError]](None)
    def submit(commit: Commit): Unit =
      if failed.get.isEmpty then
        commitAndDeliver(threadId, commit).left.foreach(e => failed.compareAndSet(None, Some(e)))
    def failure: Option[LLMError] = failed.get
    def close(): Unit             = ()

  final private class AsyncCommitter(threadId: ThreadId) extends Committer:
    private val failed = AtomicReference[Option[LLMError]](None)
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable =>
      val thread = new Thread(runnable, s"llm4s-graph-writer-${threadId.value}")
      thread.setDaemon(true)
      thread
    }
    def submit(commit: Commit): Unit =
      writer.execute { () =>
        // a failed commit stops the queue: a later commit must not land on top of a lost one
        if failed.get.isEmpty then
          commitAndDeliver(threadId, commit).left.foreach(e => failed.compareAndSet(None, Some(e)))
      }
    def failure: Option[LLMError] = failed.get
    def close(): Unit =
      writer.shutdown()
      writer.awaitTermination(Long.MaxValue, TimeUnit.NANOSECONDS)
      ()

  final private class OnExitCommitter(threadId: ThreadId) extends Committer:
    private var durableParent: Option[Option[String]] = None
    private var checkpoint: Option[Checkpoint]        = None
    private var writes: Vector[PendingWrite]          = Vector.empty
    private var events: Vector[EventDraft]            = Vector.empty
    private var failed: Option[LLMError]              = None

    def submit(commit: Commit): Unit = synchronized {
      commit.checkpoint.foreach { next =>
        if durableParent.isEmpty then durableParent = Some(next.parent)
        checkpoint = Some(next)
        writes = Vector.empty
      }
      writes ++= commit.pendingWrites
      events ++= commit.events
    }
    def failure: Option[LLMError] = synchronized(failed)
    def close(): Unit = synchronized {
      val last = checkpoint.map(c => c.copy(parent = durableParent.flatten))
      if last.isDefined || writes.nonEmpty || events.nonEmpty then
        failed = commitAndDeliver(threadId, Commit(last, writes, events)).left.toOption
    }

  /** One run: drives supersteps, recording task results, checkpoints and events as it goes. */
  final private class Run[I, O](
    graph: CompiledGraph[I, O],
    threadId: ThreadId,
    runId: RunId,
    committer: Committer,
    firstSuperstep: Int
  ):
    private var checkpoints = 0

    def begin(execution: Execution, parent: Option[String]): RunResult[O] =
      checkpoint(execution, parent, CheckpointStatus.Running, RunEvent.RunStarted) match
        case Left(error) => fail(execution, error)
        case Right(id)   => loop(execution, id, Map.empty)

    def resume(execution: Execution, checkpointId: String, reused: Map[TaskId, Command]): RunResult[O] =
      committer.submit(
        Commit(None, Vector.empty, Vector(draft(Some(checkpointId), None, RunEvent.RunRecovered(checkpointId))))
      )
      loop(execution, checkpointId, reused)

    @tailrec private def loop(execution: Execution, checkpointId: String, reused: Map[TaskId, Command]): RunResult[O] =
      committer.failure match
        case Some(error) => stop(execution, GraphError.CheckpointWriteFailed(threadId.value, error))
        case None if execution.isQuiescent =>
          graph.finish(execution) match
            case completed: RunResult.Completed[O] =>
              checkpoint(execution, Some(checkpointId), CheckpointStatus.Completed, RunEvent.RunCompleted) match
                case Left(error) => fail(execution, error)
                case Right(_) =>
                  committer.close()
                  committer.failure.fold(completed)(e =>
                    RunResult.Failed(execution.state, GraphError.CheckpointWriteFailed(threadId.value, e))
                  )
            case RunResult.Failed(_, error) => fail(execution, error)
        case None if execution.superstep - firstSuperstep >= graph.maxSupersteps =>
          fail(execution, GraphError.SuperstepLimitExceeded(graph.maxSupersteps))
        case None =>
          val outcomes = graph.taskExecutor.runAll(execution.frontier.map { task => () =>
            reused.get(task.id).fold(runTask(task, execution, checkpointId))(Right(_)).map(task -> _)
          })
          val completed = outcomes.foldLeft[Result[Vector[(Task, Command)]]](Right(Vector.empty))((done, outcome) =>
            done.flatMap(cs => outcome.map(cs :+ _))
          )
          completed.flatMap(graph.commitSuperstep(execution, _)) match
            case Left(error) => fail(execution, error)
            case Right(next) =>
              checkpoint(
                next,
                Some(checkpointId),
                CheckpointStatus.Running,
                RunEvent.CheckpointCommitted(next.superstep)
              ) match
                case Left(error) => fail(execution, error)
                case Right(id)   => loop(next, id, Map.empty)

    private def runTask(task: Task, execution: Execution, checkpointId: String): Result[Command] =
      val sink = TaskSink(task, checkpointId)
      graph
        .executeTask(task, execution, sink)
        .flatMap(command => graph.encodeWrite(checkpointId, task, command).map(command -> _)) match
        case Right((command, write)) =>
          val completed = draft(Some(checkpointId), Some(task), RunEvent.TaskCompleted)
          committer.submit(Commit(None, Vector(write), completed +: sink.customEvents))
          Right(command)
        case Left(error) =>
          committer.submit(
            Commit(
              None,
              Vector.empty,
              Vector(draft(Some(checkpointId), Some(task), RunEvent.TaskFailed(error.message)))
            )
          )
          Left(error)

    private def checkpoint(
      execution: Execution,
      parent: Option[String],
      status: CheckpointStatus,
      event: RunEvent
    ): Result[String] =
      graph.snapshot(execution).map { snapshot =>
        checkpoints += 1
        val id = s"${runId.value}/$checkpoints"
        val saved =
          Checkpoint(
            Checkpoint.CurrentFormat,
            id,
            parent,
            threadId.value,
            runId.value,
            status,
            clock.instant(),
            snapshot
          )
        committer.submit(Commit(Some(saved), Vector.empty, Vector(draft(Some(id), None, event))))
        id
      }

    private def fail(execution: Execution, error: LLMError): RunResult[O] =
      committer.submit(Commit(None, Vector.empty, Vector(draft(None, None, RunEvent.RunFailed(error.message)))))
      stop(execution, error)

    private def stop(execution: Execution, error: LLMError): RunResult[O] =
      committer.close()
      RunResult.Failed(execution.state, error)

    private def draft(checkpointId: Option[String], task: Option[Task], event: RunEvent): EventDraft =
      EventDraft(runId.value, checkpointId, task.map(_.id.value), task.map(_.node.value), clock.instant(), event)

    /** Buffers a task's custom events for its commit; forwards its progress live. */
    final private class TaskSink(task: Task, checkpointId: String) extends NodeEventSink:
      private val buffered                 = mutable.ArrayBuffer.empty[EventDraft]
      def customEvents: Vector[EventDraft] = synchronized(buffered.toVector)
      def custom(name: String, version: Int, payload: ujson.Value): Unit = synchronized {
        buffered += draft(Some(checkpointId), Some(task), RunEvent.Custom(name, version, payload))
      }
      def progress(payload: ujson.Value): Unit =
        hub.live(threadId, StreamEvent.Live(threadId.value, runId.value, task.id.value, task.node.value, payload))

/**
 * Delivers a runtime's events to subscribers: committed durable events in ascending `seq`, each at
 * most once per subscriber, and live progress as it happens.
 */
final private class EventHub(checkpointer: Checkpointer):
  final private class Subscriber(val listener: StreamEvent => Unit, var lastSeq: Long)

  private val subscribers = mutable.Map.empty[String, Vector[Subscriber]]

  def subscribe(threadId: ThreadId, afterSeq: Long, listener: StreamEvent => Unit): Result[Subscription] =
    synchronized {
      val subscriber = Subscriber(listener, afterSeq)
      replay(threadId, subscriber).map { _ =>
        subscribers.update(threadId.value, subscribers.getOrElse(threadId.value, Vector.empty) :+ subscriber)
        new Subscription:
          def cancel(): Unit = EventHub.this.synchronized {
            subscribers.updateWith(threadId.value)(_.map(_.filterNot(_ eq subscriber)))
            ()
          }
      }
    }

  def durable(threadId: ThreadId, records: Vector[EventRecord]): Unit = synchronized {
    subscribers.getOrElse(threadId.value, Vector.empty).foreach { subscriber =>
      records.filter(_.seq > subscriber.lastSeq).foreach(deliver(subscriber, _))
    }
  }

  def live(threadId: ThreadId, event: StreamEvent.Live): Unit = synchronized {
    subscribers.getOrElse(threadId.value, Vector.empty).foreach(s => Try(s.listener(event)))
  }

  @tailrec private def replay(threadId: ThreadId, subscriber: Subscriber): Result[Unit] =
    checkpointer.eventsAfter(threadId, subscriber.lastSeq, 500) match
      case Left(error)                 => Left(error)
      case Right(page) if page.isEmpty => Right(())
      case Right(page) =>
        page.foreach(deliver(subscriber, _))
        replay(threadId, subscriber)

  private def deliver(subscriber: Subscriber, record: EventRecord): Unit =
    Try(subscriber.listener(StreamEvent.Durable(record)))
    subscriber.lastSeq = record.seq
