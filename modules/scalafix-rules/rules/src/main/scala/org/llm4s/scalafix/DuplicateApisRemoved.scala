package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * Two duplicate entry points removed since 0.5.1, whose replacement is already there:
 *
 *   - `import org.llm4s.error.ServiceError.ServiceErrorOps` is deleted: `isRecoverableStatus` is now a method of
 *     `ServiceError`, so `error.isRecoverableStatus` compiles unchanged (#2266).
 *   - `Llm4sConfig.loadTextEmbeddingModel()` becomes `Llm4sConfig.textEmbeddingModel()`, which it duplicated (#2265).
 */
class DuplicateApisRemoved extends SemanticRule("DuplicateApisRemoved") {

  override def description: String =
    "Removes ServiceError.ServiceErrorOps imports and renames Llm4sConfig.loadTextEmbeddingModel to textEmbeddingModel"

  import DuplicateApisRemoved._

  override def fix(implicit doc: SemanticDocument): Patch =
    doc.tree.collect {
      case Importer(ref, importees) if ref.syntax.endsWith("ServiceError") =>
        importees.collect {
          case n: Importee.Name if n.name.value == "ServiceErrorOps" && isServiceError(ref) => Patch.removeImportee(n)
        }.asPatch
      case n: Term.Name if Migration.symbolOf(n).exists(_.value == LoadTextEmbeddingModel) =>
        Patch.replaceTree(n, "textEmbeddingModel")
    }.asPatch

  /** The importer names `org.llm4s.error.ServiceError`, by its symbol or, where none is recorded, by its text. */
  private def isServiceError(ref: Term.Ref)(implicit doc: SemanticDocument): Boolean = {
    val name = ref match {
      case Term.Select(_, n) => n
      case n: Term.Name      => n
      case _                 => ref
    }
    Migration.symbolOf(name).fold(ref.syntax == "org.llm4s.error.ServiceError")(_.value.startsWith(ServiceError))
  }
}

object DuplicateApisRemoved {
  private val ServiceError           = "org/llm4s/error/ServiceError"
  private val LoadTextEmbeddingModel = "org/llm4s/config/Llm4sConfig.loadTextEmbeddingModel()."
}
