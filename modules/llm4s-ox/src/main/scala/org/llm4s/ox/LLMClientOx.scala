package org.llm4s.ox

import _root_.ox.{ parLimit, raceSuccess, EitherMode }
import _root_.ox.channels.BufferCapacity
import _root_.ox.flow.Flow
import org.llm4s.annotation.Experimental
import org.llm4s.error.{ LLMError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.llmconnect.streaming.StreamingAccumulator
import org.llm4s.types.Result

import scala.util.{ Failure, Success, Try }

/**
 * Direct-style operations over an [[org.llm4s.llmconnect.LLMClient LLMClient]], built on SoftwareMill's Ox: a
 * streamed completion as an Ox `Flow`, bounded fan-out and a race between providers. `import org.llm4s.ox.*`
 * brings them into scope.
 *
 * Core's calls already block the calling thread and return a `Result`; interrupting that thread cancels the call,
 * which then returns `Left(CancelledError)`. These operations keep that model and add what plain direct style
 * leaves to the caller: a pull-based stream, a parallelism bound and the interruption of work nobody waits for.
 * Every fork runs on a JDK 21 virtual thread.
 */
@Experimental
object LLMClientOx {

  extension (client: LLMClient) {

    /**
     * The completion of `conversation`, streamed as an Ox `Flow` of the provider's chunks, in order.
     *
     * The flow is cold: each run makes one `streamComplete` call. The call runs on a virtual thread of its own and
     * hands each chunk to the consumer through a channel of the given `BufferCapacity` (Ox's default is 16): when
     * the consumer lags, the provider's thread waits. When the consumer stops early (a `take`, a failure, the end
     * of its scope), the run's scope interrupts the provider call, which core turns into `CancelledError`, and waits
     * for it to return, so no thread outlives the run.
     *
     * A provider `Left` fails the flow with an [[LLMException]] carrying the `LLMError`, after the chunks delivered
     * before it. `accumulate` turns it back into a `Left`.
     */
    def streamFlow(conversation: Conversation, options: CompletionOptions = CompletionOptions())(using
      BufferCapacity
    ): Flow[StreamedChunk] =
      CallbackFlow[StreamedChunk]("ox.streamFlow")(onChunk => client.streamComplete(conversation, options, onChunk))

    /**
     * Completes each conversation with `client`, at most `parallelism` at a time (a bound below 1 counts as 1), on
     * virtual threads. The results are in the order of `conversations`, one `Result` each: a failed request does
     * not cancel the others. For all-or-nothing fan-out, use Ox's `parEitherLimit` with `client.complete`.
     */
    def parComplete(
      conversations: Seq[Conversation],
      parallelism: Int,
      options: CompletionOptions = CompletionOptions()
    ): Vector[Result[Completion]] =
      parLimit(math.max(1, parallelism))(conversations.map(c => () => client.complete(c, options))).toVector
  }

  /**
   * Completes `conversation` with every client at once and returns the first success; the calls still running are
   * interrupted (core returns `CancelledError` from each) and the race returns once they have ended. When every
   * call fails, the result is the first failure to arrive. With no client, a `ValidationError`.
   */
  def raceProviders(clients: LLMClient*)(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions()
  ): Result[Completion] =
    if (clients.isEmpty) Left(ValidationError("clients", "raceProviders needs at least one client"))
    else raceSuccess(new EitherMode[LLMError])(clients.map(c => () => c.complete(conversation, options)))

  extension (flow: Flow[StreamedChunk]) {

    /** The text of each chunk that carries some, in order; tool-call, thinking and finish chunks are dropped. */
    def textDeltas: Flow[String] = flow.mapConcat(_.content.filter(_.nonEmpty))

    /**
     * Runs the flow and accumulates its chunks into the `Completion` they describe (content, tool calls, thinking,
     * finish), as core's `StreamingAccumulator` does. A chunk carries no token usage or model, so the result has
     * none. A flow failed by an [[LLMException]] returns its `LLMError` as a `Left`; any other exception, an
     * interrupt included, propagates.
     */
    def accumulate: Result[Completion] = {
      val accumulator = StreamingAccumulator.create()
      Try(flow.runForeach(accumulator.addChunk)) match {
        case Success(())              => accumulator.toCompletion
        case Failure(e: LLMException) => Left(e.error)
        case Failure(other)           => throw other
      }
    }
  }
}
