# llm4s-agent

The agent runtime for LLM4S: a tool-calling loop around an `LLMClient`, with guardrails,
multi-agent handoffs, streaming events, memory, and a graph-based orchestration engine for more
complex workflows.

## Quick Start

Add to your `build.sbt`:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-agent" % "<version>"
```

```scala
import org.llm4s.agent.Agent
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.tools.WeatherTool

val result = for {
  providerCfg     <- Llm4sConfig.defaultProvider()
  registryService <- Llm4sConfig.modelRegistryService()
  given org.llm4s.model.ModelRegistryService = registryService
  client      <- LLMConnect.getClient(providerCfg)
  weatherTool <- WeatherTool.toolSafe
  toolRegistry = new ToolRegistry(Seq(weatherTool))
  agent        = new Agent(client)
  finalState  <- agent.run("What's the weather like in Paris?", toolRegistry)
} yield finalState
```

## Main packages

| Package | Contains |
|---|---|
| `org.llm4s.agent` | `Agent`, `AgentState`, `AgentStatus`, `ToolProcessor`, `Handoff`/`HandoffExecutor` for multi-agent handoffs |
| `org.llm4s.agent.graph` | `GraphBuilder`, `CompiledGraph`, `GraphRuntime`, `Checkpoint`/`Checkpointer` - graph-based orchestration with resumable, checkpointed runs |
| `org.llm4s.agent.guardrails` | `Guardrail`, `CompositeGuardrail`, and built-in guardrails (`PIIDetector`, `PIIMasker`, `ProfanityFilter`, `SecretLeakGuardrail`, `PromptInjectionDetector`, and others) |
| `org.llm4s.agent.orchestration` | `DAG`, `PlanRunner`, `TypedAgent` - typed, DAG-based multi-step orchestration |
| `org.llm4s.agent.streaming` | `AgentEvent` and the streaming event types used by `agent.runWithEvents` |
| `org.llm4s.assistant` | `AssistantAgent`, `ConsoleInterface`, `SessionManager` - an interactive console assistant built on `Agent` |

## Learn more

See the [agents guide](../../docs/guide/agents/index.md) for the full quick start (including
`agent.run`, multi-turn conversations, and tool execution strategies), plus dedicated pages on
[guardrails](../../docs/guide/agents/guardrails.md), [memory](../../docs/guide/agents/memory.md),
[handoffs](../../docs/guide/agents/handoffs.md), and
[streaming events](../../docs/guide/agents/streaming.md).
