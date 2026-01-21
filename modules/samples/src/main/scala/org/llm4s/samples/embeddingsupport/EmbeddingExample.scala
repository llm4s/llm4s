package org.llm4s.samples.embeddingsupport

import org.llm4s.samples.embeddingsupport.{
  EmbeddingQuery,
  EmbeddingTargets,
  EmbeddingUiSettings,
  EmbeddingRuntimeSettings
}
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.utils.SimilarityUtils
import org.slf4j.LoggerFactory

import java.time.{ ZoneId, ZonedDateTime }

object EmbeddingExample {

  private val logger = LoggerFactory.getLogger(getClass)

  // ------------------------ Output knobs (typed) ------------------------
  // Now read once into a typed settings value

  // Column widths
  private val COL_IDX = 4
  private val COL_ID  = 56
  private val COL_DIM = 6
  private val COL_SIM = 28
  private def colTop(ui: EmbeddingUiSettings) =
    math.max(18, ui.tableWidth - (COL_IDX + 1 + COL_ID + 2 + COL_DIM + 2 + COL_SIM + 2 + 18))
  private val COL_META = 18

  def main(args: Array[String]): Unit = {
    logger.info("Starting embedding example...")

    val result = for {
      uiCfg <- Llm4sConfig.embeddingsUi()
      ui = EmbeddingUiSettings(
        maxRowsPerFile = uiCfg.maxRowsPerFile,
        topDimsPerRow = uiCfg.topDimsPerRow,
        globalTopK = uiCfg.globalTopK,
        showGlobalTop = uiCfg.showGlobalTop,
        colorEnabled = uiCfg.colorEnabled,
        tableWidth = uiCfg.tableWidth
      )
      inputs  <- Llm4sConfig.embeddingsInputs()
      targets <- EmbeddingTargets.fromInputs(inputs.inputPath, inputs.inputPaths).map(_.targets)
      emb     <- Llm4sConfig.embeddings()
      client  <- EmbeddingClient.from(emb._1, emb._2)
      runtime <- EmbeddingRuntimeSettings()
      textCfg <- Llm4sConfig.textEmbeddingModel()
      textModel = org.llm4s.llmconnect.config.EmbeddingModelConfig(
        name = textCfg.modelName,
        dimensions = textCfg.dimensions
      )
      chunkingCfg = org.llm4s.llmconnect.encoding.UniversalEncoder.TextChunkingConfig(
        enabled = runtime.chunkingEnabled,
        size = runtime.chunkSize,
        overlap = runtime.chunkOverlap
      )
      stubsEnabled = Llm4sConfig.experimentalStubsEnabled
      localModels <- Llm4sConfig.localEmbeddingModels()
      _ = {
        val eq          = EmbeddingQuery.loadFromEnv().getOrElse(EmbeddingQuery(None))
        val queryVecOpt = eq.value.flatMap(q => embedQueryOnce(client, q, runtime.provider, emb._2.model, ui))
        // accumulate results
        val perFileRows = collection.mutable.ArrayBuffer.empty[(String, Seq[Row])]
        val globalText  = collection.mutable.ArrayBuffer.empty[Row]
        val errors      = collection.mutable.ArrayBuffer.empty[String]
        var fileCount   = 0
        var chunkTotal  = 0

        targets.foreach { p =>
          fileCount += 1
          client.encodePath(p, textModel, chunkingCfg, stubsEnabled, localModels) match {
            case Left(err) =>
              val msg = s"${p.getFileName}: ${err.context.get("provider")} -> ${err.message}"
              errors += msg
              // Log error diagnostics to logger
              logger.error("[ERR] {}", msg)
            case Right(Nil) =>
              // UI warning on stdout
              println(yellow(s"[WARN] ${p.getFileName}: no embeddings.")(ui))
            case Right(vecs) =>
              val rows    = toRows(p.getFileName.toString, vecs, queryVecOpt, ui.topDimsPerRow)
              val clipped = rows.take(ui.maxRowsPerFile)
              perFileRows += ((p.getFileName.toString, clipped))
              chunkTotal += clipped.size
              clipped.filter(_.modality == "Text").foreach(globalText += _)
          }
        }
        eq.value.foreach(q => println(renderHeader(provider = runtime.provider, query = q, runtime)(ui)))

        perFileRows.foreach { case (name, rows) =>
          println(renderFileSection(name, rows)(ui))
        }

        if (ui.showGlobalTop && globalText.nonEmpty) {
          val top =
            globalText.sortBy(r => -r.similarity.getOrElse(Double.NegativeInfinity)).take(ui.globalTopK).toSeq
          println(renderGlobalTop(top)(ui))
        }

        println(
          renderSummary(
            files = fileCount,
            chunks = chunkTotal,
            errors = errors.toSeq,
            perFileRows = perFileRows.flatMap(_._2).toSeq
          )(ui)
        )
      }
    } yield ()

    result.fold(err => Console.println(err.toString), identity)

  }

