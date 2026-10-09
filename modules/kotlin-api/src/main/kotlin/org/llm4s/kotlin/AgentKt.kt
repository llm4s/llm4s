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
import java.util.UUID
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
 * Runs each turn off the caller's thread and converts Scala [org.llm4s.javaapi.LlmResult] errors into
 * [LLMException]. A conversation is a thread of the agent's in-memory runtime, kept until [forget]
 * removes it.
 *
 * Every suspend function that runs a turn - [run], [continueConversation], [resume] and [recover] -
 * cancels the turn when its caller is cancelled (a cancelled scope, a `withTimeout`): it throws
 * `CancellationException` once the turn has ended, and the conversation thread is no longer busy, left
 * for [recover] to finish the cancelled turn - but for [run]'s, whose random id nothing thrown carries, so
 * it forgets the thread. The cancellation waits up to 5 seconds for the turn to end, as Java's blocking
 * calls do: a turn whose provider ignores its interrupt for longer is left to end on its own, and its
 * thread is busy until it does. If the turn had already completed when the cancellation
 * arrived, it still throws `CancellationException`, but the turn's result is committed to the thread:
 * [recover] then throws [LLMException] (no incomplete execution), and the next turn continues from it.
 * Each runs as the matching flow does - [stream], [streamResume] or [streamRecover] - its events
 * discarded, collected on [Dispatchers.IO] rather than the caller's dispatcher.
 *
 * Every turn returns a [JAgentResult], read with Java types only: `answer()` is an `Optional<String>`,
 * `messages()` a `List`, and `status().kind()` an [org.llm4s.javaapi.AgentStatusKind] to `when` over.
 *
 * A turn whose tools need approval, whose tools or middleware ask a question, or whose task a static
 * breakpoint holds, ends `SUSPENDED`: `result.status().pending()` - or [pending] - lists what it waits for, and [resume] answers some or all of it and continues. A turn that failed or was cancelled
 * continues with [recover], but for [run]'s, whose thread is forgotten.
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
     * Suspends until the agent completes the given [query], the first turn of a new conversation on a
     * thread with a random id, and returns the resulting [JAgentResult]. Throws [LLMException] on failure.
     *
     * Cancelling the caller cancels the turn and returns once it has ended. The turn runs as [stream]
     * does, its events discarded.
     *
     * What it throws carries no thread id, so nothing could [recover] or [forget] the conversation: once a
     * failed or cancelled turn has ended, its thread is forgotten, as Java's `JAgent.run(query)` does - also
     * after a cancellation that lost the race to the turn's commit. A cancelled turn whose provider ignores
     * its interrupt for longer than the 5 seconds the cancellation waits has not ended, and its thread is
     * left in the runtime; the refused forget is added to the thrown exception as a suppressed one. For a
     * turn to recover, name its thread with [stream].
     */
    suspend fun run(query: String): JAgentResult {
        val threadId = UUID.randomUUID().toString()
        try {
            return runTurn(threadId, query)
        } catch (failure: Throwable) {
            forgetFailed(threadId, failure)
            throw failure
        }
    }

    /**
     * Forgets [threadId], whose turn threw [failure] and has ended, off the caller's dispatcher and whatever its
     * cancellation; a forget that fails - or throws - is added to [failure] as a suppressed exception.
     */
    private suspend fun forgetFailed(threadId: String, failure: Throwable) {
        val forgotten = withContext(NonCancellable + Dispatchers.IO) { runCatching { underlying.forget(threadId) } }
        forgotten.fold({ if (!it.isSuccess) failure.addSuppressed(it.getError()) }, { failure.addSuppressed(it) })
    }

    /**
     * Suspends until the agent completes [query] as the next turn of [previous]'s conversation - only its
     * `threadId()` is read. Throws [LLMException] on failure.
     *
     * Cancelling the caller cancels the turn and returns once it has ended, leaving the thread for
     * [recover]. The turn runs as [stream] does, its events discarded.
     */
    suspend fun continueConversation(previous: JAgentResult, query: String): JAgentResult =
        runTurn(previous.threadId(), query)

    /** [query] as one turn on [threadId], as [stream] runs it; a failure's fallback message is `run`'s. */
    private suspend fun runTurn(threadId: String, query: String): JAgentResult =
        resultOf(streaming("Agent run failed") { underlying.stream(threadId, query, it) })

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

    /**
     * The result of [turn]'s collection, its last item: the flow ends with [AgentStreamItem.Done] or throws.
     * Collected on [Dispatchers.IO], so the discarded events never hop to the caller's dispatcher;
     * `withContext` waits for the collection - and so the turn's cancellation - to end before it returns.
     */
    private suspend fun resultOf(turn: Flow<AgentStreamItem>): JAgentResult =
        withContext(Dispatchers.IO) { (turn.last() as AgentStreamItem.Done).result }

    /**
     * Runs [query] as one turn on [threadId] - a new conversation, or the next turn of one - as a cold
     * [Flow]: each collection starts the turn, emits every event of it, then [AgentStreamItem.Done].
     *
     * A refused start (a blank query, a busy thread) or a failed turn throws [LLMException], as does a
     * turn that ends without a terminal event (a crash), or whose delivery to the flow fails fatally (a
     * `VirtualMachineError` such as `OutOfMemoryError`): that error ends the facade's delivery thread, and the
     * flow ends with the facade's report of it rather than suspending. Cancelling the collection - its scope, a
     * `take(n)`, a timeout - cancels the turn and returns once it has ended (waiting up to 5 seconds for the
     * turn to end, as [run] does), leaving the thread for [streamRecover]. A collector too slow for the stream's buffer never holds the turn up: it loses
     * live events (text deltas, tool progress) and receives one `StreamEvent.LiveGap` with their count
     * where they were dropped; durable events are never dropped. Starting and cancelling the turn run
     * on [Dispatchers.IO]; the events are handed over from the stream's own thread.
     */
    fun stream(threadId: String, query: String): Flow<AgentStreamItem> =
        streaming("Agent stream failed") { underlying.stream(threadId, query, it) }

    /**
     * Answers some of [threadId]'s pending approvals and questions and continues, as a [Flow]; see
     * [stream]. Build each answer with [Answer.approve], [Answer.reject], [Answer.edit] or [Answer.reply].
     */
    fun streamResume(threadId: String, answers: List<Answer>): Flow<AgentStreamItem> =
        streaming("Agent stream failed") { underlying.streamResume(threadId, answers, it) }

    /** Continues [threadId]'s failed or cancelled turn, as a [Flow]; see [stream]. */
    fun streamRecover(threadId: String): Flow<AgentStreamItem> =
        streaming("Agent stream failed") { underlying.streamRecover(threadId, it) }

    /**
     * Starts the turn with a listener that hands each event to a channel - blocking the stream's own
     * thread, never the turn, when the channel is full - and closes it with the turn's outcome, then emits
     * what the channel receives; if delivery ends without either callback (the listener failed fatally), the
     * stream's `await()` outcome closes it. The start is not cancellable, so a turn is never started and forgotten.
     * However the collection ends - `Done`, an error, or cancelled - the turn is then cancelled and awaited
     * (a no-op once it has ended), off the caller's dispatcher. [failure] is an error's message when it has
     * none of its own.
     */
    private fun streaming(failure: String, start: (AgentStreamListener) -> LlmResult<AgentStream>): Flow<AgentStreamItem> = flow {
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
                items.close(error.toKotlin(failure, cancellation = false))
            }
        }
        // set inside the non-cancellable start: withContext can still throw on return when the collector was
        // cancelled meanwhile, and a turn it started must be cancelled all the same
        val started = AtomicReference<AgentStream?>(null)
        try {
            val stream = withContext(NonCancellable + Dispatchers.IO) {
                start(listener).also { if (it.isSuccess) started.set(it.get()) }
            }.unwrap(failure, cancellation = false)
            // a listener that fails fatally (a VirtualMachineError, a LinkageError) skips its terminal callback, so
            // nothing would close the channel: await() still returns once delivery ends, and closes it if still open
            // (a no-op after onComplete or onError). A virtual thread, so a long turn holds no pooled thread.
            Thread.ofVirtual().name("llm4s-kotlin-stream-end").start {
                items.close(unterminated(stream.await(), failure))
            }
            emitAll(items)
        } finally {
            // releases a listener blocked on a full channel nobody reads any more
            items.cancel()
            started.get()?.let { withContext(NonCancellable + Dispatchers.IO) { it.cancel() } }
        }
    }

    /**
     * What ends a flow whose listener never closed its channel, from the stream's [outcome]: its error (the facade's
     * "listener failed fatally"), or - for a turn that completed but whose `onComplete` failed fatally - an
     * [LLMException] saying so. The fatal error itself ended the facade's delivery thread and is not seen here.
     */
    private fun unterminated(outcome: LlmResult<JAgentResult>, failure: String): RuntimeException =
        if (outcome.isSuccess) LLMException("$failure: the stream's listener failed fatally before it ended the flow")
        else outcome.getError().toKotlin(failure, cancellation = false)

    companion object {
        /**
         * What [result]'s turn waits for: for a `SUSPENDED` turn, its pending approvals, then its tool
         * questions, middleware questions and static breakpoints, as [PendingInterrupt]s - `id()`, `kind()`,
         * and per kind `toolName()`, `argumentsJson()`, `reason()`, `questionJson()`, `middleware()`, `node()`
         * and `phase()`; an empty list for any other turn. A shortcut for `result.status().pending()`. Answer
         * them with [Answer] - `proceed` continues a breakpoint - and continue with [resume] or [streamResume].
         */
        fun pending(result: JAgentResult): List<PendingInterrupt> = JAgent.pending(result)
    }
}
