package org.llm4s.javaapi.reactor

import org.llm4s.error.ValidationError
import org.llm4s.javaapi.{ JMessage, LlmException, LlmResult }
import org.llm4s.javaapi.rag.{ JRag, JRagAnswer, JRagAnswerStream, JRagSource, JRagStreamListener }
import reactor.core.publisher.{ Flux, FluxSink }

import java.util.concurrent.locks.LockSupport

/**
 * A RAG answer, with its sources, as a Reactor `Flux` of [[RagAnswerEvent]]s (#2727): for a Spring WebFlux endpoint
 * that sends the citations over SSE first, then the answer as it is generated. It needs `llm4s-java-rag` on the
 * classpath, which an application holding a `JRag` has.
 *
 * Each method runs [[org.llm4s.javaapi.rag.JRag#askStream JRag.askStream]] once per subscription, so the `Flux`:
 *
 *  - is cold: each subscription makes its own search and model call, on a thread of its own;
 *  - emits one `RagAnswerEvent.Sources`, then a `RagAnswerEvent.Chunk` for each piece of the answer's text, then one
 *    `RagAnswerEvent.Complete` with the whole answer, then completes;
 *  - ends with `onError` for a failure - of the search, the model, or a pipeline that refuses the plain query - carrying
 *    an [[org.llm4s.javaapi.LlmException LlmException]] whose `getKind()` says what failed, after the elements
 *    already delivered;
 *  - honours backpressure: a subscriber that requests less than the model produces holds the call's thread rather
 *    than losing text;
 *  - cancelling the subscription (`dispose()`, `take(n)`, a client that disconnects) interrupts the call, as
 *    `JRagAnswerStream.cancel()` does, without waiting for it.
 *
 * The sources and the answer travel as elements of one `Flux`, not as a `Mono` beside it, so they cannot be
 * subscribed to apart - two subscriptions would make two searches - and their order is the stream's own.
 *
 * A `null` argument, a blank question or a pipeline without a chat client is never thrown: the `Flux` fails on
 * subscription with an `LlmException` of kind `VALIDATION` or `CONFIGURATION`.
 *
 * {{{
 * Flux<RagAnswerEvent> events = RagStreams.toFlux(rag, "How long do refunds take?");
 * events.subscribe(event -> {
 *     switch (event) {
 *         case RagAnswerEvent.Sources s -> showSources(s.sources());
 *         case RagAnswerEvent.Chunk c -> System.out.print(c.text());
 *         case RagAnswerEvent.Complete c -> System.out.println();
 *     }
 * });
 * }}}
 */
object RagStreams {

  /** The answer to `question`, from at most the pipeline's `topK` chunks, as a `Flux` of events. */
  def toFlux(rag: JRag, question: String): Flux[RagAnswerEvent] =
    adapt(rag)(_.askStream(question, _))

  /** The answer to `question`, from at most `topK` chunks, at least 1, as a `Flux` of events. */
  def toFlux(rag: JRag, question: String, topK: Int): Flux[RagAnswerEvent] =
    adapt(rag)(_.askStream(question, topK, _))

  /**
   * The answer to `question`, a turn of a chat, with `history` - the conversation so far, oldest first - sent to the
   * model as [[org.llm4s.javaapi.rag.JRag#ask JRag.ask(question, history)]] sends it (#2766), as a `Flux` of events.
   * A `null` history or a `null` or `SYSTEM` message in it fails on subscription with kind `VALIDATION`.
   */
  def toFlux(rag: JRag, question: String, history: java.util.List[JMessage]): Flux[RagAnswerEvent] =
    adapt(rag)(_.askStream(question, history, _))

  /** The answer to `question` with `history`, from at most `topK` chunks, at least 1, as a `Flux` of events. */
  def toFlux(rag: JRag, question: String, topK: Int, history: java.util.List[JMessage]): Flux[RagAnswerEvent] =
    adapt(rag)(_.askStream(question, topK, history, _))

  private def adapt(rag: JRag)(start: (JRag, JRagStreamListener) => LlmResult[JRagAnswerStream]): Flux[RagAnswerEvent] =
    if (rag == null) Flux.error(new LlmException(ValidationError.required("rag")))
    else
      Flux.create[RagAnswerEvent] { sink =>
        val started = start(rag, new Emitter(sink))
        if (started.isFailure) sink.error(started.getError())
        else {
          val stream = started.get()
          sink.onCancel(() => stream.stop())
        }
      }

  /**
   * The listener: each event waits, on the call's thread, until the subscriber has asked for one, so nothing is
   * buffered or dropped. A request wakes it, and a cancel ends the wait without emitting.
   */
  final private class Emitter(sink: FluxSink[RagAnswerEvent]) extends JRagStreamListener {

    // the call's thread, once it waits; `LockSupport.unpark(null)` does nothing
    @volatile private var waiter: Thread = null

    sink.onRequest(_ => LockSupport.unpark(waiter))

    private def emit(event: RagAnswerEvent): Unit = {
      waiter = Thread.currentThread()
      // `park` returns at once while the thread is interrupted; the loop then ends on the cancel that interrupted it
      while (sink.requestedFromDownstream() == 0 && !sink.isCancelled) LockSupport.park(this)
      if (!sink.isCancelled) sink.next(event)
    }

    override def onSources(sources: java.util.List[JRagSource]): Unit = emit(new RagAnswerEvent.Sources(sources))

    override def onChunk(text: String): Unit = emit(new RagAnswerEvent.Chunk(text))

    override def onComplete(answer: JRagAnswer): Unit = {
      emit(new RagAnswerEvent.Complete(answer))
      sink.complete()
    }

    override def onError(error: LlmException): Unit =
      if (!sink.isCancelled) sink.error(error)
  }
}
