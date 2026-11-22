package org.llm4s.javaapi.rag

import org.llm4s.javaapi.LlmException

/**
 * Receives an answer streamed from [[JRag.askStream]] (#2727): the sources it is generated from, once, through
 * [[onSources]], before the model is asked; each piece of the answer's text, in order, through [[onChunk]]; then
 * exactly one of [[onComplete]] (the whole answer, with the same sources) or [[onError]] (the error the call failed
 * with, a cancellation included). Calls are made one at a time, never on the caller's thread.
 *
 * Only [[onChunk]] is abstract, so a Java lambda is a listener:
 * {{{
 * JRagAnswerStream stream = rag.askStream("How long do refunds take?", System.out::print).get();
 * JRagAnswer answer = stream.await().get();
 * }}}
 *
 * A failure before the sources are found - of the embedding provider or the search - reaches [[onError]] with no
 * [[onSources]]. A listener that throws stops the call (its thread is interrupted, which is how llm4s providers are
 * cancelled; one that throws from [[onSources]] stops it before the model is asked), and [[onError]] receives what it
 * threw.
 */
trait JRagStreamListener {

  /**
   * The chunks the answer is generated from, best match first: what [[JRagAnswer.sources]] will hold. Called once,
   * before the first [[onChunk]]; empty when nothing is indexed. An unmodifiable list.
   */
  def onSources(sources: java.util.List[JRagSource]): Unit = ()

  /** The next piece of the answer's text. */
  def onChunk(text: String): Unit

  /** The whole answer, after its last piece. Called at most once, and never together with [[onError]]. */
  def onComplete(answer: JRagAnswer): Unit = ()

  /**
   * The error the call failed with - or, after [[JRagAnswerStream.cancel]], the cancellation - after the last piece
   * delivered. Called at most once, and never together with [[onComplete]].
   */
  def onError(error: LlmException): Unit = ()
}
