package org.llm4s.kotlin

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.llm4s.javaapi.AgentStream
import org.llm4s.error.CancelledError
import org.llm4s.error.ValidationError
import org.llm4s.javaapi.JAgent
import org.llm4s.javaapi.JLlmClient
import org.llm4s.javaapi.LlmException
import org.llm4s.javaapi.LlmResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Real-concurrency behaviour of the coroutine wrappers: cancellation must reach the blocking
 * call, work must leave the caller's thread, and errors must keep their identity. Synchronisation
 * is by latches only (no sleeps that decide the outcome), so the tests are deterministic.
 */
class CancellationAndThreadingTest {

    private val mockJClient = mockk<JLlmClient>()
    private val client = LLMClientKt(mockJClient)
    private val mockJAgent = mockk<JAgent>()
    private val agent = AgentKt(mockJAgent)

    private val seconds = 10L

    /** Blocks until interrupted (returning true) or [release] opens (returning false). */
    private fun blockUntilInterrupted(started: CountDownLatch, interrupted: CountDownLatch, release: CountDownLatch): Boolean {
        started.countDown()
        return try {
            release.await(30, TimeUnit.SECONDS)
            false
        } catch (e: InterruptedException) {
            interrupted.countDown()
            true
        }
    }

    // ---- cancellation reaches the blocking call ------------------------------------------

    @Test
    fun `cancelling a complete call interrupts the blocking provider call`() = runBlocking {
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { mockJClient.complete("q") } answers {
            blockUntilInterrupted(started, interrupted, release)
            LlmResult.success("late")
        }

        val job = launch(Dispatchers.Default) { client.complete("q") }
        assertTrue(started.await(seconds, TimeUnit.SECONDS), "provider call never started")
        job.cancel()
        val reached = interrupted.await(seconds, TimeUnit.SECONDS)
        release.countDown() // never leave the worker blocked, whatever the outcome
        job.join()
        assertTrue(reached, "cancelling the coroutine did not interrupt the blocking call")
    }

    @Test
    fun `cancelling an agent run cancels its turn`() = runBlocking {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val handle = mockk<AgentStream>()
        every { handle.cancel() } answers { cancelled.countDown() }
        // as the facade's: returns once delivery ends, which here only cancelling the turn does
        every { handle.await() } answers {
            cancelled.await(seconds, TimeUnit.SECONDS)
            LlmResult.failure(org.llm4s.error.CancelledError.apply("run", scala.Option.empty()))
        }
        // the turn never ends on its own: only cancelling it does
        every { mockJAgent.stream(any(), "q", any()) } answers {
            started.countDown()
            LlmResult.success(handle)
        }

        val job = launch(Dispatchers.Default) { agent.run("q") }
        assertTrue(started.await(seconds, TimeUnit.SECONDS))
        job.cancel()
        job.join()
        assertTrue(cancelled.await(seconds, TimeUnit.SECONDS), "cancelling the coroutine did not cancel the turn")
        assertTrue(job.isCancelled)
    }

    @Test
    fun `cancelling a flow collector interrupts the blocking call and does not leak it`() = runBlocking {
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { mockJClient.complete("q") } answers {
            blockUntilInterrupted(started, interrupted, release)
            LlmResult.success("late")
        }

        val job = launch(Dispatchers.Default) { client.streamComplete("q").collect { } }
        assertTrue(started.await(seconds, TimeUnit.SECONDS))
        job.cancel()
        val reached = interrupted.await(seconds, TimeUnit.SECONDS)
        release.countDown()
        job.join()
        assertTrue(reached, "cancelling the collector did not interrupt the blocking call")
        assertTrue(job.isCancelled)
    }

    @Test
    fun `a call cancelled by timeout surfaces as a timeout and interrupts the provider`() = runBlocking {
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { mockJClient.complete("q") } answers {
            blockUntilInterrupted(started, interrupted, release)
            LlmResult.success("late")
        }

        val outcome = runCatching { withTimeout(200) { client.complete("q") } }
        val reached = interrupted.await(seconds, TimeUnit.SECONDS)
        release.countDown()
        assertIs<kotlinx.coroutines.TimeoutCancellationException>(outcome.exceptionOrNull())
        assertTrue(reached, "withTimeout did not interrupt the provider")
    }

