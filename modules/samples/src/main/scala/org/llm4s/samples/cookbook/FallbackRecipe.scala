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
