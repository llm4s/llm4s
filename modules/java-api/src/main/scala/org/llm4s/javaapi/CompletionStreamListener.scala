package org.llm4s.javaapi

/**
 * Receives a streamed completion from [[JLlmClient.stream]]: each piece of the reply's text, in order, through
 * [[onPartial]], then exactly one of [[onComplete]] (the whole reply) or [[onError]] (the error the call failed with, a
 * cancellation included). Calls are made one at a time, never on the caller's thread.
 *
 * Only [[onPartial]] is abstract, so a Java lambda is a listener:
 * {{{
 * CompletionStream stream = client.stream("Tell me a story", System.out::print).get();
 * LlmResult<JCompletion> reply = stream.await();
 * }}}
 *
 * A listener that throws from [[onPartial]] stops the call (its thread is interrupted, which is how llm4s providers
 * are cancelled), and [[onError]] receives what it threw.
 */
trait CompletionStreamListener {

  /** The next piece of the reply's text. */
  def onPartial(text: String): Unit

  /** The whole reply, after its last piece. Called at most once, and never together with [[onError]]. */
  def onComplete(completion: JCompletion): Unit = ()

  /**
   * The error the call failed with - or, after [[CompletionStream.cancel]], the cancellation - after the last piece
   * delivered. Called at most once, and never together with [[onComplete]].
   */
  def onError(error: LlmException): Unit = ()
}
