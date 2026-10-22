package org.llm4s.kotlin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.llm4s.error.LLMError
import org.llm4s.javaapi.JMessageRole
import org.llm4s.javaapi.JTool
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.llmconnect.model.Citation
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.StreamedChunk
import org.llm4s.llmconnect.model.ToolCall
import scala.Function1
import scala.Option
import scala.jdk.javaapi.CollectionConverters
import scala.runtime.BoxedUnit
import scala.util.Either
import scala.util.Right
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.llm4s.javaapi.Llm4s as JLlm4s
import java.time.Instant

/**
 * The Kotlin tool DSL (#1495): [tool] and [tools] build the Java facade's [JTool]s, a scripted agent calls them with
 * typed arguments and the call's idempotency key (#1740), and cancelling the coroutine that runs the turn cancels a
 * suspended handler.
 */
class ToolDslTest {

    enum class Unit2 { CELSIUS, FAHRENHEIT }

    private fun parse(text: String): ujson.Value = ujson.`package`.read(ujson.Readable.fromString(text), false)

    private fun <T> seq(items: List<T>): scala.collection.immutable.Seq<T> = CollectionConverters.asScala(items).toSeq()

    private fun call(id: String, name: String, args: String): ToolCall = ToolCall.apply(id, name, parse(args))

    private fun completion(text: String, calls: List<ToolCall> = emptyList()): Either<LLMError, Completion> =
        Right(
            Completion.apply(
                "id", Instant.EPOCH, text, "m",
                AssistantMessage.apply(if (calls.isEmpty()) Option.apply(text) else Option.empty(), seq(calls), AssistantMessage.`apply$default$3`()),
                CollectionConverters.asScala(calls).toList(),
                Option.empty(), Option.empty(),
                CollectionConverters.asScala(listOf<Citation>()).toList(),
            ),
        )

    /** A model answering each call with the next of [replies], then `done`. */
    private class Scripted(private val replies: List<Either<LLMError, Completion>>, private val done: Either<LLMError, Completion>) : LLMClient {
        private val next = AtomicInteger()
        private fun reply(): Either<LLMError, Completion> = replies.getOrNull(next.getAndIncrement()) ?: done
        override fun complete(conversation: Conversation, options: CompletionOptions): Either<LLMError, Completion> = reply()
        override fun streamComplete(
            conversation: Conversation,
            options: CompletionOptions,
            onChunk: Function1<StreamedChunk, BoxedUnit>,
        ): Either<LLMError, Completion> = reply()
        override fun getContextWindow(): Int = 4096
        override fun getReserveCompletion(): Int = 256
    }

    /** A client whose model calls [calls] once, then answers `done`. */
    private fun clientCalling(vararg calls: ToolCall): LLMClientKt =
        Llm4s.wrapClient(JLlm4s.wrapClient(Scripted(listOf(completion("", calls.toList())), completion("done"))))

    @Test
    fun `a DSL tool is the JTool the Java builder makes`() {
        val dsl = tool("add", "Adds two numbers") {
            param<Long>("a", "First")
            param<Long>("b", "Second")
            optionalParam<String>("note", "A note")
            handler { args -> args.get<Long>("a") + args.get<Long>("b") }
        }
        val java = JTool.builder("add", "Adds two numbers")
            .param("a", java.lang.Long::class.java, "First")
            .param("b", java.lang.Long::class.java, "Second")
            .optionalParam("note", String::class.java, "A note")
            .handler { args -> args.getLong("a") + args.getLong("b") }
            .build()
            .get()

        assertEquals(java.name(), dsl.name())
        assertEquals(java.description(), dsl.description())
        assertEquals(java.function().toOpenAITool(true), dsl.function().toOpenAITool(true))
        assertEquals(parse("5"), dsl.function().execute(parse("""{"a":2,"b":3}""")).toOption().get())
    }

