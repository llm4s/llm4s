package org.llm4s.llmconnect.encoding

import org.apache.tika.Tika
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.LocalEmbeddingModels
import org.llm4s.llmconnect.extractors.UniversalExtractor
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.utils.{ ChunkingUtils, ModelSelector }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.io.File
import java.nio.file.Path
import scala.util.Try

/**
 * Encodes files of arbitrary MIME types into embedding vector sequences.
 *
 * MIME type is detected automatically via Apache Tika. Dispatch then
 * depends on the media type:
 *
 *  - **Text-like files** (plain text, HTML, PDF, source code, …): text is
 *    extracted by `UniversalExtractor`, optionally chunked, then embedded
 *    via the supplied `EmbeddingClient`. Real embeddings are produced.
 *
 *  - **Image / Audio / Video**: only available when `experimentalStubsEnabled`
 *    is `true`. When disabled, these modalities return a `Left` with error
 *    code `501`. When enabled, a deterministic L2-normalised vector is
 *    returned instead of a real embedding; the vector is seeded from the
 *    file name, size, and last-modified time, so the same file always
 *    produces the same stub vector.
 *
 * == Stub dimensions ==
 *
 * Stub vectors are capped at `MAX_STUB_DIMENSION` (8 192) regardless of
 * the configured model dimension, to prevent OOM errors during testing.
 *
 * == Modality disambiguation ==
 *
 * Each modality (image, audio, video) uses a different XOR seed constant
 * when generating stub vectors, so stubs for the same file differ across
 * modalities.
 */
object UniversalEncoder {
  private val logger = LoggerFactory.getLogger(getClass)
  private val tika   = new Tika()

  // Maximum dimension size for stub embeddings to prevent OOM in tests
  private val MAX_STUB_DIMENSION = 8192

  /**
   * Controls how extracted text is split before embedding.
   *
   * @param enabled When `false`, the full extracted text is embedded as a single unit.
   * @param size    Target chunk size in characters.
   * @param overlap Number of characters shared between adjacent chunks, to preserve
   *                context at chunk boundaries.
   */
  final case class TextChunkingConfig(enabled: Boolean, size: Int, overlap: Int)

  /**
   * Encodes the file at `path` into one or more embedding vectors.
   *
   * @param path                     Path to the file to encode; must exist and be a regular file.
   * @param client                   Embedding client used for text files; not called for image/audio/video stubs.
   * @param textModel                Model configuration (name + dimensions) forwarded to `client`.
   * @param chunking                 Text chunking settings; if `enabled`, the extracted text is split before embedding.
   * @param experimentalStubsEnabled When `false`, image/audio/video files return `Left(EmbeddingError)` with code
   *                                 `501`. When `true`, deterministic stub vectors are returned for those modalities.
   * @param localModels              Model configurations for image, audio, and video stubs.
   * @return `Right(vectors)` — one vector per text chunk, or one stub vector for non-text files.
   *         `Left(EmbeddingError)` when the file does not exist, the MIME type is unsupported,
   *         or stub generation is disabled.
   */
  def encodeFromPath(
    path: Path,
    client: EmbeddingClient,
    textModel: org.llm4s.llmconnect.config.EmbeddingModelConfig,
    chunking: TextChunkingConfig,
    experimentalStubsEnabled: Boolean,
    localModels: LocalEmbeddingModels
  ): Result[Seq[EmbeddingVector]] = {
    val f = path.toFile
    if (!f.exists() || !f.isFile) return Left(EmbeddingError(None, s"File not found: $path", "extractor"))

    val mime = Try(tika.detect(f)).getOrElse("application/octet-stream")
    logger.debug(s"[UniversalEncoder] MIME detected: $mime")

    // Reuse canonical MIME logic from UniversalExtractor (no duplication).
    if (UniversalExtractor.isTextLike(mime)) encodeTextFile(f, mime, client, textModel, chunking)
    else if (mime.startsWith("image/")) encodeImageFile(f, mime, experimentalStubsEnabled, localModels)
    else if (mime.startsWith("audio/")) encodeAudioFile(f, mime, experimentalStubsEnabled, localModels)
    else if (mime.startsWith("video/")) encodeVideoFile(f, mime, experimentalStubsEnabled, localModels)
    else Left(EmbeddingError(None, s"Unsupported MIME for encoding: $mime", "encoder"))
  }

