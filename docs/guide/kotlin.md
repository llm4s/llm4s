---
layout: page
title: Kotlin
description: "Call an LLM from Kotlin with llm4s-kotlin-api: suspend functions, Flow streaming, agents with tools, the Kotlin DSL, cancellation and LLMException."
search_title: "Call LLMs from Kotlin with LLM4S: coroutines, Flow, agents"
parent: Java and Kotlin
nav_order: 412
grand_parent: User Guide
---

# Kotlin
{: .no_toc }

Call a model, stream an agent's turn and declare tools from Kotlin, with suspend functions, `Flow`s and `LLMException`
instead of Scala types.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## What `llm4s-kotlin-api` is

`llm4s-kotlin-api` (`modules/kotlin-api`) is the Kotlin coroutine API over the [Java facade](java). Its entry point is
`org.llm4s.kotlin.Llm4s`, which gives you two wrappers:

- `LLMClientKt`: `suspend fun complete(query)` and `fun streamComplete(prompt): Flow<String>`.
- `AgentKt`: `suspend fun run`, `continueConversation`, `resume` and `recover`, and `fun stream(threadId, query): Flow<AgentStreamItem>`.

Blocking calls run on `Dispatchers.IO`, and failures are thrown as `LLMException`, so you never import a Scala type to
call a model. Beside the wrappers there is a DSL for clients, completion options and tools (`ClientDsl.kt`,
`CompletionOptionsDsl.kt`, `ToolDsl.kt`) and an `agent { ... }` block (`AgentDsl.kt`).

The module is **experimental**: its API may change in any release ([1.0 Scope](../reference/v1-scope)). It runs on
JDK 21, like the Java facade it wraps.

## Installation

