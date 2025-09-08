package org.llm4s.samples.embeddingsupport

import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingConfig
import org.llm4s.llmconnect.extractors.UniversalExtractor
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.utils.{ModelSelector, SimilarityUtils}
import org.slf4j.LoggerFactory

object EmbeddingExample extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  // Minimal display controls (env-tunable)
  private val HEAD: Int = sys.env.getOrElse("EMBED_VECTOR_HEAD", "8").toInt
  private val PREVIEW: Int = sys.env.getOrElse("EMBED_TEXT_PREVIEW", "120").toInt

  // 1) Extract text
  val extracted = UniversalExtractor.extract(EmbeddingConfig.inputPath)

  // 2) Select TEXT model (name + dims)
  val modelCfg = ModelSelector.selectTextModel()

  // 3) Build request (doc + optional query)
  val inputs: Seq[String] = {
    val q = Option(EmbeddingConfig.query).filter(_.nonEmpty)
    extracted.content +: q.toSeq
  }

  val req = EmbeddingRequest(
    input    = inputs,
    model    = modelCfg,
    metadata = extracted.metadata
  )

  // 4) Embed and show a clean, minimal screen view
  val provider = EmbeddingClient.fromConfig()
  val t0 = System.nanoTime()
  val result = provider.embed(req)
  val elapsedMs = (System.nanoTime() - t0) / 1e6

  result match {
    case Right(resp) =>
      val providerName = resp.metadata.getOrElse("provider", EmbeddingConfig.activeProvider)
      val modelName    = resp.metadata.getOrElse("model", modelCfg.name)
      val dims         = resp.vectors.headOption.map(_.length).getOrElse(modelCfg.dimensions)

      logger.info(s"[Embeddings] provider=$providerName  model=$modelName  dims=$dims  vectors=${resp.vectors.size}  elapsed=${"%.1f".format(elapsedMs)} ms")

      // Inputs (short preview)
      logger.info(s"[Input 0] document: ${preview(req.input.head)}")
      req.input.lift(1).foreach(q => logger.info(s"[Input 1]   query : ${preview(q)}"))

      // Optional cosine(doc, query)
      if (req.input.size >= 2 && resp.vectors.size >= 2) {
        val sim = SimilarityUtils.cosineSimilarity(resp.vectors.head, resp.vectors(1))
        logger.info(f"[Similarity] cosine(document, query) = $sim%.6f")
      }

      // If more than 2 vectors, show compact pairwise similarities (upper triangle)
      if (resp.vectors.size > 2) {
        val n = resp.vectors.size
        logger.info("[Similarity] pairwise cosine (upper triangle):")
        for (i <- 0 until n; j <- i + 1 until n) {
          val s = SimilarityUtils.cosineSimilarity(resp.vectors(i), resp.vectors(j))
          logger.info(f"  s($i,$j) = $s%.4f")
        }
      }

      // Tiny vector previews (first two only, head N dims)
      resp.vectors.zipWithIndex.take(2).foreach { case (v, i) =>
        val role = if (i == 0) "document" else if (i == 1) "query" else s"input_$i"
        logger.info(s"[Vector $i] $role  len=${v.length}  head=${headN(v, HEAD)}  l2=${l2(v)}")
      }

    case Left(err) =>
      logger.error(s"[Error] provider=${err.provider} message=${err.message} code=${err.code.getOrElse("n/a")}")
  }

  // ---------- helpers (minimal) ----------
  private def preview(s: String): String = {
    val one = s.replaceAll("\\s+", " ").trim
    if (one.length <= PREVIEW) one else one.take(PREVIEW) + "…"
  }
  private def headN(v: Seq[Double], n: Int): String =
    v.take(n).map(x => f"$x%.5f").mkString("[", ", ", if (v.length > n) ", …]" else "]")
  private def l2(v: Seq[Double]): String =
    f"${math.sqrt(v.foldLeft(0.0)((a,x) => a + x*x))}%.6f"
}
