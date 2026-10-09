package org.llm4s.samples.metrics

import org.llm4s.agent.Agent
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.llmconnect.model.{ Conversation, TokenUsage, UsageSummary, UserMessage }
import org.llm4s.metrics.{ CostTracker, MetricsCollector }
import org.llm4s.model.{ ModelCapabilities, ModelMetadata, ModelMode, ModelPricing, ModelRegistryService }
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.builtin.BuiltinTools
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import scala.util.Using

/**
 * Cost tracking end to end, at three levels, plus how to price a model the registry does not know.
 *
 *  1. '''Per request''': `Completion.estimatedCost` after one call.
 *  1. '''Per agent run''': `AgentResult.usage` after a multi-step `Agent.run` that calls a tool.
 *  1. '''Per session''': a `CostTracker`, a `MetricsCollector` the client reports to, so it sees every call
 *     made through that client.
 *  1. '''Custom pricing''': a `ModelRegistryService` built with your own `ModelMetadata`, passed to the client
 *     as the given registry. The registry is an immutable snapshot, so nothing global is changed.
 *  1. '''Composing collectors''': `MetricsCollector.compose` fans every event out to several collectors; here a
 *     session-wide tracker and one for the custom-pricing demo both receive it.
 *
 * The cost of a request is its tokens times the model's price in the registry, and it is `None` when the
 * registry has no price for the model. A model with no price shows as ''unknown'' here, never as a guessed
 * number: the sample prices the model your configuration names, so run it with a model the registry knows
 * (OpenAI, Anthropic, Gemini, and so on) or read demo 4 to supply the price yourself. Self-hosted models,
 * such as one served by Ollama, are usually not priced.
 *
 * `CostTracker` and `UsageSummary` hold a cost of `0` both for a free model and for one with no price, so the
 * output says which it is.
 *
 * To run it against the default provider of your `application.conf` (see the configuration guide):
 * {{{
 * sbt "samples/runMain org.llm4s.samples.metrics.CostTrackingExample"
 * }}}
 *
 * For production monitoring of the same events, see [[PrometheusMetricsExample]].
 */
object CostTrackingExample {

  private val logger = LoggerFactory.getLogger(getClass)

  private val RequestPrompt = "In one sentence, what is a token in a large language model?"

  private val AgentPrompt =
    "What is 17 multiplied by 23? Use the calculator tool, then tell me today's date with the date and time tool."

  /**
   * Example rates for the custom-pricing demo, in USD per million tokens. They are made up for the
   * demonstration: put your own contract's rates in their place.
   */
  private[samples] val ExampleInputPerMillion: Double  = 2.0
  private[samples] val ExampleOutputPerMillion: Double = 8.0

  /** One request's token usage and estimated cost; `cost` is `None` when the registry has no price for the model. */
  final case class RequestCost(model: String, usage: Option[TokenUsage], cost: Option[Double])

  /** The custom-pricing demo: the rates registered, the request priced with them, and the demo's own tracker. */
  final case class CustomPricing(
    modelId: String,
    inputPerMillion: Double,
    outputPerMillion: Double,
    request: RequestCost,
    demoTracker: UsageSummary
  )

  /**
   * Everything the demos measured.
   *
   * @param model        the configured model, which every demo prices
   * @param priced       whether the registry has a price for `model`
   * @param perRequest   demo 1
   * @param agent        demo 2: `AgentResult.usage`
   * @param session      the session tracker after demos 1 and 2
   * @param custom       demo 4
   * @param sessionTotal the session tracker after all of them
   */
  final case class CostReport(
    model: String,
    priced: Boolean,
    perRequest: RequestCost,
    agent: UsageSummary,
    session: UsageSummary,
    custom: CustomPricing,
    sessionTotal: UsageSummary
  )

  def main(args: Array[String]): Unit = {
    val outcome = for {
      providerCfg <- Llm4sConfig.defaultProvider()
      registry    <- Llm4sConfig.modelRegistryService()
      report      <- run(providerCfg, registry)
    } yield report

    outcome.fold(
      error => logger.error("[CostTrackingExample] Failed: {}", error.formatted),
      report => render(report).foreach(println)
    )
  }

