package org.llm4s.kotlin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.llm4s.agent.Agent
import org.llm4s.agent.AgentResult
import org.llm4s.agent.graph.StateUpdate
import org.llm4s.agent.graph.middleware.AgentMiddleware
import org.llm4s.agent.graph.middleware.ApprovalMiddleware
import org.llm4s.agent.graph.tool.AgentTool
import org.llm4s.agent.graph.tool.AgentToolSpec
import org.llm4s.agent.graph.tool.ToolContext
import org.llm4s.agent.graph.tool.ToolOutcome
import org.llm4s.agent.graph.tool.ToolSet
import org.llm4s.error.LLMError
import org.llm4s.error.NetworkError
import org.llm4s.javaapi.Answer
import org.llm4s.javaapi.InterruptKind
import org.llm4s.javaapi.PendingInterrupt
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.llmconnect.model.Citation
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.StreamedChunk
import org.llm4s.llmconnect.model.ToolCall
import org.llm4s.llmconnect.model.ToolMessage
import org.llm4s.toolapi.Schema
import scala.Function1
import scala.Option
import scala.jdk.javaapi.CollectionConverters
import scala.runtime.BoxedUnit
import scala.util.Either
import scala.util.Left
import scala.util.Right
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [AgentKt.pending], [AgentKt.resume] and [AgentKt.recover] over the real agent runtime, behind the
 * real Java facade: only the model is scripted. `deploy` needs approval; `confirm` asks a question.
 */
class AgentKtPendingTest {

    private val seconds = 60L

    private val json = upickle.default.ReadWriter().join(upickle.default.JsValueR(), upickle.default.JsValueW())

    private fun parse(text: String): ujson.Value = ujson.`package`.read(ujson.Readable.fromString(text), false)

    private fun spec(name: String): AgentToolSpec<ujson.Value> =
        AgentToolSpec.apply(
            name,
            "The $name tool",
            Schema.`object`<ujson.Value>(name).withRequiredField("text", Schema.string("Text")),
            json,
        )

    private fun success(text: String): ToolOutcome = ToolOutcome.Success.apply(ujson.Str(text), StateUpdate.empty())

    /** Approval before it runs, from the agent's middleware; records each run. */
    private val ran = CopyOnWriteArrayList<String>()
    private val deploy: AgentTool<ujson.Value> = AgentTool.apply<ujson.Value>(spec("deploy"), AgentTool.`apply$default$2`<ujson.Value>()) { args: ujson.Value, _: ToolContext ->
        ran.add(args.toString())
        success("deployed")
    }

    /** Asks `{"prompt":"really?"}`, and reports the reply it was given. */
    private val confirm = object : AgentTool.Asking<ujson.Value, ujson.Value, ujson.Value>(spec("confirm"), json, json) {
        override fun execute(args: ujson.Value, context: ToolContext): ToolOutcome =
            ask(parse("""{"prompt":"really?"}"""))

        override fun resume(args: ujson.Value, question: ujson.Value, answer: ujson.Value, context: ToolContext): ToolOutcome =
            success("confirmed $answer")
    }

    private fun <T> seq(vararg items: T): scala.collection.immutable.Seq<T> = CollectionConverters.asScala(items.toList()).toSeq()

    private fun call(id: String, name: String, text: String): ToolCall = ToolCall.apply(id, name, parse("""{"text":"$text"}"""))

    private fun completion(text: String, calls: List<ToolCall> = emptyList()): Either<LLMError, Completion> =
        Right(
            Completion.apply(
                "id", 0L, text, "m",
                AssistantMessage.apply(if (calls.isEmpty()) Option.apply(text) else Option.empty(), seq(*calls.toTypedArray()), AssistantMessage.`apply$default$3`()),
                CollectionConverters.asScala(calls).toList(),
                Option.empty(), Option.empty(),
                CollectionConverters.asScala(listOf<Citation>()).toList(),
            ),
        )