  // ---------------- TEXT ----------------
  private def encodeTextFile(
    file: File,
    mime: String,
    client: EmbeddingClient,
    textModel: org.llm4s.llmconnect.config.EmbeddingModelConfig,
    chunking: TextChunkingConfig
  ): Result[Seq[EmbeddingVector]] =
    UniversalExtractor.extract(file.getAbsolutePath) match {
      case Left(e) => Left(EmbeddingError(None, e.message, "extractor"))
      case Right(text) =>
        val inputs =
          if (chunking.enabled) {
            logger.debug(s"[UniversalEncoder] Chunking text: size=${chunking.size} overlap=${chunking.overlap}")
            ChunkingUtils.chunkText(text, chunking.size, chunking.overlap)
          } else Seq(text)

        val req = EmbeddingRequest(input = inputs, model = textModel)

        client.embed(req).map { resp =>
          val dim = textModel.dimensions
          resp.embeddings.zipWithIndex.map { case (vec, i) =>
            EmbeddingVector(
              id = s"${file.getName}#chunk_$i",
              modality = Text,
              model = textModel.name,
              dim = dim,
              values = l2(vec.map(_.toFloat).toArray),
              meta = Map(
                "provider" -> resp.metadata.getOrElse("provider", "unknown"),
                "mime"     -> mime,
                "count"    -> resp.metadata.getOrElse("count", inputs.size.toString)
              )
            )
          }
        }
    }

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
    experimentalStubsEnabled: Boolean,
    localModels: LocalEmbeddingModels
  ): Result[Seq[EmbeddingVector]] = {
    if (!experimentalStubsEnabled) return notImpl("Image")
    val modelResult = ModelSelector.selectModel(Image, localModels)
    modelResult.map { model =>
      val dim = model.dimensions
      // Limit dimension to prevent OOM in tests (max 8K dimensions)
      val safeDim = math.min(dim, MAX_STUB_DIMENSION)
      val seed    = stableSeed(file)
      val raw     = fillDeterministic(safeDim, seed)
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
    }
  }

  // ---------------- AUDIO (stub gated behind demo flag) ----------------
  private def encodeAudioFile(
    file: File,
    mime: String,
    experimentalStubsEnabled: Boolean,
    localModels: LocalEmbeddingModels
  ): Result[Seq[EmbeddingVector]] = {
    if (!experimentalStubsEnabled) return notImpl("Audio")
    val modelResult = ModelSelector.selectModel(Audio, localModels)
    modelResult.map { model =>
      val dim = model.dimensions
      // Limit dimension to prevent OOM in tests (max 8K dimensions)
      val safeDim = math.min(dim, MAX_STUB_DIMENSION)
      val seed    = stableSeed(file) ^ 0x9e3779b97f4a7c15L
      val raw     = fillDeterministic(safeDim, seed)
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
    }
  }

  // ---------------- VIDEO (stub gated behind demo flag) ----------------
  private def encodeVideoFile(
    file: File,
    mime: String,
    experimentalStubsEnabled: Boolean,
    localModels: LocalEmbeddingModels
  ): Result[Seq[EmbeddingVector]] = {
    if (!experimentalStubsEnabled) return notImpl("Video")
    val modelResult = ModelSelector.selectModel(Video, localModels)
    modelResult.map { model =>
      val dim = model.dimensions
      // Limit dimension to prevent OOM in tests (max 8K dimensions)
      val safeDim = math.min(dim, MAX_STUB_DIMENSION)
      val seed    = stableSeed(file) ^ 0xc2b2ae3d27d4eb4fL
      val raw     = fillDeterministic(safeDim, seed)
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
    }
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
