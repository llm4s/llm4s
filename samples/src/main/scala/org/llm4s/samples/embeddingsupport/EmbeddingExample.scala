package org.llm4s.samples.embeddingsupport

import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.utils.{ ModelSelector, SimilarityUtils }
import org.llm4s.config.ConfigReader.LLMConfig
import org.slf4j.LoggerFactory

import java.nio.file.{Files, Path, Paths}
import java.time.{ZoneId, ZonedDateTime}
import scala.jdk.CollectionConverters._
import scala.util.Try

object EmbeddingExample {

  private val logger = LoggerFactory.getLogger(getClass)

  // ------------------------ Output knobs (env-tunable) ------------------------
  private val MAX_ROWS_PER_FILE: Int =
    sys.env.get("MAX_ROWS_PER_FILE").flatMap(s => Try(s.toInt).toOption).getOrElse(200)
  private val TOP_DIMS_PER_ROW: Int  =
    sys.env.get("TOP_DIMS_PER_ROW").flatMap(s => Try(s.toInt).toOption).getOrElse(6)
  private val GLOBAL_TOPK: Int       =
    sys.env.get("GLOBAL_TOPK").flatMap(s => Try(s.toInt).toOption).getOrElse(10)
  private val SHOW_GLOBAL_TOP: Boolean =
    sys.env.get("SHOW_GLOBAL_TOP").exists(_.trim.equalsIgnoreCase("true"))

  // UI/formatting
  private val COLOR_ENABLED: Boolean =
    sys.env.get("COLOR").forall(_.trim.equalsIgnoreCase("true")) // default ON
  private val TABLE_WIDTH: Int =
    sys.env.get("TABLE_WIDTH").flatMap(s => Try(s.toInt).toOption).getOrElse(120)

  // Column widths
  private val COL_IDX   = 4
  private val COL_ID    = 56
  private val COL_DIM   = 6
  private val COL_SIM   = 28
  private val COL_TOP   = math.max(18, TABLE_WIDTH - (COL_IDX + 1 + COL_ID + 2 + COL_DIM + 2 + COL_SIM + 2 + 18))
  private val COL_META  = 18

