package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * `OpenAIConfig.fromValues` and `AzureConfig.fromValues` take the model, then the key, then the base URL (#2340), and
 * the key is a `Secret` (#1932).
 *
 * `OpenAIConfig.fromValues(model, key, None, baseUrl)` becomes `OpenAIConfig.fromValues(model, Secret(key), baseUrl)`,
 * and `OpenAIConfig.fromValues(model, key, Some("org-1"), baseUrl, providerId)` becomes
 * `OpenAIConfig.fromValues(model, Secret(key), baseUrl, Some("org-1"), providerId)`.
 * `AzureConfig.fromValues(deployment, endpoint, key, apiVersion)` becomes
 * `AzureConfig.fromValues(deployment, Secret(key), endpoint, apiVersion)`. A named argument keeps its name and place; once one is named, the
 * arguments after it are named too, as Scala requires. The model is wrapped in `ModelName` and the base URL in
 * `BaseUrl` (#2822), which `ModelIdNewtypes` does for the other configs.
 */
class FromValuesKeyFirst extends SemanticRule("FromValuesKeyFirst") {

  override def description: String =
    "Reorders OpenAIConfig.fromValues and AzureConfig.fromValues to model, key, base URL, with the key a Secret"

  import FromValuesKeyFirst._

  override def fix(implicit doc: SemanticDocument): Patch = {
    val patches = doc.tree.collect {
      case t: Term.Apply if !t.fun.is[Term.Apply] =>
        Migration.callee(t).flatMap(methods.get).fold(Patch.empty)(rewrite(t, _))
    }
    val patch = patches.asPatch
    if (patch.isEmpty) patch
    else
      patch + Migration.importOnce("org.llm4s.security", "Secret") +
        Migration.importOnce(Newtypes, "ModelName") + Migration.importOnce(Newtypes, "BaseUrl")
  }

  private def rewrite(t: Term.Apply, method: Method): Patch = {
    val args = t.argClause.values
    // The 0.5.1 parameter each argument binds: positional ones by place, named ones by name.
    val bound = args.zipWithIndex.map {
      case (Term.Assign(Term.Name(name), rhs), _) => (name, rhs, true)
      case (arg, i)                               => (method.old.lift(i).getOrElse(""), arg, false)
    }
    if (bound.exists(_._1.isEmpty)) Patch.empty
    else {
      val value: Map[String, (Term, Boolean)] = bound.map { case (name, rhs, named) => name -> (rhs -> named) }.toMap
      def text(name: String): String = {
        val rhs = value(name)._1.pos.text
        if (method.secret.contains(name)) s"Secret($rhs)"
        else method.wrapped.get(name).filterNot(_ => rhs.endsWith("DEFAULT_BASE_URL")).fold(rhs)(w => s"$w($rhs)")
      }
      val positional = method.updated.takeWhile(n => value.get(n).exists(!_._2))
      // An `Option` parameter given as `None` is its default: dropped when nothing positional follows it.
      val trailing = positional.reverse.dropWhile(n => method.defaulted(n) && value(n)._1.syntax == "None").reverse
      val namedAfter = method.updated
        .drop(positional.size)
        .filter(value.contains)
        .filterNot(n => !value(n)._2 && method.defaulted(n) && value(n)._1.syntax == "None")
      val all = trailing.map(text) ++ namedAfter.map(n => s"$n = ${text(n)}")
      Patch.replaceTree(t.argClause, all.mkString("(", ", ", ")"))
    }
  }
}

object FromValuesKeyFirst {

  /**
   * A method's parameters in 0.5.1 and on main, the ones whose `String` is a `Secret` now, and the `Option` parameters
   * that gained a `None` default.
   */
  final private[scalafix] case class Method(
    old: List[String],
    updated: List[String],
    secret: Set[String],
    defaulted: Set[String],
    wrapped: Map[String, String]
  )

  private val Newtypes = "org.llm4s.types.ProviderModelTypes"

  /** The configs whose `fromValues` this rule rewrites whole, so that `ModelIdNewtypes` leaves them to it. */
  private[scalafix] val configs: Set[String] =
    Set("org/llm4s/llmconnect/config/OpenAIConfig", "org/llm4s/llmconnect/config/AzureConfig")

  private val methods: Map[String, Method] = Map(
    "org/llm4s/llmconnect/config/OpenAIConfig.fromValues()." -> Method(
      List("modelName", "apiKey", "organization", "baseUrl", "providerId", "workloadIdentity"),
      List("modelName", "apiKey", "baseUrl", "organization", "providerId", "workloadIdentity"),
      Set("apiKey"),
      Set("organization", "providerId", "workloadIdentity"),
      Map("modelName" -> "ModelName", "baseUrl" -> "BaseUrl")
    ),
    "org/llm4s/llmconnect/config/AzureConfig.fromValues()." -> Method(
      List("modelName", "endpoint", "apiKey", "apiVersion"),
      List("modelName", "apiKey", "endpoint", "apiVersion"),
      Set("apiKey"),
      Set.empty,
      Map("modelName" -> "ModelName", "endpoint" -> "BaseUrl")
    )
  )
}
