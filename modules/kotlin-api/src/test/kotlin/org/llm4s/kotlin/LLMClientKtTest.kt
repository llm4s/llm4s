package org.llm4s.kotlin

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.llm4s.java.JLlmClient
import org.llm4s.java.LlmException
import org.llm4s.java.LlmResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LLMClientKtTest {

    private val mockJClient = mockk<JLlmClient>()
    private val client = LLMClientKt(mockJClient)

    @Test
    fun `complete returns text on success`() = runTest {
        val result = mockk<LlmResult<String>>()
        every { result.isSuccess } returns true
        every { result.get() } returns "Hello LLM!"
        every { mockJClient.complete("hello") } returns result

        assertEquals("Hello LLM!", client.complete("hello"))
    }

    @Test
    fun `complete throws LLMException on failure`() = runTest {
        val result = mockk<LlmResult<String>>()
        val err = mockk<LlmException>(relaxed = true)
        every { result.isSuccess } returns false
        every { result.getError() } returns err
        every { err.message } returns "provider error"
        every { mockJClient.complete("hello") } returns result

        assertFailsWith<LLMException> { client.complete("hello") }
    }

    @Test
    fun `streamComplete emits single text chunk on success`() = runTest {
        val result = mockk<LlmResult<String>>()
        every { result.isSuccess } returns true
        every { result.get() } returns "streamed response"
        every { mockJClient.complete("prompt") } returns result

        val items = client.streamComplete("prompt").toList()
        assertEquals(listOf("streamed response"), items)
    }

    @Test
    fun `streamComplete propagates LLMException from complete`() = runTest {
        val result = mockk<LlmResult<String>>()
        val err = mockk<LlmException>(relaxed = true)
        every { result.isSuccess } returns false
        every { result.getError() } returns err
        every { err.message } returns "LLM failed"
        every { mockJClient.complete("prompt") } returns result

        assertFailsWith<LLMException> {
            client.streamComplete("prompt").collect()
        }
    }

    @Test
    fun `close delegates to underlying JLlmClient`() {
        every { mockJClient.close() } just Runs
        client.close()
        verify { mockJClient.close() }
    }
}
