package org.llm4s.toolapi

/**
 * Schema builder - fluent API for creating schema definitions
 */
object Schema {
  // String schemas
  def string(description: String): StringSchema = StringSchema(description)

  // Number schemas
  def number(description: String): NumberSchema   = NumberSchema(description)
  def integer(description: String): IntegerSchema = IntegerSchema(description)

  // Boolean schemas
  def boolean(description: String): BooleanSchema = BooleanSchema(description)

  // Array schemas
  def array[A](description: String, itemSchema: SchemaDefinition[A]): ArraySchema[A] =
    ArraySchema(description, itemSchema)

  // Object schemas
  def `object`[T](description: String): ObjectSchema[T] =
    ObjectSchema[T](description, Seq.empty)

  // Nullable schemas
  def nullable[T](schema: SchemaDefinition[T]): NullableSchema[T] =
    NullableSchema(schema)

  // Properties
  def property[T](name: String, schema: SchemaDefinition[T], required: Boolean = true): PropertyDefinition[T] =
    PropertyDefinition(name, schema, required)
}
