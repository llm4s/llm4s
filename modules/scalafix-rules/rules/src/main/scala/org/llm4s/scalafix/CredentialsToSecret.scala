package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * A provider config's `apiKey` is an `org.llm4s.security.Secret`, not a `String` (#1932).
 *
 * `OpenAIConfig(apiKey = key, model = "gpt-4o", ...)` becomes `OpenAIConfig(apiKey = Secret(key), ...)`, and
 * `config.withApiKey(key)` becomes `config.withApiKey(Secret(key))`; where the key was an `Option[String]`
 * (`OpenAICompatibleConfig`, `EmbeddingProviderSection`), `Some(key)` becomes `Some(Secret(key))` and any other
 * expression `key.map(Secret(_))`. Reading `config.apiKey` becomes `config.apiKey.reveal` (`.map(_.reveal)` for an
 * `Option`), so the expression keeps its type, and a key copied from one config to another is left as it is.
 * `import org.llm4s.security.Secret` is added where a key is wrapped. A key passed by position is not rewritten.
 */
class CredentialsToSecret extends SemanticRule("CredentialsToSecret") {

  override def description: String =
    "Wraps the String apiKey of the llm4s provider configs (0.5.1) in Secret, and reads it with .reveal"

  import CredentialsToSecret._

  override def fix(implicit doc: SemanticDocument): Patch = {
    val keys = doc.tree.collect { case t: Term.Apply if !t.fun.is[Term.Apply] => arguments(t) }.flatten
    val wrapped = keys.collect {
      case (arg, optional) if read(arg).isEmpty => Patch.replaceTree(arg, secret(revealed(arg), arg, optional))
    }
    // A read inside a wrapped key is written by `revealed`; one that is the whole key is a Secret on both sides.
    val inKey = (t: Tree) => keys.exists { case (k, _) => t.pos.start >= k.pos.start && t.pos.end <= k.pos.end }
    val reads = doc.tree.collect {
      case s: Term.Select if !inKey(s) => read(s).fold(Patch.empty)(optional => Patch.addRight(s, reveal(optional)))
    }
    val patch = (wrapped ++ reads).asPatch
    if (wrapped.isEmpty) patch else patch + Migration.importOnce("org.llm4s.security", "Secret")
  }

  /** The key arguments of a call, and whether each is an `Option`. */
  private def arguments(t: Term.Apply)(implicit doc: SemanticDocument): List[(Term, Boolean)] =
    Migration.callee(t).fold(List.empty[(Term, Boolean)]) { callee =>
      val single = t.argClause.values match {
        case List(arg) if !arg.is[Term.Assign] => Some(arg)
        case _                                 => None
      }
      val owner = Migration.owner(callee)
      configs.get(owner) match {
        case Some(optional) if Migration.constructs(callee, owner) =>
          t.argClause.values.collect { case Term.Assign(Term.Name("apiKey"), rhs) => rhs -> optional }
        case _ =>
          callee match {
            case WithApiKey(config, overload) if configs.contains(config) =>
              // OpenAICompatibleConfig's second overload takes the Option.
              single.map(_ -> (overload == "+1")).toList
            case _ => Nil
          }
      }
    }

  /** A key's text, with `.reveal` after each read of another config's key inside it. */
  private def revealed(key: Term)(implicit doc: SemanticDocument): String = {
    val inner = key.collect { case s: Term.Select => read(s).map(s -> _) }.flatten
    inner.sortBy(-_._1.pos.end).foldLeft(key.pos.text) { case (text, (s, optional)) =>
      val at = s.pos.end - key.pos.start
      text.substring(0, at) + reveal(optional) + text.substring(at)
    }
  }

  /** Whether a term reads a config's key: `Some(optional)` if so. */
  private def read(t: Term)(implicit doc: SemanticDocument): Option[Boolean] = t match {
    case Term.Select(_, field @ Term.Name("apiKey")) =>
      Migration.symbolOf(field).map(_.value).collect {
        case s if s.endsWith("#apiKey.") && configs.contains(s.stripSuffix("#apiKey.")) =>
          configs(s.stripSuffix("#apiKey."))
      }
    case _ => None
  }
}

object CredentialsToSecret {

  private val WithApiKey = """(.+)#withApiKey\((\+\d+)?\)\.""".r

  /** The configs whose `apiKey` was a `String` in 0.5.1 (`true`: an `Option[String]`) and is a `Secret` now. */
  private val configs: Map[String, Boolean] = Map(
    "org/llm4s/llmconnect/config/OpenAIConfig"            -> false,
    "org/llm4s/llmconnect/config/AnthropicConfig"         -> false,
    "org/llm4s/llmconnect/config/AzureConfig"             -> false,
    "org/llm4s/llmconnect/config/GeminiConfig"            -> false,
    "org/llm4s/llmconnect/config/DeepSeekConfig"          -> false,
    "org/llm4s/llmconnect/config/ZaiConfig"               -> false,
    "org/llm4s/llmconnect/config/MistralConfig"           -> false,
    "org/llm4s/llmconnect/config/CohereConfig"            -> false,
    "org/llm4s/llmconnect/config/EmbeddingProviderConfig" -> false,
    "org/llm4s/reranker/RerankProviderConfig"             -> false,
    "org/llm4s/llmconnect/config/OpenAICompatibleConfig"  -> true,
    "org/llm4s/llmconnect/spi/EmbeddingProviderSection"   -> true
  )

  private def reveal(optional: Boolean): String = if (optional) ".map(_.reveal)" else ".reveal"

  /** The key, as `text`, as a `Secret` (or an `Option[Secret]`). */
  private def secret(text: String, key: Term, optional: Boolean): String =
    if (!optional) s"Secret($text)"
    else
      key match {
        case Term.Name("None") => "None"
        case a: Term.Apply if CopyToSetters.isSome(a) =>
          val inner = a.argClause.values.head
          s"Some(Secret(${text.substring(inner.pos.start - key.pos.start, inner.pos.end - key.pos.start)}))"
        case _: Term.Name | _: Term.Select | _: Term.Apply => s"$text.map(Secret(_))"
        case _                                             => s"($text).map(Secret(_))"
      }
}
