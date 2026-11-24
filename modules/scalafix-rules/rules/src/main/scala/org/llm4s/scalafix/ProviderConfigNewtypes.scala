package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * The configuration API takes the newtypes of `org.llm4s.types.ProviderModelTypes` where it took a `String`
 * (#2804, #2805).
 *
 *  - A provider config's `model` is a `ModelName`: `OpenAIConfig(apiKey = key, model = "gpt-4o", ...)` becomes
 *    `OpenAIConfig(apiKey = key, model = ModelName("gpt-4o"), ...)`, and `config.withModel("gpt-4o")` becomes
 *    `config.withModel(ModelName("gpt-4o"))`. A model passed by position is not rewritten, and `fromValues` still
 *    takes a `String`.
 *  - `Llm4sConfig.provider("main")` and `Llm4sConfig.listModels("main")` (and the same on `Llm4sSettings`) take a
 *    `ProviderName`: the argument becomes `ProviderName("main")`.
 *
 * The import of each newtype is added where one is used.
 */
class ProviderConfigNewtypes extends SemanticRule("ProviderConfigNewtypes") {

  override def description: String =
    "Wraps a provider config's model in ModelName and the section name of Llm4sConfig.provider/listModels in ProviderName"

  import ProviderConfigNewtypes._

  override def fix(implicit doc: SemanticDocument): Patch = {
    val wraps = doc.tree.collect { case t: Term.Apply if !t.fun.is[Term.Apply] => arguments(t) }.flatten
    val patch = wraps.map { case (arg, newtype) => Patch.replaceTree(arg, s"$newtype(${arg.pos.text})") }.asPatch
    wraps.map(_._2).distinct.foldLeft(patch)((p, newtype) => p + Migration.importOnce(Newtypes, newtype))
  }

  /** The arguments of a call that are wrapped, each with its newtype. */
  private def arguments(t: Term.Apply)(implicit doc: SemanticDocument): List[(Term, String)] =
    Migration.callee(t).fold(List.empty[(Term, String)]) { callee =>
      val single = t.argClause.values match {
        case List(arg) if !arg.is[Term.Assign] => Some(arg)
        case _                                 => None
      }
      val owner = Migration.owner(callee)
      if (configs.contains(owner) && Migration.constructs(callee, owner))
        t.argClause.values.collect {
          case Term.Assign(Term.Name("model"), rhs) if !isWrapped(rhs, "ModelName") => rhs -> "ModelName"
        }
      else
        callee match {
          case WithModel(config) if configs.contains(config) || config == ProviderConfig =>
            single.filterNot(isWrapped(_, "ModelName")).map(_ -> "ModelName").toList
          case ByName(_) =>
            // The section name is the only argument of the first clause; a second clause holds the registry.
            single.filterNot(isWrapped(_, "ProviderName")).map(_ -> "ProviderName").toList
          case _ => Nil
        }
    }

  private def isWrapped(arg: Term, newtype: String): Boolean = arg match {
    case a: Term.Apply => a.fun.syntax == newtype || a.fun.syntax.endsWith("." + newtype)
    case _             => false
  }
}

object ProviderConfigNewtypes {

  private val Newtypes       = "org.llm4s.types.ProviderModelTypes"
  private val ProviderConfig = "org/llm4s/llmconnect/config/ProviderConfig"
  private val WithModel      = """(.+)#withModel\(\)\.""".r

  /** `Llm4sConfig.provider(name)`, `Llm4sConfig.listModels(name)` and the same on `Llm4sSettings`. */
  private val ByName = """org/llm4s/config/(Llm4sConfig\.|Llm4sSettings#)(?:provider|listModels)\((?:\+\d+)?\)\.""".r

  /** The provider configs, whose `model` was a `String` in 0.6.0 and is a `ModelName` now. */
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
