package org.llm4s.kotlin

import kotlinx.coroutines.runBlocking
import org.llm4s.error.LLMError
import org.llm4s.javaapi.JCompletionOptions
import org.llm4s.javaapi.JReasoningEffort
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.llmconnect.model.Citation
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.StreamedChunk
import org.llm4s.metrics.MetricsCollector
import scala.Function1
import scala.Option
import scala.jdk.javaapi.CollectionConverters
import scala.runtime.BoxedUnit
import scala.util.Either
import scala.util.Right
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import org.llm4s.javaapi.Llm4s as JLlm4s

/**
 * The options and client DSL (#1495): [completionOptions] builds the [JCompletionOptions] the Java builder does and
 * reaches the model through [LLMClientKt.complete], and each client block of [Llm4s] is the Java builder for its
 * provider.
 */
class OptionsAndClientDslTest {

    @Test
    fun `completionOptions sets every setting as the Java builder does`() {
        val dsl = completionOptions {
            temperature = 0.2
            topP = 0.9
            maxTokens = 200
            presencePenalty = 0.5
            frequencyPenalty = -0.5
            reasoning = JReasoningEffort.HIGH
            budgetTokens = 4096
        }
        val java = JCompletionOptions.builder()
            .temperature(0.2).topP(0.9).maxTokens(200).presencePenalty(0.5).frequencyPenalty(-0.5)
            .reasoning(JReasoningEffort.HIGH).budgetTokens(4096)
            .build()
        assertEquals(java, dsl)
    }

    @Test
    fun `an unset setting keeps the base's value, or core's default without one`() {
        val base = completionOptions { temperature = 0.1; maxTokens = 50 }
        assertEquals(base.toBuilder().maxTokens(10).build(), completionOptions(base) { maxTokens = 10 })
        assertEquals(JCompletionOptions.builder().build(), completionOptions { })
    }

    @Test
    fun `a value no provider accepts throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> { completionOptions { temperature = -1.0 } }
        assertFailsWith<IllegalArgumentException> { completionOptions { maxTokens = 0 } }
    }

    @Test
    fun `complete sends the query with the DSL's options`() = runBlocking {
        val seen = AtomicReference<Pair<Conversation, CompletionOptions>>()
        val model = object : LLMClient {
            override fun complete(conversation: Conversation, options: CompletionOptions): Either<LLMError, Completion> {
                seen.set(conversation to options)
                return Right(
                    Completion.apply(
                        "id", 0L, "blue", "m",
                        AssistantMessage.apply(Option.apply("blue"), CollectionConverters.asScala(listOf<org.llm4s.llmconnect.model.ToolCall>()).toList(), AssistantMessage.`apply$default$3`()),
                        CollectionConverters.asScala(listOf<org.llm4s.llmconnect.model.ToolCall>()).toList(),
                        Option.empty(), Option.empty(),
                        CollectionConverters.asScala(listOf<Citation>()).toList(),
                    ),
                )
            }

            override fun streamComplete(
                conversation: Conversation,
                options: CompletionOptions,
                onChunk: Function1<StreamedChunk, BoxedUnit>,
            ): Either<LLMError, Completion> = complete(conversation, options)

            override fun getContextWindow(): Int = 4096
            override fun getReserveCompletion(): Int = 256
        }
        val client = Llm4s.wrapClient(JLlm4s.wrapClient(model))

        val reply = client.complete("Name a colour", completionOptions { temperature = 0.2; maxTokens = 20 })