  // ---------------- Model for printing ----------------
  final case class Row(
    file: String,
    id: String,
    modality: String,
    dim: Int,
    model: String,
    similarity: Option[Double],
    topDims: Seq[(Int, Float)],
    provider: String,
    mime: String
  )

  private def toRows(
    fileName: String,
    vecs: Seq[EmbeddingVector],
    qOpt: Option[Seq[Double]],
    topDims: Int
  ): Seq[Row] = {
    def topKAbs(values: Array[Float], k: Int): Seq[(Int, Float)] = {
      val withIdx: Array[(Int, Float)] =
        values.iterator.zipWithIndex.map { case (v, i) => (i, math.abs(v)) }.toArray
      scala.util.Sorting.stableSort(withIdx, (a: (Int, Float), b: (Int, Float)) => a._2 > b._2)
      withIdx.take(math.min(k, withIdx.length)).map { case (i, _) => (i, values(i)) }.toSeq
    }

    val modalityStr = vecs.head.modality.toString
    val scored: Seq[(EmbeddingVector, Option[Double])] =
      if (modalityStr == "Text" && qOpt.isDefined) {
        val q = qOpt.get
        vecs.map { v =>
          val d = v.values.map(_.toDouble).toSeq // already L2
          v -> Some(SimilarityUtils.cosineSimilarity(d, q))
        }
      } else vecs.map(v => v -> None)

    val ordered =
      if (modalityStr == "Text") scored.sortBy { case (_, s) => -s.getOrElse(Double.NegativeInfinity) }
      else scored

    ordered.map { case (v, s) =>
      Row(
        file = fileName,
        id = v.id,
        modality = v.modality.toString,
        dim = v.dim,
        model = v.model,
        similarity = s,
        topDims = topKAbs(v.values, topDims),
        provider = v.meta.getOrElse("provider", "n/a"),
        mime = v.meta.getOrElse("mime", "n/a")
      )
    }
  }

  // ---------------- Inputs ----------------
  // Targets moved to EmbeddingTargets settings

  // ---------------- Query embed cache ----------------
  private def embedQueryOnce(
    client: EmbeddingClient,
    query: String,
    provider: String,
    modelName: String,
    ui: EmbeddingUiSettings
  ): Option[Seq[Double]] = {
    if (query == null || query.trim.isEmpty) return None
    val dims = org.llm4s.llmconnect.config.ModelDimensionRegistry.getDimension(provider.toLowerCase, modelName)
    val req  = EmbeddingRequest(Seq(query), org.llm4s.llmconnect.config.EmbeddingModelConfig(modelName, dims))
    client.embed(req) match {
      case Right(resp) if resp.embeddings.nonEmpty =>
        Some(l2Normalize(resp.embeddings.head))
      case _ =>
        println(yellow("[WARN] Query embedding unavailable; similarities hidden.")(ui))
        None
    }
  }
  private def l2Normalize(v: Seq[Double]): Seq[Double] = {
    val n = math.sqrt(v.map(x => x * x).sum)
    if (n <= 1e-12) v else v.map(_ / n)
  }

