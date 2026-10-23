package org.llm4s.llmconnect.model

import org.llm4s.annotation.Stable

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
@Stable
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
   *
   * Build one with [[JsonSchema.apply]] and change it with the `with*` setters; the constructor and `copy` are
   * private, so a new field never breaks callers.
   */
  final case class JsonSchema private (
    schema: ujson.Value,
    name: String,
    strict: Boolean
  ) extends ResponseFormat {

    /** Sets the JSON schema. */
    def withSchema(schema: ujson.Value): JsonSchema = copy(schema = schema)

    /** Sets the schema name. */
    def withName(name: String): JsonSchema = copy(name = name)

    /** Sets whether the model must follow the schema exactly. */
    def withStrict(strict: Boolean): JsonSchema = copy(strict = strict)
  }

  object JsonSchema {

    /** Creates a [[JsonSchema]] format; `name` defaults to `"response"` and `strict` to `true`. */
    def apply(schema: ujson.Value, name: String = "response", strict: Boolean = true): JsonSchema =
      new JsonSchema(schema, name, strict)
  }
}
