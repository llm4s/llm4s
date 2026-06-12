package org.llm4s.kotlin

import io.mockk.mockk
import org.llm4s.java.LlmException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LLMExceptionTest {

    @Test
    fun `message only constructor sets message and null cause`() {
        val ex = LLMException("test error")
        assertEquals("test error", ex.message)
        assertNull(ex.cause)
    }

    @Test
    fun `message and cause constructor sets both properties`() {
        val cause = mockk<LlmException>(relaxed = true)
        val ex = LLMException("wrapped error", cause)
        assertEquals("wrapped error", ex.message)
        assertEquals(cause, ex.cause)
    }
}
