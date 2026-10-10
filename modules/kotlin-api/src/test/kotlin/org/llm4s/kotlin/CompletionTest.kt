package org.llm4s.kotlin

import org.llm4s.error.AuthenticationError
import org.llm4s.error.LLMError
import org.llm4s.error.RateLimitError
import org.llm4s.error.ServiceError
import org.llm4s.javaapi.ConversationBuilder
import org.llm4s.javaapi.JCompletion
import org.llm4s.javaapi.JLlmClient
import org.llm4s.javaapi.LlmErrorKind
import org.llm4s.javaapi.LlmException
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.llmconnect.model.Citation
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.StreamedChunk
import org.llm4s.llmconnect.model.TokenUsage
import org.llm4s.llmconnect.model.ToolCall
import scala.Function1
import scala.Option
import scala.concurrent.duration.FiniteDuration
import scala.runtime.BoxedUnit
import scala.util.Either
import scala.util.Left
import scala.util.Right
import java.math.BigDecimal
import java.time.Duration
import java.util.Optional
import java.util.OptionalInt
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A model that records each conversation and options it is sent, and answers [reply]. */
internal class RecordingClient(private val reply: Either<LLMError, Completion>) : LLMClient {
    val sent = mutableListOf<Pair<Conversation, CompletionOptions>>()

    override fun complete(conversation: Conversation, options: CompletionOptions): Either<LLMError, Completion> {
        sent += conversation to options
        return reply
    }

    override fun streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: Function1<StreamedChunk, BoxedUnit>,
    ): Either<LLMError, Completion> = complete(conversation, options)

    override fun getContextWindow(): Int = 4096
    override fun getReserveCompletion(): Int = 512
}

/** A reply with text, a model, usage, a cost and a tool call; Scala types appear only in building it. */
internal fun reply(text: String = "Let me check.", cost: Option<Any> = Option.apply(0.0015)): Completion =
    Completion.apply(
        "reply-1", 0L, text, "gpt-4o-2024-08-06", AssistantMessage.apply(text),
        scala.jdk.javaapi.CollectionConverters.asScala(listOf(ToolCall("c1", "weather", ujson.Str("Paris")))).toList(),
        Option.apply(TokenUsage.apply(12, 5, 17, Option.empty(), Option.apply<Any>(8), Option.apply<Any>(3))),
        cost,
        scala.jdk.javaapi.CollectionConverters.asScala(listOf<Citation>()).toList(),
    )

/**
 * Kotlin reads `JLlmClient.completion`'s [JCompletion] and [LlmException]'s error kind with Java types only (#1487):
 * the reply's model, usage, cost and tool calls, and a `when` over [LlmErrorKind] with no `else`.
 */
class CompletionTest {

    @Test
    fun `completion returns the model, usage, cost and tool calls of the reply`() {
        val model = RecordingClient(Right(reply()))
        val answer: JCompletion = JLlmClient(model).completion("Weather in Paris?").get()

        assertEquals("Let me check.", answer.content())
        assertEquals("gpt-4o-2024-08-06", answer.model())
        val usage = answer.usage().orElseThrow()
        assertEquals(listOf(12, 5, 17, 0), listOf(usage.promptTokens(), usage.completionTokens(), usage.totalTokens(), usage.thinkingTokens()))
        assertEquals(8 to 3, usage.cachedTokens() to usage.cacheCreationTokens())
        assertEquals(Optional.of(BigDecimal("0.0015")), answer.estimatedCost())
        assertEquals(listOf(Triple("c1", "weather", "\"Paris\"")), answer.toolCalls().map { Triple(it.id(), it.name(), it.argumentsJson()) })
        assertEquals(1, model.sent.size)
    }

    @Test
    fun `an unknown cost is an empty Optional`() {
        val answer = JLlmClient(RecordingClient(Right(reply(cost = Option.empty())))).completion("q").get()
        assertEquals(Optional.empty(), answer.estimatedCost())
    }

    @Test
    fun `completion with options sends them`() {
        val model = RecordingClient(Right(reply()))
        val conversation = ConversationBuilder.create().user("hi").build()
        val options = org.llm4s.javaapi.JCompletionOptions.builder().maxTokens(64).build()

        assertEquals("Let me check.", JLlmClient(model).completion(conversation, options).get().content())
        assertEquals(Option.apply<Any>(64), model.sent.single().second.maxTokens())
    }

    private fun kindName(kind: LlmErrorKind): String = when (kind) {
        LlmErrorKind.AUTHENTICATION -> "authentication"
        LlmErrorKind.RATE_LIMIT -> "rate-limit"
        LlmErrorKind.TIMEOUT -> "timeout"
        LlmErrorKind.NETWORK -> "network"
        LlmErrorKind.SERVICE -> "service"
        LlmErrorKind.VALIDATION -> "validation"
        LlmErrorKind.CONFIGURATION -> "configuration"
        LlmErrorKind.CANCELLED -> "cancelled"
        LlmErrorKind.OTHER -> "other"
    }

    private fun failureOf(error: LLMError): LlmException =
        JLlmClient(RecordingClient(Left(error))).completion("q").error

    @Test
    fun `a when over the error kind covers every one`() {
        assertEquals(LlmErrorKind.entries.size, LlmErrorKind.entries.map(::kindName).toSet().size)
    }

    @Test
    fun `a failed completion reads its kind, recoverability, retry delay and status as properties`() {
        val limited = failureOf(RateLimitError.apply("openai", FiniteDuration.apply(3L, TimeUnit.SECONDS)))
        assertEquals("rate-limit", kindName(limited.kind))
        assertTrue(limited.isRecoverable)
        assertEquals(Optional.of(Duration.ofSeconds(3)), limited.retryAfter)
        assertEquals(OptionalInt.empty(), limited.statusCode)

        val overloaded = failureOf(ServiceError.apply(503, "openai", "overloaded"))
        assertEquals(LlmErrorKind.SERVICE, overloaded.kind)
        assertEquals(OptionalInt.of(503), overloaded.statusCode)

        val badKey = failureOf(AuthenticationError.apply("openai", "bad key"))
        assertEquals(LlmErrorKind.AUTHENTICATION, badKey.kind)
        assertFalse(badKey.isRecoverable)
    }
}
