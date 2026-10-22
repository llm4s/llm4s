package org.llm4s.javaapi

import org.llm4s.core.safety.Safety
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, UserMessage }
import org.llm4s.types.Result

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
 * Obtain instances via [[Llm4s.createDefaultClient]] or [[Llm4s.createClient]], or wrap a Scala `LLMClient` you
 * built yourself with [[Llm4s.wrapClient]]; the constructor is the package's own (#1666).
 *
 * {{{
 * LlmResult<String> r = client.complete("What is 2+2?");
 * r.ifSuccess(System.out::println).ifFailure(e -> System.err.println(e.getMessage()));
 * }}}
 */
final class JLlmClient private[javaapi] (token: FacadeToken, private[javaapi] val underlying: LLMClient)
    extends AutoCloseable {

  FacadeToken.check(token, "JLlmClient")

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

  override def close(): Unit = underlying.close()
}