The dependency, the BOM and the provider module you add next to it are in
[For Kotlin (coroutines and Flow)](../getting-started/installation#for-kotlin-coroutines-and-flow). Two things to know
from there: `llm4s-kotlin-api` is a Kotlin artifact, so it has no `_3` suffix, and it is built by Gradle, not sbt.
It is on Maven Central from 0.6.0 only; for an earlier release the module is built locally, and the installation page
has the steps.

## A first call

`Llm4s.createDefaultClient()` builds a client from the default provider section of `application.conf` (the one
`llm4s.defaultProvider` names; see [Configuration](../getting-started/configuration)). `complete` is a suspend
function, so call it from a coroutine. `LLMClientKt` is `AutoCloseable`, so `use` closes it:

```kotlin
import kotlinx.coroutines.runBlocking
import org.llm4s.kotlin.Llm4s

fun main() = runBlocking {
    Llm4s.createDefaultClient().use { client ->
        println(client.complete("What is 2 + 2?"))
    }
}
```

*Taken from the `LLMClientKt` and `Llm4s` KDoc (`LLMClientKt.kt`, `Llm4s.kt`) and the `use` call in
[java.md](java#configure-in-code).*

To configure a client in code, with no `application.conf`, each provider has a block: `Llm4s.openAi { ... }`,
`anthropic`, `gemini`, `ollama`, `openAiCompatible` and `azureOpenAi`. Every block is the Java facade's builder for that
provider, so a field means what it means in [Configure in code](java#configure-in-code), and a timeout is a
`kotlin.time.Duration`. A required field left out throws `LLMException` naming it:

```kotlin
import kotlin.time.Duration.Companion.seconds

val openAi = Llm4s.openAi {
    apiKey = System.getenv("OPENAI_API_KEY")
    model = "gpt-4o-mini"
    requestTimeout = 30.seconds
}

val local = Llm4s.ollama {
    model = "llama3.2"             // the server is http://localhost:11434 unless baseUrl is set
}
```

*From the `Llm4s.openAi` KDoc and the "The Kotlin DSL" block in [java.md](java#the-kotlin-dsl); `Llm4s.ollama` takes
`model` and an optional `baseUrl` (`ClientDsl.kt`, `OllamaSpec`).*

If you already have a `JLlmClient`, for example one from `JLlm4s.ollama()...build().get()` or
`createClient(ProviderConfig)`, `Llm4s.wrapClient(client)` wraps it; closing the wrapper closes it.

## Options with `completionOptions`

`completionOptions { ... }` builds the Java facade's `JCompletionOptions`, which `complete(query, options)` takes. A
setting you leave out keeps the default (temperature `0.7`, no token limit), and a value no provider accepts, such as a
negative temperature, throws `IllegalArgumentException`:

```kotlin
import org.llm4s.javaapi.JReasoningEffort
import org.llm4s.kotlin.completionOptions

val reply = client.complete("Name a colour", completionOptions {
    temperature = 0.2
    maxTokens = 200
    reasoning = JReasoningEffort.LOW
})
```

*From the "The Kotlin DSL" block in [java.md](java#the-kotlin-dsl) (`CompletionOptionsDsl.kt`).*

The other settings are `topP`, `presencePenalty`, `frequencyPenalty`, `thinking` and `serviceTier`.
`completionOptions(base) { ... }` starts from existing options and changes only what the block sets.

## Streaming a reply

`streamComplete(prompt)` returns a cold `Flow<String>`: nothing runs until you collect it, and each collection makes a
new call. Collect it in a coroutine:

```kotlin
import kotlinx.coroutines.runBlocking

runBlocking {
    client.streamComplete("Explain monads").collect { chunk -> print(chunk) }
}
```

{: .note }
> Today `streamComplete` emits **one** chunk, the whole reply: the `LLMClientKt` KDoc says chunk-level streaming will
> come when the Java API gains streaming support. To receive an answer as it is written, stream an agent's turn (see
> [Streaming an agent's turn](#stream-a-turn-as-a-flow)).

## An agent with a tool

A tool is a function the model may call. `tools { tool(name, description) { ... } }` declares them: `param<T>` adds a
required parameter, `optionalParam<T>` one the model may leave out, and the `handler` receives the call's `ToolArgs`.
A parameter is a `String`, an `Int`, `Long`, `Short`, `Byte`, `Double`, `Float`, `Boolean` or an enum. The handler is a
suspend function, and what it returns is what the model sees. An exception it throws does not fail the agent: the model
receives a tool error with its message.

`Llm4s.createAgent(client, tools)` wraps a client and the tools in an `AgentKt`:

```kotlin
import org.llm4s.kotlin.Llm4s
import org.llm4s.kotlin.tools

val agent = Llm4s.createAgent(client, tools {
    tool("add", "Adds two numbers") {
        param<Long>("a", "First")
        param<Long>("b", "Second")
        handler { args -> args.get<Long>("a") + args.get<Long>("b") }
    }
})
val result = agent.run("What is 2 + 3?")   // a suspend function: call it in a coroutine
println(result.answer().orElse("(" + result.status().kind() + ")"))
```

*From the `Llm4s.createAgent` KDoc and the "The Kotlin DSL" block in [java.md](java#the-kotlin-dsl); the last line is
the `answer()` and `status().kind()` reading described in the `AgentKt` KDoc.*

A turn returns a `JAgentResult`, read with Java types: `answer()` is an `Optional<String>`, `messages()` a `List`, and
`status().kind()` an `AgentStatusKind` (`COMPLETED`, `TRUNCATED`, `BLOCKED`, `STEP_LIMIT_REACHED`,
`TOKEN_LIMIT_REACHED` or `SUSPENDED`) you can `when` over.

A conversation is a thread of the agent's in-memory runtime. `continueConversation(previous, query)` runs the next turn
of the thread `previous` belongs to, and `forget(result)` removes the thread when you are done:

```kotlin
val first = agent.run("Summarise today's news")
val second = agent.continueConversation(first, "And yesterday's?")
agent.forget(second)
```

*From the `AgentKt` KDoc.*

### The `agent` block

To set a system prompt, guardrails, a context window or streaming, build the agent with `agent(id, client) { ... }`. It
returns a Kotlin `Result<AgentKt>` instead of throwing, because a blank id, an invalid guardrail or two tools with one
name are failures to report, not crashes:

```kotlin
import org.llm4s.javaapi.JGuardrails
import org.llm4s.kotlin.AgentKt
import org.llm4s.kotlin.agent

val built: Result<AgentKt> = agent("support", client) {
    systemPrompt("You are a support assistant. Answer in JSON.")
    tools {
        tool("add", "Adds two numbers") {
            param<Long>("a", "First")
            param<Long>("b", "Second")
            handler { args -> args.get<Long>("a") + args.get<Long>("b") }
        }
    }
    inputGuardrails(JGuardrails.length(1, 10_000), JGuardrails.profanity())
    outputGuardrails(JGuardrails.json())   // inputGuardrails(JGuardrails.json()) does not compile
    contextWindow(lastMessages = 20)
    streaming()
}

val support = built.getOrThrow()          // or built.onFailure { e -> log(e.message) }
val result = support.run("What is 2 + 3?")
result.status().guardrail().ifPresent { g ->
    println("Blocked by $g: ${result.status().reason().orElse("")}")
}
```

*From `KotlinGuideSnippets.supportAgent` in `modules/kotlin-api/src/test/kotlin/org/llm4s/kotlin/KotlinGuideSnippets.kt`,
which the tests compile and run, and the same block in [java.md](java#an-agent-block).*

A guardrail that fails ends the turn `BLOCKED`, which is why the example reads `status().guardrail()` rather than the
answer. The `agent` block does not set approvals, permissions, handoffs or subagents; for those, build the agent with the
Scala `Agent.builder` and wrap it with `Llm4s.wrapAgent(agent)`.

### Stream a turn as a Flow

`AgentKt.stream(threadId, query)` runs a turn on a thread you name as a cold `Flow<AgentStreamItem>`: each collection
starts the turn and emits an `AgentStreamItem.Event` for each event, then one `AgentStreamItem.Done` carrying the
`JAgentResult`. The answer's text deltas are in the flow only when the agent's model calls stream, which is what
`streaming = true` on `createAgent` or `streaming()` in the `agent` block sets:

```kotlin
import org.llm4s.agent.events.AgentEvents
import org.llm4s.javaapi.StreamEvents
import org.llm4s.kotlin.AgentStreamItem
import org.llm4s.toolapi.ToolRegistry

val agent = Llm4s.createAgent(client, ToolRegistry.empty(), streaming = true)
agent.stream(threadId, "Explain monads").collect { item ->
    when (item) {
        is AgentStreamItem.Event -> StreamEvents.decode(AgentEvents.TextDelta(), item.event).ifPresent { print(it.text()) }
        is AgentStreamItem.Done  -> println("\n${item.result.status().kind()}")
    }
}
```

*From the "Java and Kotlin" section of [Streaming agents](agents/streaming#java-and-kotlin); `AgentDslTest` and
`AgentKtStreamTest` decode `TextDelta` the same way.*

`threadId` is any string you choose: use the same one to continue that conversation. A collector too slow for the
stream's buffer never holds the turn up: it loses live events such as text deltas and receives one `StreamEvent.LiveGap`
where they were dropped, while durable events are never dropped. A refused start or a failed turn throws `LLMException`.
`streamResume` and `streamRecover` are the flows of `resume` and `recover`.

## Suspended turns

A turn whose tools need approval, whose tools or middleware ask a question, or that stops at a breakpoint ends
`SUSPENDED`: the agent waits for you. `AgentKt.pending(result)` lists what it waits for, as `PendingInterrupt`s with an
`id()` and a `kind()`. Answer each with `Answer.approve`, `reject`, `edit`, `reply` or `proceed`, and `resume(threadId,
answers)` continues the turn. You may answer only some; the result is then `SUSPENDED` again with the rest still
pending:

```kotlin
import org.llm4s.javaapi.Answer
import org.llm4s.javaapi.InterruptKind

var turn = agent.run("Deploy the release")
while (AgentKt.pending(turn).isNotEmpty()) {
    val answers = AgentKt.pending(turn).map { p ->
        when (p.kind()) {
            InterruptKind.APPROVAL -> Answer.approve(p.id())            // or Answer.reject(p.id(), "no")
            InterruptKind.QUESTION, InterruptKind.MIDDLEWARE_QUESTION -> Answer.reply(p.id(), """{"ok":true}""")
            InterruptKind.BREAKPOINT -> Answer.proceed(p.id())
        }
    }
    turn = agent.resume(turn.threadId(), answers)
}
println(turn.answer().orElse("(" + turn.status().kind() + ")"))
```

*Adapted from `AgentKtPendingTest` ("a partial resume leaves the rest pending...") and the Java loop in
[Suspended turns from Java and Kotlin](agents/#suspended-turns-from-java-and-kotlin).*

An agent suspends only if something asks it to, such as an approval middleware, which the `agent` block does not set:
build that agent with `Agent.builder` and `Llm4s.wrapAgent`. A malformed answer, an id the thread is not waiting for or
a thread that is not suspended throws `LLMException`. `recover(threadId)` continues a turn that failed or was
cancelled, and `abandon(threadId)` closes one without continuing it. For what approvals, questions and breakpoints
mean, see [Human review](agents/human-review).

## Cancellation and threading

Cancelling the coroutine cancels the call. `complete` runs the blocking call under `runInterruptible`, so cancelling its
coroutine interrupts the thread blocked in the provider call, and a timeout does the same:

```kotlin
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

val reply = withTimeout(30.seconds) { client.complete("Summarise the report") }   // throws TimeoutCancellationException
```

Every `AgentKt` suspend function that runs a turn (`run`, `continueConversation`, `resume` and `recover`) cancels the
turn when its caller is cancelled, waiting up to 5 seconds for the turn to end, and then throws
`CancellationException`. Cancelling the collection of `stream` does the same. A turn that is cancelled leaves its
thread for `recover`, except a turn of `run(query)`, whose random thread id nothing could carry: `run` forgets it. A
thread you named for `stream` is removed with `forget(threadId)`, which throws `LLMException` while its turn still
runs.

A tool handler is a `suspend` function, but the agent runs a tool on a thread of its own, which waits for the handler in
a coroutine of its own (`runBlocking`), not in the caller's. When the coroutine that runs the turn is cancelled, the
agent cancels the turn, which interrupts that thread and so cancels the handler: a suspended `delay`, `withContext` or
other cancellable call in it throws `CancellationException`. A handler that ignores cancellation, such as one that
blocks in a plain Java call, is left to finish.

Which thread each call blocks, whether a client can be shared and what an interrupt does are in
[Threading and cancellation](java-threading-and-cancellation); the Kotlin functions behave as the Java ones described
there, with one difference: a Kotlin cancellation that loses the race to a turn's commit still throws, though the turn's
result is committed.

## Errors

A failed call throws `LLMException`, a `RuntimeException` whose message is the failure's and whose `cause` is the Java
facade's `org.llm4s.javaapi.LlmException`. The cause carries what the Java guide's
[error handling](java#handling-a-failure) reads: the `LlmErrorKind`, `isRetryable()`, and for a provider's error response the
status code and any `Retry-After`:

```kotlin
import org.llm4s.javaapi.LlmErrorKind
import org.llm4s.javaapi.LlmException
import org.llm4s.kotlin.LLMException

try {
    println(client.complete("What is 2 + 2?"))
} catch (e: LLMException) {
    val cause = e.cause as? LlmException
    System.err.println(e.message)
    when (cause?.kind) {
        LlmErrorKind.AUTHENTICATION, LlmErrorKind.CONFIGURATION -> System.err.println("check the API key and the provider section")
        LlmErrorKind.RATE_LIMIT -> System.err.println("rate limited")
        else -> Unit
    }
    if (cause?.isRetryable == true) System.err.println("a retry may succeed")
}
```

*The Java block in [java.md](java#handling-a-failure) written in Kotlin; `LLMException(message, cause: LlmException?)` is
`LLMException.kt`, and `WrapClientTest` reads `kind` the same way.*

These are the rules for what you catch:

- A call cancelled by its coroutine throws `CancellationException`, not `LLMException`: a provider answers an interrupt
  with a `CancelledError`, and the wrapper turns it into a cancellation so that the coroutine ends as cancelled.
- A flow whose turn is cancelled by something other than its own collector fails with `LLMException`.
- Building something invalid throws too: a client block with a missing required field and a tool with a blank name or no
  handler throw `LLMException`; a completion option out of range throws `IllegalArgumentException`.
- The `agent { ... }` block is the exception: it returns a failed `Result` holding the `LLMException`.

## Testing without an API key

`FakeLlmClient` in `llm4s-java-api` is a scripted `JLlmClient`: `replying("a", "b")` answers with each reply in turn, so
a test needs no key and no network. Wrap its `client()` with `Llm4s.wrapClient`, and the Kotlin code under test cannot
tell it from a real one:

```kotlin
import kotlinx.coroutines.runBlocking
import org.llm4s.javaapi.FakeLlmClient
import org.llm4s.kotlin.Llm4s

val fake = FakeLlmClient.replying("Four.")
val client = Llm4s.wrapClient(fake.client())
runBlocking { println(client.complete("What is 2 + 2?")) }   // Four.
println(fake.prompts())                                      // [What is 2 + 2?]
```

*`FakeLlmClient.replying(...).client()` and `prompts()` are as `AgentDslTest` uses them; `wrapClient` is from
[java.md](java#configure-in-code). The guide for scripting failures and the rest of its API is
[Testing your code](java#testing-your-code).*

The same works for an agent: pass the wrapped client to `agent("id", client) { ... }` or `Llm4s.createAgent(client)`.

## See also

- [Java guide](java): the facade `llm4s-kotlin-api` wraps, and the source of the client builders' fields.
- [Threading and cancellation](java-threading-and-cancellation): which thread a call blocks, and what an interrupt does.
- [Streaming agents](agents/streaming#java-and-kotlin) and [Suspended turns](agents/#suspended-turns-from-java-and-kotlin):
  the agent concepts behind `stream` and `resume`.
- [Human review](agents/human-review): approvals, questions and breakpoints.
