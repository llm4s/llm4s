package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * A count of milliseconds becomes a `FiniteDuration`, and the unit leaves the name.
 *
 * `CrawlerConfig(delayMs = 500, timeoutMs = 30000)` becomes `CrawlerConfig(delay = 500.millis, timeout = 30.seconds)`,
 * `RateLimitError("openai", 1000L)` becomes `RateLimitError("openai", 1.second)`, and reading `config.timeoutMs`
 * becomes `config.timeout.toMillis` (with `.toInt` where the old field was an `Int`), so the expression keeps its
 * type. `import scala.concurrent.duration.*` is added where it is missing.
 */
class MillisToFiniteDuration extends SemanticRule("MillisToFiniteDuration") {

  override def description: String =
    "Rewrites the llm4s millisecond parameters (timeoutMs, delayMs) and RateLimitError's retry delay to FiniteDuration"

  import MillisToFiniteDuration._

  override def fix(implicit doc: SemanticDocument): Patch = {
    val patches = doc.tree.collect {
      case t: Term.Apply =>
        Migration.callee(t).map(Migration.owner).fold(Patch.empty) { owner =>
          if (owner == RateLimitError) retryAfter(t.argClause.values) else namedArguments(owner, t.argClause.values)
        }
      case i: Init =>
        Migration
          .symbolOf(i.tpe)
          .map(Migration.owner)
          .fold(Patch.empty)(owner => i.argClauses.map(c => namedArguments(owner, c.values)).asPatch)
      case s @ Term.Select(_, name) if !s.parent.exists(_.is[Term.Assign]) =>
        Migration.symbolOf(name).fold(Patch.empty)(read(name, _))
    }
    val patch = patches.asPatch
    if (patch.isEmpty) patch else patch + Migration.importOnce("scala.concurrent.duration", "*")
  }

  private def namedArguments(owner: String, args: List[Term]): Patch =
    fields.get(owner).fold(Patch.empty) { renames =>
      args.collect {
        case Term.Assign(lhs @ Term.Name(old), rhs) if renames.contains(old) =>
          Patch.replaceTree(lhs, renames(old).name) + Patch.replaceTree(rhs, duration(rhs))
      }.asPatch
    }

  /** `RateLimitError(provider, retryAfterMillis)`: 0.4.1's only public two-argument factory. */
  private def retryAfter(args: List[Term]): Patch = args match {
    case List(_, delay) if !delay.is[Term.Assign] => Patch.replaceTree(delay, duration(delay))
    case _                                        => Patch.empty
  }

  /** `config.timeoutMs` reads the old field: keep its type with `.toMillis`. */
  private def read(name: Term.Name, symbol: Symbol): Patch = {
    val value = symbol.value
    if (!value.endsWith(".") || value.endsWith("().")) Patch.empty
    else {
      val owner = value.stripSuffix(".").reverse.dropWhile(_ != '#').reverse.stripSuffix("#")
      fields.get(owner).flatMap(_.get(name.value)).fold(Patch.empty) { field =>
        Patch.replaceTree(name, s"${field.name}.toMillis${if (field.wasInt) ".toInt" else ""}")
      }
    }
  }
}

object MillisToFiniteDuration {

  final private[scalafix] case class Field(name: String, wasInt: Boolean)

  private val RateLimitError = "org/llm4s/error/RateLimitError"

  /** The 0.4.1 millisecond fields that 0.5.0 made `FiniteDuration`s, by type. */
  private[scalafix] val fields: Map[String, Map[String, Field]] = {
    val timeoutInt = Map("timeoutMs" -> Field("timeout", wasInt = true))
    Map(
      "org/llm4s/rag/loader/CrawlerConfig"          -> (timeoutInt + ("delayMs" -> Field("delay", wasInt = true))),
      "org/llm4s/rag/loader/UrlLoader"              -> timeoutInt,
      "org/llm4s/toolapi/builtin/http/HttpConfig"   -> timeoutInt,
      "org/llm4s/toolapi/builtin/shell/ShellConfig" -> Map("timeoutMs" -> Field("timeout", wasInt = false)),
      "org/llm4s/toolapi/builtin/search/BraveSearchConfig"      -> timeoutInt,
      "org/llm4s/toolapi/builtin/search/DuckDuckGoSearchConfig" -> timeoutInt,
      "org/llm4s/toolapi/builtin/search/ExaSearchConfig"        -> timeoutInt
    )
  }

  /** `30000` is `30.seconds`, `1000L` is `1.second`, `500` is `500.millis`, and any other expression `(e).millis`. */
  private[scalafix] def duration(rhs: Term): String = rhs match {
    case Lit.Int(ms)                   => literal(ms.toLong)
    case Lit.Long(ms)                  => literal(ms)
    case _: Term.Name | _: Term.Select => s"${rhs.pos.text}.millis"
    case _                             => s"(${rhs.pos.text}).millis"
  }

  private def literal(ms: Long): String =
    if (ms == 1000) "1.second"
    else if (ms != 0 && ms % 1000 == 0) s"${ms / 1000}.seconds"
    else s"$ms.millis"
}
