package org.llm4s.kotlin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.llm4s.agent.graph.StreamEvent
import org.llm4s.javaapi.AgentStream
import org.llm4s.javaapi.Answer
import org.llm4s.javaapi.AgentStreamListener
import org.llm4s.javaapi.JAgent
import org.llm4s.javaapi.JAgentResult
import org.llm4s.javaapi.LlmException
import org.llm4s.javaapi.LlmResult
import org.llm4s.javaapi.PendingInterrupt
import java.util.concurrent.atomic.AtomicReference

/** An item of an agent turn's [kotlinx.coroutines.flow.Flow]: each of the turn's events, then its result. */
sealed interface AgentStreamItem {
    /** One event of the turn: a `StreamEvent.Durable`, a `StreamEvent.Live`, or a `StreamEvent.LiveGap`. */
    data class Event(val event: StreamEvent) : AgentStreamItem

    /** The turn's result: the last item. */
    data class Done(val result: JAgentResult) : AgentStreamItem
}

/**
 * Kotlin coroutine wrapper around [JAgent].
 *
 * Dispatches the blocking agent run on [Dispatchers.IO] and converts Scala [org.llm4s.javaapi.LlmResult]
 * errors into [LLMException]. A conversation is a thread of the agent's in-memory runtime, kept until
 * [forget] removes it.
 *
 * Cancelling the caller of [run] or [continueConversation] interrupts only the wait: the call throws
 * `CancellationException`, but the turn keeps running in the background, and its conversation thread
 * stays busy (a new turn on it is refused) until the turn finishes. To stop a turn, run it with
 * [stream] and cancel the collection.
 *
 * Every turn returns a [JAgentResult], read with Java types only: `answer()` is an `Optional<String>`,
 * `messages()` a `List`, and `status().kind()` an [org.llm4s.javaapi.AgentStatusKind] to `when` over.
 *
 * A turn whose tools need approval, or ask a question, ends `SUSPENDED`: `result.status().pending()` - or
 * [pending] - lists what it waits for, and [resume] answers some or all of it and continues. A turn that failed or was cancelled
 * continues with [recover]. Unlike [run], cancelling the caller of [resume] or [recover] cancels the
 * turn itself, leaving the thread for [recover].
 *
 * [stream], [streamResume] and [streamRecover] run a turn on a thread you name as a cold [Flow] of its
 * events ([AgentStreamItem.Event]), then its result ([AgentStreamItem.Done]).
 *
 * Obtain instances via [Llm4s.createAgent] or [Llm4s.wrapAgent].
 *
 * ```kotlin
 * val agent  = Llm4s.createAgent(client)
 * val first  = agent.run("Summarise today's news")
 * val second = agent.continueConversation(first, "And yesterday's?")
 * agent.forget(second)
 * ```
 */
class AgentKt internal constructor(private val underlying: JAgent) {

    /**
     * Suspends until the agent completes the given [query], the first turn of a new conversation, and
     * returns the resulting [JAgentResult]. Throws [LLMException] on failure.
     */
    suspend fun run(query: String): JAgentResult = runInterruptible(Dispatchers.IO) {
        underlying.run(query).unwrap("Agent run failed")
    }

    /**
     * Suspends until the agent completes [query] as the next turn of [previous]'s conversation. Throws
     * [LLMException] on failure.
     */
    suspend fun continueConversation(previous: JAgentResult, query: String): JAgentResult =
        runInterruptible(Dispatchers.IO) {
            underlying.continueConversation(previous, query).unwrap("Agent run failed")
        }

    /** Removes [previous]'s conversation from the agent's runtime. Throws [LLMException] on failure. */
    suspend fun forget(previous: JAgentResult): Unit = runInterruptible(Dispatchers.IO) {
        underlying.forget(previous).unwrap("Agent forget failed")
        Unit
    }

    /**
     * Answers some of [threadId]'s pending approvals and questions - read them with [pending] - and
     * suspends until the continued turn ends, returning its result; unanswered ones stay pending, so the
     * result can be `Suspended` again. Build each answer with [Answer.approve], [Answer.reject],
     * [Answer.edit] or [Answer.reply]. A malformed answer, an answer to an id the thread does not wait
     * for, a thread that is not suspended, or a failed turn throws [LLMException].
     *
     * Cancelling the caller cancels the turn and returns once it has ended, leaving the thread for
     * [recover]. The turn runs as [streamResume] does, its events discarded.
     */
    suspend fun resume(threadId: String, answers: List<Answer>): JAgentResult = resultOf(streamResume(threadId, answers))

