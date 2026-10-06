package org.llm4s.kotlin

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.llm4s.agent.Agent
import org.llm4s.agent.AgentResult
import org.llm4s.agent.graph.GraphError
import org.llm4s.agent.graph.GraphRuntime
import org.llm4s.agent.graph.RunEvent
import org.llm4s.agent.graph.StreamEvent
import org.llm4s.error.CancelledError
import org.llm4s.error.LLMError
import org.llm4s.error.NetworkError
import org.llm4s.error.ProcessingError
import org.llm4s.error.ValidationError
import org.llm4s.javaapi.AgentStream
import org.llm4s.javaapi.Answer
import org.llm4s.javaapi.JLlmClient
import org.llm4s.javaapi.StreamEvents
import org.llm4s.agent.events.AgentEvents
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.javaapi.AgentStreamListener
import org.llm4s.javaapi.JAgent
import org.llm4s.javaapi.LlmException
import org.llm4s.javaapi.LlmResult
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.StreamedChunk
import org.llm4s.llmconnect.model.ToolCall
import scala.Function1
import scala.Option
import scala.runtime.BoxedUnit
import scala.util.Either
import scala.util.Left
import scala.util.Right
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * [AgentKt]'s flows over the real agent runtime, behind the real Java facade: only the model is
 * scripted. Synchronisation is by latches and parking, never by sleeps that decide the outcome.
 */
class AgentKtStreamTest {

    private val seconds = 60L

    /** A model whose streamed and plain calls are scripted. */
    private class Scripted(
        val onStream: ((StreamedChunk) -> Unit) -> Either<LLMError, Completion>,
        val onComplete: () -> Either<LLMError, Completion> = { onStream {} },
    ) : LLMClient {
        override fun complete(conversation: Conversation, options: CompletionOptions): Either<LLMError, Completion> =
            onComplete()

        override fun streamComplete(
            conversation: Conversation,
            options: CompletionOptions,
            onChunk: Function1<StreamedChunk, BoxedUnit>,
        ): Either<LLMError, Completion> = onStream { onChunk.apply(it) }

        override fun getContextWindow(): Int = 4096
        override fun getReserveCompletion(): Int = 256
    }

    private fun completion(text: String): Either<LLMError, Completion> =
        Right(
            Completion.apply(
                "id", 0L, text, "m", AssistantMessage.apply(text),
                scala.jdk.javaapi.CollectionConverters.asScala(listOf<ToolCall>()).toList(),
                Option.empty(), Option.empty(), Option.empty(),
            ),
        )

    private fun chunk(i: Int): StreamedChunk =
        StreamedChunk.apply("c$i", Option.apply("x"), Option.empty(), Option.empty(), Option.empty())

    private fun agentOf(client: LLMClient, runtime: GraphRuntime = GraphRuntime.inMemory(Clock.systemUTC())): AgentKt =
        Llm4s.wrapAgent(
            Agent.builder("test", client).withRuntime(runtime).withStreaming().build().toOption().get() as Agent,
        )

