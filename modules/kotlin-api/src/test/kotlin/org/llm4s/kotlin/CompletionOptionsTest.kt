package org.llm4s.kotlin

import io.mockk.every
import io.mockk.mockk
import org.llm4s.javaapi.ConversationBuilder
import org.llm4s.javaapi.JCompletionOptions
import org.llm4s.javaapi.JLlmClient
import org.llm4s.javaapi.JReasoningEffort
import org.llm4s.javaapi.LlmResult
import java.util.Optional
import java.util.OptionalInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Kotlin sets completion options with `llm4s-java-api`'s `JCompletionOptions` builder: no `scala.Option`, and the
 * reasoning level is a Java enum a `when` covers (#1488).
 */
class CompletionOptionsTest {

    private val everySetting: JCompletionOptions = JCompletionOptions.builder()
        .temperature(0.2)
        .topP(0.9)
        .maxTokens(512)
        .presencePenalty(0.5)
        .frequencyPenalty(-0.5)
        .reasoning(JReasoningEffort.HIGH)
        .budgetTokens(4096)
        .build()

    @Test
    fun `every setting reads back with Java types`() {
        assertEquals(0.2, everySetting.temperature())
        assertEquals(0.9, everySetting.topP())
        assertEquals(OptionalInt.of(512), everySetting.maxTokens())
        assertEquals(0.5, everySetting.presencePenalty())
        assertEquals(-0.5, everySetting.frequencyPenalty())
        assertEquals(Optional.of(JReasoningEffort.HIGH), everySetting.reasoning())
        assertEquals(OptionalInt.of(4096), everySetting.budgetTokens())
    }

    @Test
    fun `an empty Optional clears a value`() {
        val cleared = everySetting.toBuilder()
            .maxTokens(OptionalInt.empty())
            .reasoning(Optional.empty())
            .budgetTokens(OptionalInt.empty())
            .build()

        assertEquals(OptionalInt.empty(), cleared.maxTokens())
        assertEquals(Optional.empty(), cleared.reasoning())
        assertEquals(OptionalInt.empty(), cleared.budgetTokens())
        assertEquals(0.2, cleared.temperature())
    }

    @Test
    fun `a when over the reasoning level covers every one`() {
        val names = JReasoningEffort.entries.map { effort ->
            when (effort) {
                JReasoningEffort.NONE -> "none"
                JReasoningEffort.LOW -> "low"
                JReasoningEffort.MEDIUM -> "medium"
                JReasoningEffort.HIGH -> "high"
            }
        }
        assertEquals(listOf("none", "low", "medium", "high"), names)
    }

    @Test
    fun `a value no provider accepts throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> { JCompletionOptions.builder().temperature(-1.0) }
        assertFailsWith<IllegalArgumentException> { JCompletionOptions.builder().maxTokens(0) }
    }

    @Test
    fun `the options are passed to JLlmClient complete`() {
        val client = mockk<JLlmClient>()
        val conversation = ConversationBuilder.create().user("hi").build()
        val result = mockk<LlmResult<String>>()
        every { result.get() } returns "4"
        every { client.complete(conversation, everySetting) } returns result

        assertEquals("4", client.complete(conversation, everySetting).get())
    }
}
