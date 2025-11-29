package org.llm4s.samples.embeddingsupport

import org.llm4s.types.Result

import java.nio.file.{ Files, Path, Paths }
import scala.jdk.CollectionConverters._

final case class EmbeddingTargets(targets: Seq[Path])

object EmbeddingTargets {

  private def splitList(s: String): Seq[String] = s.split("[,;]").toSeq

  /**
   * Build EmbeddingTargets from higher-level embeddings input settings.
   *
   * This helper is config-agnostic; callers are responsible for loading any
   * configuration (e.g. via Llm4sConfig.embeddingsInputs()) and passing in
   * the resolved values.
   */
  def fromInputs(inputPath: Option[String], inputPaths: Option[String]): Result[EmbeddingTargets] = {
    val multi  = inputPaths
    val single = inputPath

    val raw: Seq[String] = multi.map(splitList).getOrElse(single.toSeq)
    val paths            = raw.map(_.trim).filter(_.nonEmpty).map(Paths.get(_))

    val expanded = paths.flatMap { p =>
      if (Files.isDirectory(p)) Files.list(p).iterator().asScala.filter(Files.isRegularFile(_)).toSeq
      else Seq(p)
    }

    val unique = expanded.foldLeft(Vector.empty[Path])((acc, p) => if (acc.contains(p)) acc else acc :+ p)
    Right(EmbeddingTargets(unique))
  }
}
