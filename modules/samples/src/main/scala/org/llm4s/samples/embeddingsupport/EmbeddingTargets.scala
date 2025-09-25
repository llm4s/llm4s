package org.llm4s.samples.embeddingsupport

import org.llm4s.config.ConfigReader

import java.nio.file.{ Files, Path, Paths }
import scala.jdk.CollectionConverters._

final case class EmbeddingTargets(targets: Seq[Path])

object EmbeddingTargets {
  def load(config: ConfigReader): EmbeddingTargets = {
    val multi  = config.get("EMBEDDING_INPUT_PATHS")
    val single = config.get("EMBEDDING_INPUT_PATH")

    val raw: Seq[String] = multi.map(splitList).getOrElse(single.toSeq)
    val paths            = raw.map(_.trim).filter(_.nonEmpty).map(Paths.get(_))

    val expanded = paths.flatMap { p =>
      if (Files.isDirectory(p)) Files.list(p).iterator().asScala.filter(Files.isRegularFile(_)).toSeq
      else Seq(p)
    }

    val unique = expanded.foldLeft(Vector.empty[Path])((acc, p) => if (acc.contains(p)) acc else acc :+ p)
    EmbeddingTargets(unique)
  }

  private def splitList(s: String): Seq[String] = s.split("[,;]").toSeq

  def loadFromEnv(): org.llm4s.types.Result[EmbeddingTargets] =
    org.llm4s.config.ConfigReader.LLMConfig().map(load)
}
