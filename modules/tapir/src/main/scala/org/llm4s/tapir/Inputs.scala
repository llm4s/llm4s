package org.llm4s.tapir

import org.llm4s.toolapi.PropertyDefinition
import sttp.model.Method
import sttp.tapir.{ Codec, CodecFormat, DecodeResult, Endpoint, EndpointIO, EndpointInput, EndpointOutput, RawBodyType }

/**
 * An endpoint's inputs as the steps that build its request from a model's arguments, and its outputs checked for
 * what a tool can return.
 */
private[tapir] object Inputs {

  /** A model argument: its schema property, and how its JSON value (absent as `None`) becomes the raw value sent. */
  final case class Param[L](property: PropertyDefinition[?], encode: Option[ujson.Value] => Either[String, Option[L]]) {
    def name: String = property.name
  }

  sealed trait Step
  object Step {
    final case class Verb(method: Method)                                  extends Step
    final case class Segment(value: String)                                extends Step
    final case class PathArg(param: Param[String])                         extends Step
    final case class QueryArg(query: String, param: Param[List[String]])   extends Step
    final case class HeaderArg(header: String, param: Param[List[String]]) extends Step
    final case class FixedHeader(header: String, value: String)            extends Step
    final case class BodyArg(param: Param[String], contentType: String)    extends Step
  }

  /** The name of the tool parameter that carries the request body. */
  val BodyParameter = "body"

  /** The endpoint's method, when it states one; tapir's client sends `GET` otherwise. */
  def method(endpoint: Endpoint[?, ?, ?, ?, ?]): Option[Method] =
    (basics(endpoint.securityInput, auth = false) ++ basics(endpoint.input, auth = false)).collectFirst {
      case (EndpointInput.FixedMethod(m, _, _), _) => m
    }

  /** The request-building steps of `tool`'s endpoint, in order, or every reason it cannot be a tool. */
  def plan(tool: EndpointTool): Either[List[String], Vector[Step]] = {
    val inputs = basics(tool.endpoint.securityInput, auth = false) ++ basics(tool.endpoint.input, auth = false)
    val steps  = inputs.map { case (input, auth) => step(tool, input, auth) }
    val errors = steps.collect { case Left(e) => e }.toList ++ outputErrors(tool.endpoint)
    if (errors.nonEmpty) Left(errors)
    else {
      val planned = steps.collect { case Right(s) => s }.flatten
      val names   = planned.collect(paramOf).map(_.name)
      val clashes = names.diff(names.distinct).distinct
      val shared  = planned.collect { case Step.HeaderArg(h, _) => h }.filter(h => tool.applicationHeader(h).isDefined)
      val nameErrors = clashes.map(n => s"two inputs are both called '$n'; rename one so each tool parameter is unique")
      val headerErrors =
        shared.map(h => s"header '$h' is both an application header and one the model may set; make it one of them")
      if (nameErrors.isEmpty && headerErrors.isEmpty) Right(planned) else Left((nameErrors ++ headerErrors).toList)
    }
  }

  /** The model parameter of a step, if it has one. */
  val paramOf: PartialFunction[Step, Param[?]] = {
    case Step.PathArg(p)      => p
    case Step.QueryArg(_, p)  => p
    case Step.HeaderArg(_, p) => p
    case Step.BodyArg(p, _)   => p
  }

  /** The basic inputs in order, each with whether it is part of an authentication input. */
  private def basics(input: EndpointInput[?], auth: Boolean): Vector[(EndpointInput.Basic[?], Boolean)] =
    input match {
      case EndpointInput.Pair(left, right, _, _) => basics(left, auth) ++ basics(right, auth)
      case EndpointIO.Pair(left, right, _, _)    => basics(left, auth) ++ basics(right, auth)
      case EndpointInput.MappedPair(pair, _)     => basics(pair, auth)
      case EndpointIO.MappedPair(pair, _)        => basics(pair, auth)
      case a: EndpointInput.Auth[?, ?]           => basics(a.input, auth = true)
      case b: EndpointInput.Basic[?]             => Vector((b, auth))
    }

  private def step(tool: EndpointTool, input: EndpointInput.Basic[?], auth: Boolean): Either[String, Vector[Step]] =
    input match {
      case EndpointInput.FixedMethod(m, _, _) => Right(Vector(Step.Verb(m)))
      case EndpointInput.FixedPath(s, _, _)   => Right(Vector(Step.Segment(s)))
      case EndpointIO.FixedHeader(h, _, _)    => Right(Vector(Step.FixedHeader(h.name, h.value)))
      case EndpointIO.Empty(_, _)             => Right(Vector.empty)
      // Read by a server from its request; a client sends nothing for it.
      case EndpointInput.ExtractFromRequest(_, _) => Right(Vector.empty)
      case EndpointIO.Header(name, codec, _) if auth || !tool.isModelHeader(name) =>
        if (tool.applicationHeader(name).isDefined || codec.schema.isOptional) Right(Vector.empty)
        else
          Left(
            s"header '$name' is required and the application supplies no value; pass it with withHeaders" +
              (if (auth) "" else ", or let the model set it with withModelHeaders")
          )
      case _ if auth =>
        Left(s"authentication by ${kind(input)} is not supported; only header authentication is, set with withHeaders")
      case EndpointInput.PathCapture(name, codec, info) =>
        val param = name.getOrElse(s"param${index(tool, input)}")
        ToolSchemas
          .property(param, param, codec.schema, info.description)
          .map(p => Vector(Step.PathArg(Param(p, value => pathValue(param, codec, value)))))
      case EndpointInput.Query(name, _, codec, info) =>
        ToolSchemas
          .property(name, name, codec.schema, info.description)
          .map(p => Vector(Step.QueryArg(name, Param(p, value => listValue(name, codec, value)))))
      case EndpointIO.Header(name, codec, info) =>
        ToolSchemas
          .property(name, name, codec.schema, info.description)
          .map(p => Vector(Step.HeaderArg(name, Param(p, value => listValue(name, codec, value)))))
      case b: EndpointIO.Body[r, t] =>
        val raw: RawBodyType[r] = b.bodyType
        raw match {
          case RawBodyType.StringBody(_) =>
            val codec: Codec[String, t, CodecFormat] = b.codec
            ToolSchemas
              .property(BodyParameter, BodyParameter, codec.schema, b.info.description)
              .map(p =>
                Vector(Step.BodyArg(Param(p, value => bodyValue(codec, value)), codec.format.mediaType.toString))
              )
          case _ =>
            Left(s"a ${kind(input)} request body is not supported; a tool sends a text or JSON body")
        }
      case other =>
        Left(s"a ${kind(other)} input is not supported")
    }

  private def index(tool: EndpointTool, input: EndpointInput.Basic[?]): Int =
    (basics(tool.endpoint.securityInput, auth = false) ++ basics(tool.endpoint.input, auth = false))
      .collect { case (p: EndpointInput.PathCapture[?], _) => p }
      .indexWhere(_ eq input) + 1

  private def kind(input: EndpointInput.Basic[?]): String = input match {
    case _: EndpointInput.PathsCapture[?]      => "`paths`"
    case _: EndpointInput.QueryParams[?]       => "`queryParams`"
    case _: EndpointInput.Query[?]             => "query"
    case _: EndpointInput.Cookie[?]            => "cookie"
    case _: EndpointIO.Headers[?]              => "`headers`"
    case _: EndpointIO.StreamBodyWrapper[?, ?] => "streaming body"
    case _: EndpointIO.OneOfBody[?, ?]         => "content-negotiated body"
    case b: EndpointIO.Body[?, ?] =>
      b.bodyType match {
        case _: RawBodyType.MultipartBody => "multipart"
        case _                            => "binary"
      }
    case other => other.getClass.getSimpleName.stripSuffix("$")
  }

  /** Every reason the endpoint's outputs cannot be returned to a model: a stream, a web socket, a binary body. */
  private def outputErrors(endpoint: Endpoint[?, ?, ?, ?, ?]): List[String] =
    (outputBasics(endpoint.output) ++ outputBasics(endpoint.errorOutput)).flatMap {
      case _: EndpointIO.StreamBodyWrapper[?, ?]        => List("a streaming response is not supported")
      case _: EndpointOutput.WebSocketBodyWrapper[?, ?] => List("a web socket response is not supported")
      case b: EndpointIO.Body[?, ?] if !isString(b)     => List("a binary response body is not supported")
      case b: EndpointIO.OneOfBody[?, ?] =>
        b.variants.collect {
          case v if v.body.fold(body => !isString(body), _ => true) =>
            s"a response body of type ${v.range} that is not text is not supported"
        }
      case _ => Nil
    }.toList

  private def isString(body: EndpointIO.Body[?, ?]): Boolean = body.bodyType match {
    case RawBodyType.StringBody(_) => true
    case _                         => false
  }

  /** The basic outputs, those of every `oneOf` variant included. */
  def outputBasics(output: EndpointOutput[?]): Vector[EndpointOutput.Basic[?]] = output match {
    case EndpointOutput.Pair(left, right, _, _) => outputBasics(left) ++ outputBasics(right)
    case EndpointIO.Pair(left, right, _, _)     => outputBasics(left) ++ outputBasics(right)
    case EndpointOutput.MappedPair(pair, _)     => outputBasics(pair)
    case EndpointIO.MappedPair(pair, _)         => outputBasics(pair)
    case EndpointOutput.OneOf(variants, _)      => variants.toVector.flatMap(v => outputBasics(v.output))
    case b: EndpointOutput.Basic[?]             => Vector(b)
    case EndpointOutput.Void()                  => Vector.empty
  }

  /**
   * The success output's body, when it is the only one and is not a choice between variants: the tool checks a
   * response against it.
   */
  def successBody(endpoint: Endpoint[?, ?, ?, ?, ?]): Option[EndpointIO.Body[?, ?]] =
    if (hasOneOf(endpoint.output)) None
    else
      outputBasics(endpoint.output).collect { case b: EndpointIO.Body[?, ?] => b } match {
        case Vector(only) if isString(only) => Some(only)
        case _                              => None
      }

  private def hasOneOf(output: EndpointOutput[?]): Boolean = output match {
    case EndpointOutput.Pair(left, right, _, _) => hasOneOf(left) || hasOneOf(right)
    case EndpointIO.Pair(left, right, _, _)     => hasOneOf(left) || hasOneOf(right)
    case EndpointOutput.MappedPair(pair, _)     => hasOneOf(pair)
    case EndpointIO.MappedPair(pair, _)         => hasOneOf(pair)
    case _: EndpointOutput.OneOf[?, ?]          => true
    case _: EndpointIO.OneOfBody[?, ?]          => true
    case _                                      => false
  }

  // ---- arguments to raw values ---------------------------------------------------------------------------------

  private def pathValue[T](
    name: String,
    codec: Codec[String, T, ?],
    value: Option[ujson.Value]
  ): Either[String, Option[String]] =
    value match {
      case None    => Left(s"'$name' is required")
      case Some(v) => scalar(name, v).flatMap(raw => through(name, codec, raw)).map(Some(_))
    }

  private def listValue[T](
    name: String,
    codec: Codec[List[String], T, ?],
    value: Option[ujson.Value]
  ): Either[String, Option[List[String]]] = {
    val raw: Either[String, List[String]] = value match {
      case None => Right(Nil)
      case Some(ujson.Arr(items)) =>
        items.toList.foldRight[Either[String, List[String]]](Right(Nil)) { (item, acc) =>
          for {
            rest <- acc
            s    <- scalar(name, item)
          } yield s :: rest
        }
      case Some(v) => scalar(name, v).map(List(_))
    }
    raw.flatMap(r => through(name, codec, r)).map(Some(_))
  }

  private def bodyValue[T](
    codec: Codec[String, T, CodecFormat],
    value: Option[ujson.Value]
  ): Either[String, Option[String]] =
    value match {
      case None if codec.schema.isOptional => Right(None)
      case None                            => Left(s"'$BodyParameter' is required")
      case Some(v) =>
        val raw = (codec.format, v) match {
          case (_: CodecFormat.Json, json) => ujson.write(json)
          case (_, ujson.Str(s))           => s
          case (_, json)                   => ujson.write(json)
        }
        through(BodyParameter, codec, raw).map(Some(_))
    }

  /** A JSON scalar as the text tapir decodes. */
  private def scalar(name: String, value: ujson.Value): Either[String, String] = value match {
    case ujson.Str(s)              => Right(s)
    case ujson.Num(n) if n.isWhole => Right(BigDecimal(n).toBigInt.toString)
    case ujson.Num(n)              => Right(n.toString)
    case ujson.Bool(b)             => Right(b.toString)
    case _                         => Left(s"'$name' must be a string, a number or a boolean")
  }

  /** Decodes `raw` as the endpoint would, so the endpoint's own types and validators check it, then re-encodes it. */
  private def through[L, T](name: String, codec: Codec[L, T, ?], raw: L): Either[String, L] =
    codec.decode(raw) match {
      case DecodeResult.Value(v)         => Right(codec.encode(v))
      case failure: DecodeResult.Failure => Left(s"'$name' ${Failures.describe(failure)}")
    }
}
