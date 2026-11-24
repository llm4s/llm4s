---
layout: page
title: "5. Safety and Memory"
parent: Build a Support Assistant
grand_parent: Tutorials
nav_order: 5
description: "Support assistant tutorial, part 5: LLM4S guardrails that mask personal data and refuse prompt injection, and a conversation that remembers the turn before."
search_title: "Support assistant tutorial 5: guardrails and memory"
---

# Part 5: safety and memory
{: .no_toc }

Customers paste their email address into a message, and now and then someone tries to talk the assistant into
something it should not do. This part puts guardrails around the agent, gives every way a run can end a reply, and
lets a conversation carry on from the turn before.
{: .fs-6 .fw-300 }

The code is
[`Part5SafetyMemory.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/tutorial/supportassistant/Part5SafetyMemory.scala).

## Guardrails

A guardrail checks text, or rewrites it, at the edge of a run. Input guardrails run on the customer's message before
the model sees it; output guardrails run on the final answer before the customer does. They are set once on the
agent, with the builder:

```scala
import org.llm4s.agent.Agent
import org.llm4s.agent.guardrails.{ InputGuardrail, OutputGuardrail }
import org.llm4s.agent.guardrails.builtin.{ LengthCheck, PIIMasker, PromptInjectionDetector }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.Result

val inputGuardrails: Seq[InputGuardrail]   = Seq(LengthCheck(1, 2000), PromptInjectionDetector(), PIIMasker())
val outputGuardrails: Seq[OutputGuardrail] = Seq(PIIMasker())

def safeAgent(client: LLMClient): Result[Agent] =
  for {
    order  <- Part3Tools.orderTool
    policy <- Part3Tools.policyTool
    registry <- ToolRegistry.of(Seq(order, policy))
    agent <- Agent
      .builder("support", client)
      .withSystemPrompt("You are the support assistant of Acme Shop. Use the tools; never guess an order's status.")
      .withTools(registry)
      .withInputGuardrails(inputGuardrails*)
      .withOutputGuardrails(outputGuardrails*)
      .build()
  } yield agent
```

- `LengthCheck(1, 2000)` refuses an empty or very long message.
- `PromptInjectionDetector()` refuses messages such as "ignore all previous instructions". The message never reaches
  the model, so a refused message costs nothing.
- `PIIMasker()` never refuses: it replaces email addresses, phone numbers, card numbers and social security numbers
  with placeholders such as `[REDACTED_EMAIL]`. On the input side the model, the thread and any trace see only the
  masked message; on the output side the customer never sees personal data the model repeated.

The input guardrails run in order, each on the text the one before returned. All of them are plain code, with no second
model call; the [guardrails guide](../../guide/agents/guardrails) has the full list, including the LLM-as-judge ones.

## A reply for every ending

A refusal is not an error: the run ends with `AgentStatus.Blocked(guardrail, reason)`, and `agent.run` returns
`Right`. Errors (the provider is down, a tool failed) are the `Left`. So the assistant matches on the status, and
hands a refused message to a person instead of answering it:

```scala
import org.llm4s.agent.{ AgentResult, AgentStatus }

val handOver: String = "Sorry, I cannot help with that message. A colleague from the support team will reply."

def reply(result: AgentResult): String =
  result.status match {
    case AgentStatus.Completed(answer) => answer
    case AgentStatus.Blocked(_, _)     => handOver
    case _                             => s"$handOver (the run ended ${result.status})"
  }
```

`reason` says what the guardrail found. Log it; do not show it to the customer, who would learn how to get round it.

## Memory: one conversation, several turns

"And when will it arrive?" means nothing on its own. An agent's run belongs to a thread, and the thread keeps its
messages: `continueConversation` runs the next question on the same thread, so the model sees the turn before,
its tool calls and its answer.

```scala
def conversation(client: LLMClient, first: String, followUp: String): Result[(String, String)] =
  for {
    agent <- safeAgent(client)
    turn1 <- agent.run(first)
    turn2 <- agent.continueConversation(turn1, followUp)
  } yield (reply(turn1), reply(turn2))
```

Run the part. The scripted model looks the order up in the first turn and answers the follow-up from it:

```bash
sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part5SafetyMemory"
sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part5SafetyMemory --live"
```

`Part5Spec` checks that the email address in the first message reached the model as `[REDACTED_EMAIL]`, in both
turns, that a prompt injection is refused without a model call, and that the second turn's request carries the first
turn's question and answer.

A thread lives as long as its agent's checkpointer keeps it: in memory by default, or in SQLite or Postgres with the
[durable checkpointers](../../guide/agents/durable-checkpointers). Facts to remember across conversations, such as a customer's preferences,
are [agent memory](../../guide/agents/memory), a separate module.

## Exercise 5: no promises

Support assistants must not promise what the shop cannot keep. Write `Part5Exercise.noPromises` in
[`Exercises.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/tutorial/supportassistant/Exercises.scala):
an `OutputGuardrail` named `NoPromises` whose `validate` refuses an answer containing the word "guarantee", in any
case, with `ValidationError.invalid("answer", ...)`, and returns every other answer unchanged. Then run

```bash
sbt "samples/testOnly *Part5Spec"
```

The spec checks the guardrail on its own and on an agent, whose run must end `Blocked("NoPromises", _)`. For a bonus,
add it to the output guardrails of `safeAgent`. The solution is `Part5Solution` in the
[`solutions`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/tutorial/supportassistant/solutions/Solutions.scala)
package.

Previous: [Part 4, help-centre answers](part-4-help-centre). The next parts (testing with `llm4s-testkit` and a
judge, serving and observing) are tracked in [#2292](https://github.com/llm4s/llm4s/issues/2292).
