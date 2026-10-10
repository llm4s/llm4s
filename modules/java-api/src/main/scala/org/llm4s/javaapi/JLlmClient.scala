package org.llm4s.javaapi

import org.llm4s.core.safety.Safety
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, UserMessage }
import org.llm4s.types.Result

/**
 * Java-friendly wrapper around [[LLMClient]].
 *
 * The underlying Scala client returns `Result[Completion]`; this wrapper
 * unwraps the content string and surfaces it as [[LlmResult]] so Java
 * callers never import any Scala types.
 *
 * Every `complete` blocks the calling thread and never throws: a failure comes back inside the
 * [[LlmResult]]. Interrupting the thread blocked in a call returns a failed result whose error is a
 * [[org.llm4s.error.CancelledError CancelledError]], with the thread's interrupt flag still set;
 * `InterruptedException` is never thrown. The methods therefore declare no checked exception, and
 * `javac` rejects a `catch (InterruptedException e)` around them as "never thrown in body of
 * corresponding try statement" - test the result instead:
 *
 * {{{
 * LlmResult<String> result = client.complete("Long question");
 * if (result.isFailure() && result.getError().error() instanceof CancelledError) {
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
    else guarded(underlying.complete(Conversation(Seq(UserMessage(query)))))

  /**
   * Sends a pre-built conversation and returns the assistant's text response. Blocks and reports an
   * interrupt as `complete(String)` does: a `CancelledError` result, the interrupt flag left set,
   * never an `InterruptedException`.
   */
  def complete(conversation: Conversation): LlmResult[String] =
    if (conversation == null) nullArgument("conversation")
    else guarded(underlying.complete(conversation))

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
    else guarded(underlying.complete(conversation, options))

  private def nullArgument(name: String): LlmResult[String] =
    LlmResult.failure(ValidationError.required(name))

  /**
   * The wrapper's contract is that it never throws for a failed call: an exception escaping the
   * underlying client (a provider bug, a serialization error) becomes a failed result carrying it
   * as the cause. The built-in providers answer an interrupt with `Left(CancelledError)` themselves;
   * an `InterruptedException` that a custom client lets escape becomes the same `CancelledError`,
   * with the interrupt flag restored, so no caller meets a checked exception the method does not
   * declare (#1591).
   */
  private def guarded(call: => Result[Completion]): LlmResult[String] =
    LlmResult.from(
      CancelledError.attempt("JLlmClient.complete")(Safety.safely(call).flatMap(identity)).map(_.content)
    )

  override def close(): Unit = underlying.close()
}