    @Test
    fun `a provider that reports cancellation as CancelledError makes the caller cancelled and not failed`() = runBlocking<Unit> {
        // Core providers answer an interrupt with Left(CancelledError). That must read as
        // cancellation to coroutines, otherwise a cancelled child fails its parent scope.
        every { mockJClient.complete("q") } returns
            LlmResult.failure<String>(CancelledError.apply("openai.complete", scala.Option.empty()))

        assertFailsWith<CancellationException> { client.complete("q") }
        assertFailsWith<CancellationException> { client.streamComplete("q").toList() }
    }

    @Test
    fun `a CancelledError from a cancelled child does not fail its parent scope`() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { mockJClient.complete("q") } answers {
            started.countDown()
            release.await(30, TimeUnit.SECONDS)
            LlmResult.failure<String>(CancelledError.apply("openai.complete", scala.Option.empty()))
        }
        val parent = launch {
            val child = launch(Dispatchers.Default) { client.complete("q") }
            assertTrue(started.await(seconds, TimeUnit.SECONDS))
            child.cancel()
            release.countDown()
            child.join()
        }
        parent.join()
        assertFalse(parent.isCancelled, "a cancelled child must not cancel the parent")
    }

    // ---- threading -----------------------------------------------------------------------

    @Test
    fun `the blocking call runs off the caller thread`() = runBlocking {
        val caller = Thread.currentThread()
        val seen = AtomicReference<Thread>()
        every { mockJClient.complete("q") } answers {
            seen.set(Thread.currentThread())
            LlmResult.success("ok")
        }
        assertEquals("ok", client.complete("q"))
        assertNotSame(caller, seen.get())
    }

    @Test
    fun `many blocking calls run in parallel, beyond the CPU-bound pool size`() = runBlocking {
        // 32 calls that each wait for all the others: only completes if >= 32 threads are used at
        // once, i.e. blocking work is on the IO-sized pool and not serialised on a small one.
        val n = 32
        val barrier = CyclicBarrier(n)
        every { mockJClient.complete(any<String>()) } answers {
            barrier.await(seconds, TimeUnit.SECONDS)
            LlmResult.success("ok")
        }
        val results = (1..n).map { i -> async(Dispatchers.Default) { client.complete("q$i") } }.awaitAll()
        assertEquals(List(n) { "ok" }, results)
    }

    // ---- cold flow semantics ---------------------------------------------------------------

    @Test
    fun `streamComplete is cold - nothing is called until collected, once per collection`() = runBlocking {
        val calls = AtomicInteger()
        every { mockJClient.complete("q") } answers {
            calls.incrementAndGet()
            LlmResult.success("r")
        }
        val flow = client.streamComplete("q")
        assertEquals(0, calls.get(), "creating the flow must not call the provider")
        assertEquals(listOf("r"), flow.toList())
        assertEquals("r", flow.first())
        assertEquals(2, calls.get())
    }

    // ---- error identity ----------------------------------------------------------------------

    @Test
    fun `the thrown LLMException keeps the message and the original LlmException as cause`() = runBlocking {
        val scalaError = ValidationError.apply("field", "bad value")
        val result = LlmResult.failure<String>(scalaError)
        every { mockJClient.complete("q") } returns result

        val ex = assertFailsWith<LLMException> { client.complete("q") }
        assertEquals(scalaError.message(), ex.message)
        val cause = assertIs<LlmException>(ex.cause)
        assertSame(scalaError, cause.error())
    }

    @Test
    fun `agent failures keep the message and the original error too`() = runBlocking {
        val scalaError = ValidationError.apply("query", "empty")
        every { mockJAgent.stream(any(), "q", any()) } returns LlmResult.failure<AgentStream>(scalaError)

        val ex = assertFailsWith<LLMException> { agent.run("q") }
        assertEquals(scalaError.message(), ex.message)
        assertSame(scalaError, assertIs<LlmException>(ex.cause).error())
    }

    @Test
    fun `factory failures keep the original error`() {
        val scalaError = ValidationError.apply("llm.model", "missing")
        val factory = mockk<ClientFactory>()
        every { factory.createDefault() } returns LlmResult.failure<JLlmClient>(scalaError)
        val original = Llm4s.factory
        Llm4s.factory = factory
        try {
            val ex = assertFailsWith<LLMException> { Llm4s.createDefaultClient() }
            assertSame(scalaError, assertIs<LlmException>(ex.cause).error())
        } finally {
            Llm4s.factory = original
        }
    }

    @Test
    fun `close is delegated on every call, with no idempotence guard`() = runBlocking {
        every { mockJClient.close() } returns Unit
        client.close()
        client.close()
        verify(exactly = 2) { mockJClient.close() }
    }
}
