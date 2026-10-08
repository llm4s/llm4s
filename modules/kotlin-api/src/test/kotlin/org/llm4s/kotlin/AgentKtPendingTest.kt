package org.llm4s.kotlin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.llm4s.javaapi.LlmException
import org.llm4s.javaapi.Llm4s as JLlm4s
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertIs
import org.llm4s.agent.Agent
import org.llm4s.javaapi.JAgentResult
import org.llm4s.agent.graph.GraphError
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
import org.llm4s.javaapi.AgentStatusKind
import org.llm4s.javaapi.InterruptKind
import org.llm4s.javaapi.JMessageRole
import org.llm4s.javaapi.PendingInterrupt
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.llmconnect.model.Citation
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.StreamedChunk
import org.llm4s.llmconnect.model.ToolCall
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
import java.util.Optional

/**
 * [AgentKt.pending], [AgentKt.resume] and [AgentKt.recover] - and cancelling [AgentKt.run] and
 * [AgentKt.continueConversation] - over the real agent runtime, behind the real Java facade: only the model
 * is scripted. `deploy` needs approval; `confirm` asks a question; `whoami` records its turn's thread.
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

    /** Records the thread its turn runs on. */
    private val threadOfTurn = AtomicReference<String>()
    private val whoami: AgentTool<ujson.Value> = AgentTool.apply<ujson.Value>(spec("whoami"), AgentTool.`apply$default$2`<ujson.Value>()) { _: ujson.Value, context: ToolContext ->
        threadOfTurn.set(context.run().position().threadId().toString())
        success("noted")
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

    private fun agentOf(vararg replies: () -> Either<LLMError, Completion>): AgentKt = Llm4s.wrapAgent(scalaAgentOf(*replies))

    private fun scalaAgentOf(vararg replies: () -> Either<LLMError, Completion>): Agent {
        val approval: AgentMiddleware = ApprovalMiddleware(
            { request -> if (request.spec().name() == "deploy") Option.apply("deploying") else Option.empty() },
            "approval",
        )
        val tools = ToolSet.of(seq<AgentTool<*>>(deploy, confirm, whoami)).toOption().get() as ToolSet
        return Agent.builder("test", Scripted(replies.toList(), completion("done")))
            .withTools(tools)
            .withMiddleware(seq(approval))
            .build().toOption().get() as Agent
    }

    /** A model call that parks until the cancelled turn interrupts it, then reports the cancellation. */
    private fun parking(parked: CountDownLatch, unparked: CountDownLatch): () -> Either<LLMError, Completion> = {
        parked.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (!Thread.currentThread().isInterrupted && System.nanoTime() < deadline) LockSupport.parkNanos(10_000_000L)
        if (Thread.currentThread().isInterrupted) unparked.countDown()
        Left(org.llm4s.error.CancelledError.apply("test", Option.empty()))
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
        assertEquals(Optional.of("shipped"), done.answer())
        assertEquals(listOf("""{"text":"prod"}"""), ran.toList())
        assertTrue(AgentKt.pending(done).isEmpty())
    }

    @Test
    fun `a result reads with Java types only - when over the status kind and each message's role`() = runBlocking {
        val agent = agentOf({ completion("", listOf(call("c1", "deploy", "prod"))) }, { completion("shipped") })
        val first = agent.run("deploy")
        fun describe(result: JAgentResult): String = when (result.status().kind()) {
            AgentStatusKind.COMPLETED -> "completed:" + result.answer().get()
            AgentStatusKind.BLOCKED -> "blocked:" + result.status().guardrail().get()
            AgentStatusKind.STEP_LIMIT_REACHED -> "step-limit"
            AgentStatusKind.SUSPENDED -> "suspended:" + result.status().pending().joinToString { it.toolName() }
        }
        assertEquals("suspended:deploy", describe(first))
        assertEquals(AgentKt.pending(first), first.status().pending())

        val done = agent.resume(first.threadId(), listOf(Answer.approve(first.status().pending().single().id())))
        assertEquals("completed:shipped", describe(done))
        val history = done.messages().map { m ->
            when (m.role()) {
                JMessageRole.SYSTEM, JMessageRole.USER -> m.content()
                JMessageRole.ASSISTANT -> m.content() + m.toolCalls().joinToString { it.name() + it.argumentsJson() }
                JMessageRole.TOOL -> m.toolCallId().get() + "=" + m.content()
            }
        }
        assertEquals(listOf("deploy", """deploy{"text":"prod"}""", "c1=deployed", "shipped"), history)
        assertEquals(2L, done.usage().requestCount())
        assertTrue(done.usage().byModel().keys.all { it is String })
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
        assertEquals(Optional.of("confirmed"), done.answer())
        val results = done.messages().filter { it.role() == JMessageRole.TOOL }.map { it.content() }
        assertTrue(results.any { it.contains("""confirmed {"ok":true}""") }, "the tool's result: $results")
    }

    @Test
    fun `a partial resume leaves the rest pending, and answering it with when over the kind completes`() = runBlocking {
        ran.clear()
        val agent = agentOf(
            { completion("", listOf(call("c1", "confirm", "go"), call("c2", "deploy", "prod"))) },
            { completion("both") },
        )
        var turn: JAgentResult = agent.run("both")
        val pending = AgentKt.pending(turn)
        assertEquals(listOf(InterruptKind.APPROVAL, InterruptKind.QUESTION), pending.map { it.kind() })

        turn = agent.resume(turn.threadId(), listOf(Answer.approve(pending.first().id())))
        assertEquals(Optional.empty(), turn.answer())
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
        assertEquals(Optional.of("both"), turn.answer())
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
        assertEquals(Optional.of("ok"), agent.resume(first.threadId(), listOf(Answer.approve(AgentKt.pending(first).single().id()))).answer())
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
        assertEquals(Optional.of("back"), agent.recover(first.threadId()).answer())
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
        assertEquals(Optional.of("recovered"), agent.recover(first.threadId()).answer())
    }

    // ---- cancelling run or continueConversation cancels the turn ---------------------------------

    @Test
    fun `cancelling a run cancels the turn, freeing its thread for recover and the next turn`() = runBlocking {
        val parked = CountDownLatch(1)
        val unparked = CountDownLatch(1)
        val agent = agentOf(
            { completion("", listOf(call("c1", "whoami", "x"))) },
            parking(parked, unparked),
            { completion("recovered") },
        )
        val running = async(Dispatchers.Default) { agent.run("go") }
        assertTrue(withContext(Dispatchers.IO) { parked.await(seconds, TimeUnit.SECONDS) })
        withTimeout(seconds * 1000) { running.cancelAndJoin() }
        assertTrue(running.isCancelled)
        assertFailsWith<CancellationException> { running.await() }
        assertTrue(withContext(Dispatchers.IO) { unparked.await(seconds, TimeUnit.SECONDS) }, "the model call was interrupted")
        // the turn has ended (cancelled): the thread is not busy, recover finishes it, and the conversation goes on
        val recovered = agent.recover(threadOfTurn.get())
        assertEquals(Optional.of("recovered"), recovered.answer())
        assertEquals(Optional.of("done"), agent.continueConversation(recovered, "again").answer())
    }

    @Test
    fun `a timeout around continueConversation cancels the turn, freeing its thread`() = runBlocking {
        val parked = CountDownLatch(1)
        val unparked = CountDownLatch(1)
        val agent = agentOf({ completion("first") }, parking(parked, unparked), { completion("recovered") })
        val first = agent.run("hi")
        // the model parks until interrupted, and the timeout is generous enough for it to have parked by then
        val outcome = runCatching { withTimeout(5_000) { agent.continueConversation(first, "more") } }
        assertIs<TimeoutCancellationException>(outcome.exceptionOrNull())
        assertEquals(0L, parked.count, "the timeout landed while the model call was parked")
        assertTrue(withContext(Dispatchers.IO) { unparked.await(seconds, TimeUnit.SECONDS) }, "the model call was interrupted")
        val recovered = agent.recover(first.threadId())
        assertEquals(Optional.of("recovered"), recovered.answer())
        assertEquals(Optional.of("done"), agent.continueConversation(recovered, "again").answer())
    }

    @Test
    fun `a cancellation that loses the race to a completed turn throws, but the turn's result is committed`() = runBlocking {
        val modelCalled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val agent = agentOf(
            { completion("first") },
            {
                modelCalled.countDown()
                release.await(seconds, TimeUnit.SECONDS)
                completion("second")
            },
        )
        val first = agent.run("hi")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val caller = executor.asCoroutineDispatcher()
            val continuing = async(caller) { agent.continueConversation(first, "more") }
            assertTrue(withContext(Dispatchers.IO) { modelCalled.await(seconds, TimeUnit.SECONDS) })
            // the caller is suspended in the turn: occupy its only thread, so the result cannot be handed back to it
            val occupied = CountDownLatch(1)
            val freed = CountDownLatch(1)
            executor.execute {
                occupied.countDown()
                freed.await(seconds, TimeUnit.SECONDS)
            }
            assertTrue(withContext(Dispatchers.IO) { occupied.await(seconds, TimeUnit.SECONDS) })
            release.countDown()
            // the turn completes and is committed: recover first sees a busy thread, then nothing to recover
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            var refusal = ""
            while (!refusal.contains("no incomplete execution") && System.nanoTime() < deadline) {
                refusal = assertFailsWith<LLMException> { agent.recover(first.threadId()) }.message.orEmpty()
            }
            assertTrue(refusal.contains("no incomplete execution"), refusal)
            // only now is the caller cancelled, before it could resume with the result
            continuing.cancel()
            freed.countDown()
            assertFailsWith<CancellationException> { continuing.await() }
            assertTrue(continuing.isCancelled)
            // the completed turn is the thread's latest: the next turn continues from it
            val next = agent.continueConversation(first, "again")
            assertEquals(Optional.of("done"), next.answer())
            assertTrue(next.messages().any { it.content() == "second" }, "the committed turn's answer is in the history")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `run and continueConversation return what the Java facade's blocking calls return`() = runBlocking {
        fun script(): Array<() -> Either<LLMError, Completion>> =
            arrayOf({ completion("", listOf(call("c1", "whoami", "x"))) }, { completion("one") }, { completion("two") })
        val java = JLlm4s.wrapAgent(scalaAgentOf(*script()))
        val kotlin = Llm4s.wrapAgent(scalaAgentOf(*script()))
        fun shape(r: JAgentResult): List<Any> =
            listOf(r.status().kind(), r.answer(), r.messages().map { it.role() to it.content() }, r.usage().requestCount())

        val javaFirst = java.run("go").get()
        val kotlinFirst = kotlin.run("go")
        assertEquals(shape(javaFirst), shape(kotlinFirst))
        assertEquals(shape(java.continueConversation(javaFirst, "more").get()), shape(kotlin.continueConversation(kotlinFirst, "more")))
    }

    @Test
    fun `a failed run or continueConversation throws the Java facade's error, as LLMException`() = runBlocking<Unit> {
        val down = NetworkError.apply("down", Option.empty(), "http://x")
        fun script(): Array<() -> Either<LLMError, Completion>> = arrayOf({ completion("first") }, { Left(down) })
        val java = JLlm4s.wrapAgent(scalaAgentOf(*script()))
        val kotlin = Llm4s.wrapAgent(scalaAgentOf(*script()))
        val javaFirst = java.run("go").get()
        val kotlinFirst = kotlin.run("go")

        val expected = java.continueConversation(javaFirst, "more").getError()
        val thrown = assertFailsWith<LLMException> { kotlin.continueConversation(kotlinFirst, "more") }
        assertEquals(expected.message, thrown.message)
        assertEquals(expected.error().javaClass, assertIs<LlmException>(thrown.cause).error().javaClass)

        // a refused start (the thread is not completed: its failed turn waits for recover) is the facade's refusal too
        val refused = java.continueConversation(javaFirst, "again").getError()
        val thrownRefusal = assertFailsWith<LLMException> { kotlin.continueConversation(kotlinFirst, "again") }
        // the same message, but for the two threads' (and checkpoints') random ids
        val ids = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        assertEquals(refused.message!!.replace(ids, "<id>"), thrownRefusal.message!!.replace(ids, "<id>"))
        assertEquals(refused.error().javaClass, assertIs<LlmException>(thrownRefusal.cause).error().javaClass)
    }

    @Test
    fun `a turn cancelled by its own run, the caller not cancelled, throws LLMException as the facade's error`() = runBlocking<Unit> {
        val selfCancelled: () -> Either<LLMError, Completion> = { Left(org.llm4s.error.CancelledError.apply("model", Option.empty())) }
        val expected = JLlm4s.wrapAgent(scalaAgentOf(selfCancelled)).run("go").getError()
        assertIs<GraphError.Cancelled>(expected.error())
        val thrown = assertFailsWith<LLMException> { agentOf(selfCancelled).run("go") }
        assertIs<GraphError.Cancelled>(assertIs<LlmException>(thrown.cause).error())
    }

    @Test
    fun `pending is empty for a completed turn`() = runBlocking {
        val done = agentOf({ completion("hi") }).run("hi")
        assertTrue(AgentKt.pending(done).isEmpty())
    }
}
