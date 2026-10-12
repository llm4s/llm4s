package org.llm4s.schema

/**
 * Documents that satisfy a JSON schema of the shape `ObjectSchema` and its siblings emit, for the property-style
 * round trip: every one of them must be read by uPickle. Each alternative a schema allows appears in at least one
 * sample - every `enum` value, `null` where a type admits it, an empty, a one-item and a two-item array, both
 * booleans, negative, zero and large numbers - and an object's samples line its properties' samples up side by
 * side, so the count stays the largest of its properties' rather than their product.
 *
 * A string with no `enum` is sampled as decimal text (`"7"`, `"-12"`): `BigDecimal` and `BigInt` are written as
 * strings, and llm4s-core's schema model has no `pattern` to say that only numeric text reads back.
 */
object SchemaSamples {

  def of(schema: ujson.Value): List[ujson.Value] = {
    val types = schema.obj.get("type").toList.flatMap {
      case ujson.Str(name) => List(name)
      case names           => names.arr.toList.map(_.str)
    }
    val enumValues = schema.obj.get("enum").map(_.arr.toList)
    types.flatMap {
      case "null"    => List(ujson.Null)
      case "string"  => enumValues.getOrElse(List(ujson.Str("7"), ujson.Str("-12")))
      case "integer" => enumValues.getOrElse(List(ujson.Num(0), ujson.Num(-3), ujson.Num(Int.MaxValue)))
      case "number"  => enumValues.getOrElse(List(ujson.Num(1.5), ujson.Num(-2), ujson.Num(0)))
      case "boolean" => List(ujson.True, ujson.False)
      case "array" =>
        val items = of(schema("items"))
        List(ujson.Arr(), ujson.Arr(items.head), ujson.Arr.from(items.take(2)))
      case "object" =>
        val properties = schema.obj.get("properties").map(_.obj.toList).getOrElse(Nil)
        val samples    = properties.map { case (name, property) => name -> of(property) }
        val count      = (1 :: samples.map(_._2.size)).max
        List.tabulate(count) { i =>
          ujson.Obj.from(samples.map { case (name, values) => name -> values(i % values.size) })
        }
      case other => throw new IllegalArgumentException(s"no samples for the type $other")
    }
  }
}