  // ---------------- Rendering helpers ----------------
  private def renderHeader(provider: String, query: String, runtime: EmbeddingRuntimeSettings)(
    ui: EmbeddingUiSettings
  ): String = {
    val time = ZonedDateTime.now(ZoneId.systemDefault()).toString
    val qStr = Option(query).map(_.trim).filter(_.nonEmpty).map(q => s""" | query: "$q"""").getOrElse("")
    val lines = Seq(
      "",
      bold("=" * ui.tableWidth)(ui),
      bold(s"Embedding Report  |  provider: $provider$qStr")(ui),
      s"generated: $time",
      s"chunking: enabled=${runtime.chunkingEnabled}  size=${runtime.chunkSize}  overlap=${runtime.chunkOverlap}",
      s"flags: SHOW_GLOBAL_TOP=${ui.showGlobalTop}  GLOBAL_TOPK=${ui.globalTopK}  COLOR=${ui.colorEnabled}",
      bold("=" * ui.tableWidth)(ui)
    )
    lines.mkString("\n")
  }

  private def renderFileSection(name: String, rows: Seq[Row])(ui: EmbeddingUiSettings): String = {
    if (rows.isEmpty) return s"-- $name: (no rows)\n"
    val head     = rows.head
    val modality = head.modality
    val dim      = head.dim
    val model    = head.model
    val count    = rows.size

    val header =
      s"\n${cyan(s"-- File: $name")(ui)}  |  modality: ${bold(modality)(ui)}  |  model: ${bold(model)(ui)}  |  dim: ${bold(
          dim.toString
        )(ui)}  |  chunks: ${bold(count.toString)(ui)}\n" +
        ("-" * ui.tableWidth) + "\n" +
        bold(
          col("#", COL_IDX) + " " +
            col("chunk-id", COL_ID) + "  " +
            col("dim", COL_DIM) + "  " +
            col("similarity", COL_SIM) + "  " +
            col("top dims (idx:val)", colTop(ui)) + "  " +
            col("meta", COL_META)
        )(ui)

    val body = rows.zipWithIndex
      .map { case (r, i) =>
        val simStr = r.similarity.map(s => s"${fmt4(s)} ${simBar(s, 18)(ui)}").getOrElse(gray("n/a")(ui))
        val tops   = r.topDims.map { case (idx, v) => s"$idx:${fmt3(v)}" }.mkString(", ")
        val idStr  = truncate(r.id, COL_ID)
        val meta   = truncate(s"${r.provider},${r.mime}", COL_META)

        col((i + 1).toString, COL_IDX) + " " +
          col(idStr, COL_ID) + "  " +
          col(r.dim.toString, COL_DIM) + "  " +
          col(simStr, COL_SIM) + "  " +
          col(truncate(tops, colTop(ui)), colTop(ui)) + "  " +
          col(meta, COL_META)
      }
      .mkString("\n")

