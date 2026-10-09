---
layout: page
title: Java Threading and Cancellation
parent: User Guide
nav_order: 20
---

# Java Threading and Cancellation
{: .no_toc }

Which thread a call blocks, whether one client can be shared, how to run calls on virtual threads and what an
interrupt does, for code that uses `llm4s-java-api`.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

Every statement on this page is checked by a test in
[`ThreadingModelSpec`](https://github.com/llm4s/llm4s/blob/main/modules/java-api/src/test/scala/org/llm4s/javaapi/ThreadingModelSpec.scala)
unless it says otherwise, and was observed on JDK 21. Virtual threads need JDK 21.
The Java snippets below are compiled by
[`ThreadingGuideSnippets.java`](https://github.com/llm4s/llm4s/blob/main/modules/java-api/src/test/java/org/llm4s/javaapi/ThreadingGuideSnippets.java).

## Which calls block

`JLlmClient.complete(...)`, `JAgent.run(...)`, `JAgent.continueConversation(...)`, `JAgent.resume(...)` and
`JAgent.recover(...)` block the calling thread until the answer is back. The only asynchronous variants in the
Java API are `JAgent.stream`, `streamResume` and `streamRecover`, which return an `AgentStream` at once. `LlmResult.toCompletableFuture()` does not make a call asynchronous: it
returns a future that is already complete, so `cancel(true)` on it returns `false` and changes nothing.

To run a call off your own thread, submit it to an executor:

```java
ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
Future<LlmResult<String>> answer = pool.submit(() -> client.complete("Summarise this."));
LlmResult<String> result = answer.get();   // waits on your thread; answer.cancel(true) interrupts the call
```

## Which thread runs the call

| Call | The provider call runs on |
|------|---------------------------|
| `JLlmClient.complete` | your thread, whether it is a platform thread or a virtual thread |
| `JAgent.run` | a virtual thread of the library's, never yours: your thread waits in `run`, and the model call runs on a virtual thread of its own |

The library drives an agent run from a virtual thread it starts with `Thread.ofVirtual()`
([`RunHandle.scala`](https://github.com/llm4s/llm4s/blob/main/modules/agent/src/main/scala/org/llm4s/agent/graph/RunHandle.scala)),
and runs each task of the run, the model call included, on a virtual thread of its own, forked with the Ox
structured-concurrency library: the `TaskExecutor` in
[`GraphBuilder.scala`](https://github.com/llm4s/llm4s/blob/main/modules/agent/src/main/scala/org/llm4s/agent/graph/GraphBuilder.scala)
documents that "the default runs them concurrently on virtual threads in an Ox scope". Virtual threads are always
daemon threads. The test checks the thread the model call runs on: not the caller, virtual and a daemon.
On JDK 21 that thread had an empty name (observed, not pinned by a test), so look for the run thread, named
`llm4s-run-<thread id>`, in a thread dump instead.

## Sharing one client or agent

One `JLlmClient` and one `JAgent` can be used by many threads at once. The tests start 50 platform threads on one
client and 32 virtual threads on one agent, hold every call inside the client until all of them are in flight
together, and check that each thread gets the answer to its own query.

## Virtual threads

Calls from virtual threads work, and a call does not hold on to the virtual thread's carrier thread while it waits
for the provider. The test starts more virtual threads than the machine has cores (at least 64), has a local server
that answers only once every request has arrived, and checks that all of them were served at once through
`JLlmClient` and the real Ollama client. A call that pinned its carrier thread could not have served more requests
than there are carriers, so the server would never have answered. A one-off run of the same check with the
scheduler limited to two carrier threads (`-Djdk.virtualThreadScheduler.parallelism=2
-Djdk.virtualThreadScheduler.maxPoolSize=2`) served 16 concurrent requests too; the committed test does not set
those options.

This was verified for the facade, the Ollama provider and the JDK's `HttpClient`. The other providers were not
tested. The OpenAI and Anthropic providers use their vendor SDKs, and this page makes no claim about them.

## Interrupting a call

Interrupt the thread that is blocked in a call and the call returns a failed result whose error is a
`CancelledError`, with the thread's interrupt flag still set, so your own code (and any executor) still sees the
interruption. It behaves the same on a platform thread and on a virtual thread.

```java
LlmResult<String> result = client.complete("Long question");
if (result.isFailure() && result.getError().error() instanceof CancelledError) {
    // the thread was interrupted; its interrupt flag is still set
}
```

`Future.cancel(true)` on a task that wraps a call interrupts the thread running it, which cancels the provider
call in the same way. The Spring Boot starter's `completeAsync` is built on this.

### `InterruptedException` is never thrown, so Java cannot catch it

Neither `JLlmClient.complete` nor `JAgent.run` throws `InterruptedException`. An interrupt comes back as the
`CancelledError` result above, with the flag set, and an `InterruptedException` that a custom `LLMClient` lets escape
is turned into the same `CancelledError`, with the flag restored. The methods therefore declare no checked exception,
and the Java compiler rejects `catch (InterruptedException e)` around either call:

```
error: exception InterruptedException is never thrown in body of corresponding try statement
```

(This message was produced with JDK 21; the test checks the declared exception list, which is what makes the
compiler behave this way.) Test the result instead of catching an exception the call never throws:

```java
LlmResult<String> result = client.complete("hi");
if (result.isFailure() && result.getError().error() instanceof CancelledError) {
    // interrupted while blocked: Thread.currentThread().isInterrupted() is still true, so a
    // loop or an executor further up the stack sees the interruption too
    return null;
}
return result.getOrNull();
```

The flag is still set when the result comes back, so there is nothing to restore; clear it with `Thread.interrupted()`
only if you mean to carry on. The test runs this snippet against a call interrupted on a real provider's request
path and against a custom client that throws `InterruptedException`, and checks the flag in both cases.

### Interrupting the caller cancels an agent turn

All four blocking `JAgent` calls - `run`, `continueConversation`, `resume` and `recover` - cancel the turn they are
waiting on when the thread blocked in them is interrupted (since [#1330](https://github.com/llm4s/llm4s/issues/1330);
before, they stopped only the wait and the turn carried on in the background). The call returns a failed result
whose error is a `CancelledError`, with the interrupt flag still set; the turn's model call is interrupted, and the
conversation's thread is left for `recover`. The call returns once the cancelled turn has ended, so `recover` can
follow at once. It waits up to 5 seconds for the turn to end (with tracing, delivering the ended turn's last trace
events can add to that): a provider that ignores the interrupt for longer is logged at WARN and left to finish on its
own, and until it does the thread is busy (`ThreadBusy`). A thread that is already
interrupted when it calls one of them starts no turn. To stop a turn from Java, interrupt the thread that called it -
for example `Future.cancel(true)` on the task that called it.

A turn that had already begun committing its outcome when the interrupt came cannot be cancelled: the call returns
that outcome - a completed or suspended turn - with the interrupt flag still set. `run(query)` starts a conversation
whose id a failed result does not carry, so a turn of it that fails or is cancelled is forgotten once it has ended.
A conversation you named, for `stream` say, is forgotten with `forget(threadId)`, which needs no result.

To cancel a turn without interrupting any thread, start it with `JAgent.stream`, `streamResume` or `streamRecover`
and call `cancel()` on the returned `AgentStream`. `cancel()` stops the turn and leaves the thread for `streamRecover`
or `recover`. Like the blocking calls, it waits up to 5 seconds for the turn to end; if the turn has not ended by then
(a provider that ignores the interrupt), it logs a WARN and returns, leaving that turn to finish on its own.

Kotlin matches Java: every `AgentKt` suspend function that runs a turn - `run`, `continueConversation`, `resume` and
`recover` - cancels the turn when the calling coroutine is cancelled (see
[Suspended turns from Java and Kotlin](agents/#suspended-turns-from-java-and-kotlin)), waiting up to 5 seconds for the
turn to end through `AgentStream.cancel()`, and the one-shot `run(query)` forgets the thread of a turn that failed or
was cancelled once it has ended. One difference remains, as coroutine cancellation requires: a Kotlin cancellation
that loses the race to a turn's commit still throws, though the turn's result is committed.

`ThreadingModelSpec` checks `run`; `JAgentPendingSpec` checks `continueConversation`, `resume`, `recover` and an
already-interrupted caller; `JAgentStreamSpec` checks `AgentStream.cancel()` and its 5-second bound; the blocking
calls' 5-second bound, the commit that beats a cancel and the forgotten one-shot and `runMultiTurn` threads are
checked by `AgentRunCancellationSpec` in `llm4s-agent`, and the Kotlin functions - their bound and `run`'s forgotten
thread included - by `AgentKtStreamTest`, `AgentKtPendingTest` and `AgentKtTest`.

## Timeouts

A call that gets no answer ends with a `TimeoutError`. The limit is set by each provider client: two minutes for a
non-streaming call in the Ollama
([`OllamaClient.scala`](https://github.com/llm4s/llm4s/blob/main/modules/ollama/src/main/scala/org/llm4s/llmconnect/provider/OllamaClient.scala)),
Gemini and Vertex AI clients and in the OpenAI-compatible clients, which allow five minutes for a streaming call
([`OpenAICompatibleClient.scala`](https://github.com/llm4s/llm4s/blob/main/modules/openai-compatible/src/main/scala/org/llm4s/llmconnect/provider/OpenAICompatibleClient.scala)).
Against a server that never answered, the Ollama client returned `TimeoutError: request timed out after 120
seconds`. That run took two minutes, so no test pins it. The OpenAI and Anthropic limits come from their SDKs and
were not checked. The limit cannot be configured in this release; [#712](https://github.com/llm4s/llm4s/issues/712)
tracks making it configurable.

## Shutting down

`JLlmClient` implements `AutoCloseable` and `close()` closes the provider client, so use it in a
try-with-resources block. The agent's run and task threads are virtual and so are daemon threads, and its graph writer
thread is a daemon thread
([`GraphRuntime.scala`](https://github.com/llm4s/llm4s/blob/main/modules/agent/src/main/scala/org/llm4s/agent/graph/GraphRuntime.scala)).
In a one-off check on JDK 21, a call through the Ollama provider left only daemon threads (the JDK
`HttpClient`'s selector and workers), and none were left after `close()`. No test pins that, because the set of
threads in a shared test JVM is not predictable. Forgetting to close a client therefore should not keep the JVM from
exiting, but close it anyway.
