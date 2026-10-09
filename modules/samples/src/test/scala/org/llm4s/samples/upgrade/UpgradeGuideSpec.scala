package org.llm4s.samples.upgrade

import org.llm4s.agent.{ Agent, AgentId, AgentStatus }
import org.llm4s.config.PgSearchIndexConfigLoader
import org.llm4s.error.{ CancelledError, LLMError, RateLimitError }
import org.llm4s.extract.TikaDocumentExtractor
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.{ BedrockProvider, Llm4sOpenAIModule }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.trace.spi.TracingBackends
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ ArrayBlockingQueue, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.util.Try

/**
 * Compiles and runs the snippets of `docs/migrations/0-4-1-to-0-5-0.md`, and checks the facts its tables state
 * against the code they describe.
 *
 * It lives in `llm4s-samples` because that is the one module with every provider, the agent and the tracing
 * backends on its classpath, which is what the guide's tables are about. The configuration snippets (a section
 * that holds only `provider` and `model`, the key coming from the bound variable) are verified by
 * `DocumentedProviderConfigSpec` in `llm4s-openai`, which loads them as written. No request is sent: the agent
 * talks to a script, not a model.
 */
class UpgradeGuideSpec extends AnyFlatSpec with Matchers with EitherValues {

  // ---- "which artifact do I add?" ----

  /** The provider table of the guide: each module's `Llm4sProviderModule`, its chat ids and its embedding ids. */
  private val providerTable: Map[String, (Set[String], Set[String])] = Map(
    "Llm4sOpenAIModule"    -> (Set("openai", "azure", "requesty"), Set("openai")),
    "Llm4sAnthropicModule" -> (Set("anthropic"), Set.empty[String]),
    "Llm4sGeminiModule"    -> (Set("gemini", "vertexai"), Set.empty[String]),
    "Llm4sOllamaModule"    -> (Set("ollama"), Set("ollama")),
    "Llm4sOpenAICompatibleModule" -> (
      Set("openai-compatible", "deepseek", "zai", "openrouter", "mistral", "cohere"),
      Set.empty[String]
    ),
    "Llm4sBedrockModule" -> (Set("bedrock"), Set.empty[String]),
    "Llm4sWatsonXModule" -> (Set("watsonx"), Set.empty[String]),
    "Llm4sVoyageModule"  -> (Set.empty[String], Set("voyage")),
    "Llm4sJinaModule"    -> (Set.empty[String], Set("jina")),
    "Llm4sCohereModule"  -> (Set.empty[String], Set("cohere"))
  )

  "The provider table" should "name exactly the ids each provider module registers" in {
    val registry = ProviderRegistry.discover()
    registry.report.failures shouldBe empty

    val found = registry.report.modules.map { module =>
      module.moduleClass.split('.').last -> (module.providerIds.toSet, module.embeddingProviderIds.toSet)
    }.toMap

    found shouldBe providerTable
  }

  "Registering a provider module explicitly" should "work without a classpath scan, for a fat jar that lost its services files" in {
    // The snippet of the guide.
    val registry = ProviderRegistry.ofModules(new Llm4sOpenAIModule).withProvider(BedrockProvider)

    (registry.ids should contain).allOf("openai", "azure", "requesty", "bedrock")
    registry.ids should not contain "anthropic"
    registry.report.discovered shouldBe false
  }

  // ---- tracing ----

  "Tracing backends" should "be found on the classpath, by the mode name TRACING_MODE selects" in {
    // `llm4s-samples` depends on `llm4s-observability` but not on `llm4s-observability-otel`: a backend is on the
    // classpath exactly when its artifact is, which is why the guide says to add the artifact.
    val modes = TracingBackends.discover().modes.map(_.name)

    modes should contain("langfuse")
    modes should not contain "opentelemetry"
  }

  // ---- agent users ----

  private class ScriptedClient(answer: String) extends LLMClient {
    val calls = new AtomicInteger(0)

