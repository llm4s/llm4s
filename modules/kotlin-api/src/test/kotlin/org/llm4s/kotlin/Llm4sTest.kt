package org.llm4s.kotlin

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.llm4s.javaapi.JAgent
import org.llm4s.javaapi.JLlmClient
import org.llm4s.javaapi.LlmException
import org.llm4s.javaapi.LlmResult
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class Llm4sTest {

    private val mockFactory = mockk<ClientFactory>()
    private lateinit var originalFactory: ClientFactory

    @BeforeTest
    fun setUp() {
        originalFactory = Llm4s.factory
        Llm4s.factory = mockFactory
    }

    @AfterTest
    fun tearDown() {
        Llm4s.factory = originalFactory
    }

    @Test
    fun `createDefaultClient returns LLMClientKt on success`() {
        val jClient = mockk<JLlmClient>()
        val result = mockk<LlmResult<JLlmClient>>()
        every { result.isSuccess } returns true
        every { result.get() } returns jClient
        every { mockFactory.createDefault() } returns result

        val client = Llm4s.createDefaultClient()
        assertSame(jClient, client.underlying)
    }

    @Test
    fun `createDefaultClient throws LLMException on failure`() {
        val result = mockk<LlmResult<JLlmClient>>()
        val err = mockk<LlmException>(relaxed = true)
        every { result.isSuccess } returns false
        every { result.getError() } returns err
        every { err.message } returns "no provider configured"
        every { mockFactory.createDefault() } returns result

        assertFailsWith<LLMException> { Llm4s.createDefaultClient() }
    }

    @Test
    fun `createAgent wraps JAgent in AgentKt`() {
        val jClient = mockk<JLlmClient>()
        val jAgent = mockk<JAgent>()
        val client = LLMClientKt(jClient)
        every { mockFactory.createAgent(jClient) } returns jAgent

        Llm4s.createAgent(client)
        verify(exactly = 1) { mockFactory.createAgent(jClient) }
    }

    @Test
    fun `DefaultClientFactory is the initial factory`() {
        // Restore original and verify it is DefaultClientFactory
        Llm4s.factory = originalFactory
        assertSame(DefaultClientFactory, Llm4s.factory)
    }
}
