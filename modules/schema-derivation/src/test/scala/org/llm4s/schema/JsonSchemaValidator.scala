package org.llm4s.schema

/**
 * A validator for the part of JSON Schema that `ObjectSchema` and its siblings emit: `type` (one name or an array
 * of names), `enum`, `properties`, `required`, `additionalProperties: false`, `items`, and the `anyOf` a sum type is
 * derived as. It exists so a spec can
 * ask whether a document the model might return satisfies the derived schema, independently of the uPickle reader
 * the document is then read with.
 */
object JsonSchemaValidator {

  /** The problems of `value` against `schema`, empty if it conforms. Each names the path it is at. */
  def validate(schema: ujson.Value, value: ujson.Value, path: String = "$"): List[String] =
    schema.obj.get("anyOf") match {
      case Some(alternatives) =>
        val each = alternatives.arr.toList.map(validate(_, value, path))
        if (each.exists(_.isEmpty)) Nil
        else
          List(
            s"$path: ${render(value)} matches none of the alternatives: ${each.map(_.mkString("; ")).mkString(" | ")}"
          )
      case None => validateOne(schema, value, path)
    }

  private def validateOne(schema: ujson.Value, value: ujson.Value, path: String): List[String] = {
    val declared = schema.obj.get("type").map {
      case ujson.Str(name) => List(name)
      case names           => names.arr.toList.map(_.str)
    }
    val typeProblems = declared.toList.flatMap { names =>
      if (names.exists(matches(_, value))) Nil
      else List(s"$path: ${render(value)} is none of the types ${names.mkString(", ")}")
    }
    val enumProblems = schema.obj.get("enum").toList.flatMap { allowed =>
      if (allowed.arr.contains(value)) Nil else List(s"$path: ${render(value)} is not one of ${render(allowed)}")
    }
    typeProblems ++ enumProblems ++ (value match {
      case obj: ujson.Obj => objectProblems(schema, obj, path)
      case arr: ujson.Arr => arrayProblems(schema, arr, path)
      case _              => Nil
    })
  }

  private def matches(name: String, value: ujson.Value): Boolean = (name, value) match {
    case ("null", ujson.Null)       => true
    case ("string", _: ujson.Str)   => true
    case ("boolean", _: ujson.Bool) => true
    case ("number", _: ujson.Num)   => true
    case ("integer", n: ujson.Num)  => n.value.isWhole
    case ("object", _: ujson.Obj)   => true
    case ("array", _: ujson.Arr)    => true
    case _                          => false
  }

  private def objectProblems(schema: ujson.Value, obj: ujson.Obj, path: String): List[String] = {
    val properties = schema.obj
      .get("properties")
      .map(_.obj)
      .getOrElse(scala.collection.mutable.LinkedHashMap.empty[String, ujson.Value])
    val required = schema.obj.get("required").map(_.arr.toList.map(_.str)).getOrElse(Nil)
    val missing = required.filterNot(obj.value.contains).map(name => s"$path: the required property '$name' is missing")
    val extra =
      if (schema.obj.get("additionalProperties").contains(ujson.False))
        obj.value.keys.filterNot(properties.contains).toList.map(name => s"$path: the property '$name' is not allowed")
      else Nil
    val nested = obj.value.toList.flatMap { case (name, v) =>
      properties.get(name).toList.flatMap(validate(_, v, s"$path.$name"))
    }
    missing ++ extra ++ nested
  }

  private def arrayProblems(schema: ujson.Value, arr: ujson.Arr, path: String): List[String] =
    schema.obj
      .get("items")
      .toList
      .flatMap(items =>
        arr.value.toList.zipWithIndex.flatMap { case (v, i) =>
          validate(items, v, s"$path[$i]")
        }
      )

  private def render(value: ujson.Value): String = ujson.write(value)
}
