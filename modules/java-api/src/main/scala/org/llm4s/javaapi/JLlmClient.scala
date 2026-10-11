package org.llm4s.javaapi

import org.llm4s.core.safety.Safety
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, UserMessage }
import org.llm4s.types.Result

/**
 * Java-friendly wrapper around [[LLMClient]].
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
 * Obtain instances via [[Llm4s.createDefaultClient]] or
 * [[Llm4s.createClient]].
 *
 * {{{
 * LlmResult<String> r = client.complete("What is 2+2?");
 * r.ifSuccess(System.out::println).ifFailure(e -> System.err.println(e.getMessage()));
 * }}}
 */
final class JLlmClient private[javaapi] (private[javaapi] val underlying: LLMClient) extends AutoCloseable {

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
   * Full access: send a conversation with core's Scala [[CompletionOptions]], returning the raw text
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
      guarded("JLlmClient.completion")(underlying.complete(Conversation(Seq(UserMessage(query)))).map(JCompletion.of))

  /**
   * Sends a pre-built conversation and returns the whole reply, as `completion(String)` does. A `null` conversation
   * yields a failed [[LlmResult]]. Blocks and reports an interrupt as `complete(String)` does.
   */
  def completion(conversation: Conversation): LlmResult[JCompletion] =
    if (conversation == null) nullArgument("conversation")
    else guarded("JLlmClient.completion")(underlying.complete(conversation).map(JCompletion.of))

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
    else guarded("JLlmClient.completion")(underlying.complete(conversation, options.underlying).map(JCompletion.of))

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
    LlmResult.from(CancelledError.attempt(operation)(Safety.safely(call).flatMap(identity)))

  override def close(): Unit = underlying.close()
}
