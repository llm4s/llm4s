package org.llm4s.pekko

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.{ Attributes, Outlet, SourceShape }
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.stage.{ AsyncCallback, GraphStage, GraphStageLogic, OutHandler }
import org.llm4s.error.{ LLMError, ValidationError }

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger, AtomicReference }

/**
 * What a producer hands items to: a bounded buffer in front of the stream. [[emit]] blocks the
 * producer's thread while the buffer is full, which is how a blocking, callback-style API (a
 * provider's `streamComplete`, an agent's event listener) is slowed down to the pace of the stream's
 * consumer. It throws `InterruptedException` when the stream is gone, so a producer stops where it is.
 */
final private[pekko] class Emitter[T](queue: ArrayBlockingQueue[T], wake: () => Unit) {

  /** Queues `item`, waiting for room; wakes the stream. */
  def emit(item: T): Unit = {
    queue.put(item)
    wake()
  }
}

/**
 * The blocking side of a [[BlockingSource]]: one instance per materialization, run on a thread of its
 * own. `run` emits every item, then returns `Right(())` to complete the stream or a `Left` to fail it
 * with an [[LLMException]]; anything it throws also fails the stream. Items emitted before a failure are
 * delivered first.
 */
private[pekko] trait Producer[T] {

  /** Produces the stream's items on the calling thread; blocks as long as it needs to. */
  def run(emitter: Emitter[T]): Either[LLMError, Unit]

  /**
   * The stream has ended or been cancelled; release what `run` holds. Called once, from a stream thread, so
   * it must not block: hand any waiting to a thread of its own. `run` may still be running, and is
   * interrupted separately.
   */
  def abandon(): Unit
}

/**
 * A Pekko `Source` fed by a blocking [[Producer]] on a dedicated daemon thread.
 *
 *  - '''Backpressure''': the producer hands items over through a buffer of `capacity` items. Pekko pulls
 *    from the buffer at the consumer's pace; once it is full, the producer's `emit` blocks until the
 *    consumer takes an item. A callback-style API cannot be paused, but it can be blocked, which is what
 *    this does; nothing is dropped and nothing is buffered without bound.
 *  - '''Cancellation''': when the stream stops for any reason - the consumer cancels, a stage downstream
 *    fails, the materializer shuts down - the producer's thread is interrupted and
 *    [[Producer.abandon]] is called.
 *  - '''Ending''': the stream completes, or fails, exactly once, after every item emitted before. A
 *    producer that dies of any `Throwable` fails the stream instead of leaving it hanging.
 *  - Each materialization starts its own producer; the thread starts when the stream does.
 */
private[pekko] object BlockingSource {

  private val threadIds = new AtomicInteger(0)

  /**
   * A source of the items `newProducer()` produces. `newProducer` is called once per materialization.
   * A `capacity` below 1 gives a stream that fails at once with a [[ValidationError]].
   */
  def apply[T](name: String, capacity: Int)(newProducer: () => Producer[T]): Source[T, NotUsed] =
    if (capacity < 1)
      Source.failed(new LLMException(ValidationError("bufferSize", s"must be at least 1, got $capacity")))
    else
      Source.fromGraph(new Stage[T](name, capacity, newProducer)).named(name)

  /** How a producer ended. */
  sealed private trait Outcome
  private case object Completed                     extends Outcome
  final private case class Failed(cause: Throwable) extends Outcome

  final private class Stage[T](name: String, capacity: Int, newProducer: () => Producer[T])
      extends GraphStage[SourceShape[T]] {

    private val out: Outlet[T]                 = Outlet[T](s"$name.out")
    override val shape: SourceShape[T]         = SourceShape(out)
    override def initialAttributes: Attributes = Attributes.name(name)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) with OutHandler {
        private val queue                    = new ArrayBlockingQueue[T](capacity)
        private val outcome                  = new AtomicReference[Outcome](null)
        private val finished                 = new AtomicBoolean(false)
        private val stopped                  = new AtomicBoolean(false)
        private val producer                 = newProducer()
        @volatile private var thread: Thread = _

        // lazy: the callback is first used in `preStart`, which Pekko allows, and Scala 3's initialization check
        // cannot see that `drain` is safe to call before the logic is fully constructed
        private lazy val wake = getAsyncCallback[Unit](_ => drain())

        setHandler(out, this)

        override def preStart(): Unit = {
          // The callback is created here, on the stream's thread, before the producer exists: Pekko requires
          // `getAsyncCallback` to be called from the stage, and a `lazy val` first evaluated on the producer's
          // thread would be created there.
          val callback = wake
          val emitter  = new Emitter[T](queue, () => callback.invoke(()))
          val t        = new Thread(null, () => produce(emitter, callback), s"$name-${threadIds.incrementAndGet()}")
          t.setDaemon(true)
          thread = t
          t.start()
        }

        /** Runs on the producer thread; always records how it ended, so the stream cannot hang. */
        private def produce(emitter: Emitter[T], callback: AsyncCallback[Unit]): Unit = {
          val result = attempt(emitter)
          outcome.set(result)
          finished.set(true)
          callback.invoke(())
        }

        // scalafix:off DisableSyntax.NoKeywordCatch
        // A producer thread must report any Throwable, fatal ones included: if it died silently the
        // stream would wait for it forever. `Try` would miss the fatal ones.
        private def attempt(emitter: Emitter[T]): Outcome =
          try
            producer.run(emitter) match {
              case Right(_)    => Completed
              case Left(error) => Failed(new LLMException(error))
            }
          catch {
            case t: Throwable => Failed(t)
          }
        // scalafix:on DisableSyntax.NoKeywordCatch

        override def onPull(): Unit = drain()

        /**
         * Pushes the next buffered item when the consumer has asked for one; once the producer has ended and the
         * buffer is empty, completes or fails the stream - which needs no demand, so a consumer that asked for
         * exactly as many items as there are still sees the end. The outcome is read before the buffer, and the
         * producer sets it after its last `emit`, so an item emitted before the end is never missed, and a failure
         * is raised only after every item before it has been taken.
         */
        private def drain(): Unit = {
          val ended = outcome.get()
          if (isAvailable(out)) {
            val item = queue.poll()
            if (item != null) push(out, item) else end(ended)
          } else if (queue.isEmpty) end(ended)
        }

        private def end(ended: Outcome): Unit =
          ended match {
            case Completed     => completeStage()
            case Failed(cause) => failStage(cause)
            case null          => ()
          }

        override def postStop(): Unit =
          if (stopped.compareAndSet(false, true)) {
            if (!finished.get()) Option(thread).foreach(_.interrupt())
            producer.abandon()
          }
      }
  }
}
