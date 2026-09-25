// scalafix:off DisableSyntax.NoKeywordCatch
package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.spi.{ EmbeddingConfigSpec, EmbeddingProviderDescriptor }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.llm4s.llmconnect.model.{ EmbeddingError, EmbeddingRequest, EmbeddingResponse }
import org.llm4s.util.Redaction
import org.slf4j.LoggerFactory
import ujson.{ Obj, read }

import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.nio.charset.StandardCharsets
import java.time.Duration
import scala.util.Try
import scala.util.control.NonFatal

/**
 * Embedding provider implementation for Ollama, a local model inference server.
 *
 * Generates text embeddings by calling the Ollama `/api/embeddings` HTTP endpoint.
 * Each input text is embedded individually (one HTTP request per text) because the
 * Ollama embedding API accepts a single prompt per call. Results are collected and
 * returned as an [[org.llm4s.llmconnect.model.EmbeddingResponse]].
 *
 * No API key is required when Ollama runs locally, though one can be supplied for
 * remote or authenticated deployments.
 *
 * Ollama's entry in the embedding half of the provider SPI.
 *
 * It shares the `ollama` id with [[OllamaProvider]]'s chat client - the two
 * namespaces are separate - so `EMBEDDING_MODEL=ollama/nomic-embed-text` and
 * `LLM_MODEL=ollama/llama3` name the same provider.
 */
object OllamaEmbeddingProvider extends EmbeddingProviderDescriptor {

  val id: ProviderId = ProviderId("ollama")

  override val configSpec: EmbeddingConfigSpec = EmbeddingConfigSpec(
    defaultBaseUrl = Some("http://localhost:11434"),
    defaultModel = Some("nomic-embed-text"),
    // Ollama ignores the field when run locally, but the client still wants a string.
    defaultApiKey = Some("not-required"),
    modelEnv = Some("OLLAMA_EMBEDDING_MODEL")
  )

  /**
   * The embedding models in Ollama's library that are commonly pulled, under
   * their untagged names. Ollama model names carry an optional `:tag`, and
   * different tags of one model can differ in size - `snowflake-arctic-embed:22m`
   * is 384-dimensional, `:335m` is 1024 - so only `:latest`, which is what the
   * untagged name means, is folded onto these.
   */
  override val modelDimensions: Map[String, Int] = Map(
    "nomic-embed-text"  -> 768,
    "mxbai-embed-large" -> 1024,
    "all-minilm"        -> 384
  )

  override def dimensionsOf(model: String): Option[Int] =
    modelDimensions.get(model.stripSuffix(":latest"))

  /** Builds the provider for the SPI; see [[fromConfig]] for the direct route. */
  def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider] = Right(fromConfig(config))

  /** Creates an [[EmbeddingProvider]] backed by Ollama using the given configuration. */
  def fromConfig(cfg: EmbeddingProviderConfig): EmbeddingProvider = new EmbeddingProvider {
    private val httpClient = HttpClient.newHttpClient()
    private val logger     = LoggerFactory.getLogger(getClass)

    override def embed(request: EmbeddingRequest): Either[EmbeddingError, EmbeddingResponse] = {
      val model = request.model.name
      val input = request.input

      val results = input.map(text => embedSingle(cfg, model, text))

      val (errors, vectors) = results.partition(_.isLeft)
      if (errors.nonEmpty) {
        errors.head
          .asInstanceOf[Left[EmbeddingError, Vector[Double]]]
          .swap
          .toOption
          .map(Left(_))
          .getOrElse(Left(EmbeddingError(code = Some("500"), message = "Unknown error", provider = "ollama")))
      } else {
        val embeddings = vectors.collect { case Right(v) => v }
        val metadata   = Map("provider" -> "ollama", "model" -> model, "count" -> input.size.toString)
        Right(EmbeddingResponse(embeddings = embeddings, metadata = metadata))
      }
    }

    private def embedSingle(
      cfg: EmbeddingProviderConfig,
      model: String,
      text: String
    ): Either[EmbeddingError, Vector[Double]] = {
      val payload = Obj(
        "model"  -> model,
        "prompt" -> text
      )

      val url = s"${cfg.baseUrl}/api/embeddings"

      logger.debug(s"[OllamaEmbeddingProvider] POST $url model=$model text_length=${text.length}")

      val builder = HttpRequest
        .newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofMinutes(2))
        .POST(HttpRequest.BodyPublishers.ofString(payload.render()))

      if (cfg.apiKey.nonEmpty && cfg.apiKey != "not-required") {
        builder.header("Authorization", s"Bearer ${cfg.apiKey}")
      }

      val httpRequest = builder.build()

      val respEither: Either[EmbeddingError, HttpResponse[String]] =
        try Right(httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)))
        catch {
          case e: InterruptedException =>
            Thread.currentThread().interrupt()
            Left(
              EmbeddingError(code = None, message = s"HTTP request interrupted: ${e.getMessage}", provider = "ollama")
            )
          case NonFatal(e) =>
            Left(EmbeddingError(code = None, message = s"HTTP request failed: ${e.getMessage}", provider = "ollama"))
        }

      respEither.flatMap { response =>
        response.statusCode() match {
          case 200 =>
            Try {
              val json   = read(response.body())
              val vector = json("embedding").arr.map(_.num).toVector
              vector
            }.toEither.left
              .map { ex =>
                logger.error(s"[OllamaEmbeddingProvider] Parse error: ${ex.getMessage}")
                EmbeddingError(code = None, message = s"Parsing error: ${ex.getMessage}", provider = "ollama")
              }
          case status =>
            val body = Redaction.truncateForLog(response.body())
            logger.error(s"[OllamaEmbeddingProvider] HTTP error: $body")
            Left(
              EmbeddingError(
                code = Some(status.toString),
                message = body,
                provider = "ollama"
              )
            )
        }
      }
    }
  }
}
