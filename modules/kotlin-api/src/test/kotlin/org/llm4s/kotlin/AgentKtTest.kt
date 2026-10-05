package org.llm4s.kotlin

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.llm4s.agent.AgentThread
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
    fun `run returns AgentThread on success`() = runTest {
        val state = mockk<AgentThread>()
        val result = mockk<LlmResult<AgentThread>>()
        every { result.isSuccess } returns true
        every { result.get() } returns state
        every { mockJAgent.run("query") } returns result

        assertEquals(state, agent.run("query"))
    }

    @Test
    fun `run throws LLMException on failure`() = runTest {
        val result = mockk<LlmResult<AgentThread>>()
        val err = mockk<LlmException>(relaxed = true)
        every { result.isSuccess } returns false
        every { result.getError() } returns err
        every { err.message } returns "agent failed"
        every { mockJAgent.run("query") } returns result

        assertFailsWith<LLMException> { agent.run("query") }
    }
}
