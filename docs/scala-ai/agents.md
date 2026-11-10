---
layout: page
title: Build AI agents in Scala
parent: Scala AI
nav_order: 2
description: "Build an AI agent in Scala with LLM4S: a tool the model can call, input guardrails and streamed events, in one compiled example, then where to go next."
search_title: "Build AI agents in Scala: tools, guardrails, streaming"
---

# Build AI agents in Scala
{: .no_toc }

To build an AI agent in Scala, add `llm4s-agent` and give `Agent.builder` a model client and the tools the model may
call: the agent loops between the model and your tools until it has an answer. The example below adds an input
guardrail and streams the agent's events, in about 25 lines.
{: .fs-6 .fw-300 }

1. TOC
{:toc}

## Add the modules

The agent runtime is its own module, next to the core and the module of your model provider:

```scala
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s-agent"  % "{{ site.data.project.latest_release }}",
  "org.llm4s" %% "llm4s-openai" % "{{ site.data.project.latest_release }}"
)
```

`llm4s-agent` brings `llm4s-core`. The client is built from your configuration as on the
[first-call page](llm-framework#a-first-call), and you can test the agent with no model at all using
[`llm4s-testkit`](../guide/testing-with-the-testkit).

## An agent with a tool, a guardrail and streaming

```scala
import org.llm4s.agent.{ Agent, AgentResult }
import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph.ThreadId
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.guardrails.builtin.{ LengthCheck, ProfanityFilter }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.llm4s.types.Result

def weatherAgent(client: LLMClient, question: String): Result[AgentResult] = for {
  forecast <- ToolBuilder[Map[String, Any], String](
    name = "get_forecast",
    description = "Returns today's forecast for a city",
    schema = Schema
      .`object`[Map[String, Any]]("Forecast parameters")
      .withProperty(Schema.property("city", Schema.string("The city to look up")))
  ).withHandler(args => args.getString("city").map(city => s"Sunny and 18 C in $city")).buildSafe()
  agent <- Agent
    .builder("weather", client)
    .withSystemPrompt("You answer questions about the weather.")
    .withTools(new ToolRegistry(Seq(forecast)))
    .withMiddleware(new GuardrailMiddleware(input = Seq(new LengthCheck(1, 2000), new ProfanityFilter()), Nil))
    .withStreaming()
    .build()
  run <- agent.stream(ThreadId("weather-1"), question) {
    case AgentEvents.TextDelta(delta)    => print(delta.text)
    case AgentEvents.ToolCallStarted(tc) => println(s"[calling ${tc.tool}]")
    case _                               => ()
  }
  result <- run.await()
} yield result
```

What each part does:

- **The tool.** `ToolBuilder` declares a function the model may call, with a JSON schema for its arguments; the
  handler reads its arguments as `Either` values, so a missing argument is a value, not an exception. The
  [tools guide](../guide/custom-tools) goes further, and [typed tools](../guide/derived-schemas#typed-tools) derive
  the schema from a case class.
- **The guardrails.** `GuardrailMiddleware` checks the input before the model sees it. A question outside the length
  limit ends the run as `AgentStatus.Blocked`, an outcome you match on rather than an error, and the model is never
  called. Output guardrails, LLM-as-judge checks and your own are in the [guardrails guide](../guide/agents/guardrails).
- **Streaming.** `withStreaming()` streams the model's answer, and `agent.stream` hands every event of the turn to your
  function: text as it arrives, tool calls as they start, and more ([streaming events](../guide/agents/streaming)).
- **The result.** `AgentResult` carries the status, the thread's messages and the token usage; `result.answer` is the
  final answer when the run completed. A provider or tool failure is a `Left`.

The run belongs to a thread (`ThreadId`): call `agent.run(threadId, ...)` again with the same id to continue the
conversation.

## Going further

- [Agents guide](../guide/agents/): threads and results, typed answers, tool permissions and timeouts, context
  window management and conversation persistence.
- [Handoffs](../guide/agents/handoffs) between agents, [human review](../guide/agents/human-review) (approving a tool call, for example),
  and [memory](../guide/agents/memory).
- [Durable checkpointers](../guide/agents/durable-checkpointers), so a run resumes after a crash or a deploy.
- The [cookbook](../examples/cookbook): runnable recipes for [tool calling](../examples/cookbook/tool-calling),
  [guardrails](../examples/cookbook/guardrails), [streaming](../examples/cookbook/streaming) and a
  [multi-agent graph](../examples/cookbook/multi-agent-graph).
- With cats-effect or ZIO: [`AgentIO`](../guide/cats-effect) and [`AgentZ`](../guide/zio).