    @Test
    fun `a scripted agent calls a DSL tool with typed arguments and the call's idempotency key`() = runBlocking {
        val seen = CopyOnWriteArrayList<ToolArgs>()
        val agent = Llm4s.createAgent(
            clientCalling(call("c1", "add", """{"a":2,"b":3,"unit":"CELSIUS","scale":1.5,"exact":true}""")),
            tools {
                tool("add", "Adds two numbers") {
                    param<Long>("a", "First")
                    param<Long>("b", "Second")
                    param<Unit2>("unit")
                    param<Double>("scale")
                    param<Boolean>("exact")
                    optionalParam<String>("note", "A note")
                    handler { args ->
                        seen += args
                        delay(1) // a suspending handler
                        args.get<Long>("a") + args.get<Long>("b")
                    }
                }
            },
        )

        val result = agent.run("What is 2 + 3?")

        assertEquals("done", result.answer().get())
        val args = seen.single()
        assertEquals(2L, args.get<Long>("a"))
        assertEquals(Unit2.CELSIUS, args.get<Unit2>("unit"))
        assertEquals(1.5, args.get<Double>("scale"))
        assertEquals(true, args.get<Boolean>("exact"))
        assertEquals("c1", args.toolCallId)
        assertNotNull(args.idempotencyKey)
        assertEquals(args.java.idempotencyKey().get(), args.idempotencyKey)
        assertTrue(args.has("a"))
        assertFalse(args.has("note"))
        assertNull(args.getOrNull<String>("note"))
        assertFailsWith<NoSuchElementException> { args.get<String>("note") }
        assertEquals(setOf("a", "b", "unit", "scale", "exact"), args.asMap().keys)
        assertTrue(args.toString().startsWith("ToolArgs("))
        val toolMessage = result.messages().single { it.role() == JMessageRole.TOOL }
        assertEquals("5", toolMessage.content())
    }

    @Test
    fun `tools keeps a tool built elsewhere, in order`() {
        val annotated = JTool.builder("echo", "Echoes").param("text", String::class.java, "Text").handler { it.getString("text") }.build().get()
        val all = tools {
            add(annotated)
            tool("upper", "Upper-cases") {
                param<String>("text")
                handler { args -> args.get<String>("text").uppercase() }
            }
        }
        assertEquals(listOf("echo", "upper"), all.map { it.name() })
    }

    @Test
    fun `outside an agent run a tool has no call id or idempotency key`() {
        val seen = CopyOnWriteArrayList<ToolArgs>()
        val echo = tool("echo", "Echoes") {
            param("text", String::class)
            optionalParam("times", Int::class)
            handler { args -> seen += args; args.getOrNull("text", String::class) }
        }
        echo.function().execute(parse("""{"text":"hi","times":2}"""))
        val args = seen.single()
        assertNull(args.toolCallId)
        assertNull(args.idempotencyKey)
        assertEquals(2, args.get<Int>("times"))
    }

    @Test
    fun `a tool that cannot be built, and two tools with one name, throw LLMException`() {
        val noHandler = assertFailsWith<LLMException> { tool("broken", "No handler") { param<String>("x") } }
        assertTrue(noHandler.message!!.contains("handler"), noHandler.message)

        val one = tool("same", "One") { handler { "one" } }
        val clash = assertFailsWith<LLMException> { Llm4s.createAgent(clientCalling(), listOf(one, one)) }
        assertTrue(clash.message!!.contains("same"), clash.message)
    }

    @Test
    fun `an exception the handler throws is a tool error the model sees, and the agent keeps running`() = runBlocking {
        val agent = Llm4s.createAgent(
            clientCalling(call("c1", "fail", "{}")),
            tools { tool("fail", "Always fails") { handler { throw IllegalStateException("no luck") } } },
        )
        val result = agent.run("try it")
        assertEquals("done", result.answer().get())
        val toolMessage = result.messages().single { it.role() == JMessageRole.TOOL }
        assertTrue(toolMessage.content().contains("no luck"), toolMessage.content())
    }

    @Test
    fun `cancelling the coroutine that runs the turn cancels a suspended tool handler`() = runBlocking {
        val started = CompletableDeferred<kotlin.Unit>()
        val cancelled = CompletableDeferred<Throwable>()
        val agent = Llm4s.createAgent(
            clientCalling(call("c1", "wait", "{}")),
            tools {
                tool("wait", "Waits forever") {
                    handler {
                        started.complete(kotlin.Unit)
                        try {
                            awaitCancellation()
                        } catch (e: Throwable) {
                            cancelled.complete(e)
                            throw e
                        }
                    }
                }
            },
        )

        val turn = launch(Dispatchers.Default) { agent.run("wait") }
        withTimeout(10.seconds) { started.await() }
        turn.cancelAndJoin()

        val cause = withTimeout(10.seconds) { cancelled.await() }
        assertTrue(cause is kotlinx.coroutines.CancellationException, cause.toString())
        assertTrue(turn.isCancelled)
    }
}
