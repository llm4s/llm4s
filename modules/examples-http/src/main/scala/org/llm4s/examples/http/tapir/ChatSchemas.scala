package org.llm4s.examples.http.tapir

import sttp.tapir.Schema

/**
 * The JSON of [[ChatEndpoints]], described for the OpenAPI documentation. The bodies themselves are the shared
 * protocol's `ujson` values ([[org.llm4s.examples.http.ChatProtocol ChatProtocol]],
 * [[org.llm4s.examples.http.HttpFailure.body HttpFailure.body]]); these types only give them a schema.
 *
 * Excluded from coverage (`coverageExcludedFiles` in `build.sbt`): `derives Schema` expands to Tapir's derivation
 * code here, which the documentation exercises but statement coverage counts as hundreds of synthetic statements.
 */
object ChatSchemas {

  /** The request body of both endpoints. */
  final case class ChatRequest(message: String) derives Schema

  /** The `/chat` response, and the data of the stream's `done` event. `answer` is set when `status` is `Completed`. */
  final case class ChatResponse(threadId: String, status: String, answer: Option[String]) derives Schema

  /** An error body: `{"error": {"type", "message", "recoverable"}}`. */
  final case class ErrorBody(error: ErrorDetail) derives Schema

  /** The error's `LLMError` type, the library's message, and whether trying again may succeed. */
  final case class ErrorDetail(`type`: String, message: String, recoverable: Boolean) derives Schema
}
