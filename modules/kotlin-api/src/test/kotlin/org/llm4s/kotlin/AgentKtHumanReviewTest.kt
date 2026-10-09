package org.llm4s.kotlin

import kotlinx.coroutines.runBlocking
import org.llm4s.agent.Agent
import org.llm4s.agent.AgentNode
import org.llm4s.agent.graph.RunContext
import org.llm4s.agent.graph.StateUpdate
import org.llm4s.agent.graph.middleware.AgentMiddleware
import org.llm4s.agent.graph.middleware.ToolCallRequest
import org.llm4s.agent.graph.tool.AgentTool
import org.llm4s.agent.graph.tool.AgentToolSpec
import org.llm4s.agent.graph.tool.ToolContext
import org.llm4s.agent.graph.tool.ToolOutcome
import org.llm4s.agent.graph.tool.ToolSet
import org.llm4s.error.LLMError
import org.llm4s.javaapi.Answer
import org.llm4s.javaapi.BreakpointPhase
import org.llm4s.javaapi.InterruptKind
import org.llm4s.javaapi.JAgentResult
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.llmconnect.model.Citation
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.StreamedChunk
import org.llm4s.llmconnect.model.ToolCall
import org.llm4s.toolapi.Schema
import scala.Function0
import scala.Function1
import scala.Option
import scala.jdk.javaapi.CollectionConverters
import scala.runtime.BoxedUnit
import scala.util.Either
import scala.util.Right
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Static breakpoints and middleware questions (#1704) through [AgentKt]: [AgentKt.pending] lists them as
 * `BREAKPOINT` and `MIDDLEWARE_QUESTION` [org.llm4s.javaapi.PendingInterrupt]s, and [AgentKt.resume]
 * answers them with `Answer.proceed` and `Answer.reply`, one at a time or together.
 */
class AgentKtHumanReviewTest {

    private val json = upickle.default.ReadWriter().join(upickle.default.JsValueR(), upickle.default.JsValueW())

    private fun parse(text: String): ujson.Value = ujson.`package`.read(ujson.Readable.fromString(text), false)

    private fun <T> seq(vararg items: T): scala.collection.immutable.Seq<T> = CollectionConverters.asScala(items.toList()).toSeq()

    private val ran = CopyOnWriteArrayList<String>()

    private val deploy: AgentTool<ujson.Value> = AgentTool.apply<ujson.Value>(
        AgentToolSpec.apply(
            "deploy",
            "The deploy tool",
            Schema.`object`<ujson.Value>("deploy").withRequiredField("text", Schema.string("Text")),
            json,
        ),
        AgentTool.`apply$default$2`<ujson.Value>(),
    ) { args: ujson.Value, _: ToolContext ->
        ran.add(args.toString())
        ToolOutcome.Success.apply(ujson.Str("deployed"), StateUpdate.empty())
    }

    /** Asks which environment each call is for, then lets it run. */
    private val environment = object : AgentMiddleware.Asking<ujson.Value, ujson.Value>(json, json) {
        override fun id(): String = "environment"

        override fun wrapToolCall(request: ToolCallRequest, context: ToolContext, next: Function0<ToolOutcome>): ToolOutcome =
            if (answered(context).isDefined) next.apply() else askAbout(parse("""{"what":"environment"}"""))
    }

    /** Asks for the region before the turn runs. */
    private val region = object : AgentMiddleware.Asking<ujson.Value, ujson.Value>(json, json) {
        override fun id(): String = "region"

        @Suppress("UNCHECKED_CAST")
        override fun beforeAgent(input: String, context: RunContext): Either<LLMError, String> =
            if (answered(context).isDefined) Right(input) else ask(parse("""{"what":"region"}""")) as Either<LLMError, String>
    }

    private fun call(id: String, text: String): ToolCall = ToolCall.apply(id, "deploy", parse("""{"text":"$text"}"""))

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

    private fun agentOf(configure: (org.llm4s.agent.AgentBuilder) -> org.llm4s.agent.AgentBuilder): AgentKt {
        val tools = ToolSet.of(seq<AgentTool<*>>(deploy)).toOption().get() as ToolSet
        val builder = Agent.builder(
            "test",
            Scripted(listOf(completion("", listOf(call("c1", "one"), call("c2", "two")))), completion("done")),
        ).withTools(tools)
        return Llm4s.wrapAgent(configure(builder).build().toOption().get() as Agent)
    }

    @Test
    fun `a breakpoint is pending as BREAKPOINT, each held call on its own, and proceed continues it`() = runBlocking {
        ran.clear()
        val agent = agentOf { it.withInterruptBefore(AgentNode.valueOf("Tool")) }
        val first = agent.run("deploy")
        val pending = AgentKt.pending(first)
        assertEquals(listOf(InterruptKind.BREAKPOINT, InterruptKind.BREAKPOINT), pending.map { it.kind() })
        val p = pending.first()
        assertEquals(Optional.of("test/call-tool"), p.node())
        assertEquals(Optional.of(BreakpointPhase.BEFORE), p.phase())
        assertEquals(Optional.of("deploy"), p.toolName())
        assertTrue(ran.isEmpty())

        val partly = agent.resume(first.threadId(), listOf(Answer.proceed(pending.last().id())))
        assertEquals(listOf(p.id()), AgentKt.pending(partly).map { it.id() })
        val done = agent.resume(first.threadId(), listOf(Answer.proceed(p.id())))
        assertEquals(Optional.of("done"), done.answer())
        assertEquals(2, ran.size)
    }

    @Test
    fun `middleware questions are pending as MIDDLEWARE_QUESTION, answered one at a time`() = runBlocking {
        ran.clear()
        val agent = agentOf { it.withMiddleware(seq(region, environment)) }
        var turn: JAgentResult = agent.run("deploy")
        val asked = AgentKt.pending(turn).single()
        assertEquals(InterruptKind.MIDDLEWARE_QUESTION, asked.kind())
        assertEquals(Optional.of("region"), asked.middleware())
        assertEquals(Optional.of("""{"what":"region"}"""), asked.questionJson())
        assertTrue(asked.toolName().isEmpty)

        turn = agent.resume(turn.threadId(), listOf(Answer.reply(asked.id(), """"eu"""")))
        val perCall = AgentKt.pending(turn)
        assertEquals(listOf(Optional.of("environment"), Optional.of("environment")), perCall.map { it.middleware() })
        assertEquals(listOf("""{"text":"one"}""", """{"text":"two"}"""), perCall.map { it.argumentsJson().get() })

        while (AgentKt.pending(turn).isNotEmpty()) {
            val next = AgentKt.pending(turn).first()
            val answer = when (next.kind()) {
                InterruptKind.APPROVAL -> Answer.approve(next.id())
                InterruptKind.QUESTION, InterruptKind.MIDDLEWARE_QUESTION -> Answer.reply(next.id(), """"prod"""")
                InterruptKind.BREAKPOINT -> Answer.proceed(next.id())
            }
            turn = agent.resume(turn.threadId(), listOf(answer))
        }
        assertEquals(Optional.of("done"), turn.answer())
        assertEquals(2, ran.size)
    }
}
