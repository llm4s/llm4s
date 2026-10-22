package org.llm4s.javaapi

import org.llm4s.toolapi.{ ObjectSchema, Schema, SchemaDefinition }

import java.lang.reflect.{ ParameterizedType, RecordComponent, Type }
import java.util.Optional
import scala.util.Try

/**
 * The JSON Schema of a Java record, derived by reflection, and the record read back from a model's JSON answer (#1486):
 * what `JLlmClient.complete(prompt, SomeRecord.class)` sends and returns.
 *
 * A component may be a `String`; an `int`, `long`, `short` or `byte` (boxed or not); a `double` or `float`; a `boolean`;
 * an enum (its constant names); another record; a `java.util.List` of any of these; or an `Optional` of any of these,
 * which is the one way to let the model answer `null` (or leave the field out). Anything else - a `Map`, an array, a
 * raw `List`, an interface, a record that contains itself - is refused before any call, with a message naming the
 * component. Every property is required, as `completeStructured` sends schemas strictly.
 *
 * Reading is strict too: a missing or `null` field that is not an `Optional`, a value of the wrong JSON type, a
 * fraction or an out-of-range number for an integer, and an enum name that is not a constant each fail with a message
 * naming the field's path (`items[2].sku`). Extra fields are ignored. Numbers pass through a `double`, so a `long`
 * above 2^53 is rounded; ask for large identifiers as `String`s.
 */