    /**
     * Continues [threadId]'s failed or cancelled turn, re-running only the work that did not finish, and
     * suspends until it ends, returning its result. A thread with nothing to recover, or a failed turn,
     * throws [LLMException]. Cancelling the caller cancels the turn, as for [resume].
     */
    suspend fun recover(threadId: String): JAgentResult = resultOf(streamRecover(threadId))

    /** The result of [turn]'s collection, its last item: the flow ends with [AgentStreamItem.Done] or throws. */
    private suspend fun resultOf(turn: Flow<AgentStreamItem>): JAgentResult = (turn.last() as AgentStreamItem.Done).result

    /**
     * Runs [query] as one turn on [threadId] - a new conversation, or the next turn of one - as a cold
     * [Flow]: each collection starts the turn, emits every event of it, then [AgentStreamItem.Done].
     *
     * A refused start (a blank query, a busy thread) or a failed turn throws [LLMException], as does a
     * turn that ends without a terminal event (a crash). Cancelling the collection - its scope, a
     * `take(n)`, a timeout - cancels the turn and returns once it has ended, leaving the thread for
     * [streamRecover]. A collector too slow for the stream's buffer never holds the turn up: it loses
     * live events (text deltas, tool progress) and receives one `StreamEvent.LiveGap` with their count
     * where they were dropped; durable events are never dropped. Starting and cancelling the turn run
     * on [Dispatchers.IO]; the events are handed over from the stream's own thread.
     */
    fun stream(threadId: String, query: String): Flow<AgentStreamItem> =
        streaming { underlying.stream(threadId, query, it) }

    /**
     * Answers some of [threadId]'s pending approvals and questions and continues, as a [Flow]; see
     * [stream]. Build each answer with [Answer.approve], [Answer.reject], [Answer.edit] or [Answer.reply].
     */
    fun streamResume(threadId: String, answers: List<Answer>): Flow<AgentStreamItem> =
        streaming { underlying.streamResume(threadId, answers, it) }

    /** Continues [threadId]'s failed or cancelled turn, as a [Flow]; see [stream]. */
    fun streamRecover(threadId: String): Flow<AgentStreamItem> =
        streaming { underlying.streamRecover(threadId, it) }

    /**
     * Starts the turn with a listener that hands each event to a channel - blocking the stream's own
     * thread, never the turn, when the channel is full - and closes it with the turn's outcome, then emits
     * what the channel receives. The start is not cancellable, so a turn is never started and forgotten.
     * However the collection ends - `Done`, an error, or cancelled - the turn is then cancelled and awaited
     * (a no-op once it has ended), off the caller's dispatcher.
     */
    private fun streaming(start: (AgentStreamListener) -> LlmResult<AgentStream>): Flow<AgentStreamItem> = flow {
        val items = Channel<AgentStreamItem>(Channel.BUFFERED)
        val listener = object : AgentStreamListener {
            override fun onEvent(event: StreamEvent) {
                items.trySendBlocking(AgentStreamItem.Event(event))
            }

            override fun onComplete(result: JAgentResult) {
                items.trySendBlocking(AgentStreamItem.Done(result))
                items.close()
            }

            // a CancelledError here is a turn cancelled by someone other than this collector: an error to the
            // collector, as fs2 and ZIO raise it, not a CancellationException its coroutine would swallow
            override fun onError(error: LlmException) {
                items.close(error.toKotlin("Agent stream failed", cancellation = false))
            }
        }
        // set inside the non-cancellable start: withContext can still throw on return when the collector was
        // cancelled meanwhile, and a turn it started must be cancelled all the same
        val started = AtomicReference<AgentStream?>(null)
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                start(listener).also { if (it.isSuccess) started.set(it.get()) }
            }.unwrap("Agent stream failed", cancellation = false)
            emitAll(items)
        } finally {
            // releases a listener blocked on a full channel nobody reads any more
            items.cancel()
            started.get()?.let { withContext(NonCancellable + Dispatchers.IO) { it.cancel() } }
        }
    }

    companion object {
        /**
         * What [result]'s turn waits for: for a `SUSPENDED` turn, its pending approvals, then its
         * questions, as [PendingInterrupt]s - `id()`, `kind()`, `toolName()`, `argumentsJson()`,
         * `reason()` and `questionJson()`; an empty list for any other turn. A shortcut for
         * `result.status().pending()`. Answer them with [Answer] and continue with [resume] or [streamResume].
         */
        fun pending(result: JAgentResult): List<PendingInterrupt> = JAgent.pending(result)
    }
}
