package org.llm4s.kotlin

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.llm4s.java.JAgent
import org.llm4s.java.JLlmClient
import org.llm4s.java.LlmResult
import org.llm4s.java.Llm4s as JLlm4s
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests [DefaultClientFactory] by mocking the Scala static forwarders on
 * [org.llm4s.java.Llm4s] so no real LLM credentials are needed.
 */
class DefaultClientFactoryTest {

    @BeforeTest
    fun setUp() {
        mockkStatic(JLlm4s::class)
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic(JLlm4s::class)
    }

    @Test
    fun `createDefault delegates to JLlm4s createDefaultClient`() {
        val expected = mockk<LlmResult<JLlmClient>>()
        every { JLlm4s.createDefaultClient() } returns expected

        val result = DefaultClientFactory.createDefault()
        assertEquals(expected, result)
    }

    @Test
    fun `createAgent delegates to JLlm4s createAgent`() {
        val jClient = mockk<JLlmClient>()
        val expected = mockk<JAgent>()
        every { JLlm4s.createAgent(jClient) } returns expected

        val agent = DefaultClientFactory.createAgent(jClient)
        assertEquals(expected, agent)
    }
}
