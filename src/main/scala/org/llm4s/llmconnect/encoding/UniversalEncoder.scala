package org.llm4s.llmconnect.encoding

import org.apache.tika.Tika
import org.llm4s.config.ConfigReader
import org.llm4s.config.ConfigReader.LLMConfig
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config._
import org.llm4s.llmconnect.extractors.UniversalExtractor
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.utils.{ ChunkingUtils, ModelSelector }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.io.File
import java.nio.file.Path
import scala.util.Try

object UniversalEncoder {
  private val logger = LoggerFactory.getLogger(getClass)
  private val tika   = new Tika()

  // Maximum dimension size for stub embeddings to prevent OOM in tests
  private val MAX_STUB_DIMENSION = 8192

  /** Entry point: detect MIME → extract → encode → normalized vectors + metadata. */
  def encodeFromPath(path: Path, client: EmbeddingClient): Result[Seq[EmbeddingVector]] =
    LLMConfig().flatMap(cfg => encodeFromPath(path, client, cfg))

  /** Entry point with explicit config for better testability and error handling. */
  def encodeFromPath(
    path: Path,
    client: EmbeddingClient,
    config: ConfigReader
  ): Result[Seq[EmbeddingVector]] = {
    val f = path.toFile
    if (!f.exists() || !f.isFile) return Left(EmbeddingError(None, s"File not found: $path", "extractor"))

    val mime = Try(tika.detect(f)).getOrElse("application/octet-stream")
    logger.debug(s"[UniversalEncoder] MIME detected: $mime")

    // Reuse canonical MIME logic from UniversalExtractor (no duplication).
    if (UniversalExtractor.isTextLike(mime)) encodeTextFile(f, mime, client, config)
    else if (mime.startsWith("image/")) encodeImageFile(f, mime, config)
    else if (mime.startsWith("audio/")) encodeAudioFile(f, mime, config)
    else if (mime.startsWith("video/")) encodeVideoFile(f, mime, config)
    else Left(EmbeddingError(None, s"Unsupported MIME for encoding: $mime", "encoder"))
  }

  // ---------------- TEXT ----------------
  private def encodeTextFile(
    file: File,
    mime: String,
    client: EmbeddingClient,
    config: ConfigReader
  ): Result[Seq[EmbeddingVector]] =
    UniversalExtractor.extract(file.getAbsolutePath) match {
      case Left(e) => Left(EmbeddingError(None, e.message, "extractor"))
      case Right(text) =>
        val inputs: Seq[String] =
          if (EmbeddingConfig.chunkingEnabled(config)) {
            val size = EmbeddingConfig.chunkSize(config)
            val ovlp = EmbeddingConfig.chunkOverlap(config)
            logger.debug(s"[UniversalEncoder] Chunking text: size=$size overlap=$ovlp")
            ChunkingUtils.chunkText(text, size, ovlp)
          } else Seq(text)

        val model = ModelSelector.selectModel(Text, config)
        val req   = EmbeddingRequest(input = inputs, model = model)

        client.embed(req).map { r =>
          val dim = model.dimensions
          r.embeddings.zipWithIndex.map { case (vec, i) =>
            EmbeddingVector(
              id = s"${file.getName}#chunk_$i",
              modality = Text,
              model = model.name,
              dim = dim,
              values = l2(vec.map(_.toFloat).toArray),
              meta = Map(
                "provider" -> r.metadata.getOrElse("provider", EmbeddingConfig.activeProvider(config)),
                "mime"     -> mime,
                "count"    -> r.metadata.getOrElse("count", inputs.size.toString)
              )
            )
          }
        }
    }

  // --------------- EXPERIMENTAL GATE ----------------
  private def experimentalOn: Boolean =
    sys.env.get("ENABLE_EXPERIMENTAL_STUBS").exists(_.trim.equalsIgnoreCase("true"))

  private def notImpl(mod: String) =
    Left(
      EmbeddingError(
        code = Some("501"),
        message = s"$mod embeddings not implemented. Set ENABLE_EXPERIMENTAL_STUBS=true to enable demo stubs.",
        provider = "encoder"
      )
    )