    /** A model answering each call with the next of [replies], then `done`. */
    private class Scripted(private val replies: List<() -> Either<LLMError, Completion>>, private val done: Either<LLMError, Completion>) : LLMClient {
        private val next = AtomicInteger()
        private fun reply(): Either<LLMError, Completion> = replies.getOrNull(next.getAndIncrement())?.invoke() ?: done
        override fun complete(conversation: Conversation, options: CompletionOptions): Either<LLMError, Completion> = reply()
        override fun streamComplete(
            conversation: Conversation,
            options: CompletionOptions,
            onChunk: Function1<StreamedChunk, BoxedUnit>,
        ): Either<LLMError, Completion> = reply()
        override fun getContextWindow(): Int = 4096
        override fun getReserveCompletion(): Int = 256
    }

    private fun agentOf(vararg replies: () -> Either<LLMError, Completion>): AgentKt {
        val approval: AgentMiddleware = ApprovalMiddleware(
            { request -> if (request.spec().name() == "deploy") Option.apply("deploying") else Option.empty() },
            "approval",
        )
        val tools = ToolSet.of(seq<AgentTool<*>>(deploy, confirm)).toOption().get() as ToolSet
        val agent = Agent.builder("test", Scripted(replies.toList(), completion("done")))
            .withTools(tools)
            .withMiddleware(seq(approval))
            .build().toOption().get() as Agent
        return Llm4s.wrapAgent(agent)
    }

    @Test
    fun `an approval round trip - run, pending, approve, resume - completes`() = runBlocking {
        ran.clear()
        val agent = agentOf({ completion("", listOf(call("c1", "deploy", "prod"))) }, { completion("shipped") })
        val first = agent.run("deploy")
        val pending: List<PendingInterrupt> = AgentKt.pending(first)
        assertEquals(1, pending.size)
        val p = pending.single()
        assertEquals(InterruptKind.APPROVAL, p.kind())
        assertEquals("deploy", p.toolName())
        assertEquals("""{"text":"prod"}""", p.argumentsJson())
        assertEquals("deploying", p.reason().get())
        assertTrue(p.questionJson().isEmpty)
        assertTrue(ran.isEmpty())

        val done = agent.resume(first.threadId(), listOf(Answer.approve(p.id())))
        assertEquals(Option.apply("shipped"), done.answer())
        assertEquals(listOf("""{"text":"prod"}"""), ran.toList())
        assertTrue(AgentKt.pending(done).isEmpty())
    }

    @Test
    fun `a question round trip - run, pending, reply, resume - completes`() = runBlocking {
        val agent = agentOf({ completion("", listOf(call("c1", "confirm", "go"))) }, { completion("confirmed") })
        val first = agent.run("ask")
        val p = AgentKt.pending(first).single()
        assertEquals(InterruptKind.QUESTION, p.kind())
        assertEquals("""{"prompt":"really?"}""", p.questionJson().get())
        assertTrue(p.reason().isEmpty)

        val done = agent.resume(first.threadId(), listOf(Answer.reply(p.id(), """{"ok":true}""")))
        assertEquals(Option.apply("confirmed"), done.answer())
        val results = CollectionConverters.asJava(done.messages()).filterIsInstance<ToolMessage>().map { it.content() }
        assertTrue(results.any { it.contains("""confirmed {"ok":true}""") }, "the tool's result: $results")
    }

