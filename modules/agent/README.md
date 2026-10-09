# llm4s-agent

The agent runtime for LLM4S. An `Agent` is built once with `Agent.builder(id, client)` (its tools,
guardrails, handoffs and middleware belong to it) and run by thread on a graph runtime, with
streaming run events, cancellable runs, and checkpointed threads that can be resumed or recovered.
Agent memory is a separate artifact, [`llm4s-memory`](../memory/README.md).

## Quick Start

Add to your `build.sbt` (`llm4s-agent-tools` provides the `WeatherTool` used below):

```scala
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s-agent"       % "<version>",
  "org.llm4s" %% "llm4s-agent-tools" % "<version>"
)
```

```scala
import org.llm4s.agent.Agent
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.model.ModelRegistryService
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.tools.WeatherTool

val result = for {
  providerCfg     <- Llm4sConfig.defaultProvider()
  registryService <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registryService
  client      <- LLMConnect.getClient(providerCfg)
  weatherTool <- WeatherTool.toolSafe
  agent       <- Agent.builder("weather", client).withTools(new ToolRegistry(Seq(weatherTool))).build()
  r           <- agent.run("What's the weather like in Paris?")
} yield r.answer.getOrElse(s"Run ended: ${r.status}")

result.fold(err => println(s"Error: ${err.formatted}"), println)
```

## Main packages

| Package | Contains |
|---|---|
| `org.llm4s.agent` | `Agent`, `AgentBuilder` (`Agent.builder(id, client)`), `AgentResult`, `AgentStatus` (`Completed`, `Blocked`, `StepLimitReached`, `Suspended`), `AgentRun` (a started, cancellable turn), `Handoff` for multi-agent handoffs |
| `org.llm4s.agent.graph` | `GraphBuilder`, `CompiledGraph`, `GraphRuntime`, `RunConfig`, `ThreadId`, `Checkpointer`/`InMemoryCheckpointer` - the graph runtime agents run on, with checkpointed, resumable threads |
| `org.llm4s.agent.graph.middleware` | `AgentMiddleware`, `GuardrailMiddleware` (attach guardrails with `withMiddleware`), `ApprovalMiddleware`, `ContextWindowMiddleware` |
| `org.llm4s.agent.guardrails` | `Guardrail`, `InputGuardrail`, `OutputGuardrail`, `CompositeGuardrail`, `LLMGuardrail`; built-in guardrails in `guardrails.builtin` (`LengthCheck`, `ProfanityFilter`, `JSONValidator`, `PIIDetector`, `PIIMasker`, `SecretLeakGuardrail`, `PromptInjectionDetector`, and others) and RAG guardrails in `guardrails.rag` |
| `org.llm4s.agent.events` | `AgentEvents` - the run events (`TextDelta`, `ToolCallStarted`, `ToolExecuted`, `HandedOff`, ...) received with `agent.stream(threadId, query)(listener)` |
| `org.llm4s.assistant` | `AssistantAgent`, `SessionManager`, `SessionState` - an interactive console assistant built on `Agent` |

## Learn more

See the [agents guide](../../docs/guide/agents/index.md) for the full quick start (including
`agent.run`, multi-turn conversations and handling the result), plus dedicated pages on
[guardrails](../../docs/guide/agents/guardrails.md), [memory](../../docs/guide/agents/memory.md),
[handoffs](../../docs/guide/agents/handoffs.md), and
[streaming events](../../docs/guide/agents/streaming.md).
