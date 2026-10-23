package org.llm4s.deploy

import org.llm4s.agent.{ Agent, AgentId }
import org.llm4s.agent.graph.GraphRuntime
import org.llm4s.agent.graph.tool.{ AgentTool, AgentToolSpec, ToolContent, ToolOutcome, ToolSet }
import org.llm4s.config.Llm4sConfig
import org.llm4s.error.CancelledError
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.llmconnect.model.*
import org.llm4s.model.ModelRegistryService
import org.llm4s.toolapi.Schema
import org.llm4s.types.Result
import upickle.default.ReadWriter

import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*

/**
 * A model that needs no provider, network or key, so the example service runs anywhere, CI included (design 4.60).
 * Each turn is two calls: the first asks for the `pause` tool ([[ExampleAgent.pauseTool]]), the second - after the
 * tool's result - answers `Echo: <the user's last message>`. With a zero `toolDelay` it answers at once.
 *
 * It keeps no state, so any number of runs may share it, and a run recovered on another machine calls it again
 * with the same history and gets the same answer.
 */
final class DemoModel(toolDelay: FiniteDuration) extends LLMClient:

  override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
    val lastUser = conversation.messages.reverseIterator.collectFirst { case u: UserMessage => u.content }
    val answered = conversation.messages.lastOption.exists(_.isInstanceOf[ToolMessage])
    if toolDelay > Duration.Zero && !answered then
      val call = ToolCall(s"pause-${conversation.messages.size}", ExampleAgent.PauseTool, ujson.Obj())
      Right(completion(AssistantMessage(contentOpt = None, toolCalls = Seq(call)), ""))
    else
      val text = s"Echo: ${lastUser.getOrElse("")}"
      Right(completion(AssistantMessage(text), text))

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = complete(conversation, options)

  override def getContextWindow(): Int     = 128000
  override def getReserveCompletion(): Int = 4096

  private def completion(message: AssistantMessage, text: String): Completion =
    Completion(
      id = s"demo-${java.util.UUID.randomUUID()}",
      created = Instant.now(),
      content = text,
      model = "demo",
      message = message,
      toolCalls = message.toolCalls.toList,
      usage = Some(TokenUsage(promptTokens = 0, completionTokens = 0, totalTokens = 0))
    )

/** The example service's agent: one id, the configured model, and the demo model's `pause` tool. */
object ExampleAgent:

  /** The demo model's tool: it waits `toolDelay`, or until its run is cancelled. */
  val PauseTool: String = "pause"

  final case class NoArgs() derives ReadWriter

  /** A tool that sleeps `delay`; an interrupt - the run's cancellation - ends it early with an error result. */
  def pauseTool(delay: FiniteDuration): AgentTool[NoArgs] =
    AgentTool(
      AgentToolSpec[NoArgs](PauseTool, "Waits a moment, standing in for real work", Schema.`object`[NoArgs]("pause"))
    ) { (_, _) =>
      CancelledError.catchInterrupt(TimeUnit.NANOSECONDS.sleep(delay.toNanos)) match
        case Right(_) => ToolOutcome.Success(ToolContent.Json(ujson.Obj("paused" -> delay.toMillis.toDouble)))
        case Left(_) =>
          Thread.currentThread().interrupt()
          ToolOutcome.Error("the pause was interrupted")
    }

  /** The model `config` names: the demo model, or a client for the default provider section. */
  def client(config: AgentServiceConfig): Result[LLMClient] =
    config.model match
      case AgentModel.Demo => Right(new DemoModel(config.toolDelay))
      case AgentModel.Provider =>
        for
          provider <- Llm4sConfig.defaultProvider()
          registry <- Llm4sConfig.modelRegistryService()
          client <- {
            given ModelRegistryService = registry
            LLMConnect.getClient(provider)
          }
        yield client

  /** The agent `id` over `runtime`, calling `client`, with the `pause` tool when the model is the demo one. */
  def build(id: AgentId, client: LLMClient, runtime: GraphRuntime, config: AgentServiceConfig): Result[Agent] =
    val builder = Agent
      .builder(id.value, client)
      .withRuntime(runtime)
      .withSystemPrompt("You are the llm4s example agent service's assistant.")
    val tools = config.model match
      case AgentModel.Demo     => ToolSet.of(pauseTool(config.toolDelay))
      case AgentModel.Provider => Right(ToolSet.empty)
    tools.flatMap(set => builder.withTools(set).build())
