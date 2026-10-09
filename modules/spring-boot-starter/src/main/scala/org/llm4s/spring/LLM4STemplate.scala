package org.llm4s.spring

import org.llm4s.javaapi.{ JLlmClient, LlmResult }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation }

import java.util.concurrent.{ CompletableFuture, ExecutorService, Future }
import java.util.concurrent.atomic.AtomicReference
import scala.util.{ Failure, Success, Try }

/**
 * Spring-friendly entry point to an LLM.
 *
 * `complete` and `tryComplete` block the calling thread. `completeAsync` never does: the blocking
 * call runs on `executor` (the `llm4sTaskExecutor` bean) and the returned future completes from
 * there. Cancelling the future with `cancel(true)` interrupts the provider call, which is how llm4s
 * cancels work; a failure completes the future exceptionally with the `LlmException`.
 */
final class LLM4STemplate(private val client: JLlmClient, private val executor: ExecutorService) {

  def complete(query: String): String =
    client.complete(query).get()

  def complete(conversation: Conversation): String =
    client.complete(conversation).get()

  def complete(conversation: Conversation, options: CompletionOptions): String =
    client.complete(conversation, options).get()

  def tryComplete(query: String): LlmResult[String] =
    client.complete(query)

  def tryComplete(conversation: Conversation): LlmResult[String] =
    client.complete(conversation)

  def completeAsync(query: String): CompletableFuture[String] =
    runAsync(() => client.complete(query))

  def completeAsync(conversation: Conversation): CompletableFuture[String] =
    runAsync(() => client.complete(conversation))

  private def runAsync(call: () => LlmResult[String]): CompletableFuture[String] = {
    val running = new AtomicReference[Future[?]]()
    // A plain CompletableFuture.cancel does not reach the running task; forward it to the
    // executor's Future, whose cancel(true) interrupts the worker thread.
    val result = new CompletableFuture[String]() {
      override def cancel(mayInterruptIfRunning: Boolean): Boolean = {
        val cancelled = super.cancel(mayInterruptIfRunning)
        if (cancelled) Option(running.get()).foreach(_.cancel(mayInterruptIfRunning))
        cancelled
      }
    }
    // JLlmClient returns failures as values and never throws, so there is nothing to catch here. An
    // interrupt (cancel, or shutdownNow at context close) comes back as a failed LlmResult too.
    val task: Runnable = () => {
      val r = call()
      if (r.isSuccess) result.complete(r.get()) else result.completeExceptionally(r.getError())
      ()
    }
    Try(executor.submit(task)) match {
      case Success(f) => running.set(f)
      case Failure(t) => result.completeExceptionally(t); ()
    }
    result
  }
}