    private def reply: Completion = {
      calls.incrementAndGet(): Unit
      val message = AssistantMessage(answer)
      Completion(id = "script", created = 0L, content = message.content, model = "script", message = message)
    }

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = Right(reply)

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = Right(reply)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  "The agent snippet" should "build an agent, run a query and read an AgentResult instead of an AgentState" in {
    val client = new ScriptedClient("Hello from the script")

    // The snippet of the guide, from here ...
    val result = for {
      agent  <- Agent.builder("assistant", client).build()
      result <- agent.run("Say hello")
    } yield result
    // ... to here.

    val run = result.value
    run.status shouldBe AgentStatus.Completed("Hello from the script")
    run.answer shouldBe Some("Hello from the script")
    run.messages.map(_.content) should contain("Say hello")
    // `result.usage` replaces `state.usageSummary`: one request, made against the scripted model.
    run.usage.requestCount shouldBe 1L
    run.usage.byModel.keySet shouldBe Set("script")
  }

  "An interrupted run" should "return CancelledError with the interrupt flag set, and start no turn" in {
    val client = new ScriptedClient("never asked")
    val agent  = Agent.builder("assistant", client).build().value

    // Run on a thread of its own, so the interrupt flag never leaks into the test runner's thread.
    val outcome = new ArrayBlockingQueue[(Result[?], Boolean)](1)
    val caller = Thread.ofVirtual().start { () =>
      Thread.currentThread().interrupt()
      val result = agent.run("Say hello")
      // `offer`, not `put`: `put` would throw on the interrupt flag the run leaves set.
      outcome.offer(result -> Thread.currentThread().isInterrupted): Unit
    }
    val (result, flagSet) = Option(outcome.poll(30, TimeUnit.SECONDS)).getOrElse(fail("the run did not return"))
    caller.join()

    result.left.value shouldBe a[CancelledError]
    flagSet shouldBe true
    client.calls.get shouldBe 0
  }

  "Orchestration" should "be gone, with the agent id in org.llm4s.agent" in {
    val removed = Seq(
      "org.llm4s.agent.orchestration.PlanRunner",
      "org.llm4s.agent.orchestration.TypedAgent",
      "org.llm4s.agent.orchestration.CancellationToken",
      "org.llm4s.agent.orchestration.OrchestrationError",
      "org.llm4s.types.package$AgentId",
      "org.llm4s.types.package$PlanId"
    )
    removed.filter(name => Try(Class.forName(name)).isSuccess) shouldBe empty
    // The newtypes that stayed resolve under the same naming, so the check above is not vacuous.
    Try(Class.forName("org.llm4s.types.package$SessionId")).isSuccess shouldBe true

    AgentId.of("assistant").isRight shouldBe true
  }

  // ---- RAG, memory, extraction ----

  "The RAG source breaks" should "resolve to the names the guide gives" in {
    // `UniversalExtractor` and `rag.extract.DocumentExtractor` became one extractor in `org.llm4s.extract`.
    TikaDocumentExtractor.canExtract("application/pdf") shouldBe true

    // `Llm4sConfig.pgSearchIndex()` became `PgSearchIndexConfigLoader.default()`. The `llm4s.rag.permissions.pg`
    // defaults ship in `llm4s-rag`'s own `reference.conf`, so it loads with no application configuration.
    val pg = PgSearchIndexConfigLoader.default().value
    pg.host shouldBe "localhost"
    pg.port shouldBe 5432
  }

  // ---- errors, cancellation and types ----

  "The error model" should "have a total isRecoverable and a typed retry delay" in {
    // `LLMError.isRecoverable(error)` replaced the extension `error.isRecoverable`.
    LLMError.isRecoverable(RateLimitError("openai", 1.second)) shouldBe true
    LLMError.isRecoverable(CancelledError("a call")) shouldBe false

    // The delay is a `FiniteDuration`, not a number of milliseconds.
    RateLimitError("openai", 1500.millis).retryDelay shouldBe Some(1500.millis)
  }

  "Growth-prone types" should "be changed with with* setters, not copy" in {
    val options = CompletionOptions(temperature = 0.2).withMaxTokens(500)

    options.temperature shouldBe 0.2
    options.maxTokens shouldBe Some(500)
  }
}
