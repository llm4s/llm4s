package org.llm4s.kotlin

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.llm4s.agent.events.AgentEvents
import org.llm4s.error.LLMError
import org.llm4s.javaapi.AgentStatusKind
import org.llm4s.javaapi.FakeLlmClient
import org.llm4s.javaapi.JGuardrail
import org.llm4s.javaapi.JGuardrails
import org.llm4s.javaapi.StreamEvents
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.llmconnect.model.Citation
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.Message
import org.llm4s.llmconnect.model.StreamedChunk
import org.llm4s.llmconnect.model.SystemMessage
import org.llm4s.llmconnect.model.ToolCall
import org.llm4s.llmconnect.model.UserMessage
import scala.Function1
import scala.Option
import scala.jdk.javaapi.CollectionConverters
import scala.runtime.BoxedUnit
import scala.util.Either
import scala.util.Right
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The [agent] block (#2534): each setting reaches the agent the Java facade's `Llm4s.agentBuilder` builds, and a
 * problem is a failed [Result], never a throw. Only the model is scripted.
 */
class AgentDslTest {

    /** One model call as the model saw it. */
    private data class Call(val messages: List<Message>, val tools: List<String>, val streamed: Boolean) {
        val system: List<String> get() = messages.filterIsInstance<SystemMessage>().map { it.content() }
        val users: List<String> get() = messages.filterIsInstance<UserMessage>().map { it.content() }
    }

    /** A model that answers [answer] to every call and records what each call was sent. */
    private class Recording(private val answer: String) : LLMClient {
        val calls = CopyOnWriteArrayList<Call>()
        val last: Call get() = calls.last()

        private fun reply(c: Conversation, o: CompletionOptions, streamed: Boolean): Either<LLMError, Completion> {
            calls.add(
                Call(
                    CollectionConverters.asJava(c.messages()).toList(),
                    CollectionConverters.asJava(o.tools()).map { it.name() },
                    streamed,
                ),
            )
            return Right(
                Completion.apply(
                    "id", Instant.EPOCH, answer, "m", AssistantMessage.apply(answer),
                    CollectionConverters.asScala(listOf<ToolCall>()).toList(),
                    Option.empty(), Option.empty(),
                    CollectionConverters.asScala(listOf<Citation>()).toList(),
                ),
            )
        }

        override fun complete(conversation: Conversation, options: CompletionOptions): Either<LLMError, Completion> =
            reply(conversation, options, streamed = false)

        override fun streamComplete(
            conversation: Conversation,
            options: CompletionOptions,
            onChunk: Function1<StreamedChunk, BoxedUnit>,
        ): Either<LLMError, Completion> {
            onChunk.apply(StreamedChunk.apply("c", Option.apply(answer), Option.empty(), Option.empty(), Option.empty()))
            return reply(conversation, options, streamed = true)
        }

        override fun getContextWindow(): Int = 4096
        override fun getReserveCompletion(): Int = 512
    }

    private fun client(model: LLMClient) = LLMClientKt(org.llm4s.javaapi.Llm4s.wrapClient(model))

    private val add = tool("add", "Adds two numbers") {
        param<Long>("a", "First")
        param<Long>("b", "Second")
        handler { args -> args.get<Long>("a") + args.get<Long>("b") }
    }

    private fun failureOf(built: Result<AgentKt>): String =
        assertIs<LLMException>(built.exceptionOrNull(), "expected a failed build, got $built").message.orEmpty()

    // ---- each setting reaches the agent ----------------------------------------------------------

    @Test
    fun `the block gives the agent its system prompt, tools and streaming`() = runBlocking {
        val model = Recording("""{"sum": 5}""")
        val support = agent("support", client(model)) {
            systemPrompt("You are a support assistant.")
            tools {
                tool("add", "Adds two numbers") {
                    param<Long>("a")
                    handler { args -> args.get<Long>("a") }
                }
            }
            streaming()
        }.getOrThrow()
        val result = support.run("What is 2 + 3?")
        assertEquals(AgentStatusKind.COMPLETED, result.status().kind())
        assertEquals(Optional.of("""{"sum": 5}"""), result.answer())
        assertEquals(listOf("You are a support assistant."), model.last.system)
        assertEquals(listOf("add"), model.last.tools)
        assertTrue(model.last.streamed)
    }

    @Test
    fun `an agent with nothing configured has no prompt, no tools, and calls complete`() = runBlocking {
        val model = Recording("hi")
        val plain = agent("plain", client(model)).getOrThrow()
        assertEquals(Optional.of("hi"), plain.run("hello").answer())
        assertEquals(emptyList(), model.last.system)
        assertEquals(emptyList(), model.last.tools)
        assertFalse(model.last.streamed)
    }

    @Test
    fun `a later setting replaces an earlier one`() = runBlocking {
        val model = Recording("ok")
        val agent = agent("a", client(model)) {
            systemPrompt("first")
            systemPrompt("second")
            tools(add)
            tools(listOf())
            streaming()
            streaming(false)
        }.getOrThrow()
        agent.run("hi")
        assertEquals(listOf("second"), model.last.system)
        assertEquals(emptyList(), model.last.tools)
        assertFalse(model.last.streamed)
    }

    @Test
    fun `a streaming agent's flow carries the answer's text deltas`() = runBlocking {
        val agent = agent("s", client(Recording("hello"))) { streaming() }.getOrThrow()
        val deltas = agent.stream("t1", "hi").toList().mapNotNull { item ->
            (item as? AgentStreamItem.Event)?.let { StreamEvents.decode(AgentEvents.TextDelta(), it.event).orElse(null)?.text() }
        }
        assertEquals(listOf("hello"), deltas)
    }

    // ---- guardrails ------------------------------------------------------------------------------

    @Test
    fun `an input guardrail ends a turn BLOCKED before the model is called`() = runBlocking {
        val fake = FakeLlmClient.replying("ok", "ok")
        val agent = agent("g", LLMClientKt(fake.client())) {
            inputGuardrails(JGuardrails.length(1, 8), JGuardrails.profanity())
        }.getOrThrow()
        val long = agent.run("far too long")
        assertEquals(AgentStatusKind.BLOCKED, long.status().kind())
        assertEquals(Optional.of("LengthCheck"), long.status().guardrail())
        assertEquals("ProfanityFilter", agent.run("badword").status().guardrail().orElseThrow())
        assertEquals(0, fake.callCount())
        assertEquals(AgentStatusKind.COMPLETED, agent.run("fine").status().kind())
        assertEquals(listOf("fine"), fake.prompts())
    }

    @Test
    fun `input guardrails from a list run in order, with a pattern's own message`() = runBlocking {
        val agent = agent("r", LLMClientKt(FakeLlmClient.replying("ok").client())) {
            inputGuardrails(listOf(JGuardrails.regex("^1", "must start with 1")))
        }.getOrThrow()
        val blocked = agent.run("2").status()
        assertEquals(Optional.of("RegexValidator"), blocked.guardrail())
        assertTrue(blocked.reason().orElseThrow().contains("must start with 1"))
    }

    @Test
    fun `an output guardrail blocks an answer that fails it and keeps one that passes`() = runBlocking {
        val prose = agent("j", client(Recording("five"))) { outputGuardrails(JGuardrails.json()) }.getOrThrow()
        val result = prose.run("Answer in JSON")
        assertEquals(AgentStatusKind.BLOCKED, result.status().kind())
        assertEquals(Optional.of("JSONValidator"), result.status().guardrail())
        assertEquals(Optional.empty(), result.answer())

        val json = agent("j", client(Recording("[1, 2]"))) {
            outputGuardrails(listOf(JGuardrails.json(), JGuardrails.length(1, 100)))
        }.getOrThrow()
        assertEquals(Optional.of("[1, 2]"), json.run("Answer in JSON").answer())
    }

    @Test
    fun `an answer-only guardrail is not an input guardrail`() {
        // inputGuardrails(JGuardrails.json()) does not compile: json() is a JOutputGuardrail, not a JGuardrail
        assertFalse((JGuardrails.json() as Any) is JGuardrail)
        val inputs = AgentSpec::class.java.methods.filter { it.name == "inputGuardrails" }
        assertEquals(2, inputs.size)
        inputs.forEach { m ->
            val type = m.genericParameterTypes.single().typeName
            assertTrue(type.contains(JGuardrail::class.java.name), type)
        }
    }

    // ---- the context window ----------------------------------------------------------------------

    @Test
    fun `a context window prunes what the model is sent, not what the conversation keeps`() = runBlocking {
        val model = Recording("ok")
        val agent = agent("w", client(model)) {
            systemPrompt("Be brief.")
            contextWindow(lastMessages = 3)
        }.getOrThrow()
        val third = agent.continueConversation(agent.continueConversation(agent.run("one"), "two"), "three")
        assertEquals(listOf("two", "three"), model.last.users)
        assertEquals(listOf("Be brief."), model.last.system)
        assertIs<SystemMessage>(model.last.messages.first())
        assertTrue(third.messages().map { it.content() }.containsAll(listOf("one", "two", "three")))
    }

    // ---- problems are values ---------------------------------------------------------------------

    @Test
    fun `each problem is a failed result naming it, never a throw`() {
        val c = client(Recording("x"))
        assertTrue(failureOf(agent(" ", c)).isNotEmpty())
        assertTrue(failureOf(agent("a", c) { contextWindow(lastMessages = 0) }).contains("messages"))
        assertTrue(failureOf(agent("a", c) { inputGuardrails(JGuardrails.length(5, 1)) }).contains("max"))
        assertTrue(failureOf(agent("a", c) { outputGuardrails(JGuardrails.length(-1, 1)) }).contains("min"))
        assertTrue(failureOf(agent("a", c) { tools(add, add) }).contains("two tools are named 'add'"))
    }

    @Test
    fun `a tool the tools block cannot build fails the result, the first such failure kept`() {
        val built = agent("a", client(Recording("x"))) {
            tools { tool("first", "No handler") {} }
            tools { tool(" ", "Blank") { handler { "x" } } }
            tools(add)
        }
        val first = failureOf(built)
        assertTrue(first.isNotEmpty())
        assertEquals(first, failureOf(agent("a", client(Recording("x"))) { tools { tool("first", "No handler") {} } }))
    }

    // ---- the guide's block -----------------------------------------------------------------------

    @Test
    fun `the guide's agent block completes with a JSON answer and reports a blocked one`() = runBlocking {
        assertEquals(AgentStatusKind.COMPLETED, KotlinGuideSnippets.supportAgent(client(Recording("""{"answer": 5}"""))).status().kind())
        val blocked = KotlinGuideSnippets.supportAgent(client(Recording("five")))
        assertEquals(Optional.of("JSONValidator"), blocked.status().guardrail())
    }
}
