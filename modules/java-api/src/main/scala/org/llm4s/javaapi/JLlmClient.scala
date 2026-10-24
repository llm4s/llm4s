package org.llm4s.javaapi

import org.llm4s.core.safety.Safety
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, UserMessage }
import org.llm4s.types.Result

import java.util.Objects
import java.util.concurrent.{ CompletableFuture, Executor, ExecutorService }

/**
 * Java-friendly wrapper around [[org.llm4s.llmconnect.LLMClient LLMClient]].
 *
 * The underlying Scala client returns `Result[Completion]`; this wrapper surfaces it as [[LlmResult]] so Java
 * callers never import any Scala types. `complete` returns the reply's text alone; `completion` returns the whole
 * reply as a [[JCompletion]] - the text, the model that answered, its tool calls, its token usage and estimated cost.
 *
 * Every `complete` and `completion` blocks the calling thread and never throws: a failure comes back inside the
 * [[LlmResult]]. Interrupting the thread blocked in a call returns a failed result whose error is a
 * [[org.llm4s.error.CancelledError CancelledError]], with the thread's interrupt flag still set;
 * `InterruptedException` is never thrown. The methods therefore declare no checked exception, and
 * `javac` rejects a `catch (InterruptedException e)` around them as "never thrown in body of
 * corresponding try statement" - test the result instead:
 *
 * {{{
 * LlmResult<String> result = client.complete("Long question");
 * if (result.isFailure() && result.getError().getKind() == LlmErrorKind.CANCELLED) {
 *     // interrupted while blocked; Thread.currentThread().isInterrupted() is still true
 * }
 * }}}
 *
 * Each blocking call has an `*Async` variant - `completeAsync`, `completionAsync` - that returns a `CompletableFuture`
 * at once and makes the call on another thread: by default a virtual thread of this client's own
 * virtual-thread-per-task executor, which `close()` shuts down; [[withAsyncExecutor]] names another executor. The
 * future completes with the value, or exceptionally with the [[LlmException]] the blocking call's result would hold.
 * `cancel(true)` - and any other completion from outside, such as `orTimeout` - interrupts the call:
 *
 * {{{
 * CompletableFuture<String> a = client.completeAsync("Summarise the first report");
 * CompletableFuture<String> b = client.completeAsync("Summarise the second report");
 * CompletableFuture.allOf(a, b).join();
 * }}}
 *
 * Obtain instances via [[Llm4s.createDefaultClient]] or [[Llm4s.createClient]], or wrap a Scala `LLMClient` you
 * built yourself with [[Llm4s.wrapClient]]; the constructor is the package's own (#1666).
 *
 * {{{
 * LlmResult<String> r = client.complete("What is 2+2?");
 * r.ifSuccess(System.out::println).ifFailure(e -> System.err.println(e.getMessage()));
 * }}}
 */
