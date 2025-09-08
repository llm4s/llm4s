package org.llm4s.samples.embeddingsupport

import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingConfig
import org.llm4s.llmconnect.extractors.UniversalExtractor
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.utils.{ModelSelector, SimilarityUtils, Chunker}
import org.slf4j.LoggerFactory

/** Text-only embedding example with Markdown-friendly chunking and a simple, readable on-screen embeddings table. */
object EmbeddingExample extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  // ---- Small, env-tunable knobs ----
  private val PREVIEW: Int        = sys.env.getOrElse("EMBED_TEXT_PREVIEW", "120").toInt
  private val CH_MAX: Int         = sys.env.getOrElse("EMBED_CHUNK_MAX_CHARS", "1000").toInt
  private val CH_OVERLAP: Int     = sys.env.getOrElse("EMBED_CHUNK_OVERLAP", "200").toInt
  private val CH_MAX_COUNT: Int   = sys.env.getOrElse("EMBED_CHUNK_MAX_COUNT", "32").toInt
  private val TOPK: Int           = sys.env.getOrElse("EMBED_TOPK", "5").toInt

  // Embeddings table controls
  private val SHOW_EMB: Boolean   = sys.env.getOrElse("EMBED_SHOW", "true").equalsIgnoreCase("true")
  private val SHOW_FULL: Boolean  = sys.env.getOrElse("EMBED_SHOW_FULL", "false").equalsIgnoreCase("true")
  private val HEAD: Int           = sys.env.getOrElse("EMBED_VECTOR_HEAD", "12").toInt
  private val ROWS: Int           = sys.env.getOrElse("EMBED_VECTOR_ROWS", "5").toInt
  private val PREC: Int           = sys.env.getOrElse("EMBED_VECTOR_PREC", "5").toInt

  // 1) Extract text
  val extracted = UniversalExtractor.extract(EmbeddingConfig.inputPath)
  logger.info(s"[Extract] source=${extracted.metadata.getOrElse("filename", extracted.metadata.getOrElse("source","?"))} mime=${extracted.mimeType.getOrElse("?")}")

  // 2) Chunk document (Markdown-aware; headings & fenced code treated as boundaries)
  val allChunks = Chunker.chunkText(extracted.content, CH_MAX, CH_OVERLAP)
  val chunks    = allChunks.take(CH_MAX_COUNT)
  logger.info(s"[Chunks] total=${allChunks.size} used=${chunks.size} (maxChars=$CH_MAX overlap=$CH_OVERLAP)")

  if (chunks.isEmpty) {
    logger.warn("[Chunks] No content to embed.")
    sys.exit(0)
  }

  // 3) Select TEXT model (name + dims)
  val modelCfg = ModelSelector.selectTextModel()

  // 4) Build request: CHUNKS (+ optional QUERY at the end)
  val queryOpt = Option(EmbeddingConfig.query).filter(_.nonEmpty)
  val inputs: Seq[String] = chunks.map(_.text) ++ queryOpt.toSeq

  val req = EmbeddingRequest(
    input    = inputs,
    model    = modelCfg,
    metadata = extracted.metadata ++ Map("chunk_count" -> chunks.size.toString)
  )

  // 5) Embed (batch)
  val provider = EmbeddingClient.fromConfig()
  val t0 = System.nanoTime()
  val result = provider.embed(req)
  val elapsedMs = (System.nanoTime() - t0) / 1e6

  result match {
    case Right(resp) =>
      val providerName = resp.metadata.getOrElse("provider", EmbeddingConfig.activeProvider)
      val modelName    = resp.metadata.getOrElse("model", modelCfg.name)
      val dims         = resp.vectors.headOption.map(_.length).getOrElse(modelCfg.dimensions)
      val hasQuery     = queryOpt.isDefined && resp.vectors.size == chunks.size + 1
      logger.info(f"[Embeddings] provider=$providerName model=$modelName dims=$dims vectors=${resp.vectors.size} elapsed=${elapsedMs}%.1f ms")

      // 6) Ranking when query is present (query vector is last)
      val topIdxs: Seq[Int] =
        if (hasQuery) {
          val qv = resp.vectors.last
          val scored = resp.vectors.zipWithIndex.take(chunks.size)
            .map { case (v, i) => (i, SimilarityUtils.cosineSimilarity(v, qv)) }
            .sortBy(-_._2)
          logger.info(s"[Top-$TOPK] most relevant chunks to query:")
          scored.take(TOPK).foreach { case (i, s) =>
            logger.info(f"  #$i  score=${s}%.4f  ${oneLine(preview(chunks(i).text))}")
          }
          scored.take(ROWS).map(_._1)
        } else {
          // No query: display first ROWS chunks
          logger.info(s"[Preview] first ${math.min(3, chunks.size)} chunk(s):")
          chunks.take(3).zipWithIndex.foreach { case (ch, i) =>
            logger.info(s"  #$i  ${oneLine(preview(ch.text))}")
          }
          (0 until math.min(ROWS, chunks.size)).toSeq
        }

      // 7) Simple, readable embeddings table (head N dims); includes query row if present
      if (SHOW_EMB) {
        printEmbeddingsTable(
          vectors = resp.vectors,
          dims = dims,
          chunkCount = chunks.size,
          showIdxs = topIdxs,
          includeQuery = hasQuery
        )
      }

    case Left(err) =>
      logger.error(s"[Error] provider=${err.provider} message=${err.message} code=${err.code.getOrElse("n/a")} details=${err.details}")
  }

  // ----------- helpers -----------
  private def preview(s: String): String = {
    val one = s.replaceAll("\\s+", " ").trim
    if (one.length <= PREVIEW) one else one.take(PREVIEW) + "…"
  }
  private def oneLine(s: String): String =
    s.replace('\n', ' ').replaceAll("\\s+", " ").trim

  private def fmt(d: Double): String =
    String.format(s"%.${PREC}f", Double.box(d))

  private def l2(v: Seq[Double]): Double =
    math.sqrt(v.foldLeft(0.0)((a,x) => a + x*x))

  private def headStr(v: Seq[Double]): String = {
    if (SHOW_FULL) v.map(fmt).mkString("[", ", ", "]")
    else {
      val h = v.take(HEAD).map(fmt).mkString(", ")
      if (v.length > HEAD) s"[$h, …]" else s"[$h]"
    }
  }

  private def roleOf(i: Int, chunkCount: Int, hasQuery: Boolean): String =
    if (hasQuery && i == chunkCount) "query" else s"chunk#$i"

  private def padRight(s: String, n: Int): String =
    if (s.length >= n) s else s + (" " * (n - s.length))
  private def padLeft(s: String, n: Int): String =
    if (s.length >= n) s else (" " * (n - s.length)) + s

  private def printEmbeddingsTable(
                                    vectors: Seq[Seq[Double]],
                                    dims: Int,
                                    chunkCount: Int,
                                    showIdxs: Seq[Int],
                                    includeQuery: Boolean
                                  ): Unit = {
    val sep = "+----+----------+------+----------+--------------------------------"
    logger.info(sep)
    logger.info(s"| ${padRight("idx",3)} | ${padRight("role",8)} | ${padRight("len",4)} | ${padRight("l2",8)} | head${if (SHOW_FULL) s" (all $dims dims)" else s" ($HEAD dims)"}")
    logger.info(sep)

    // rows for selected chunks
    showIdxs.foreach { i =>
      val v   = vectors(i)
      val id  = padLeft(i.toString, 3)
      val role= padRight(roleOf(i, chunkCount, includeQuery), 8)
      val len = padLeft(v.length.toString, 4)
      val nrm = padLeft(f"${l2(v)}%.6f", 8)
      logger.info(s"| $id | $role | $len | $nrm | ${headStr(v)}")
    }

    // query row (always last) if present
    if (includeQuery) {
      val q = vectors.last
      val id  = padLeft("Q", 3)
      val role= padRight("query", 8)
      val len = padLeft(q.length.toString, 4)
      val nrm = padLeft(f"${l2(q)}%.6f", 8)
      logger.info(sep)
      logger.info(s"| $id | $role | $len | $nrm | ${headStr(q)}")
    }

    logger.info(sep)
  }
}
