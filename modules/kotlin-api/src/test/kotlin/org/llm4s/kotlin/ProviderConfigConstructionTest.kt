package org.llm4s.kotlin

import org.llm4s.llmconnect.config.AnthropicConfig
import org.llm4s.llmconnect.config.OllamaConfig
import org.llm4s.llmconnect.config.OpenAIConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Kotlin builds provider configs with the short `apply` and the `with*` setters, never the
 * constructor, so a field added to a config does not break this code (#1388).
 */
class ProviderConfigConstructionTest {

    @Test
    fun `OpenAIConfig is built from the API key and model, then adjusted with setters`() {
        val config = OpenAIConfig.apply("sk-test", "gpt-4o")
            .withOrganization("org-1")
            .withBaseUrl("https://proxy.example.com/v1")
            .withContextWindow(64000)

        assertEquals("gpt-4o", config.model())
        assertEquals(scala.Option.apply("org-1"), config.organization())
        assertEquals("https://proxy.example.com/v1", config.baseUrl())
        assertEquals(64000, config.contextWindow())
        assertEquals(
            scala.Option.empty<String>(),
            config.withOrganization(scala.Option.empty<String>()).organization(),
        )
    }

    @Test
    fun `AnthropicConfig is built from the API key and model, then adjusted with setters`() {
        val config = AnthropicConfig.apply("sk-ant-test", "claude-sonnet-4-5").withReserveCompletion(2048)

        assertEquals(AnthropicConfig.DEFAULT_BASE_URL(), config.baseUrl())
        assertEquals(200000, config.contextWindow())
        assertEquals(2048, config.reserveCompletion())
    }

    @Test
    fun `OllamaConfig is built from the model and base URL, then adjusted with setters`() {
        val config = OllamaConfig.apply("llama3", "http://localhost:11434").withContextWindow(16384)

        assertEquals("llama3", config.model())
        assertEquals(16384, config.contextWindow())
    }
}
