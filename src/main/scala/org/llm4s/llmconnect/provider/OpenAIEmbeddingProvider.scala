package org.llm4s.llmconnect.provider

import sttp.client4._
import ujson._
import org.llm4s.llmconnect.config.EmbeddingConfig
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.llm4s.error.{LLMError => LErr} // <-- If your repo names differ, adjust this import.

object OpenAIEmbeddingProvider extends EmbeddingProvider {

  override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
    val cfg     = EmbeddingConfig.openai
    val backend = DefaultSyncBackend()

    try {
      val payload = Obj(
        "model" -> Str(cfg.model),
        "input" -> Arr.from(request.input.map(Str(_)))
      ).render()

      val resp = basicRequest
        .post(uri"${cfg.baseUrl}/embeddings")
        .header("Authorization", s"Bearer ${cfg.apiKey}")
        .header("Content-Type", "application/json")
        .body(payload)
        .send(backend)

      resp.body match {
        case Right(body) =>
          parseSuccess(body, cfg.model)

        case Left(errText) =>
          // If your error type has a different constructor, swap it here (e.g., HttpError)
          Left(LErr.HttpError(status = resp.code.code, body = errText))
      }
    } catch {
      case e: ReadException =>
        Left(LErr.Unexpected(message = s"Network/read error: ${e.getMessage}", cause = Some(e)))
      case e: Throwable =>
        Left(LErr.Unexpected(message = s"Unexpected error: ${e.getMessage}", cause = Some(e)))
    }
  }

  /** Parse OpenAI /v1/embeddings JSON into EmbeddingResponse */
  private def parseSuccess(body: String, model: String): Result[EmbeddingResponse] = {
    try {
      val json = ujson.read(body)

      // OpenAI shape:
      // { "data": [ { "embedding": [..], "index": 0 }, ... ], "model": "text-embedding-3-large", ... }
      val dataArr = json("data").arr

      val vectors: Vector[Array[Float]] =
        dataArr.toVector.map { item =>
          val emb = item("embedding").arr
          val arr = new Array[Float](emb.length)
          var i   = 0
          while (i < emb.length) {
            arr(i) = emb(i).num.toFloat
            i += 1
          }
          arr
        }

      val dimension = if (vectors.nonEmpty) vectors.head.length else 0
      Right(EmbeddingResponse(vectors = vectors, model = model, dimension = dimension))
    } catch {
      case e: Throwable =>
        Left(LErr.ParseError(message = s"Failed to parse OpenAI embeddings: ${e.getMessage}"))
    }
  }
}
