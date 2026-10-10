---
layout: page
title: Fall back between providers
parent: Cookbook
grand_parent: Examples
nav_order: 8
---

# Fall back between providers
{: .no_toc }

Retry a provider with ReliableClient, and ask a second provider when the first still fails.
{: .fs-6 .fw-300 }

## The problem

Providers have outages and rate limits. `ReliableClient` wraps a client with retries, a circuit breaker and a
deadline, so a brief failure is retried and a long one fails fast. When the first provider is still failing, the
recipe asks a second. The demo simulates the outage: the primary always fails with a 503, and the fallback is the
scripted client, or with `--live` your configured provider.

## The program

The whole program, [`FallbackRecipe.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/cookbook/FallbackRecipe.scala). `script` is the stand-in for a model that answers without a
network or a key; `demo` runs the recipe and returns what to print.

```scala
package org.llm4s.samples.cookbook

import org.llm4s.error.ServiceError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, Conversation, UserMessage }
import org.llm4s.reliability.{ ReliabilityConfig, ReliableClient, RetryPolicy }
import org.llm4s.types.Result

import scala.concurrent.duration._

/** An answer and the provider that gave it. */
final case class Served(by: String, text: String)

/** Two providers, each retried on its own, the second asked only when the first still fails. */
final class FallbackClients(primary: LLMClient, fallback: LLMClient, config: ReliabilityConfig) {
  // Build these once and keep them: each circuit breaker counts failures across calls.
  private val first  = new ReliableClient(primary, "primary", config)
  private val second = new ReliableClient(fallback, "fallback", config)

  def ask(question: String): Result[Served] = {
    val conversation = Conversation(Seq(UserMessage(question)))
    first.complete(conversation).map(c => Served("primary", c.content)) match {
      case Left(_) => second.complete(conversation).map(c => Served("fallback", c.content))
      case served  => served
    }
  }
}

/** Recipe: fall back to a second provider when the first is down, after retrying it. */
object FallbackRecipe extends RecipeApp {

  val info: RecipeInfo = RecipeInfo(
    id = "fallback",
    title = "Fall back between providers",
    summary = "Retry a provider with ReliableClient, and ask a second one when the first still fails.",
    mainClass = "org.llm4s.samples.cookbook.FallbackRecipe"
  )

  /** Two attempts per provider, 200 ms apart, and at most 30 seconds per call including the retries. */
  val config: ReliabilityConfig = ReliabilityConfig(
    retryPolicy = RetryPolicy.fixedDelay(maxAttempts = 2, delay = 200.millis),
    deadline = Some(30.seconds)
  )

  /** A provider that is down: every call fails with a 503, which `ReliableClient` treats as worth retrying. */
  def outage: ScriptedClient =
    new ScriptedClient((_, _) => Left(ServiceError(503, "primary", "service unavailable")))

  /** The provider that is up. */
  def script: ScriptedClient =
    new ScriptedClient((_, _) => Right(AssistantMessage("Our office opens at nine on weekdays.")))

  /** The primary is a simulated outage, so the fallback, `client`, answers; with `--live` it is your provider. */
  def demo(client: LLMClient): Result[String] =
    new FallbackClients(outage, client, config).ask("When does the office open?").map(s => s"[${s.by}] ${s.text}")
}
```

## Run it

```bash
sbt "samples/runMain org.llm4s.samples.cookbook.FallbackRecipe"          # scripted client, no API key
sbt "samples/runMain org.llm4s.samples.cookbook.FallbackRecipe --live"   # the provider your configuration names
```

The first command needs nothing but sbt. The second uses the section `llm4s.providers.provider` names; see
[running the samples](../../getting-started/configuration#running-the-samples). CI runs every recipe against its scripted client, and checks
that the program on this page is the source file, so what you read here compiles and works.

## Use a real provider

Build the two clients from two named sections of your configuration, and drop the simulated outage:

```scala
for {
  registry <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registry
  primary  <- Llm4sConfig.provider("openai-main").flatMap(config => LLMConnect.getClient(config))
  fallback <- Llm4sConfig.provider("claude").flatMap(config => LLMConnect.getClient(config))
  served   <- new FallbackClients(primary, fallback, FallbackRecipe.config).ask(question)
} yield served
```

Each section needs its provider module and key; see [Switching providers](../../getting-started/configuration#switching-providers)
and the [reliability guide](https://github.com/llm4s/llm4s/blob/main/docs/reliability-guide.md).

## Pitfalls

- Build each `ReliableClient` once and keep it: its circuit breaker counts failures across calls. One built per
  request never opens.
- `ReliableClient` retries only errors a retry can fix (timeouts, 429, 5xx). A bad key or an invalid request fails
  at once; the recipe still tries the fallback then, which helps with a revoked key and not with a bad request.
- The two providers answer differently, and may not support the same features (tools, schemas, context size).
  Test your prompts on both.
- The retries and the deadline add up: two attempts per provider with a 30-second deadline can wait a minute before
  the fallback's error comes back.

[Back to the cookbook](../cookbook)