  /** Demos 1 and 2, measured on one client that reports to the session tracker. */
  final private case class LevelsOneAndTwo(perRequest: RequestCost, agent: UsageSummary, session: UsageSummary)

  /** Runs the demos against `providerCfg`, pricing from `registry`. */
  private[samples] def run(providerCfg: ProviderConfig, registry: ModelRegistryService): Result[CostReport] = {
    val session = CostTracker.create()

    for {
      // Demos 1 to 3: one client, reporting to the session tracker, priced from the registry as it is.
      levels <- withClient(providerCfg, session, registry)(levelsOneAndTwo(_, session))
      // Demos 4 and 5: a second client, priced from a registry that carries the custom price.
      custom <- demonstrateCustomPricing(providerCfg, registry, session)
    } yield CostReport(
      model = providerCfg.model,
      priced = isPriced(registry, providerCfg.model),
      perRequest = levels.perRequest,
      agent = levels.agent,
      session = levels.session,
      custom = custom,
      sessionTotal = session.snapshot
    )
  }

  /** Builds a client priced from `registry`, runs `use` with it, and closes it: `LLMClient` is `AutoCloseable`. */
  private def withClient[A](providerCfg: ProviderConfig, metrics: MetricsCollector, registry: ModelRegistryService)(
    use: LLMClient => Result[A]
  ): Result[A] = {
    given ModelRegistryService = registry
    LLMConnect.getClient(providerCfg, metrics).flatMap(client => Using.resource(client)(use))
  }

  /** Demos 1 and 2 on `client`: the per-request cost, then an agent run, and the session tracker after both. */
  private def levelsOneAndTwo(client: LLMClient, session: CostTracker): Result[LevelsOneAndTwo] =
    for {
      perRequest <- requestCost(client)
      tools      <- BuiltinTools.coreSafe
      agent      <- Agent.builder("cost-demo", client).withTools(new ToolRegistry(tools)).build()
      result     <- agent.run(AgentPrompt)
      _          <- agent.forget(result.threadId)
    } yield LevelsOneAndTwo(perRequest, result.usage, session.snapshot)

  private def requestCost(client: LLMClient): Result[RequestCost] =
    for {
      conversation <- Conversation.create(UserMessage(RequestPrompt))
      completion   <- client.complete(conversation)
    } yield RequestCost(completion.model, completion.usage, completion.estimatedCost)

  private def demonstrateCustomPricing(
    providerCfg: ProviderConfig,
    registry: ModelRegistryService,
    session: CostTracker
  ): Result[CustomPricing] =
    for {
      priced <- withCustomPricing(
        registry,
        providerCfg.model,
        providerCfg.providerId.asString,
        ExampleInputPerMillion,
        ExampleOutputPerMillion
      )
      demoTracker = CostTracker.create()
      // compose: the session tracker and the demo's own tracker both receive every event.
      request <- withClient(providerCfg, MetricsCollector.compose(session, demoTracker), priced)(requestCost)
    } yield CustomPricing(
      providerCfg.model,
      ExampleInputPerMillion,
      ExampleOutputPerMillion,
      request,
      demoTracker.snapshot
    )

  /**
   * A registry that prices `modelId` at the given rates and otherwise knows what `base` knows.
   *
   * `ModelRegistryService` is an immutable snapshot, so this builds a new one and leaves `base` as it was: pass
   * the result as the given registry of the client that should use the price. A model already in `base` under
   * `modelId` is replaced. Only `pricing` matters for cost estimation; the other fields describe the model.
   */
  private[samples] def withCustomPricing(
    base: ModelRegistryService,
    modelId: String,
    provider: String,
    inputPerMillion: Double,
    outputPerMillion: Double
  ): Result[ModelRegistryService] =
    for {
      providers <- base.listProviders()
      known <- providers.foldLeft[Result[List[ModelMetadata]]](Right(Nil)) { (accumulated, name) =>
        for {
          models <- accumulated
          more   <- base.listByProvider(name)
        } yield models ++ more
      }
    } yield {
      val custom = ModelMetadata(
        modelId = modelId,
        provider = provider,
        mode = ModelMode.Chat,
        maxInputTokens = None,
        maxOutputTokens = None,
        inputCostPerToken = None,
        outputCostPerToken = None,
        capabilities = ModelCapabilities(),
        pricing = ModelPricing(
          inputCostPerToken = Some(inputPerMillion / 1.0e6),
          outputCostPerToken = Some(outputPerMillion / 1.0e6)
        ),
        deprecationDate = None
      )
      ModelRegistryService.fromModels(known.filterNot(_.modelId == modelId) :+ custom)
    }