  def main(args: Array[String]): Unit = {
    logger.info("Starting embedding example...")

    val config = LLMConfig()
    
    val targets = parseTargets(config)
    if (targets.isEmpty) {
      println(red("[ERR] No inputs. Set EMBEDDING_INPUT_PATH or EMBEDDING_INPUT_PATHS."))
      return
    }

    val client = EmbeddingClient.fromConfigEither(config) match {
      case Left(err) =>
        println(red(s"[ERR] config: ${err.provider} -> ${err.message}"))
        return
      case Right(c) => c
    }

    val query       = EmbeddingConfig.query(config)
    val queryVecOpt = for {
      configQuery <- query
      quertVect <- embedQueryOnce(client, configQuery, config)
    } yield quertVect

    // accumulate results
    val perFileRows = collection.mutable.ArrayBuffer.empty[(String, Seq[Row])]
    val globalText  = collection.mutable.ArrayBuffer.empty[Row]
    val errors      = collection.mutable.ArrayBuffer.empty[String]
    var fileCount   = 0
    var chunkTotal  = 0

    targets.foreach { p =>
      fileCount += 1
      client.encodePath(p) match {
        case Left(err) =>
          val msg = s"${p.getFileName}: ${err.provider} -> ${err.message}"
          errors += msg
          println(red(s"[ERR] $msg"))
        case Right(Nil) =>
          println(yellow(s"[WARN] ${p.getFileName}: no embeddings."))
        case Right(vecs) =>
          val rows = toRows(p.getFileName.toString, vecs, queryVecOpt, TOP_DIMS_PER_ROW)
          val clipped = rows.take(MAX_ROWS_PER_FILE)
          perFileRows += ((p.getFileName.toString, clipped))
          chunkTotal += clipped.size
          clipped.filter(_.modality == "Text").foreach(globalText += _)
      }
    }

    // ---- print report ----
    for {
      query <- query
    } yield println(renderHeader(provider = EmbeddingConfig.activeProvider(config), query = query, config))

    perFileRows.foreach { case (name, rows) =>
      println(renderFileSection(name, rows))
    }

    if (SHOW_GLOBAL_TOP && globalText.nonEmpty) {
      val top = globalText.sortBy(r => -r.similarity.getOrElse(Double.NegativeInfinity)).take(GLOBAL_TOPK).toSeq
      println(renderGlobalTop(top))
    }

    println(renderSummary(
      files = fileCount,
      chunks = chunkTotal,
      errors = errors.toSeq,
      perFileRows = perFileRows.flatMap(_._2).toSeq
    ))
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
        file       = fileName,
        id         = v.id,
        modality   = v.modality.toString,
        dim        = v.dim,
        model      = v.model,
        similarity = s,
        topDims    = topKAbs(v.values, topDims),
        provider   = v.meta.getOrElse("provider", "n/a"),
        mime       = v.meta.getOrElse("mime", "n/a")
      )
    }
  }

  // ---------------- Inputs ----------------
  private def parseTargets(config: ConfigReader): Seq[Path] = {
    val multi  = config.get("EMBEDDING_INPUT_PATHS")
    val single = config.get("EMBEDDING_INPUT_PATH")
    val raw: Seq[String] =
      multi.map(splitList).getOrElse(single.toSeq)

    val paths = raw.map(_.trim).filter(_.nonEmpty).map(Paths.get(_))
    val expanded = paths.flatMap { p =>
      if (Files.isDirectory(p))
        Files.list(p).iterator().asScala.filter(Files.isRegularFile(_)).toSeq
      else Seq(p)
    }
    expanded.foldLeft(Vector.empty[Path]) { (acc, p) => if (acc.contains(p)) acc else acc :+ p }
  }
  private def splitList(s: String): Seq[String] = s.split("[,;]").toSeq

  // ---------------- Query embed cache ----------------
  private def embedQueryOnce(client: EmbeddingClient, query: String, config: ConfigReader): Option[Seq[Double]] = {
    if (query == null || query.trim.isEmpty) return None
    val model = ModelSelector.selectModel(Text, config)
    val req   = EmbeddingRequest(Seq(query), model)
    client.embed(req) match {
      case Right(resp) if resp.embeddings.nonEmpty =>
        Some(l2Normalize(resp.embeddings.head))
      case _ =>
        println(yellow("[WARN] Query embedding unavailable; similarities hidden."))
        None
    }
  }
  private def l2Normalize(v: Seq[Double]): Seq[Double] = {
    val n = math.sqrt(v.map(x => x * x).sum)
    if (n <= 1e-12) v else v.map(_ / n)
  }

  // ---------------- Rendering helpers ----------------
  private def renderHeader(provider: String, query: String, config: ConfigReader): String = {
    val time = ZonedDateTime.now(ZoneId.systemDefault()).toString
    val qStr = Option(query).map(_.trim).filter(_.nonEmpty).map(q => s""" | query: "$q"""").getOrElse("")
    val lines = Seq(
      "",
      bold("=" * TABLE_WIDTH),
      bold(s"Embedding Report  |  provider: $provider$qStr"),
      s"generated: $time",
      s"chunking: enabled=${EmbeddingConfig.chunkingEnabled(config)}  size=${EmbeddingConfig.chunkSize(config)}  overlap=${EmbeddingConfig.chunkOverlap(config)}",
      s"flags: SHOW_GLOBAL_TOP=$SHOW_GLOBAL_TOP  GLOBAL_TOPK=$GLOBAL_TOPK  COLOR=$COLOR_ENABLED",
      bold("=" * TABLE_WIDTH)
    )
    lines.mkString("\n")
  }

  private def renderFileSection(name: String, rows: Seq[Row]): String = {
    if (rows.isEmpty) return s"-- $name: (no rows)\n"
    val head     = rows.head
    val modality = head.modality
    val dim      = head.dim
    val model    = head.model
    val count    = rows.size

    val header =
      s"\n${cyan(s"-- File: $name")}  |  modality: ${bold(modality)}  |  model: ${bold(model)}  |  dim: ${bold(dim.toString)}  |  chunks: ${bold(count.toString)}\n" +
        ("-" * TABLE_WIDTH) + "\n" +
        bold(
          col("#", COL_IDX) + " " +
            col("chunk-id", COL_ID) + "  " +
            col("dim", COL_DIM) + "  " +
            col("similarity", COL_SIM) + "  " +
            col("top dims (idx:val)", COL_TOP) + "  " +
            col("meta", COL_META)
        )

    val body = rows.zipWithIndex.map { case (r, i) =>
      val simStr = r.similarity.map(s => s"${fmt4(s)} ${simBar(s, 18)}").getOrElse(gray("n/a"))
      val tops   = r.topDims.map{ case (idx, v) => s"$idx:${fmt3(v)}" }.mkString(", ")
      val idStr  = truncate(r.id, COL_ID)
      val meta   = truncate(s"${r.provider},${r.mime}", COL_META)

      col((i + 1).toString, COL_IDX) + " " +
        col(idStr, COL_ID) + "  " +
        col(r.dim.toString, COL_DIM) + "  " +
        col(simStr, COL_SIM) + "  " +
        col(truncate(tops, COL_TOP), COL_TOP) + "  " +
        col(meta, COL_META)
    }.mkString("\n")

    header + "\n" + body + "\n"
  }

  private def renderGlobalTop(rows: Seq[Row]): String = {
    val header = "\n" + magenta(bold("== Global Text Top ==")) + "\n" + ("-" * TABLE_WIDTH) + "\n" +
      bold(
        col("#", COL_IDX) + " " +
          col("file:chunk", COL_ID) + "  " +
          col("dim", COL_DIM) + "  " +
          col("similarity", COL_SIM) + "  " +
          col("top dims (idx:val)", COL_TOP) + "  " +
          col("meta", COL_META)
      )

    val body = rows.zipWithIndex.map { case (r, i) =>
      val simStr = r.similarity.map(s => s"${fmt4(s)} ${simBar(s, 18)}").getOrElse(gray("n/a"))
      val tops   = r.topDims.map{ case (idx, v) => s"$idx:${fmt3(v)}" }.mkString(", ")
      val idStr  = truncate(s"${r.file}:${r.id}", COL_ID)
      val meta   = truncate(s"${r.provider},${r.mime}", COL_META)

      col((i + 1).toString, COL_IDX) + " " +
        col(idStr, COL_ID) + "  " +
        col(r.dim.toString, COL_DIM) + "  " +
        col(simStr, COL_SIM) + "  " +
        col(truncate(tops, COL_TOP), COL_TOP) + "  " +
        col(meta, COL_META)
    }.mkString("\n")

    header + "\n" + body + "\n"
  }

  private def renderSummary(files: Int, chunks: Int, errors: Seq[String], perFileRows: Seq[Row]): String = {
    val byMod = perFileRows.groupBy(_.modality).map{ case (m, rs) => s"$m=${rs.size}" }.toSeq.sorted.mkString(", ")
    val models = perFileRows.groupBy(_.model).map{ case (m, rs) => s"$m(${rs.size})" }.toSeq.sorted.mkString(", ")

    val lines = Seq(
      bold("=" * TABLE_WIDTH),
      bold("Summary"),
      s"files processed: $files   |   chunks emitted: $chunks",
      s"by modality: $byMod",
      s"models: $models"
    ) ++ {
      if (errors.nonEmpty)
        Seq(red(s"errors (${errors.size}):")) ++ errors.take(10).map(e => red(s" - $e")) ++
          (if (errors.size > 10) Seq(red(s" - ... and ${errors.size - 10} more")) else Nil)
      else Seq(green("errors: none"))
    } :+ bold("=" * TABLE_WIDTH)

    "\n" + lines.mkString("\n") + "\n"
  }

  // ---------------- Pretty bits ----------------
  private def simBar(value: Double, width: Int): String = {
    val mag  = math.min(1.0, math.max(-1.0, value))
    val fill = (((mag + 1.0) / 2.0) * width).round.toInt
    val full = "#".repeat(fill) + ".".repeat(math.max(0, width - fill))
    val bar  = (if (value >= 0) "+" else "-") + "[" + full + "]"
    if (!COLOR_ENABLED || value.isNaN) bar
    else if (value >= 0.6) green(bar)
    else if (value >= 0.3) yellow(bar)
    else cyan(bar) // changed from red(bar)
  }

  private def fmt4(d: Double): String = f"$d%1.4f"
  private def fmt3(f: Float): String  = f"$f%1.3f"
  private def truncate(s: String, n: Int): String = if (s.length <= n) s else s.take(math.max(0, n - 3)) + "..."
  private def col(s: String, n: Int): String = {
    val t = truncate(s, n)
    if (t.length >= n) t else t + " " * (n - t.length)
  }

  // ANSI helpers
  private def wrap(code: String, s: String): String = if (COLOR_ENABLED) s"\u001b[${code}m$s\u001b[0m" else s
  private def bold(s: String)    = wrap("1", s)
  private def red(s: String)     = wrap("31", s)
  private def green(s: String)   = wrap("32", s)
  private def yellow(s: String)  = wrap("33", s)
  private def magenta(s: String) = wrap("35", s)
  private def cyan(s: String)    = wrap("36", s)
  private def gray(s: String)    = wrap("90", s)
}
