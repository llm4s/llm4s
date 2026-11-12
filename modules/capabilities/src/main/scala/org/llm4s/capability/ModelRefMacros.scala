package org.llm4s.capability

import org.llm4s.model.{ ModelMetadata, ModelRegistryService }
import org.llm4s.types.Result

import scala.quoted.*

/**
 * Expands `model"provider/model"` at compile time, from the model data bundled with llm4s-core: the same resources,
 * read the same way, as `ModelRegistryService.default()` reads them at run time, so the expansion depends only on
 * the llm4s-core on the compile classpath.
 */
private[capability] object ModelRefMacros {

  /** The bundled registry and every id it knows, as `provider/model`. */
  private[capability] type Bundled = (ModelRegistryService, List[String])

  // Read once per compiler run: the macro class loader, and so this object, belongs to one run.
  private lazy val bundled: Either[String, Bundled] = bundledFrom(ModelRegistryService.default())

  /** The registry with its ids, or why the bundled model data cannot be read. */
  private[capability] def bundledFrom(registry: Result[ModelRegistryService]): Either[String, Bundled] =
    registry match {
      case Left(error) => Left(s"the bundled model data of llm4s-core cannot be read (${error.message})")
      case Right(registry) =>
        val ids = registry.listProviders().getOrElse(Nil).flatMap { provider =>
          registry.listByProvider(provider).getOrElse(Nil).map(qualifiedId)
        }
        Right((registry, ids.distinct.sorted))
    }

  private def qualifiedId(metadata: ModelMetadata): String =
    if (metadata.modelId.toLowerCase.startsWith(metadata.provider.toLowerCase + "/")) metadata.modelId
    else s"${metadata.provider}/${metadata.modelId}"

  /**
   * The model a literal names, as `(provider, model, metadata)`, or the compile error to report: the literal is
   * not `provider/model`, the bundled data cannot be read, or the data does not know the model.
   */
  private[capability] def resolve(
    written: String,
    bundled: Either[String, Bundled]
  ): Either[String, (String, String, ModelMetadata)] =
    for {
      named <- providerAndModel(written)
      known <- bundled.left.map(error => s"model\"$written\" cannot be checked: $error.")
      metadata <- known._1.lookup(named._1, named._2).left.map { _ =>
        val suggestion = closest(written, known._2).fold("")(id => s"; did you mean $id?")
        s"unknown model $written in the bundled model data$suggestion"
      }
    } yield (named._1, named._2, metadata)

  private def providerAndModel(written: String): Either[String, (String, String)] =
    written.split("/", 2) match {
      case Array(p, m) if p.nonEmpty && m.nonEmpty => Right((p, m))
      case _ => Left(s"model\"$written\" is not a provider/model id, such as model\"openai/gpt-4o\".")
    }

  def literal(context: Expr[StringContext], args: Expr[Seq[Any]])(using Quotes): Expr[ModelRef[Any]] = {
    import quotes.reflect.*

    args match {
      case Varargs(Seq()) => ()
      case _              => report.errorAndAbort("model\"...\" takes a literal provider/model id, with no ${} in it.")
    }
    val (provider, model, metadata) = resolve(context.valueOrAbort.parts.mkString.trim, bundled) match {
      case Right(resolved) => resolved
      case Left(error)     => report.errorAndAbort(error)
    }

    val supported = Capability.values.toList.filter(capability => metadata.supports(capability.supportsKey))
    val markers = supported.map {
      case Capability.Vision           => TypeRepr.of[Vision]
      case Capability.Tools            => TypeRepr.of[Tools]
      case Capability.ParallelTools    => TypeRepr.of[ParallelTools]
      case Capability.StructuredOutput => TypeRepr.of[StructuredOutput]
      case Capability.Reasoning        => TypeRepr.of[Reasoning]
      case Capability.PromptCaching    => TypeRepr.of[PromptCaching]
      case Capability.SystemMessages   => TypeRepr.of[SystemMessages]
    }
    val set   = markers.reduceOption(AndType(_, _)).getOrElse(TypeRepr.of[Any])
    val names = Expr(supported.map(_.toString))
    set.asType match {
      case '[c] => '{ ModelRef.unsafeOf[c](${ Expr(provider) }, ${ Expr(model) }, $names) }
    }
  }

  /** The known id nearest to `written` by edit distance, when it is near enough to be a typo of it. */
  private def closest(written: String, ids: List[String]): Option[String] =
    ids
      .map(id => (distance(written.toLowerCase, id.toLowerCase), id))
      .minOption
      .collect { case (d, id) if d <= math.max(2, written.length / 4) => id }

  /** Levenshtein distance. */
  private def distance(a: String, b: String): Int = {
    val previous = Array.tabulate(b.length + 1)(identity)
    val current  = new Array[Int](b.length + 1)
    a.indices.foreach { i =>
      current(0) = i + 1
      b.indices.foreach { j =>
        val substitution = if (a(i) == b(j)) 0 else 1
        current(j + 1) = math.min(math.min(current(j) + 1, previous(j + 1) + 1), previous(j) + substitution)
      }
      Array.copy(current, 0, previous, 0, current.length)
    }
    previous(b.length)
  }
}