        assertEquals("blue", reply)
        val (conversation, options) = seen.get()
        assertEquals("Name a colour", conversation.messages().last().content())
        assertEquals(0.2, options.temperature())
        assertEquals(20, options.maxTokens().get())
    }

    private val metrics: MetricsCollector = MetricsCollector.noop()

    @Test
    fun `each client block is the Java builder for its provider`() {
        val openAi = OpenAiSpec().apply {
            apiKey = "k"; model = "gpt-4o-mini"; baseUrl = "https://example.test/v1"; organization = "org"
            contextWindow = 1000; reserveCompletion = 100; requestTimeout = 30.seconds; streamTimeout = 5.seconds
            this.metrics = this@OptionsAndClientDslTest.metrics
        }
        assertEquals(
            JLlm4s.openAi().apiKey("k").model("gpt-4o-mini").baseUrl("https://example.test/v1").organization("org")
                .contextWindow(1000).reserveCompletion(100).requestTimeout(30.seconds.toJavaDuration())
                .streamTimeout(5.seconds.toJavaDuration()).metrics(metrics).toString(),
            openAi.toBuilder().toString(),
        )

        val anthropic = AnthropicSpec().apply { apiKey = "k"; model = "claude"; baseUrl = "https://a.test"; contextWindow = 1; reserveCompletion = 0 }
        assertEquals(
            JLlm4s.anthropic().apiKey("k").model("claude").baseUrl("https://a.test").contextWindow(1).reserveCompletion(0).toString(),
            anthropic.toBuilder().toString(),
        )

        val gemini = GeminiSpec().apply { apiKey = "k"; model = "gemini"; baseUrl = "https://g.test"; contextWindow = 2; reserveCompletion = 1 }
        assertEquals(
            JLlm4s.gemini().apiKey("k").model("gemini").baseUrl("https://g.test").contextWindow(2).reserveCompletion(1).toString(),
            gemini.toBuilder().toString(),
        )

        val ollama = OllamaSpec().apply { model = "llama3.2"; baseUrl = "http://o.test:11434"; contextWindow = 3; reserveCompletion = 2 }
        assertEquals(
            JLlm4s.ollama().model("llama3.2").baseUrl("http://o.test:11434").contextWindow(3).reserveCompletion(2).toString(),
            ollama.toBuilder().toString(),
        )

        val compatible = OpenAiCompatibleSpec().apply {
            apiKey = "k"; model = "m"; baseUrl = "https://c.test/v1"; contextWindow = 4; reserveCompletion = 3
            header("X-Tenant", "a"); header("X-Team", "b")
        }
        assertEquals(
            JLlm4s.openAiCompatible().apiKey("k").model("m").baseUrl("https://c.test/v1").contextWindow(4).reserveCompletion(3)
                .header("X-Tenant", "a").header("X-Team", "b").toString(),
            compatible.toBuilder().toString(),
        )

        val azure = AzureOpenAiSpec().apply {
            endpoint = "https://r.openai.azure.com"; apiKey = "k"; model = "dep"; apiVersion = "2024-10-21"
            contextWindow = 5; reserveCompletion = 4
        }
        assertEquals(
            JLlm4s.azureOpenAi().endpoint("https://r.openai.azure.com").apiKey("k").model("dep").apiVersion("2024-10-21")
                .contextWindow(5).reserveCompletion(4).toString(),
            azure.toBuilder().toString(),
        )
    }

    @Test
    fun `each client block builds a client without a request`() {
        val clients = listOf(
            Llm4s.openAi { apiKey = "k"; model = "gpt-4o-mini" },
            Llm4s.anthropic { apiKey = "k"; model = "claude-sonnet-4-20250514" },
            Llm4s.gemini { apiKey = "k"; model = "gemini-2.0-flash" },
            Llm4s.ollama { model = "llama3.2" },
            Llm4s.openAiCompatible { baseUrl = "http://localhost:1/v1"; model = "m" },
            Llm4s.azureOpenAi { endpoint = "https://r.openai.azure.com"; apiKey = "k"; model = "dep" },
        )
        clients.forEach { it.close() }
        assertEquals(6, clients.size)
    }

    @Test
    fun `a required field left out throws LLMException naming it`() {
        val missing = assertFailsWith<LLMException> { Llm4s.openAi { apiKey = "k" } }
        assertTrue(missing.message!!.contains("model"), missing.message)
    }
}