    @Test
    fun `a partial resume leaves the rest pending, and answering it with when over the kind completes`() = runBlocking {
        ran.clear()
        val agent = agentOf(
            { completion("", listOf(call("c1", "confirm", "go"), call("c2", "deploy", "prod"))) },
            { completion("both") },
        )
        var turn: AgentResult = agent.run("both")
        val pending = AgentKt.pending(turn)
        assertEquals(listOf(InterruptKind.APPROVAL, InterruptKind.QUESTION), pending.map { it.kind() })

        turn = agent.resume(turn.threadId(), listOf(Answer.approve(pending.first().id())))
        assertEquals(Option.empty(), turn.answer())
        assertEquals(listOf(pending.last()), AgentKt.pending(turn))

        while (AgentKt.pending(turn).isNotEmpty()) {
            val answers = AgentKt.pending(turn).map { p ->
                when (p.kind()) {
                    InterruptKind.APPROVAL -> Answer.reject(p.id(), "no")
                    InterruptKind.QUESTION -> Answer.reply(p.id(), """{"ok":false}""")
                }
            }
            turn = agent.resume(turn.threadId(), answers)
        }
        assertEquals(Option.apply("both"), turn.answer())
        assertEquals(listOf("""{"text":"prod"}"""), ran.toList())
    }

    @Test
    fun `resume throws LLMException for an id the thread does not wait for, an unknown thread, or a bad answer`() = runBlocking<Unit> {
        val agent = agentOf({ completion("", listOf(call("c1", "deploy", "x"))) }, { completion("ok") })
        val first = agent.run("go")
        assertFailsWith<LLMException> { agent.resume(first.threadId(), listOf(Answer.approve("no-such-interrupt"))) }
        assertFailsWith<LLMException> { agent.resume("no-such-thread", listOf(Answer.approve("i"))) }
        assertFailsWith<LLMException> { agent.resume(first.threadId(), listOf(Answer.edit(AgentKt.pending(first).single().id(), "{bad"))) }
        // none of them resumed anything
        assertEquals(Option.apply("ok"), agent.resume(first.threadId(), listOf(Answer.approve(AgentKt.pending(first).single().id()))).answer())
        assertFailsWith<LLMException> { agent.resume(first.threadId(), listOf(Answer.approve(AgentKt.pending(first).single().id()))) }
    }

    @Test
    fun `recover continues a resumed turn that failed, and throws when there is nothing to recover`() = runBlocking<Unit> {
        ran.clear()
        val down = NetworkError.apply("down", Option.empty(), "http://x")
        val agent = agentOf(
            { completion("", listOf(call("c1", "deploy", "prod"))) },
            { Left(down) },
            { completion("back") },
        )
        val first = agent.run("deploy")
        assertFailsWith<LLMException> { agent.resume(first.threadId(), listOf(Answer.approve(AgentKt.pending(first).single().id()))) }
        assertEquals(Option.apply("back"), agent.recover(first.threadId()).answer())
        assertEquals(listOf("""{"text":"prod"}"""), ran.toList()) // the approved call is not run again
        assertFailsWith<LLMException> { agent.recover(first.threadId()) }
    }

    @Test
    fun `cancelling a resume cancels the turn, and recover then completes it`() = runBlocking {
        val parked = CountDownLatch(1)
        val agent = agentOf(
            { completion("", listOf(call("c1", "deploy", "prod"))) },
            {
                // the model call after the approved tool parks until the cancelled turn interrupts it
                parked.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
                while (!Thread.currentThread().isInterrupted && System.nanoTime() < deadline) LockSupport.parkNanos(10_000_000L)
                Left(org.llm4s.error.CancelledError.apply("test", Option.empty()))
            },
            { completion("recovered") },
        )
        val first = agent.run("deploy")
        val id = AgentKt.pending(first).single().id()
        val resuming = async(Dispatchers.Default) { agent.resume(first.threadId(), listOf(Answer.approve(id))) }
        assertTrue(parked.await(seconds, TimeUnit.SECONDS))
        resuming.cancelAndJoin()
        assertTrue(resuming.isCancelled)
        // the turn has ended (cancelled): the thread is free, and recover finishes it
        assertEquals(Option.apply("recovered"), agent.recover(first.threadId()).answer())
    }

    @Test
    fun `pending is empty for a completed turn`() = runBlocking {
        val done = agentOf({ completion("hi") }).run("hi")
        assertTrue(AgentKt.pending(done).isEmpty())
    }
}