    /** Waits, without sleeping to decide anything, until [cond] holds or the deadline passes. */
    private fun eventually(cond: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!cond() && System.nanoTime() < deadline) Thread.yield()
        return cond()
    }

    private fun durable(items: List<AgentStreamItem>): List<RunEvent> =
        items.mapNotNull { ((it as? AgentStreamItem.Event)?.event as? StreamEvent.Durable)?.record()?.event() }

    private fun causeOf(e: LLMError): LLMError = if (e is GraphError.NodeFailed) e.cause() else e

    /**
     * The first model call sends one delta (or [flood] of them), then parks until interrupted; later
     * calls answer "recovered".
     */
    private class ParksOnce(flood: Int = 1) {
        val calls = AtomicInteger()
        val parked = CountDownLatch(1)
        val unparked = CountDownLatch(1)
        val client: LLMClient
        init {
            val self = this
            client = Scripted({ onChunk ->
                if (calls.getAndIncrement() == 0) {
                    repeat(flood) { onChunk(StreamedChunk.apply("c$it", Option.apply("x"), Option.empty(), Option.empty(), Option.empty())) }
                    self.parked.countDown()
                    if (park()) self.unparked.countDown()
                    Left(CancelledError.apply("test", Option.empty()))
                } else {
                    Right(
                        Completion.apply(
                            "id", 0L, "recovered", "m", AssistantMessage.apply("recovered"),
                            scala.jdk.javaapi.CollectionConverters.asScala(listOf<ToolCall>()).toList(),
                            Option.empty(), Option.empty(), Option.empty(),
                        ),
                    )
                }
            })
        }

        private fun park(): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
            while (!Thread.currentThread().isInterrupted && System.nanoTime() < deadline) LockSupport.parkNanos(10_000_000L)
            return Thread.currentThread().isInterrupted
        }
    }

    private suspend fun assertRecovers(agent: AgentKt, threadId: String) {
        val done = agent.streamRecover(threadId).toList().last()
        assertEquals(Option.apply("recovered"), assertIs<AgentStreamItem.Done>(done).result.answer())
    }

    // ---- the turn's events, then its result -----------------------------------------------

    @Test
    fun `stream emits the turn's events, then Done with its result`() = runBlocking {
        val items = agentOf(Scripted({ onChunk -> onChunk(chunk(0)); completion("hello") })).stream("k1", "hi").toList()
        val done = assertIs<AgentStreamItem.Done>(items.last())
        assertEquals(Option.apply("hello"), done.result.answer())
        assertTrue(items.dropLast(1).all { it is AgentStreamItem.Event })
        assertIs<RunEvent.RunStarted>(durable(items).first())
        assertEquals("RunCompleted", durable(items).last().toString())
    }

    @Test
    fun `an agent created with streaming streams text deltas, one without does not`() = runBlocking {
        val model = Scripted({ onChunk -> onChunk(StreamedChunk.apply("c", Option.apply("he"), Option.empty(), Option.empty(), Option.empty())); completion("hello") })
        val client = LLMClientKt(JLlmClient(model))
        suspend fun deltas(agent: AgentKt, threadId: String): List<String> =
            agent.stream(threadId, "hi").toList().mapNotNull { item ->
                (item as? AgentStreamItem.Event)?.let { StreamEvents.decode(AgentEvents.TextDelta(), it.event).orElse(null)?.text() }
            }
        assertEquals(listOf("he"), deltas(Llm4s.createAgent(client, ToolRegistry.empty(), true), "k8"))
        assertEquals(emptyList(), deltas(Llm4s.createAgent(client, ToolRegistry.empty(), false), "k9"))
    }

    @Test
    fun `a failing turn throws LLMException with the turn's error`() = runBlocking<Unit> {
        val down = NetworkError.apply("down", Option.empty(), "http://x")
        val ex = assertFailsWith<LLMException> { agentOf(Scripted({ Left(down) })).stream("k2", "hi").toList() }
        assertIs<NetworkError>(causeOf(assertIs<LlmException>(ex.cause).error()))
    }

    @Test
    fun `a refused start throws LLMException and emits nothing`() = runBlocking<Unit> {
        val seen = AtomicInteger()
        val ex = assertFailsWith<LLMException> {
            agentOf(Scripted({ completion("x") })).stream("k3", "  ").collect { seen.incrementAndGet() }
        }
        assertIs<ValidationError>(assertIs<LlmException>(ex.cause).error())
        assertEquals(0, seen.get())
    }

    @Test
    fun `a slow collector gets a LiveGap for the deltas it missed, and the turn still completes`() = runBlocking {
        val flooded = CountDownLatch(1)
        // far more live text than the flow's channel, the stream's buffer and the subscription's queue hold
        val client = Scripted({ onChunk ->
            repeat(3000) { onChunk(chunk(it)) }
            flooded.countDown()
            completion("done")
        })
        var first = true
        val items = agentOf(client).stream("k4", "hi").onEach {
            // the collector takes nothing more until the model has sent everything
            if (first) withContext(Dispatchers.IO) { flooded.await(seconds, TimeUnit.SECONDS) }
            first = false
        }.toList()
        val gaps = items.sumOf { ((it as? AgentStreamItem.Event)?.event as? StreamEvent.LiveGap)?.dropped() ?: 0 }
        assertTrue(gaps > 0, "the slow collector was told of the dropped deltas")
        assertEquals(Option.apply("done"), assertIs<AgentStreamItem.Done>(items.last()).result.answer())
        assertEquals("RunCompleted", durable(items).last().toString())
    }

    // ---- cancelling the collection cancels the turn -------------------------------------------

    @Test
    fun `stopping early with take cancels the turn and releases its subscription`() = runBlocking {
        val model = ParksOnce(flood = 3000)
        val runtime = GraphRuntime.inMemory(Clock.systemUTC())
        val agent = agentOf(model.client, runtime)
        val first = agent.stream("k5", "hi").onEach {
            withContext(Dispatchers.IO) { model.parked.await(seconds, TimeUnit.SECONDS) }
        }.take(1).toList()
        assertEquals(1, first.size)
        assertTrue(model.unparked.await(seconds, TimeUnit.SECONDS), "the model call was interrupted")
        assertTrue(eventually { runtime.liveSubscriptions("k5") == 0 }, "the turn's subscription ended")
        assertRecovers(agent, "k5")
    }

    @Test
    fun `cancelling the collecting scope while it waits for an event cancels the turn`() = runBlocking {
        val model = ParksOnce()
        val runtime = GraphRuntime.inMemory(Clock.systemUTC())
        val agent = agentOf(model.client, runtime)
        val seen = AtomicInteger()
        val job = launch(Dispatchers.Default) { agent.stream("k6", "hi").collect { seen.incrementAndGet() } }
        assertTrue(withContext(Dispatchers.IO) { model.parked.await(seconds, TimeUnit.SECONDS) })
        assertTrue(withContext(Dispatchers.IO) { eventually { seen.get() > 0 } })
        withTimeout(seconds * 1000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertTrue(model.unparked.await(seconds, TimeUnit.SECONDS), "the model call was interrupted")
        assertTrue(eventually { runtime.liveSubscriptions("k6") == 0 }, "the turn's subscription ended")
        assertRecovers(agent, "k6")
    }

    @Test
    fun `a timeout cancels the turn`() = runBlocking {
        val model = ParksOnce()
        val runtime = GraphRuntime.inMemory(Clock.systemUTC())
        val agent = agentOf(model.client, runtime)
        // the model parks until interrupted, and the timeout is generous enough for it to have parked by then;
        // the collector also holds its first event until the model is parked, so the timeout lands mid-call
        val outcome = runCatching {
            withTimeout(5_000) {
                agent.stream("k7", "hi").collect { withContext(Dispatchers.IO) { model.parked.await(seconds, TimeUnit.SECONDS) } }
            }
        }
        assertIs<TimeoutCancellationException>(outcome.exceptionOrNull())
        assertTrue(model.unparked.await(seconds, TimeUnit.SECONDS), "the model call was interrupted")
        assertTrue(eventually { runtime.liveSubscriptions("k7") == 0 }, "the turn's subscription ended")
        assertRecovers(agent, "k7")
    }

    // ---- the bridge, over a scripted Java facade ----------------------------------------------

    private val mockJAgent = mockk<JAgent>()
    private val mocked = AgentKt(mockJAgent)

    /** A started stream whose listener is driven by [drive] on a thread of its own, as the facade's is. */
    private fun started(handle: AgentStream, drive: (AgentStreamListener) -> Unit): (AgentStreamListener) -> LlmResult<AgentStream> =
        { listener ->
            thread { drive(listener) }
            LlmResult.success(handle)
        }

    @Test
    fun `the facade's onError ends the flow with LLMException, after the events before it`() = runBlocking<Unit> {
        val handle = mockk<AgentStream>(relaxed = true)
        val event = StreamEvent.LiveGap.apply(3)
        every { mockJAgent.stream("t", "q", any()) } answers {
            started(handle) { l ->
                l.onEvent(event)
                l.onError(LlmException(ProcessingError.apply("store", "store down", Option.empty())))
            }(thirdArg())
        }
        val seen = mutableListOf<AgentStreamItem>()
        val ex = assertFailsWith<LLMException> { mocked.stream("t", "q").collect { seen.add(it) } }
        assertTrue(ex.message!!.contains("store down"), "the turn's error: ${ex.message}")
        assertEquals(listOf<AgentStreamItem>(AgentStreamItem.Event(event)), seen)
        verify { handle.cancel() }
    }

    @Test
    fun `a turn cancelled by someone else fails the flow with LLMException, not a swallowed cancellation`() = runBlocking<Unit> {
        val handle = mockk<AgentStream>(relaxed = true)
        every { mockJAgent.streamRecover("t", any()) } answers {
            started(handle) { l -> l.onError(LlmException(CancelledError.apply("run", Option.empty()))) }(secondArg())
        }
        val ex = assertFailsWith<LLMException> { mocked.streamRecover("t").toList() }
        assertIs<CancelledError>(assertIs<LlmException>(ex.cause).error())
    }

    @Test
    fun `streamResume passes the answers through and ends with Done`() = runBlocking {
        val handle = mockk<AgentStream>(relaxed = true)
        val result = mockk<AgentResult>()
        val answers = listOf(Answer.approve("i1"), Answer.reply("i2", "\"yes\""))
        every { mockJAgent.streamResume("t", answers, any()) } answers {
            started(handle) { l -> l.onComplete(result) }(thirdArg())
        }
        assertEquals(listOf<AgentStreamItem>(AgentStreamItem.Done(result)), mocked.streamResume("t", answers).toList())
    }

    @Test
    fun `starting and cancelling the turn run off the collector's thread`() = runBlocking {
        val caller = Thread.currentThread()
        val startedOn = AtomicReference<Thread>()
        val cancelledOn = AtomicReference<Thread>()
        val handle = mockk<AgentStream>()
        every { handle.cancel() } answers { cancelledOn.set(Thread.currentThread()) }
        every { mockJAgent.stream("t", "q", any()) } answers {
            startedOn.set(Thread.currentThread())
            started(handle) { l -> l.onComplete(mockk()) }(thirdArg())
        }
        mocked.stream("t", "q").toList()
        assertNotSame(caller, startedOn.get())
        assertNotSame(caller, cancelledOn.get())
    }

    @Test
    fun `a turn whose start completes after the collector was cancelled is cancelled, and its listener released`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val handle = mockk<AgentStream>()
        every { handle.cancel() } answers { cancelled.countDown() }
        every { mockJAgent.stream("t", "q", any()) } answers {
            val listener = thirdArg<AgentStreamListener>()
            entered.countDown()
            release.await(seconds, TimeUnit.SECONDS)
            // the turn has started: far more events than the flow's channel holds, from the stream's own thread
            thread {
                repeat(1000) { listener.onEvent(StreamEvent.LiveGap.apply(1)) }
                delivered.countDown()
            }
            LlmResult.success(handle)
        }
        val job = launch(Dispatchers.Default) { mocked.stream("t", "q").collect { } }
        assertTrue(withContext(Dispatchers.IO) { entered.await(seconds, TimeUnit.SECONDS) })
        job.cancel()
        release.countDown()
        withTimeout(seconds * 1000) { job.join() }
        assertTrue(cancelled.await(seconds, TimeUnit.SECONDS), "the started turn was cancelled")
        assertTrue(delivered.await(seconds, TimeUnit.SECONDS), "the listener was not left blocked on the channel")
    }

    @Test
    fun `a refused start of the facade throws LLMException`() = runBlocking<Unit> {
        every { mockJAgent.stream("t", "q", any()) } returns
            LlmResult.failure(ValidationError.apply("query", "blank"))
        assertFailsWith<LLMException> { mocked.stream("t", "q").toList() }
    }
}
