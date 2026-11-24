package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * The imports of the `Result` helpers and conversions that moved since 0.6.0 (#2821):
 *
 *   - `import org.llm4s.Result` (the helper object) becomes `import org.llm4s.types.Result`, which is now the type
 *     and the object;
 *   - `import org.llm4s.types.TryOps` (or `OptionOps`, `FutureOps`) becomes `import org.llm4s.syntax.toResult`: the
 *     conversions are extensions of the one object `org.llm4s.syntax`;
 *   - `import org.llm4s.error.ThrowableOps._` becomes `import org.llm4s.syntax.toLLMError`;
 *   - `import org.llm4s.syntax.syntax._` becomes `import org.llm4s.syntax.*`;
 *   - `import org.llm4s.core.safety.Safety` becomes `import org.llm4s.error.Safety`, and `NetworkSecurity` moves to
 *     `org.llm4s.security`;
 *   - `import org.llm4s.identity.TokenizerId` becomes `import org.llm4s.context.tokens.TokenizerId` (#2818),
 *     `import org.llm4s.llmconnect.model.EmbeddingError` becomes `import org.llm4s.error.EmbeddingError` (#2808), and
 *     `import org.llm4s.types.TraceId` becomes `import org.llm4s.trace.model.TraceId`, in `llm4s-observability`
 *     (#2822).
 *
 * Only imports are rewritten. A fully qualified use (`org.llm4s.core.safety.Safety.safely(...)`) and the removed
 * members (`ErrorMapper`, `Result.combine`, ...) are left for the compiler and `RemovedApis_1_0` to report.
 */
class ResultSyntaxMoved extends SemanticRule("ResultSyntaxMoved") {

  override def description: String =
    "Rewrites the imports of Result, TryOps, ThrowableOps, syntax.syntax, Safety, NetworkSecurity, TokenizerId, " +
      "EmbeddingError and TraceId to where they moved"

  import ResultSyntaxMoved._

  override def fix(implicit doc: SemanticDocument): Patch = {
    val moved = doc.tree.collect { case i: Importer =>
      moves.get(i.ref.syntax).toList.flatMap { byName =>
        i.importees.collect {
          case n: Importee.Name if byName.contains(n.name.value) => (n: Importee) -> byName(n.name.value)
          case w: Importee.Wildcard if byName.contains("*")      => (w: Importee) -> byName("*")
        }
      }
    }.flatten
    val removed = moved.map { case (importee, _) => Patch.removeImportee(importee) }
    val added   = moved.map(_._2).distinct.map { case (pkg, name) => Migration.importOnce(pkg, name) }
    removed.asPatch + added.asPatch
  }
}

object ResultSyntaxMoved {

  private val toResult   = "org.llm4s.syntax" -> "toResult"
  private val toLLMError = "org.llm4s.syntax" -> "toLLMError"

  /** By the importer's prefix: the imported name (`*` for a wildcard), and the package and name that replace it. */
  val moves: Map[String, Map[String, (String, String)]] = Map(
    "org.llm4s" -> Map("Result" -> ("org.llm4s.types" -> "Result")),
    "org.llm4s.types" -> Map(
      "TryOps"    -> toResult,
      "OptionOps" -> toResult,
      "FutureOps" -> toResult,
      "TraceId"   -> ("org.llm4s.trace.model" -> "TraceId")
    ),
    "org.llm4s.identity"           -> Map("TokenizerId" -> ("org.llm4s.context.tokens" -> "TokenizerId")),
    "org.llm4s.llmconnect.model"   -> Map("EmbeddingError" -> ("org.llm4s.error" -> "EmbeddingError")),
    "org.llm4s.error.ThrowableOps" -> Map("*" -> toLLMError, "RichThrowable" -> toLLMError),
    "org.llm4s.syntax.syntax" -> Map("*" -> ("org.llm4s.syntax" -> "*"), "ResultOps" -> ("org.llm4s.syntax" -> "*")),
    "org.llm4s.core.safety" -> Map(
      "Safety"          -> ("org.llm4s.error"    -> "Safety"),
      "NetworkSecurity" -> ("org.llm4s.security" -> "NetworkSecurity")
    )
  )
}