    header + "\n" + body + "\n"
  }

  private def renderGlobalTop(rows: Seq[Row])(ui: EmbeddingUiSettings): String = {
    val header =
      "\n" + magenta(bold("== Global Text Top ==")(ui))(ui) + "\n" + ("-" * ui.tableWidth) + "\n" +
        bold(
          col("#", COL_IDX) + " " +
            col("file:chunk", COL_ID) + "  " +
            col("dim", COL_DIM) + "  " +
            col("similarity", COL_SIM) + "  " +
            col("top dims (idx:val)", colTop(ui)) + "  " +
            col("meta", COL_META)
        )(ui)

    val body = rows.zipWithIndex
      .map { case (r, i) =>
        val simStr = r.similarity.map(s => s"${fmt4(s)} ${simBar(s, 18)(ui)}").getOrElse(gray("n/a")(ui))
        val tops   = r.topDims.map { case (idx, v) => s"$idx:${fmt3(v)}" }.mkString(", ")
        val idStr  = truncate(s"${r.file}:${r.id}", COL_ID)
        val meta   = truncate(s"${r.provider},${r.mime}", COL_META)

        col((i + 1).toString, COL_IDX) + " " +
          col(idStr, COL_ID) + "  " +
          col(r.dim.toString, COL_DIM) + "  " +
          col(simStr, COL_SIM) + "  " +
          col(truncate(tops, colTop(ui)), colTop(ui)) + "  " +
          col(meta, COL_META)
      }
      .mkString("\n")

    header + "\n" + body + "\n"
  }

  private def renderSummary(files: Int, chunks: Int, errors: Seq[String], perFileRows: Seq[Row])(
    ui: EmbeddingUiSettings
  ): String = {
    val byMod  = perFileRows.groupBy(_.modality).map { case (m, rs) => s"$m=${rs.size}" }.toSeq.sorted.mkString(", ")
    val models = perFileRows.groupBy(_.model).map { case (m, rs) => s"$m(${rs.size})" }.toSeq.sorted.mkString(", ")

    val lines = Seq(
      bold("=" * ui.tableWidth)(ui),
      bold("Summary")(ui),
      s"files processed: $files   |   chunks emitted: $chunks",
      s"by modality: $byMod",
      s"models: $models"
    ) ++ {
      if (errors.nonEmpty)
        Seq(red(s"errors (${errors.size}):")(ui)) ++ errors.take(10).map(e => red(s" - $e")(ui)) ++
          (if (errors.size > 10) Seq(red(s" - ... and ${errors.size - 10} more")(ui)) else Nil)
      else Seq(green("errors: none")(ui))
    } :+ bold("=" * ui.tableWidth)(ui)

    "\n" + lines.mkString("\n") + "\n"
  }

  // ---------------- Pretty bits ----------------
  private def simBar(value: Double, width: Int)(ui: EmbeddingUiSettings): String = {
    val mag  = math.min(1.0, math.max(-1.0, value))
    val fill = (((mag + 1.0) / 2.0) * width).round.toInt
    val full = "#".repeat(fill) + ".".repeat(math.max(0, width - fill))
    val bar  = (if (value >= 0) "+" else "-") + "[" + full + "]"
    if (!ui.colorEnabled || value.isNaN) bar
    else if (value >= 0.6) green(bar)(ui)
    else if (value >= 0.3) yellow(bar)(ui)
    else cyan(bar)(ui) // changed from red(bar)
  }

  private def fmt4(d: Double): String             = f"$d%1.4f"
  private def fmt3(f: Float): String              = f"$f%1.3f"
  private def truncate(s: String, n: Int): String = if (s.length <= n) s else s.take(math.max(0, n - 3)) + "..."
  private def col(s: String, n: Int): String = {
    val t = truncate(s, n)
    if (t.length >= n) t else t + " " * (n - t.length)
  }

  // ANSI helpers
  private def wrap(code: String, s: String)(ui: EmbeddingUiSettings): String =
    if (ui.colorEnabled) s"\u001b[${code}m$s\u001b[0m" else s
  private def bold(s: String)(ui: EmbeddingUiSettings)    = wrap("1", s)(ui)
  private def red(s: String)(ui: EmbeddingUiSettings)     = wrap("31", s)(ui)
  private def green(s: String)(ui: EmbeddingUiSettings)   = wrap("32", s)(ui)
  private def yellow(s: String)(ui: EmbeddingUiSettings)  = wrap("33", s)(ui)
  private def magenta(s: String)(ui: EmbeddingUiSettings) = wrap("35", s)(ui)
  private def cyan(s: String)(ui: EmbeddingUiSettings)    = wrap("36", s)(ui)
  private def gray(s: String)(ui: EmbeddingUiSettings)    = wrap("90", s)(ui)
}
