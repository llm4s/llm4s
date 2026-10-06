package org.llm4s.kotlin

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.llm4s.agent.AgentResult
import org.llm4s.javaapi.JAgent
import org.llm4s.javaapi.LlmException
import org.llm4s.javaapi.LlmResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentKtTest {

    private val mockJAgent = mockk<JAgent>()
    private val agent = AgentKt(mockJAgent)

    @Test
    fun `run returns AgentResult on success`() = runTest {
        val state = mockk<AgentResult>()
        val result = mockk<LlmResult<AgentResult>>()
        every { result.isSuccess } returns true
        every { result.get() } returns state
        every { mockJAgent.run("query") } returns result

        assertEquals(state, agent.run("query"))
    }

    @Test
    fun `run throws LLMException on failure`() = runTest {
        val result = mockk<LlmResult<AgentResult>>()
        val err = mockk<LlmException>(relaxed = true)
        every { result.isSuccess } returns false
        every { result.getError() } returns err
        every { err.message } returns "agent failed"
        every { mockJAgent.run("query") } returns result

        assertFailsWith<LLMException> { agent.run("query") }
    }

    @Test
    fun `continueConversation returns the next turn's AgentResult on success`() = runTest {
        val previous = mockk<AgentResult>()
        val next = mockk<AgentResult>()
        val result = mockk<LlmResult<AgentResult>>()
        every { result.isSuccess } returns true
        every { result.get() } returns next
        every { mockJAgent.continueConversation(previous, "more") } returns result

        assertEquals(next, agent.continueConversation(previous, "more"))
    }

    @Test
    fun `continueConversation throws LLMException on failure`() = runTest {
        val previous = mockk<AgentResult>()
        val result = mockk<LlmResult<AgentResult>>()
        val err = mockk<LlmException>(relaxed = true)
        every { result.isSuccess } returns false
        every { result.getError() } returns err
        every { err.message } returns "agent failed"
        every { mockJAgent.continueConversation(previous, "more") } returns result

        assertFailsWith<LLMException> { agent.continueConversation(previous, "more") }
    }

    @Test
    fun `forget removes the conversation`() = runTest {
        val previous = mockk<AgentResult>()
        val result = mockk<LlmResult<Void>>()
        every { result.isSuccess } returns true
        every { result.get() } returns null
        every { mockJAgent.forget(previous) } returns result

        agent.forget(previous)
        verify { mockJAgent.forget(previous) }
    }

    @Test
    fun `forget throws LLMException on failure`() = runTest {
        val previous = mockk<AgentResult>()
        val result = mockk<LlmResult<Void>>()
        val err = mockk<LlmException>(relaxed = true)
        every { result.isSuccess } returns false
        every { result.getError() } returns err
        every { err.message } returns "busy"
        every { mockJAgent.forget(previous) } returns result

        assertFailsWith<LLMException> { agent.forget(previous) }
    }
}
