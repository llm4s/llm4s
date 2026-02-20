package org.llm4s.toolapi

/**
 * Schema builder — fluent API for creating JSON Schema [[SchemaDefinition]] values.
 *
 * @example
 * {{{import org.llm4s.toolapi.Schema
 *
 * val schema = Schema.`object`[Map[String, Any]]("Query parameters")
 *   .withProperty(Schema.property("q",    Schema.string("Search query")))
 *   .withProperty(Schema.property("limit", Schema.integer("Max results"), required = false))
 * }}}
 */
object Schema {

  // ---- Primitive schemas -------------------------------------------------

  /**
   * Create a JSON string schema.
   *
   * @param description Human-readable description shown to the LLM
   */
  def string(description: String): StringSchema = StringSchema(description)

  /**
   * Create a JSON number (floating-point) schema.
   *
   * @param description Human-readable description shown to the LLM
   */
  def number(description: String): NumberSchema = NumberSchema(description)

  /**
   * Create a JSON integer schema.
   *
   * @param description Human-readable description shown to the LLM
   */
  def integer(description: String): IntegerSchema = IntegerSchema(description)

  /**
   * Create a JSON boolean schema.
   *
   * @param description Human-readable description shown to the LLM
   */
  def boolean(description: String): BooleanSchema = BooleanSchema(description)

  // ---- Composite schemas -------------------------------------------------

  /**
   * Create a JSON array schema whose items conform to `itemSchema`.
   *
   * @param description Human-readable description shown to the LLM
   * @param itemSchema  Schema applied to every element of the array
   */
  def array[A](description: String, itemSchema: SchemaDefinition[A]): ArraySchema[A] =
    ArraySchema(description, itemSchema)

  /**
   * Create a JSON object schema with no initial properties.
   * Use [[ObjectSchema.withProperty]] to add fields.
   *
   * @param description Human-readable description shown to the LLM
   */
  def `object`[T](description: String): ObjectSchema[T] =
    ObjectSchema[T](description, Seq.empty)

  /**
   * Wrap an existing schema to allow `null` as a valid value.
   *
   * @param schema The underlying non-nullable schema
   */
  def nullable[T](schema: SchemaDefinition[T]): NullableSchema[T] =
    NullableSchema(schema)

  // ---- Property helpers --------------------------------------------------

  /**
   * Create a [[PropertyDefinition]] for use with [[ObjectSchema.withProperty]].
   *
   * @param name     Property key in the JSON object
   * @param schema   Schema for the property value
   * @param required Whether the property is required (default: `true`)
   */
  def property[T](name: String, schema: SchemaDefinition[T], required: Boolean = true): PropertyDefinition[T] =
    PropertyDefinition(name, schema, required)
}
