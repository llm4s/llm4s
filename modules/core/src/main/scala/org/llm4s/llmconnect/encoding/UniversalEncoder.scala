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

object UniversalEncoder {
  private val logger = LoggerFactory.getLogger(getClass)
  private val tika   = new Tika()

  // Maximum dimension size for stub embeddings to prevent OOM in tests
  private val MAX_STUB_DIMENSION = 8192

  final case class TextChunkingConfig(enabled: Boolean, size: Int, overlap: Int)

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

    if (UniversalExtractor.isTextLike(mime)) encodeTextFile(f, mime, client, textModel, chunking)
    else if (mime.startsWith("image/")) encodeImageFile(f, mime, experimentalStubsEnabled, localModels, client)
    else if (mime.startsWith("audio/")) encodeAudioFile(f, mime, experimentalStubsEnabled, localModels, client)
    else if (mime.startsWith("video/")) encodeVideoFile(f, mime, experimentalStubsEnabled, localModels, client)
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

  private def encodeImageFile(
    file: File,
    mime: String,
    experimentalStubsEnabled: Boolean,
    localModels: LocalEmbeddingModels,
    client: EmbeddingClient
  ): Result[Seq[EmbeddingVector]] = {
    val modelResult = ModelSelector.selectModel(Image, localModels)

    if (!experimentalStubsEnabled) {
      return modelResult.flatMap { model =>
        Try(java.nio.file.Files.readAllBytes(file.toPath)).toEither.left
          .map(e => EmbeddingError(None, s"Failed to read image file: ${e.getMessage}", "encoder"))
          .flatMap { bytes =>
            val req = MultimediaEmbeddingRequest(
              inputs = Seq(RawMediaInput(bytes, mime)),
              model = model,
              modality = Image,
              meta = Map("mime" -> mime, "file" -> file.getName)
            )
            client.embedMultimodal(req).map { resp =>
              val dim = model.dimensions
              resp.embeddings.zipWithIndex.map { case (vec, i) =>
                EmbeddingVector(
                  id = s"${file.getName}#image_$i",
                  modality = Image,
                  model = model.name,
                  dim = dim,
                  values = l2(vec.map(_.toFloat).toArray),
                  meta = resp.metadata ++ Map("mime" -> mime)
                )
              }
            }
          }
      }
    }

    modelResult.map { model =>
      val dim     = model.dimensions
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

  private def encodeAudioFile(
    file: File,
    mime: String,
    experimentalStubsEnabled: Boolean,
    localModels: LocalEmbeddingModels,
    client: EmbeddingClient
  ): Result[Seq[EmbeddingVector]] = {
    val modelResult = ModelSelector.selectModel(Audio, localModels)

    if (!experimentalStubsEnabled) {
      return modelResult.flatMap { model =>
        Try(java.nio.file.Files.readAllBytes(file.toPath)).toEither.left
          .map(e => EmbeddingError(None, s"Failed to read audio file: ${e.getMessage}", "encoder"))
          .flatMap { bytes =>
            val req = MultimediaEmbeddingRequest(
              inputs = Seq(RawMediaInput(bytes, mime)),
              model = model,
              modality = Audio,
              meta = Map("mime" -> mime, "file" -> file.getName)
            )
            client.embedMultimodal(req).map { resp =>
              val dim = model.dimensions
              resp.embeddings.zipWithIndex.map { case (vec, i) =>
                EmbeddingVector(
                  id = s"${file.getName}#audio_$i",
                  modality = Audio,
                  model = model.name,
                  dim = dim,
                  values = l2(vec.map(_.toFloat).toArray),
                  meta = resp.metadata ++ Map("mime" -> mime)
                )
              }
            }
          }
      }
    }

    modelResult.map { model =>
      val dim     = model.dimensions
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

  private def encodeVideoFile(
    file: File,
    mime: String,
    experimentalStubsEnabled: Boolean,
    localModels: LocalEmbeddingModels,
    client: EmbeddingClient
  ): Result[Seq[EmbeddingVector]] = {
    val modelResult = ModelSelector.selectModel(Video, localModels)

    if (!experimentalStubsEnabled) {
      return modelResult.flatMap { model =>
        Try(java.nio.file.Files.readAllBytes(file.toPath)).toEither.left
          .map(e => EmbeddingError(None, s"Failed to read video file: ${e.getMessage}", "encoder"))
          .flatMap { bytes =>
            val req = MultimediaEmbeddingRequest(
              inputs = Seq(RawMediaInput(bytes, mime)),
              model = model,
              modality = Video,
              meta = Map("mime" -> mime, "file" -> file.getName)
            )
            client.embedMultimodal(req).map { resp =>
              val dim = model.dimensions
              resp.embeddings.zipWithIndex.map { case (vec, i) =>
                EmbeddingVector(
                  id = s"${file.getName}#video_$i",
                  modality = Video,
                  model = model.name,
                  dim = dim,
                  values = l2(vec.map(_.toFloat).toArray),
                  meta = resp.metadata ++ Map("mime" -> mime)
                )
              }
            }
          }
      }
    }

    modelResult.map { model =>
      val dim     = model.dimensions
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