  // ---------------- IMAGE (stub gated behind demo flag) ----------------
  private def encodeImageFile(
    file: File,
    mime: String,
    config: ConfigReader
  ): Result[Seq[EmbeddingVector]] = {
    if (!experimentalOn) return notImpl("Image")
    val model = ModelSelector.selectModel(Image, config)
    val dim   = model.dimensions
    // Limit dimension to prevent OOM in tests (max 8K dimensions)
    val safeDim = math.min(dim, MAX_STUB_DIMENSION)
    val seed    = stableSeed(file)
    val raw     = fillDeterministic(safeDim, seed)
    Right(
      Seq(
        EmbeddingVector(
          id = file.getName,
          modality = Image,
          model = model.name,
          dim = safeDim,
          values = l2(raw),
          meta = Map("mime" -> mime, "experimental" -> "true", "provider" -> "local-experimental")
        )
      )
    )
  }

  // ---------------- AUDIO (stub gated behind demo flag) ----------------
  private def encodeAudioFile(
    file: File,
    mime: String,
    config: ConfigReader
  ): Result[Seq[EmbeddingVector]] = {
    if (!experimentalOn) return notImpl("Audio")
    val model = ModelSelector.selectModel(Audio, config)
    val dim   = model.dimensions
    // Limit dimension to prevent OOM in tests (max 8K dimensions)
    val safeDim = math.min(dim, MAX_STUB_DIMENSION)
    val seed    = stableSeed(file) ^ 0x9e3779b97f4a7c15L
    val raw     = fillDeterministic(safeDim, seed)
    Right(
      Seq(
        EmbeddingVector(
          id = file.getName,
          modality = Audio,
          model = model.name,
          dim = safeDim,
          values = l2(raw),
          meta = Map("mime" -> mime, "experimental" -> "true", "provider" -> "local-experimental")
        )
      )
    )
  }

  // ---------------- VIDEO (stub gated behind demo flag) ----------------
  private def encodeVideoFile(
    file: File,
    mime: String,
    config: ConfigReader
  ): Result[Seq[EmbeddingVector]] = {
    if (!experimentalOn) return notImpl("Video")
    val model = ModelSelector.selectModel(Video, config)
    val dim   = model.dimensions
    // Limit dimension to prevent OOM in tests (max 8K dimensions)
    val safeDim = math.min(dim, MAX_STUB_DIMENSION)
    val seed    = stableSeed(file) ^ 0xc2b2ae3d27d4eb4fL
    val raw     = fillDeterministic(safeDim, seed)
    Right(
      Seq(
        EmbeddingVector(
          id = file.getName,
          modality = Video,
          model = model.name,
          dim = safeDim,
          values = l2(raw),
          meta = Map("mime" -> mime, "experimental" -> "true", "provider" -> "local-experimental")
        )
      )
    )
  }

  // ---------------- helpers ----------------
  private def l2(v: Array[Float]): Array[Float] = {
    val n = math.sqrt(v.foldLeft(0.0)((s, x) => s + x * x)).toFloat
    if (n <= 1e-6f) v else v.map(_ / n)
  }

  /** Stable file-based seed for deterministic stub vectors. */
  private def stableSeed(file: File): Long = {
    val s1 = file.getName.hashCode.toLong
    val s2 = file.length()
    val s3 = file.lastModified()
    // mix
    var x = s1 ^ (s2 << 1) ^ (s3 << 3)
    x ^= (x >>> 33); x *= 0xff51afd7ed558ccdL
    x ^= (x >>> 33); x *= 0xc4ceb9fe1a85ec53L
    x ^ (x >>> 33)
  }

  /** Pseudo-random, but deterministic for a given seed; values in [-0.5, 0.5]. */
  private def fillDeterministic(dim: Int, seed: Long): Array[Float] = {
    var z   = seed
    val out = Array.ofDim[Float](dim)
    var i   = 0
    while (i < dim) {
      z ^= (z << 13); z ^= (z >>> 7); z ^= (z << 17) // xorshift
      out(i) = ((z & 0xffffff).toInt % 1001) / 1000.0f - 0.5f
      i += 1
    }
    out
  }
}