  /** Whether `registry` has an input price for `model`, which is what makes an estimated cost possible. */
  private[samples] def isPriced(registry: ModelRegistryService, model: String): Boolean =
    registry.lookup(model).toOption.exists(_.pricing.inputCostPerToken.isDefined)

  private def dollars(amount: BigDecimal): String = "$" + amount.setScale(6, BigDecimal.RoundingMode.HALF_UP)

  private def tokens(usage: UsageSummary): String = s"${usage.inputTokens} in, ${usage.outputTokens} out"

  private def costOrUnknown(request: RequestCost): String =
    request.cost.fold(s"unknown (no price for '${request.model}' in the registry)")(c => dollars(BigDecimal.decimal(c)))

  /** The output, one line each; the cost lines say when a total of 0 means "no price" and not "free". */
  private[samples] def render(report: CostReport): Seq[String] = {
    def header(title: String): Seq[String] = Seq("", "=" * 60, title, "=" * 60)
    val noPrice =
      if (report.priced) None
      else Some(s"  (the registry has no price for '${report.model}': a total of 0 means unknown, not free)")

    val perRequest = header("1. Per request: Completion.estimatedCost") ++ Seq(
      s"  model:  ${report.perRequest.model}",
      s"  tokens: ${report.perRequest.usage
          .fold("not reported by the provider")(u => s"${u.promptTokens} in, ${u.completionTokens} out")}",
      s"  cost:   ${costOrUnknown(report.perRequest)}"
    )

    val agent = header("2. Per agent run: AgentResult.usage") ++ Seq(
      s"  requests: ${report.agent.requestCount} (one per model call the agent made, including the one after the tool ran)",
      s"  tokens:   ${tokens(report.agent)}",
      s"  cost:     ${dollars(report.agent.totalCost)}"
    ) ++ noPrice

    val session = header("3. Per session: CostTracker, after demos 1 and 2") ++ Seq(
      s"  requests: ${report.session.requestCount}",
      s"  tokens:   ${tokens(report.session)}",
      s"  cost:     ${dollars(report.session.totalCost)}"
    ) ++ noPrice

    val custom = report.custom
    val check = custom.request.usage.map { u =>
      val inCost  = u.promptTokens * custom.inputPerMillion / 1.0e6
      val outCost = u.completionTokens * custom.outputPerMillion / 1.0e6
      f"  check:  ${u.promptTokens} in x $$${custom.inputPerMillion}%.2f/M + ${u.completionTokens} out x $$${custom.outputPerMillion}%.2f/M = $$${inCost + outCost}%.6f"
    }
    val customLines = header("4. Custom pricing: a registry that prices your model") ++ Seq(
      f"  registered '${custom.modelId}' at $$${custom.inputPerMillion}%.2f/M input and $$${custom.outputPerMillion}%.2f/M output",
      "  (example rates for the demonstration; ModelRegistryService is an immutable snapshot, so nothing global changed)",
      s"  cost:   ${costOrUnknown(custom.request)}"
    ) ++ check

    val unpricedBefore =
      if (report.priced) None
      else
        Some(
          s"  (the ${report.session.requestCount} requests of demos 1 to 3 had no price, so only the custom-priced one is costed)"
        )
    val composed = header("5. Composed collectors: MetricsCollector.compose") ++ Seq(
      s"  the demo's own tracker saw ${custom.demoTracker.requestCount} request, costing ${dollars(custom.demoTracker.totalCost)}",
      s"  the session tracker saw ${report.sessionTotal.requestCount} requests in all, costing ${dollars(report.sessionTotal.totalCost)}"
    ) ++ unpricedBefore ++ Seq("  For production monitoring of the same events, see PrometheusMetricsExample.")

    perRequest ++ agent ++ session ++ customLines ++ composed
  }
}
