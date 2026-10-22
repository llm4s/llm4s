package org.llm4s.javaapi

import org.llm4s.error.ValidationError
import org.llm4s.toolapi.{ ObjectSchema, Schema, SchemaDefinition, ToolBuilder, ToolCallInfo }
import org.llm4s.types.Result

import java.lang.reflect.{ InvocationTargetException, Method, Modifier }
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** How a [[JTool]] is made: its schema, the reading of the model's arguments, the handler's result (#1484). */
private[javaapi] object JTools {

  final case class ParamSpec(name: String, tpe: Class[?], description: String, required: Boolean)

  final case class Spec(name: String, description: String, params: Vector[ParamSpec], handler: Option[JToolHandler]) {
    def withParam(p: ParamSpec): Spec      = copy(params = params :+ p)
    def withHandler(h: JToolHandler): Spec = copy(handler = Option(h))
  }

  def build(spec: Spec): Result[JTool] =
    for {
      name    <- Option(spec.name).filter(_.trim.nonEmpty).toRight(ValidationError("name", "a tool needs a name"))
      handler <- spec.handler.toRight(ValidationError("handler", s"tool '$name' has no handler"))
      _ <- spec.params
        .groupBy(_.name)
        .collectFirst { case (n, ps) if ps.size > 1 => n }
        .toLeft(())
        .left
        .map(n => ValidationError("param", s"tool '$name' declares parameter '$n' twice"))
      schema <- schemaOf(name, spec)
      function <- ToolBuilder[Map[String, Any], ujson.Value](name, Option(spec.description).getOrElse(""), schema)
        .withHandler(extractor => call(name, spec.params, handler, extractor.params, extractor.call))
        .buildSafe()
    } yield new JTool(FacadeToken.INSTANCE, function)

  private def schemaOf(tool: String, spec: Spec): Result[ObjectSchema[Map[String, Any]]] =
    spec.params.foldLeft[Result[ObjectSchema[Map[String, Any]]]](
      Right(Schema.`object`[Map[String, Any]](Option(spec.description).getOrElse("")))
    ) { (acc, p) =>
      for {
        obj <- acc
        name <- Option(p.name)
          .filter(_.trim.nonEmpty)
          .toRight(ValidationError("param", s"tool '$tool' has a parameter with no name"))
        schema <- typeSchema(p.tpe, Option(p.description).getOrElse("")).toRight(
          ValidationError("param", s"tool '$tool', parameter '$name': ${typeName(p.tpe)} is not supported")
        )
        _ <- Either.cond(
          p.required || !p.tpe.isPrimitive,
          (),
          ValidationError(
            "param",
            s"tool '$tool', parameter '$name': an optional parameter cannot be a primitive ${p.tpe}"
          )
        )
      } yield obj.withProperty(Schema.property(name, schema, required = p.required))
    }

  private def typeName(c: Class[?]): String = Option(c).fold("null")(_.getName)

  private def typeSchema(c: Class[?], description: String): Option[SchemaDefinition[?]] =
    Option(c).flatMap { c =>
      if (c == classOf[String]) Some(Schema.string(description))
      else if (Integers.contains(c)) Some(Schema.integer(description))
      else if (Numbers.contains(c)) Some(Schema.number(description))
      else if (Booleans.contains(c)) Some(Schema.boolean(description))
      else if (c.isEnum) Some(Schema.string(description).withEnum(constants(c).map(_.name)))
      else None
    }

  /** The model's arguments read into the declared Java types, then the handler; every failure a tool error. */
  private def call(
    tool: String,
    params: Vector[ParamSpec],
    handler: JToolHandler,
    json: ujson.Value,
    info: Option[ToolCallInfo]
  ): Either[String, ujson.Value] =
    for {
      obj    <- json.objOpt.toRight(s"tool '$tool' expects an object of arguments")
      values <- read(params, obj)
      result <- Try(handler.call(new JToolArgs(FacadeToken.INSTANCE, values, info))).toEither.left.map(described)
    } yield toJson(result)

  private def read(
    params: Vector[ParamSpec],
    obj: collection.Map[String, ujson.Value]
  ): Either[String, java.util.Map[String, AnyRef]] =
    params
      .foldLeft[Either[String, Vector[(String, AnyRef)]]](Right(Vector.empty)) { (acc, p) =>
        acc.flatMap { read =>
          obj.get(p.name).filterNot(_ == ujson.Null) match {
            case Some(v)            => value(p, v).map(x => read :+ (p.name -> x))
            case None if p.required => Left(s"missing required parameter '${p.name}'")
            case None               => Right(read)
          }
        }
      }
      .map(pairs => java.util.Map.copyOf(pairs.toMap.asJava))

  private def value(p: ParamSpec, v: ujson.Value): Either[String, AnyRef] = {
    val c                      = p.tpe
    def expected(what: String) = s"parameter '${p.name}': expected $what"
    if (c == classOf[String]) v.strOpt.toRight(expected("a string"))
    else if (Integers.contains(c))
      v.numOpt
        .filter(d => d == Math.rint(d))
        .toRight(expected("an integer"))
        .flatMap(d => Integers(c)(d).toRight(expected(s"an integer that fits a ${c.getSimpleName}")))
    else if (Numbers.contains(c))
      v.numOpt
        .toRight(expected("a number"))
        .map(d =>
          if (c == classOf[Float] || c == classOf[java.lang.Float]) java.lang.Float.valueOf(d.toFloat)
          else java.lang.Double.valueOf(d)
        )
    else if (Booleans.contains(c)) v.boolOpt.toRight(expected("a boolean")).map(java.lang.Boolean.valueOf)
    else
      v.strOpt
        .toRight(expected("a string"))
        .flatMap(n =>
          constants(c)
            .find(_.name == n)
            .toRight(s"parameter '${p.name}': '$n' is not one of ${constants(c).map(_.name).mkString(", ")}")
        )
  }

  /** Each integer type, primitive and boxed: a whole double as that type, or `None` out of its range. */
  private val Integers: Map[Class[?], Double => Option[AnyRef]] = {
    def in(min: Double, max: Double)(box: Double => AnyRef): Double => Option[AnyRef] =
      d => Option.when(d >= min && d <= max)(box(d))
    val int   = in(Int.MinValue, Int.MaxValue)(d => Integer.valueOf(d.toInt))
    val long  = in(Long.MinValue.toDouble, Long.MaxValue.toDouble)(d => java.lang.Long.valueOf(d.toLong))
    val short = in(Short.MinValue, Short.MaxValue)(d => java.lang.Short.valueOf(d.toShort))
    val byte  = in(Byte.MinValue, Byte.MaxValue)(d => java.lang.Byte.valueOf(d.toByte))
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
  private val Numbers: Set[Class[?]] =
    Set(classOf[Double], classOf[java.lang.Double], classOf[Float], classOf[java.lang.Float])
  private val Booleans: Set[Class[?]] = Set(classOf[Boolean], classOf[java.lang.Boolean])

  private def constants(c: Class[?]): List[Enum[?]] = c.getEnumConstants.toList.map(_.asInstanceOf[Enum[?]])

  /** What a handler's exception tells the model: its class and message, the method's own for an annotated tool. */
  private def described(e: Throwable): String = {
    val cause = e match {
      case i: InvocationTargetException if i.getCause != null => i.getCause
      case other                                              => other
    }
    s"${cause.getClass.getSimpleName}: ${cause.getMessage}"
  }

  /** A handler's result as JSON. */
  private[javaapi] def toJson(result: Any): ujson.Value = result match {
    case null                       => ujson.Null
    case s: String                  => ujson.Str(s)
    case b: java.lang.Boolean       => ujson.Bool(b)
    case n: java.lang.Number        => ujson.Num(n.doubleValue)
    case e: Enum[?]                 => ujson.Str(e.name)
    case m: java.util.Map[?, ?]     => ujson.Obj.from(m.asScala.map { case (k, v) => String.valueOf(k) -> toJson(v) })
    case c: java.util.Collection[?] => ujson.Arr.from(c.asScala.map(toJson))
    case other                      => ujson.Str(other.toString)
  }

  def fromAnnotated(target: Object): LlmResult[java.util.List[JTool]] =
    FacadeInternals.result(for {
      t <- Option(target).toRight(ValidationError.required("target"))
      methods <- Some(t.getClass.getMethods.toList.filter(_.isAnnotationPresent(classOf[Tool])).sortBy(_.getName))
        .filter(_.nonEmpty)
        .toRight(ValidationError("target", s"${t.getClass.getName} has no public @Tool method"))
      tools <- methods.foldLeft[Result[Vector[JTool]]](Right(Vector.empty))((acc, m) =>
        acc.flatMap(ts => annotated(t, m).map(ts :+ _))
      )
    } yield java.util.List.copyOf(tools.asJava))

  /**
   * A tool for one annotated method. A parameter of type [[JToolArgs]] is not shown to the model: it receives the call's
   * arguments, id and idempotency key (#1740). Every other parameter is one the model passes.
   */
  private def annotated(target: Object, method: Method): Result[JTool] = {
    val tool = method.getAnnotation(classOf[Tool])
    val name = if (tool.name.trim.nonEmpty) tool.name else method.getName
    method.getParameters.toList
      .foldLeft[Result[Vector[Option[ParamSpec]]]](Right(Vector.empty)) { (acc, p) =>
        acc.flatMap { slots =>
          if (p.getType == classOf[JToolArgs]) Right(slots :+ None)
          else {
            val ann       = Option(p.getAnnotation(classOf[Param]))
            val paramName = ann.map(_.name).filter(_.trim.nonEmpty).orElse(Option.when(p.isNamePresent)(p.getName))
            paramName
              .toRight(
                ValidationError(
                  "param",
                  s"tool '$name', parameter ${slots.size + 1} of ${method.getName}: give it @Param(name = ...) or compile with javac -parameters"
                )
              )
              .map(n => slots :+ Some(ParamSpec(n, p.getType, ann.fold("")(_.description), ann.forall(_.required))))
          }
        }
      }
      .flatMap { slots =>
        val receiver = if (Modifier.isStatic(method.getModifiers)) null else target
        val handler: JToolHandler =
          args => method.invoke(receiver, slots.map(_.fold[AnyRef](args)(s => args.asMap().get(s.name))).toArray*)
        build(Spec(name, tool.description, slots.flatten, Some(handler)))
      }
  }
}
