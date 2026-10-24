package org.llm4s.javaapi

import org.llm4s.error.ProcessingError

import java.util.concurrent.{ CompletableFuture, Executor, ExecutorService, Executors }
import scala.util.Try

/**
 * The `*Async` methods of the facade (#2276): a blocking call run on an `Executor`, returned as a `CompletableFuture`
 * that completes with the call's value or, exceptionally, with its [[LlmException]].
 *
 * The future knows the thread running its call, so cancelling it interrupts that thread whatever the executor is - a
 * `ForkJoinPool` too, whose own `Future.cancel(true)` interrupts nothing. The blocking methods answer an interrupt
 * with a `CancelledError` result, which the cancelled future no longer reports.
 */
private[javaapi] object FacadeAsync {

  /** The thread name of every thread of a default executor; a thread dump shows these as `llm4s-async-N`. */
  val ThreadPrefix: String = "llm4s-async-"

  /** A virtual-thread-per-task executor of the facade's own, for a client to close with itself. */
  def ownExecutor(): ExecutorService =
    Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(ThreadPrefix, 0).factory())

  /**
   * The default of a wrapper that has no `close()` to shut an executor down with - a [[JAgent]], a [[JEmbeddingClient]]:
   * each call on a new virtual thread. Virtual threads are daemon threads, so it keeps no JVM running.
   */
  val virtualThreadPerCall: Executor = (task: Runnable) => {
    Thread.ofVirtual().name(ThreadPrefix + "call").start(task)
    ()
  }

  /**
   * `call` run on `executor`. An executor that refuses the task - shut down with its client, or its queue full - fails
   * the future at once with an [[LlmException]] of kind `OTHER` whose cause is the executor's exception; the caller is
   * never blocked.
   */
  def run[A](executor: Executor, operation: String)(call: => LlmResult[A]): CompletableFuture[A] = {
    val future = new Interruptible[A]
    Try(executor.execute(() => future.runHere(operation)(call))).failed.foreach { refused =>
      future.completeExceptionally(
        new LlmException(ProcessingError(operation, s"the executor refused the call: $refused", Some(refused)))
      )
    }
    future
  }

  /**
   * A future whose call stops when the future is completed from outside: `cancel(true)`, `orTimeout`, `complete`... Its
   * answer could not be delivered any more, so the thread running it is interrupted, once. `cancel(false)` lets it
   * finish, and discards its answer. A call cancelled before it started never starts.
   */
  final private class Interruptible[A] extends CompletableFuture[A] {
    private val lock                   = new Object
    private var worker: Thread         = null
    private var interruptedIt: Boolean = false

    def runHere(operation: String)(call: => LlmResult[A]): Unit = {
      val started = lock.synchronized {
        if (isDone) false
        else { worker = Thread.currentThread(); true }
      }
      if (started) {
        val result = Try(call).fold(t => LlmResult.failure[A](ProcessingError(operation, t.toString, Some(t))), r => r)
        lock.synchronized {
          worker = null
          // the interrupt was meant for this call alone: the executor's thread goes back clean
          if (interruptedIt) { Thread.interrupted(); () }
        }
        if (result.isSuccess) super.complete(result.get())
        else super.completeExceptionally(result.getError())
        ()
      }
    }

    private def stop(): Unit = lock.synchronized {
      if (worker != null && !interruptedIt) {
        interruptedIt = true
        worker.interrupt()
      }
    }

    /** Interrupts only when this cancel is what completed the future: `cancel(false)` then `cancel(true)` does not. */
    override def cancel(mayInterruptIfRunning: Boolean): Boolean = {
      val wasDone   = isDone
      val cancelled = super.cancel(mayInterruptIfRunning)
      if (!wasDone && cancelled && mayInterruptIfRunning) stop()
      cancelled
    }

    override def complete(value: A): Boolean = {
      val completed = super.complete(value)
      if (completed) stop()
      completed
    }

    override def completeExceptionally(ex: Throwable): Boolean = {
      val completed = super.completeExceptionally(ex)
      if (completed) stop()
      completed
    }
  }
}
