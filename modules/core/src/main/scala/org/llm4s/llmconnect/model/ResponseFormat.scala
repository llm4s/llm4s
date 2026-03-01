package org.llm4s.llmconnect.model

/**
 * Requested format for LLM completion output (structured output).
 *
 * When set in [[CompletionOptions.responseFormat]], the provider is asked to return
 * output in the specified format. Support is provider- and model-dependent; see
 * [[CompletionOptions]] ScalaDoc for capability validation and fallback behavior.
 *
 * - '''Json''': Generic JSON object mode; the model is instructed to return valid JSON.
 * - '''JsonSchema(schema)''': Provider-specific JSON schema; the model's output is
 *   constrained to match the given schema where supported.
 */
sealed trait ResponseFormat

object ResponseFormat {

  /**
   * Generic JSON object mode.
   * Maps to e.g. OpenAI `response_format: { "type": "json_object" }`.
   */
  case object Json extends ResponseFormat

  /**
   * JSON schema constraint.
   *
   * @param schema JSON schema as ujson value. For OpenAI, the schema is wrapped with
   *               name/strict in the provider mapping layer.
   * @param name   Schema name for debugging/tracing (OpenAI requires it; max 64 chars).
   *               Default "response" for backward compatibility.
   * @param strict When true, model must follow the exact schema (OpenAI strict mode).
   *               Default true; set false for partial schema adherence.
   */
  case class JsonSchema(
    schema: ujson.Value,
    name: String = "response",
    strict: Boolean = true
  ) extends ResponseFormat
}