private[javaapi] object JavaRecords {

  /** The object schema of record class `cls`, or a message naming the component that cannot be described. */
  def schema(cls: Class[?]): Either[String, ObjectSchema[ujson.Value]] =
    if (!cls.isRecord) Left(s"${cls.getName} is not a record")
    else recordSchema(cls, cls.getSimpleName, Set.empty)

  /** `json` read as an instance of record class `cls`, or a message naming the field that does not fit. */
  def read[T](cls: Class[T], json: ujson.Value): Either[String, T] =
    readValue(cls, json, "").map(cls.cast)

  private def recordSchema(
    cls: Class[?],
    path: String,
    seen: Set[Class[?]]
  ): Either[String, ObjectSchema[ujson.Value]] =
    if (seen.contains(cls))
      Left(s"$path: record ${cls.getName} contains itself, which a JSON Schema here cannot describe")
    else
      cls.getRecordComponents.toList.foldLeft[Either[String, ObjectSchema[ujson.Value]]](
        Right(Schema.`object`[ujson.Value](cls.getSimpleName))
      ) { (acc, component) =>
        for {
          obj    <- acc
          schema <- typeSchema(component.getGenericType, at(path, component.getName), seen + cls)
        } yield obj.withProperty(Schema.property(component.getName, schema))
      }

  private def typeSchema(t: Type, path: String, seen: Set[Class[?]]): Either[String, SchemaDefinition[?]] =
    t match {
      case c: Class[?] if c == classOf[String]   => Right(Schema.string(path))
      case c: Class[?] if isInteger(c)           => Right(Schema.integer(path))
      case c: Class[?] if isNumber(c)            => Right(Schema.number(path))
      case c: Class[?] if isBoolean(c)           => Right(Schema.boolean(path))
      case c: Class[?] if c.isEnum               => Right(Schema.string(path).withEnum(constants(c).map(_.name)))
      case c: Class[?] if c.isRecord             => recordSchema(c, path, seen)
      case p: ParameterizedType if isList(p)     => typeSchema(arg(p), s"$path[]", seen).map(Schema.array(path, _))
      case p: ParameterizedType if isOptional(p) => typeSchema(arg(p), path, seen).map(Schema.nullable(_))
      case other                                 => Left(s"$path: ${other.getTypeName} is not supported")
    }

  private def readValue(t: Type, json: ujson.Value, path: String): Either[String, Any] =
    t match {
      case p: ParameterizedType if isOptional(p) =>
        if (json == ujson.Null) Right(Optional.empty()) else readValue(arg(p), json, path).map(Optional.of(_))
      case _ if json == ujson.Null => Left(s"${label(path)} is null")
      case c: Class[?] if c == classOf[String] =>
        json.strOpt.toRight(mismatch(path, "a string", json))
      case c: Class[?] if isInteger(c) => readInteger(c, json, path)
      case c: Class[?] if isNumber(c) =>
        json.numOpt
          .toRight(mismatch(path, "a number", json))
          .map(d => if (c == classOf[Float] || c == classOf[java.lang.Float]) d.toFloat else d)
      case c: Class[?] if isBoolean(c) => json.boolOpt.toRight(mismatch(path, "a boolean", json))
      case c: Class[?] if c.isEnum =>
        json.strOpt.toRight(mismatch(path, "a string", json)).flatMap { name =>
          constants(c)
            .find(_.name == name)
            .toRight(s"${label(path)}: '$name' is not one of ${constants(c).map(_.name).mkString(", ")}")
        }
      case c: Class[?] if c.isRecord => readRecord(c, json, path)
      case p: ParameterizedType if isList(p) =>
        json.arrOpt.toRight(mismatch(path, "an array", json)).flatMap { items =>
          items.zipWithIndex
            .foldLeft[Either[String, Vector[Any]]](Right(Vector.empty)) { case (acc, (item, i)) =>
              acc.flatMap(read => readValue(arg(p), item, s"$path[$i]").map(read :+ _))
            }
            .map(values => java.util.List.copyOf(java.util.Arrays.asList(values.map(_.asInstanceOf[AnyRef])*)))
        }
      case other => Left(s"${label(path)}: ${other.getTypeName} is not supported")
    }

  private def readRecord(cls: Class[?], json: ujson.Value, path: String): Either[String, Any] =
    json.objOpt.toRight(mismatch(path, "an object", json)).flatMap { obj =>
      val components = cls.getRecordComponents.toList
      components
        .foldLeft[Either[String, Vector[Any]]](Right(Vector.empty)) { (acc, component) =>
          acc.flatMap { values =>
            val fieldPath = at(path, component.getName)
            val value = obj.get(component.getName) match {
              case Some(v)                     => readValue(component.getGenericType, v, fieldPath)
              case None if optional(component) => Right(Optional.empty())
              case None                        => Left(s"${label(fieldPath)} is missing")
            }
            value.map(values :+ _)
          }
        }
        .flatMap { values =>
          Try {
            val constructor = cls.getDeclaredConstructor(components.map(_.getType)*)
            constructor.setAccessible(true)
            constructor.newInstance(values.map(_.asInstanceOf[AnyRef])*)
          }.toEither.left.map(e => s"${label(path)}: could not build ${cls.getName}: ${rootCause(e).getMessage}")
        }
    }

  private def readInteger(c: Class[?], json: ujson.Value, path: String): Either[String, Any] = {
    val (min, max, box) = Integers(c)
    json.numOpt.toRight(mismatch(path, "an integer", json)).flatMap { d =>
      if (d != Math.rint(d) || d < min || d > max)
        Left(s"${label(path)}: $d is not an integer of type ${c.getSimpleName}")
      else Right(box(d))
    }
  }

  /** Each integer type, primitive and boxed: its range, and how a whole `double` becomes one. */
  private val Integers: Map[Class[?], (Double, Double, Double => Any)] = {
    val int: (Double, Double, Double => Any)   = (Int.MinValue.toDouble, Int.MaxValue.toDouble, _.toInt)
    val long: (Double, Double, Double => Any)  = (Long.MinValue.toDouble, Long.MaxValue.toDouble, _.toLong)
    val short: (Double, Double, Double => Any) = (Short.MinValue.toDouble, Short.MaxValue.toDouble, _.toShort)
    val byte: (Double, Double, Double => Any)  = (Byte.MinValue.toDouble, Byte.MaxValue.toDouble, _.toByte)
    Map(
      classOf[Int]             -> int,
      classOf[Integer]         -> int,
      classOf[Long]            -> long,
      classOf[java.lang.Long]  -> long,
      classOf[Short]           -> short,
      classOf[java.lang.Short] -> short,
      classOf[Byte]            -> byte,
      classOf[java.lang.Byte]  -> byte
    )
  }
  private def isInteger(c: Class[?]): Boolean = Integers.contains(c)
  private val Booleans: Set[Class[?]]         = Set(classOf[Boolean], classOf[java.lang.Boolean])
  private def isNumber(c: Class[?]): Boolean =
    Set[Class[?]](classOf[Double], classOf[java.lang.Double], classOf[Float], classOf[java.lang.Float]).contains(c)
  private def isBoolean(c: Class[?]): Boolean = Booleans.contains(c)

  private def isList(p: ParameterizedType): Boolean     = p.getRawType == classOf[java.util.List[?]]
  private def isOptional(p: ParameterizedType): Boolean = p.getRawType == classOf[Optional[?]]
  private def arg(p: ParameterizedType): Type           = p.getActualTypeArguments.head
  private def optional(component: RecordComponent): Boolean = component.getGenericType match {
    case p: ParameterizedType => isOptional(p)
    case _                    => false
  }

  private def constants(c: Class[?]): List[Enum[?]] = c.getEnumConstants.toList.map(_.asInstanceOf[Enum[?]])

  private def at(path: String, name: String): String = if (path.isEmpty) name else s"$path.$name"
  private def label(path: String): String            = if (path.isEmpty) "the answer" else s"field '$path'"
  private def mismatch(path: String, expected: String, got: ujson.Value): String =
    s"${label(path)}: expected $expected, got ${kind(got)}"
  private def kind(v: ujson.Value): String = v match {
    case _: ujson.Str  => "a string"
    case _: ujson.Num  => "a number"
    case _: ujson.Bool => "a boolean"
    case _: ujson.Arr  => "an array"
    case _             => "an object" // null never gets here: it is read before the type is checked
  }

  @scala.annotation.tailrec
  private def rootCause(e: Throwable): Throwable =
    if (e.getCause == null || e.getCause == e) e else rootCause(e.getCause)
}