final class JLlmClient private[javaapi] (
  token: FacadeToken,
  private[javaapi] val underlying: LLMClient,
  supplied: Executor = null // null: the client's own executor
) extends AutoCloseable {

  FacadeToken.check(token, "JLlmClient")

  /** The default executor of the `*Async` methods, this client's own and shut down by `close()`; none when one is supplied. */
  private val owned: Option[ExecutorService] = if (supplied == null) Some(FacadeAsync.ownExecutor()) else None

  /** Where the `*Async` methods run their call. */
  private[javaapi] val asyncExecutor: Executor =
    Option(supplied).orElse(owned).getOrElse(FacadeAsync.virtualThreadPerCall)

  /**
   * Sends a single user query and returns the assistant's text response. A `null` query yields a
   * failed [[LlmResult]], never an exception.
   *
   * Blocks the calling thread. If that thread is interrupted while it waits, the result is a failure
   * whose error is a [[org.llm4s.error.CancelledError CancelledError]] and the thread's interrupt
   * flag is left set; `InterruptedException` is never thrown, and the method declares none.
   */
  def complete(query: String): LlmResult[String] =
    if (query == null) nullArgument("query")
    else guarded("JLlmClient.complete")(underlying.complete(Conversation(Seq(UserMessage(query)))).map(_.content))

  /**
   * Sends a pre-built conversation and returns the assistant's text response. Blocks and reports an
   * interrupt as `complete(String)` does: a `CancelledError` result, the interrupt flag left set,
   * never an `InterruptedException`.
   */
  def complete(conversation: Conversation): LlmResult[String] =
    if (conversation == null) nullArgument("conversation")
    else guarded("JLlmClient.complete")(underlying.complete(conversation).map(_.content))

  /**
   * Sends a conversation with the options a [[JCompletionOptions]] builder made - temperature, token limit,
   * reasoning - and returns the assistant's text response. A `null` argument yields a failed [[LlmResult]].
   * Blocks and reports an interrupt as `complete(String)` does: a `CancelledError` result, the interrupt flag
   * left set, never an `InterruptedException`.
   *
   * {{{
   * JCompletionOptions options = JCompletionOptions.builder().temperature(0.2).maxTokens(512).build();
   * LlmResult<String> answer = client.complete(conversation, options);
   * }}}
   */
  def complete(conversation: Conversation, options: JCompletionOptions): LlmResult[String] =
    if (options == null) completing(conversation, null)
    else completing(conversation, options.underlying)

  /**
   * Full access: send a conversation with core's Scala [[org.llm4s.llmconnect.model.CompletionOptions CompletionOptions]], returning the raw text
   * content; from Java, [[JCompletionOptions]] builds the same options without Scala types. Blocks and
   * reports an interrupt as `complete(String)` does: a `CancelledError` result, the interrupt flag left
   * set, never an `InterruptedException`.
   */
  def complete(conversation: Conversation, options: CompletionOptions): LlmResult[String] =
    completing(conversation, options)

  private def completing(conversation: Conversation, options: CompletionOptions): LlmResult[String] =
    if (conversation == null) nullArgument("conversation")
    else if (options == null) nullArgument("options")
    else guarded("JLlmClient.complete")(underlying.complete(conversation, options).map(_.content))

  /**
   * Sends a single user query and returns the whole reply: its text, the model that answered, the tool calls it asked
   * for, the tokens it used and its estimated cost. `complete(String)` returns the text alone.
   * A `null` query yields a failed [[LlmResult]], never an exception.
   *
   * Blocks and reports an interrupt as `complete(String)` does: a `CancelledError` result, the interrupt flag left
   * set, never an `InterruptedException`.
   *
   * {{{
   * JCompletion reply = client.completion("What is 2+2?").get();
   * System.out.println(reply.model() + ": " + reply.content());
   * }}}
   */
  def completion(query: String): LlmResult[JCompletion] =
    if (query == null) nullArgument("query")
    else
      guarded("JLlmClient.completion")(
        underlying.complete(Conversation(Seq(UserMessage(query)))).map(FacadeInternals.completion)
      )

  /**
   * Sends a pre-built conversation and returns the whole reply, as `completion(String)` does. A `null` conversation
   * yields a failed [[LlmResult]]. Blocks and reports an interrupt as `complete(String)` does.
   */
  def completion(conversation: Conversation): LlmResult[JCompletion] =
    if (conversation == null) nullArgument("conversation")
    else guarded("JLlmClient.completion")(underlying.complete(conversation).map(FacadeInternals.completion))

  /**
   * Sends a conversation with the options a [[JCompletionOptions]] builder made and returns the whole reply, as
   * `completion(String)` does. A `null` argument yields a failed [[LlmResult]]. Blocks and reports an interrupt as
   * `complete(String)` does.
   *
   * There is no overload taking core's Scala `CompletionOptions`, so `completion(conversation, null)` compiles and
   * returns a failed result, where `complete(conversation, null)` needs a cast to pick an overload.
   *
   * {{{
   * JCompletionOptions options = JCompletionOptions.builder().maxTokens(512).build();
   * JCompletion reply = client.completion(conversation, options).get();
   * }}}
   */
  def completion(conversation: Conversation, options: JCompletionOptions): LlmResult[JCompletion] =
    if (conversation == null) nullArgument("conversation")
    else if (options == null) nullArgument("options")
    else
      guarded("JLlmClient.completion")(
        underlying.complete(conversation, options.underlying).map(FacadeInternals.completion)
      )

  /**
   * Sends a single user query and streams the reply to `listener`: each piece of its text as it arrives, then the whole
   * reply or the error (see [[CompletionStreamListener]]). Returns at once with the running [[CompletionStream]],
   * which cancels the call (`close()` or `cancel()`) and awaits its reply (`await()`). A `null` argument is a failed
   * result, and `listener` hears nothing.
   *
   * {{{
   * CompletionStream stream = client.stream("Tell me a story", System.out::print).get();
   * JCompletion reply = stream.await().get();
   * }}}
   */
  def stream(query: String, listener: CompletionStreamListener): LlmResult[CompletionStream] =
    if (query == null) nullArgument("query")
    else streaming(Conversation(Seq(UserMessage(query))), CompletionOptions(), listener)

  /** Sends a conversation and streams the reply to `listener`, as `stream(String, listener)` does. */
  def stream(conversation: Conversation, listener: CompletionStreamListener): LlmResult[CompletionStream] =
    streaming(conversation, CompletionOptions(), listener)

  /**
   * Sends a conversation with the options a [[JCompletionOptions]] builder made and streams the reply to `listener`, as
   * `stream(String, listener)` does.
   */
  def stream(
    conversation: Conversation,
    options: JCompletionOptions,
    listener: CompletionStreamListener
  ): LlmResult[CompletionStream] =
    if (options == null) nullArgument("options") else streaming(conversation, options.underlying, listener)

  private def streaming(
    conversation: Conversation,
    options: CompletionOptions,
    listener: CompletionStreamListener
  ): LlmResult[CompletionStream] =
    if (conversation == null) nullArgument("conversation")
    else if (listener == null) nullArgument("listener")
    else
      LlmResult.success(
        new CompletionStream(
          FacadeToken.INSTANCE,
          underlying,
          conversation,
          options,
          listener,
          5000L // how long cancel() waits for the listener to hear the outcome
        )
      )

  /**
   * The reply to a single user query as a `java.util.concurrent.Flow.Publisher` of its text: in the JDK, so it adapts to
   * Reactor, RxJava or Mutiny with their own `Flow` adapters and adds no dependency. It is cold: each subscription
   * makes its own provider call, on a thread of its own. A subscriber slower than the provider holds the provider's
   * thread rather than losing text (up to 64 pieces wait for it); cancelling the
   * subscription interrupts the call. The publisher completes after the last piece, or fails with an [[LlmException]]
   * - at once, on subscription, for a `null` argument.
   */
  def streamAsPublisher(query: String): java.util.concurrent.Flow.Publisher[String] =
    if (query == null) publishing(Left(ValidationError.required("query")))
    else publishing(Right((Conversation(Seq(UserMessage(query))), CompletionOptions())))

  /** A conversation's reply as a `Flow.Publisher` of its text, as `streamAsPublisher(String)`. */
  def streamAsPublisher(conversation: Conversation): java.util.concurrent.Flow.Publisher[String] =
    publishing(Option(conversation).toRight(ValidationError.required("conversation")).map((_, CompletionOptions())))

  /** A conversation's reply, with the options of a [[JCompletionOptions]] builder, as `streamAsPublisher(String)`. */
  def streamAsPublisher(
    conversation: Conversation,
    options: JCompletionOptions
  ): java.util.concurrent.Flow.Publisher[String] =
    publishing(for {
      c <- Option(conversation).toRight(ValidationError.required("conversation"))
      o <- Option(options).toRight(ValidationError.required("options"))
    } yield (c, o.underlying))

  private def publishing(
    start: Either[ValidationError, (Conversation, CompletionOptions)]
  ): java.util.concurrent.Flow.Publisher[String] =
    new CompletionPublisher(start.map((c, o) => (underlying, c, o)), CompletionPublisher.BufferSize)

  /**
   * Sends a single user query and returns the answer as an instance of the Java record `type`: the record's JSON
   * Schema is derived from its components, sent as the structured-output format, and the reply is read back into the
   * record. `complete(String)` returns the text instead.
   *
   * A component may be a `String`, an integer type, a `double` or `float`, a `boolean`, an enum, another record, a
   * `java.util.List` of these, or an `Optional` of these (the one way to let the model answer `null`). A type outside
   * that list, or a reply that does not fit the record - a missing or `null` field that is not an `Optional`, the wrong
   * JSON type, an unknown enum name - is a failed result whose message names the field. How firmly the schema binds the
   * model depends on the provider: OpenAI-style providers send it as a native JSON Schema, Anthropic as a prompt
   * instruction. Blocks and reports an interrupt as `complete(String)` does.
   *
   * {{{
   * record City(String name, String country, int population) {}
   * City city = client.complete("The largest city in Japan", City.class).get();
   * }}}
   */
  def complete[T](query: String, `type`: Class[T]): LlmResult[T] =
    if (query == null) nullArgument("query")
    else structured(Conversation(Seq(UserMessage(query))), CompletionOptions(), `type`)

  /** Sends a conversation and returns the answer as an instance of the record `type`, as `complete(String, Class)`. */
  def complete[T](conversation: Conversation, `type`: Class[T]): LlmResult[T] =
    structured(conversation, CompletionOptions(), `type`)

  /**
   * Sends a conversation with the options a [[JCompletionOptions]] builder made and returns the answer as an instance of
   * the record `type`, as `complete(String, Class)`. The options' response format is replaced by the record's schema.
   */
  def complete[T](conversation: Conversation, options: JCompletionOptions, `type`: Class[T]): LlmResult[T] =
    if (options == null) nullArgument("options") else structured(conversation, options.underlying, `type`)

  private def structured[T](conversation: Conversation, options: CompletionOptions, cls: Class[T]): LlmResult[T] =
    if (conversation == null) nullArgument("conversation")
    else if (cls == null) nullArgument("type")
    else
      guarded("JLlmClient.complete") {
        for {
          schema <- JavaRecords.schema(cls).left.map(ValidationError("type", _))
          json   <- underlying.completeStructured[ujson.Value](conversation, schema, options)
          record <- JavaRecords.read(cls, json).left.map(ValidationError("structured_output", _))
        } yield record
      }

  /**
   * `complete(String)` on another thread: returns at once with a future of the text. The future completes
   * exceptionally with the [[LlmException]] the blocking call's failed result would hold - of the same
   * [[LlmErrorKind]] - and never with another exception, except the `CancellationException` of a future cancelled
   * yourself. `cancel(true)` interrupts the call, which the provider answers by stopping; `cancel(false)` lets it run
   * and discards its answer. Completing the future yourself - `orTimeout`, `completeOnTimeout`, `complete` - also
   * interrupts the call. Cancel this future, not one derived from it with `thenApply`: a derived future's
   * cancellation does not reach the call.
   *
   * The call runs on this client's executor: by default a new virtual thread for each call, from an executor that
   * `close()` shuts down - a call still running then is interrupted, and a call made after it fails with kind
   * `OTHER`; see [[withAsyncExecutor]].
   */
  def completeAsync(query: String): CompletableFuture[String] =
    FacadeAsync.run(asyncExecutor, "JLlmClient.completeAsync")(complete(query))

  /** `complete(Conversation)` on another thread, as `completeAsync(String)`. */
  def completeAsync(conversation: Conversation): CompletableFuture[String] =
    FacadeAsync.run(asyncExecutor, "JLlmClient.completeAsync")(complete(conversation))

  /** `complete(Conversation, JCompletionOptions)` on another thread, as `completeAsync(String)`. */
  def completeAsync(conversation: Conversation, options: JCompletionOptions): CompletableFuture[String] =
    FacadeAsync.run(asyncExecutor, "JLlmClient.completeAsync")(complete(conversation, options))

  /** `completion(String)` on another thread - the whole reply - as `completeAsync(String)`. */
  def completionAsync(query: String): CompletableFuture[JCompletion] =
    FacadeAsync.run(asyncExecutor, "JLlmClient.completionAsync")(completion(query))

  /** `completion(Conversation)` on another thread, as `completeAsync(String)`. */
  def completionAsync(conversation: Conversation): CompletableFuture[JCompletion] =
    FacadeAsync.run(asyncExecutor, "JLlmClient.completionAsync")(completion(conversation))

  /** `completion(Conversation, JCompletionOptions)` on another thread, as `completeAsync(String)`. */
  def completionAsync(conversation: Conversation, options: JCompletionOptions): CompletableFuture[JCompletion] =
    FacadeAsync.run(asyncExecutor, "JLlmClient.completionAsync")(completion(conversation, options))

  /**
   * Structured output, `complete(String, Class)`, on another thread, as `completeAsync(String)`: a future of the
   * record.
   *
   * {{{
   * CompletableFuture<City> city = client.completeAsync("The largest city in Japan", City.class);
   * }}}
   */
  def completeAsync[T](query: String, `type`: Class[T]): CompletableFuture[T] =
    FacadeAsync.run(asyncExecutor, "JLlmClient.completeAsync")(complete(query, `type`))

  /** `complete(Conversation, Class)` on another thread, as `completeAsync(String)`. */
  def completeAsync[T](conversation: Conversation, `type`: Class[T]): CompletableFuture[T] =
    FacadeAsync.run(asyncExecutor, "JLlmClient.completeAsync")(complete(conversation, `type`))

  /** `complete(Conversation, JCompletionOptions, Class)` on another thread, as `completeAsync(String)`. */
  def completeAsync[T](
    conversation: Conversation,
    options: JCompletionOptions,
    `type`: Class[T]
  ): CompletableFuture[T] =
    FacadeAsync.run(asyncExecutor, "JLlmClient.completeAsync")(complete(conversation, options, `type`))

  /**
   * This client with its `*Async` calls run on `executor` - a Spring `TaskExecutor`, a bounded pool, a Quarkus or
   * Micronaut managed executor - rather than a virtual thread of the client's own. It is a view: it shares this
   * client's provider connection, so closing either closes it, and it never shuts `executor` down. Any `Executor`
   * works, a `ForkJoinPool` too: cancelling a future interrupts the thread running its call, whatever the executor.
   * An executor that refuses a call - shut down, its queue full - fails that call's future at once with kind `OTHER`,
   * the executor's exception as its cause.
   *
   * @throws NullPointerException when `executor` is `null`
   */
  def withAsyncExecutor(executor: Executor): JLlmClient =
    new JLlmClient(
      FacadeToken.INSTANCE,
      underlying,
      Objects.requireNonNull(executor, "executor must not be null")
    )

  private def nullArgument[A](name: String): LlmResult[A] =
    LlmResult.failure(ValidationError.required(name))

  /**
   * The wrapper's contract is that it never throws for a failed call: an exception escaping the
   * underlying client (a provider bug, a serialization error) - or the conversion of its reply - becomes a failed
   * result carrying it as the cause. The built-in providers answer an interrupt with `Left(CancelledError)`
   * themselves; an `InterruptedException` that a custom client lets escape becomes the same `CancelledError`,
   * with the interrupt flag restored, so no caller meets a checked exception the method does not
   * declare (#1591).
   */
  private def guarded[A](operation: String)(call: => Result[A]): LlmResult[A] =
    FacadeInternals.result(CancelledError.attempt(operation)(Safety.safely(call).flatMap(identity)))

  /**
   * Closes the provider client and shuts down this client's own async executor, interrupting any `*Async` call still
   * running; a supplied executor ([[withAsyncExecutor]]) is left alone.
   */
  override def close(): Unit = {
    owned.foreach(_.shutdownNow())
    underlying.close()
  }
}
