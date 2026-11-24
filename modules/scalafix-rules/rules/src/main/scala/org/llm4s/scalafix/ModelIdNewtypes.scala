package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * A model id that crosses the public API is a `ModelName`, a provider id a `ProviderId`, and a provider config's base
 * URL a `BaseUrl`, where 0.6.0 took a `String` (#2822). The argument is wrapped, whatever expression it is, at the
 * common call sites:
 *
 *  - `Completion(..., model = "gpt-4o", ...)` (named, or fourth by position, after 0.6.0's `content`) and
 *    `completion.withModel("gpt-4o")`;
 *  - `ModelMetadata(modelId = ..., provider = ...)` (named, or first and second by position), `withModelId` and
 *    `withProvider`;
 *  - `EmbeddingModelConfig("text-embedding-3-small", 1536)` (the name, named or first by position);
 *  - `ModelRegistryService#lookup(id)`, `lookup(provider, model)` and `listByProvider(provider)`,
 *    `ModelDimensionRegistry.getDimension(provider, model)`, `CostEstimator.estimate(model, ...)`,
 *    `UsageSummary#add(model, ...)`, `StreamingAccumulator(model)`, and `withModel` on `ProviderExchange`;
 *  - a provider config's `fromValues`: its model (first by position, or named `modelName`/`model`) and its base URL
 *    (`baseUrl`/`endpoint`, named or in its 0.6.0 position); a named `baseUrl`/`endpoint` of its constructor, and
 *    `config.withBaseUrl(...)`.
 *
 * A model or base URL passed to a constructor by position, a `UsageSummary`'s `byModel` map and a function typed
 * `String => ...` that is now `ModelName => ...` are left for the compiler to report. The imports are added where a
 * newtype is used.
 */
class ModelIdNewtypes(leaveFromValuesOf: Set[String]) extends SemanticRule("ModelIdNewtypes") {

  /** The rule run by name: it rewrites every config's `fromValues`. */
  def this() = this(Set.empty)

  override def description: String =
    "Wraps model ids in ModelName, provider ids in ProviderId and provider configs' base URLs in BaseUrl at the common call sites"

  import ModelIdNewtypes._

  override def fix(implicit doc: SemanticDocument): Patch = {
    val wraps = doc.tree.collect { case t: Term.Apply if !t.fun.is[Term.Apply] => arguments(t) }.flatten
    val patch = wraps.map { case (arg, newtype) => Patch.replaceTree(arg, s"$newtype(${arg.pos.text})") }.asPatch
    wraps.map(_._2).distinct.foldLeft(patch)((p, newtype) => p + Migration.importOnce(Newtypes, newtype))
  }

  /** The arguments of a call that are wrapped, each with its newtype. */
  private def arguments(t: Term.Apply)(implicit doc: SemanticDocument): List[(Term, String)] =
    Migration.callee(t).fold(List.empty[(Term, String)]) { callee =>
      val args  = t.argClause.values
      val owner = Migration.owner(callee)
      def named(names: Set[String], newtype: String): List[(Term, String)] =
        args.collect { case Term.Assign(Term.Name(n), rhs) if names(n) => rhs -> newtype }
      def positional(index: Int, newtype: String): List[(Term, String)] =
        args.lift(index).filterNot(_.is[Term.Assign]).map(_ -> newtype).toList
      def single(newtype: String): List[(Term, String)] = args match {
        case List(arg) if !arg.is[Term.Assign] => List(arg -> newtype)
        case _                                 => Nil
      }
      val found: List[(Term, String)] =
        if (Migration.constructs(callee, Completion))
          // 0.5.x and 0.6.x: (id, created, content, model, message, ...); CompletionFromMessage drops the content
          named(Set("model"), Model) ++ positional(3, Model)
        else if (Migration.constructs(callee, Metadata))
          named(Set("modelId"), Model) ++ positional(0, Model) ++ named(Set("provider"), Provider) ++
            positional(1, Provider)
        else if (Migration.constructs(callee, EmbeddingModel))
          named(Set("name"), Model) ++ positional(0, Model)
        else if (Migration.constructs(callee, Accumulator))
          single(Model)
        else if (configs.contains(owner) && Migration.constructs(callee, owner))
          named(Set("baseUrl", "endpoint"), Url)
        else
          callee match {
            case FromValues(config) if configs.contains(config) && !leaveFromValuesOf.contains(config) =>
              named(Set("modelName", "model"), Model) ++ positional(0, Model) ++
                named(Set("baseUrl", "endpoint"), Url) ++ baseUrlPosition.get(config).toList.flatMap(positional(_, Url))
            case WithBaseUrl(config) if configs.contains(config)  => single(Url)
            case WithModel(on) if modelOwners.contains(on)        => single(Model)
            case s if s.startsWith(Metadata + "#withModelId(")    => single(Model)
            case s if s.startsWith(Metadata + "#withProvider(")   => single(Provider)
            case s if s.startsWith(Registry + "#listByProvider(") => single(Provider)
            case s if s.startsWith(Registry + "#lookup(") =>
              if (args.size == 2)
                positional(0, Provider) ++ named(Set("provider"), Provider) ++ positional(1, Model) ++
                  named(Set("modelName"), Model)
              else single(Model)
            case s if s.startsWith(Dimensions + ".getDimension(") =>
              positional(0, Provider) ++ named(Set("provider"), Provider) ++ positional(1, Model) ++
                named(Set("model"), Model)
            case s if s.startsWith(Estimator + ".estimate(") => positional(0, Model) ++ named(Set("model"), Model)
            case s if s.startsWith(Summary + "#add(")        => positional(0, Model)
            case _                                           => Nil
          }
      found.filterNot { case (arg, newtype) => isWrapped(arg, newtype) }
    }

  // A config's DEFAULT_BASE_URL is a BaseUrl already
  private def isWrapped(arg: Term, newtype: String): Boolean = arg match {
    case a: Term.Apply => a.fun.syntax == newtype || a.fun.syntax.endsWith("." + newtype)
    case _             => newtype == Url && arg.syntax.endsWith("DEFAULT_BASE_URL")
  }
}

object ModelIdNewtypes {

  private val Newtypes = "org.llm4s.types.ProviderModelTypes"
  private val Model    = "ModelName"
  private val Url      = "BaseUrl"
  private val Provider = "ProviderId"

  private val Completion     = "org/llm4s/llmconnect/model/Completion"
  private val Metadata       = "org/llm4s/model/ModelMetadata"
  private val EmbeddingModel = "org/llm4s/llmconnect/config/EmbeddingModelConfig"
  private val Accumulator    = "org/llm4s/llmconnect/streaming/StreamingAccumulator"
  private val Registry       = "org/llm4s/model/ModelRegistryService"
  private val Estimator      = "org/llm4s/llmconnect/provider/CostEstimator"
  private val Summary        = "org/llm4s/llmconnect/model/UsageSummary"
  private val Dimensions     = "org/llm4s/llmconnect/config/ModelDimensionRegistry"

  private val FromValues  = """(.+)\.fromValues\((?:\+\d+)?\)\.""".r
  private val WithBaseUrl = """(.+)#withBaseUrl\((?:\+\d+)?\)\.""".r
  private val WithModel   = """(.+)#withModel\((?:\+\d+)?\)\.""".r

  /** The types whose `withModel` takes a `ModelName` now and a `String` in 0.6.0 (provider configs: see ProviderConfigNewtypes). */
  private val modelOwners: Set[String] = Set(
    Completion,
    "org/llm4s/llmconnect/ProviderExchange",
    "org/llm4s/llmconnect/provider/ProviderExchangeCall"
  )

  /** Where `fromValues` took the base URL by position in 0.6.0, by config. */
  private val baseUrlPosition: Map[String, Int] = Map(
    "AnthropicConfig"        -> 2,
    "AzureConfig"            -> 2,
    "CohereConfig"           -> 2,
    "DeepSeekConfig"         -> 2,
    "GeminiConfig"           -> 2,
    "MistralConfig"          -> 2,
    "OllamaConfig"           -> 1,
    "OpenAICompatibleConfig" -> 1,
    "OpenAIConfig"           -> 2,
    "PerplexityConfig"       -> 2,
    "WatsonXConfig"          -> 4,
    "XaiConfig"              -> 2,
    "ZaiConfig"              -> 2
  ).map { case (config, index) => ("org/llm4s/llmconnect/config/" + config) -> index }

  /** The provider configs, whose `fromValues` and base URL took a `String` in 0.6.0. */
  private val configs: Set[String] = Set(
    "AnthropicConfig",
    "AzureConfig",
    "BedrockConfig",
    "CohereConfig",
    "DeepSeekConfig",
    "GeminiConfig",
    "MistralConfig",
    "OllamaConfig",
    "OpenAICompatibleConfig",
    "OpenAIConfig",
    "PerplexityConfig",
    "VertexAIConfig",
    "WatsonXConfig",
    "XaiConfig",
    "ZaiConfig"
  ).map("org/llm4s/llmconnect/config/" + _)
}
