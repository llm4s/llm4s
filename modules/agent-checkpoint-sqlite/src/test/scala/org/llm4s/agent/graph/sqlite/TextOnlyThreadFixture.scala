package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.{ Agent, AgentResult }
import org.llm4s.agent.graph.{ GraphRuntime, ThreadId }
import org.llm4s.agent.graph.tool.{ AgentTool, AgentToolSpec, ToolContent, ToolOutcome, ToolSet }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.toolapi.Schema
import org.llm4s.types.Result
import upickle.default.ReadWriter

import java.nio.file.{ Files, Path, StandardCopyOption }
import java.time.Clock
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }

/**
 * The agent of `text-only-thread.db`, a SQLite file written by `main` before #1913 gave messages content parts:
 * thread `text-only` holds one completed turn (`lookup` called, answered) and a second turn whose batch of
 * `good` and `flaky` failed when `flaky` returned `Fatal`, so its last checkpoint has text-only messages at
 * `messages@1` and a pending `tool-results@1` write for `good`.
 */
object TextOnlyThreadFixture:
  val Thread: ThreadId = ThreadId("text-only")
  val Resource         = "/fixtures/text-only-thread.db"

  final case class Arg(text: String) derives ReadWriter

  private def spec(name: String) =
    AgentToolSpec[Arg](
      name,
      s"The $name tool",
      Schema.`object`[Arg](name).withRequiredField("text", Schema.string("Text"))
    )

  def call(id: String, name: String): ToolCall = ToolCall(id, name, ujson.Obj("text" -> "x"))

  def completion(message: AssistantMessage): Completion =
    Completion(
      id = "fixture",
      created = 0L,
      content = message.content,
      model = "test-model",
      message = message,
      toolCalls = message.toolCalls.toList,
      usage = Some(TokenUsage(promptTokens = 10, completionTokens = 20, totalTokens = 30))
    )

  /** Answers each call with the next completion; records what each call was sent. */
  final class Scripted(completions: Completion*) extends LLMClient:
    private val index                           = new AtomicInteger()
    @volatile var sent: Vector[Vector[Message]] = Vector.empty
    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      sent = sent :+ conversation.messages.toVector
      completions.lift(index.getAndIncrement()).toRight(ValidationError("scripted", "no completion left"))
    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    def getContextWindow(): Int     = 128000
    def getReserveCompletion(): Int = 4096

  /**
   * The fixture's agent over `store`: `flaky` fails while `broken` is set, `lookup` returns `lookup`, and each `good`
   * call is counted in `goodRuns`. A tool's behaviour is not part of the agent's fingerprint, so the agent restores the
   * fixture's thread whatever these are.
   */
  def agent(
    client: LLMClient,
    store: SqliteCheckpointer,
    broken: AtomicBoolean,
    lookup: ToolContent = ToolContent.Json(ujson.Str("sunny")),
    goodRuns: AtomicInteger = AtomicInteger()
  ): Agent =
    def tool(name: String)(outcome: => ToolOutcome) = AgentTool(spec(name))((_, _) => outcome)
    val tools = ToolSet
      .of(
        tool("lookup")(ToolOutcome.Success(lookup)),
        tool("good") {
          goodRuns.incrementAndGet()
          ToolOutcome.Success(ToolContent.Json(ujson.Str("good result")))
        },
        tool("flaky")(
          if broken.get then ToolOutcome.Fatal(ValidationError("flaky", "backend down"))
          else ToolOutcome.Success(ToolContent.Json(ujson.Str("flaky result")))
        )
      )
      .fold(e => throw new IllegalStateException(e.message), identity)
    Agent
      .builder("assistant", client)
      .withSystemPrompt("You are a fixture.")
      .withTools(tools)
      .withRuntime(GraphRuntime(store))
      .build()
      .fold(e => throw new IllegalStateException(e.message), identity)

  /** A copy of the fixture file, opened. */
  def opened(): SqliteCheckpointer =
    val db = SqliteFiles.fresh()
    Files.copy(getClass.getResourceAsStream(Resource), db, StandardCopyOption.REPLACE_EXISTING)
    SqliteFiles.opened(db, Clock.systemUTC())

/** Writes `text-only-thread.db`; run once, on `main` before #1913, with the target path as its argument. */
object WriteTextOnlyThreadFixture:
  import TextOnlyThreadFixture.*

  def main(args: Array[String]): Unit =
    val target = Path.of(args(0))
    Files.deleteIfExists(target)
    val store = SqliteFiles.opened(target, Clock.systemUTC())
    val client = Scripted(
      completion(AssistantMessage(None, Seq(call("c1", "lookup")))),
      completion(AssistantMessage("It is sunny.")),
      completion(AssistantMessage(None, Seq(call("c2", "good"), call("c3", "flaky"))))
    )
    val fixture                    = agent(client, store, AtomicBoolean(true))
    val first: Result[AgentResult] = fixture.run(Thread, "What is the weather?")
    require(first.exists(_.answer.contains("It is sunny.")), s"first turn: $first")
    val second = fixture.run(Thread, "And tomorrow?")
    require(second.isLeft, s"second turn should fail: $second")
    store.close()
