package org.llm4s.schema.iron

import io.github.iltotore.iron.:|
import org.llm4s.schema.SchemaOf
import org.llm4s.toolapi.{ ArraySchema, IntegerSchema, NumberSchema, SchemaDefinition, StringSchema }

/**
 * The JSON Schema bounds a constraint stands for, worked out at compile time by [[IronMacros]]. An integer's bounds
 * are inclusive (`exclusiveMinimum` and `exclusiveMaximum` are only set for `Double` and `Float`); a string's
 * `minLength`/`maxLength` are an array's `minItems`/`maxItems`.
 */
final private[iron] case class Bounds(
  minimum: Option[Double],
  maximum: Option[Double],
  exclusiveMinimum: Option[Double],
  exclusiveMaximum: Option[Double],
  minLength: Option[Int],
  maxLength: Option[Int]
)

private[iron] object IronSchemas {

  /** `base`'s schema, with `bounds` written into it, under the same name and description. */
  def refined[A, C](base: SchemaOf[A], bounds: Bounds): SchemaOf[A :| C] = new SchemaOf[A :| C] {
    def typeName: String                         = base.typeName
    override def typeDescription: Option[String] = base.typeDescription
    override def isOptional: Boolean             = base.isOptional
    // `SchemaDefinition`'s type parameter is not used when the schema is written: see `SchemaOf.leaf`
    def schema(d: String): SchemaDefinition[A :| C] =
      bounded(base.schema(d), bounds, base.typeName).asInstanceOf[SchemaDefinition[A :| C]]
    override def jsonSchema(d: String, strict: Boolean): ujson.Value =
      bounded(base.jsonSchema(d, strict), bounds)
  }

  /**
   * `schema` with `bounds` set. The macro only accepts a constraint on a type whose `SchemaOf` builds one of the
   * schemas below; a hand-written `SchemaOf` of that type that builds another is refused rather than silently left
   * without its bounds.
   */
  def bounded(schema: SchemaDefinition[?], bounds: Bounds, typeName: String): SchemaDefinition[?] = schema match {
    case s: IntegerSchema =>
      s.withMinimum(bounds.minimum.map(_.toInt)).withMaximum(bounds.maximum.map(_.toInt))
    case s: NumberSchema =>
      s.withMinimum(bounds.minimum)
        .withMaximum(bounds.maximum)
        .withExclusiveMinimum(bounds.exclusiveMinimum)
        .withExclusiveMaximum(bounds.exclusiveMaximum)
    case s: StringSchema   => s.withMinLength(bounds.minLength).withMaxLength(bounds.maxLength)
    case s: ArraySchema[?] => s.withMinItems(bounds.minLength).withMaxItems(bounds.maxLength)
    case other =>
      throw new IllegalStateException(
        s"llm4s-schema-iron cannot write the bounds of a refined $typeName into ${other.getClass.getSimpleName}: " +
          "its SchemaOf does not describe it as a number, a string or an array"
      )
  }

  /**
   * As [[bounded]], on the JSON Schema: the keys are added in the order core's schemas write them, so a type whose
   * schema core can express gets the same document either way. An array of a sum type, which core cannot express,
   * gets its `minItems` and `maxItems` here.
   */
  def bounded(json: ujson.Value, bounds: Bounds): ujson.Value = {
    val out                                           = ujson.Obj.from(json.obj)
    def set(key: String, value: Option[Double]): Unit = value.foreach(v => out(key) = ujson.Num(v))
    json.obj.get("type") match {
      case Some(ujson.Str("integer" | "number")) =>
        set("minimum", bounds.minimum)
        set("maximum", bounds.maximum)
        set("exclusiveMinimum", bounds.exclusiveMinimum)
        set("exclusiveMaximum", bounds.exclusiveMaximum)
      case Some(ujson.Str("string")) =>
        set("minLength", bounds.minLength.map(_.toDouble))
        set("maxLength", bounds.maxLength.map(_.toDouble))
      case Some(ujson.Str("array")) =>
        set("minItems", bounds.minLength.map(_.toDouble))
        set("maxItems", bounds.maxLength.map(_.toDouble))
      case other =>
        throw new IllegalStateException(
          s"llm4s-schema-iron cannot write the bounds of a refined type into a schema of type ${other.getOrElse("(none)")}"
        )
    }
    out
  }
}
