package org.llm4s.kotlin

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.llm4s.javaapi.AgentStream
import org.llm4s.javaapi.AgentStreamListener
import org.llm4s.javaapi.JAgentResult
import org.llm4s.javaapi.JAgent
import org.llm4s.javaapi.LlmException
import org.llm4s.javaapi.LlmResult
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentKtTest {

    private val mockJAgent = mockk<JAgent>()
    private val agent = AgentKt(mockJAgent)

    /**
     * The facade's stream start, as [JAgent.stream] answers it: [drive] runs the listener from a thread of its own,
     * and `await()` returns once it has, as the facade's does.
     */
    private fun started(drive: (AgentStreamListener) -> Unit): (AgentStreamListener) -> LlmResult<AgentStream> = { listener ->
        val driver = thread { drive(listener) }
        val handle = mockk<AgentStream>(relaxed = true)
        every { handle.await() } answers { driver.join(); LlmResult.success(mockk()) }
        LlmResult.success(handle)
    }

    @Test
    fun `run returns JAgentResult on success, each run on a new thread`() = runTest {
        val state = mockk<JAgentResult>()
        val threads = mutableListOf<String>()
        every { mockJAgent.stream(any(), "query", any()) } answers {
            threads.add(firstArg())
            started { it.onComplete(state) }(thirdArg())
        }

        assertEquals(state, agent.run("query"))
        assertEquals(state, agent.run("query"))
        assertEquals(2, threads.toSet().size)
    }

    @Test
    fun `run throws LLMException on failure`() = runTest {
        val result = mockk<LlmResult<AgentStream>>()
        val err = mockk<LlmException>(relaxed = true)
        every { result.isSuccess } returns false
        every { result.getError() } returns err
        every { err.message } returns "agent failed"
        every { mockJAgent.stream(any(), "query", any()) } returns result
        every { mockJAgent.forget(any<String>()) } returns LlmResult.success<Void>(null)

        assertEquals("agent failed", assertFailsWith<LLMException> { agent.run("query") }.message)
    }

    @Test
    fun `a failure without a message of its own reads as a failed run`() = runTest {
        val err = mockk<LlmException>(relaxed = true)
        every { err.message } returns null
        every { mockJAgent.stream(any(), "query", any()) } answers { started { it.onError(err) }(thirdArg()) }
        every { mockJAgent.forget(any<String>()) } returns LlmResult.success<Void>(null)

        assertEquals("Agent run failed", assertFailsWith<LLMException> { agent.run("query") }.message)
    }

    @Test
    fun `a failed run forgets the thread it ran on, and only then throws`() = runTest {
        val err = mockk<LlmException>(relaxed = true)
        every { err.message } returns "agent failed"
        val ranOn = mutableListOf<String>()
        every { mockJAgent.stream(any(), "query", any()) } answers {
            ranOn.add(firstArg())
            started { it.onError(err) }(thirdArg())
        }
        every { mockJAgent.forget(any<String>()) } returns LlmResult.success<Void>(null)

        val thrown = assertFailsWith<LLMException> { agent.run("query") }
        verify(exactly = 1) { mockJAgent.forget(ranOn.single()) }
        assertEquals(0, thrown.suppressed.size)
    }

    @Test
    fun `a forget that fails, or throws, is suppressed in what the failed run throws`() = runTest {
        val err = mockk<LlmException>(relaxed = true)
        every { err.message } returns "agent failed"
        every { mockJAgent.stream(any(), "query", any()) } answers { started { it.onError(err) }(thirdArg()) }
        val busy = org.llm4s.error.ValidationError.apply("thread", "busy")
        every { mockJAgent.forget(any<String>()) } returns LlmResult.failure<Void>(busy)

        val refused = assertFailsWith<LLMException> { agent.run("query") }
        assertEquals("agent failed", refused.message)
        assertEquals(busy, (refused.suppressed.single() as LlmException).error())

        val broken = IllegalStateException("store down")
        every { mockJAgent.forget(any<String>()) } throws broken
        val thrown = assertFailsWith<LLMException> { agent.run("query") }
        assertEquals("agent failed", thrown.message)
        assertEquals(broken, thrown.suppressed.single())
    }

    @Test
    fun `continueConversation returns the next turn's JAgentResult on success`() = runTest {
        val previous = mockk<JAgentResult>()
        val next = mockk<JAgentResult>()
        every { previous.threadId() } returns "t1"
        every { mockJAgent.stream("t1", "more", any()) } answers { started { it.onComplete(next) }(thirdArg()) }

        assertEquals(next, agent.continueConversation(previous, "more"))
    }

    @Test
    fun `continueConversation throws LLMException on failure`() = runTest {
        val previous = mockk<JAgentResult>()
        val err = mockk<LlmException>(relaxed = true)
        every { previous.threadId() } returns "t1"
        every { err.message } returns "agent failed"
        every { mockJAgent.stream("t1", "more", any()) } answers { started { it.onError(err) }(thirdArg()) }

        assertEquals("agent failed", assertFailsWith<LLMException> { agent.continueConversation(previous, "more") }.message)
    }

    @Test
    fun `forget removes the conversation`() = runTest {
        val previous = mockk<JAgentResult>()
        val result = mockk<LlmResult<Void>>()
        every { result.isSuccess } returns true
        every { result.get() } returns null
        every { mockJAgent.forget(previous) } returns result

        agent.forget(previous)
        verify { mockJAgent.forget(previous) }
    }

    @Test
    fun `forget throws LLMException on failure`() = runTest {
        val previous = mockk<JAgentResult>()
        val result = mockk<LlmResult<Void>>()
        val err = mockk<LlmException>(relaxed = true)
        every { result.isSuccess } returns false
        every { result.getError() } returns err
        every { err.message } returns "busy"
        every { mockJAgent.forget(previous) } returns result

        assertFailsWith<LLMException> { agent.forget(previous) }
    }
}
