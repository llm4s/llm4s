package org.llm4s.tapir

import org.llm4s.annotation.Experimental
import org.llm4s.error.ValidationError
import org.llm4s.tapir.Inputs.Step
import org.llm4s.toolapi.{ ObjectSchema, ToolFunction, ToolRegistry }
import org.llm4s.types.Result
import sttp.client3.{ asStringAlways, basicRequest, Identity, Response, SttpBackend }
import sttp.model.{ Method, QueryParams, Uri }
import sttp.tapir.{ Codec, CodecFormat, DecodeResult, Endpoint, EndpointIO, RawBodyType }

import scala.util.Try

/**
 * Turns tapir endpoints a service already has into tools a model can call, with each tool's parameter schema derived
 * from the endpoint's own inputs.
 *
 * {{{
 * val tools: Result[ToolRegistry] = EndpointTools.registry(
 *   uri"https://orders.internal",
 *   HttpClientSyncBackend(),
 *   EndpointTools.tool("get_order", getOrder),
 *   EndpointTools.tool("cancel_order", cancelOrder)
 * )
 * }}}
 *
 * A call decodes the model's arguments with the endpoint's own codecs, so its types and validators check them
 * before anything is sent, then calls the endpoint through sttp. Every failure (an argument the endpoint rejects, an
 * error response, a server error, a request that cannot be sent) is a tool error the model reads; nothing throws.
 */
@Experimental
object EndpointTools {

  private val ToolName = "[a-zA-Z0-9_-]{1,64}".r

  /**
   * `endpoint` as the tool `name`. It requires approval unless its method is safe (`GET`, `HEAD` or `OPTIONS`, or
   * none, which tapir sends as `GET`): an agent then asks before a call that may change something.
   */
  def tool[A, I, E, O](name: String, endpoint: Endpoint[A, I, E, O, Any]): EndpointTool =
    EndpointTool(name, endpoint, requiresApproval = !EndpointTool.isSafe(endpoint))

  /** `endpoint` as the tool `name`, requiring approval when `requiresApproval` is `true`. */
  def tool[A, I, E, O](name: String, endpoint: Endpoint[A, I, E, O, Any], requiresApproval: Boolean): EndpointTool =
    EndpointTool(name, endpoint, requiresApproval)

  /**
   * The tools, each calling its endpoint at `baseUri` through `backend`; or a `ValidationError` listing every
   * endpoint that cannot be a tool and why: an input the model cannot supply (a stream, a multipart or binary body,
   * a cookie, a required header nobody supplies), a type its schema cannot state (a map, a recursive type, a sealed
   * hierarchy that is not an enumeration), a binary or streaming output, an invalid or repeated tool name.
   */
  def registry(baseUri: Uri, backend: SttpBackend[Identity, Any], tools: EndpointTool*): Result[ToolRegistry] = {
    val names = tools.map(_.name)
    val repeated =
      names.diff(names.distinct).distinct.map(n => s"$n: the name is used by more than one tool")
    val built  = tools.map(tool => function(baseUri, backend, tool))
    val errors = repeated ++ built.collect { case Left(errors) => errors }.flatten
    if (errors.isEmpty) Right(new ToolRegistry(built.collect { case Right(f) => f }))
    else Left(ValidationError("tapir.endpoints", errors.toList))
  }

  /**
   * The names of the tools that require approval, for an agent's approval middleware:
   * `new ApprovalMiddleware(call => Option.when(gated(call.spec.name))("changes data"))`.
   */
  def requiringApproval(tools: EndpointTool*): Set[String] =
    tools.filter(_.requiresApproval).map(_.name).toSet

  private def function(
    baseUri: Uri,
    backend: SttpBackend[Identity, Any],
    tool: EndpointTool
  ): Either[List[String], ToolFunction[ujson.Value, ujson.Value]] = {
    val nameError =
      if (ToolName.matches(tool.name)) Nil else List(s"the name must match [a-zA-Z0-9_-]{1,64}")
    Inputs.plan(tool) match {
      case Right(steps) if nameError.isEmpty =>
        val schema = ObjectSchema[ujson.Value](description(tool), steps.collect(Inputs.paramOf).map(_.property))
        val call   = new Call(tool, steps, baseUri, backend)
        Right(ToolFunction[ujson.Value, ujson.Value](tool.name, description(tool), schema, args => call(args.params)))
      case Right(_)      => Left(nameError.map(e => s"${tool.name}: $e"))
      case Left(reasons) => Left((nameError ++ reasons).map(e => s"${tool.name}: $e"))
    }
  }

  private def description(tool: EndpointTool): String = {
    val info = tool.endpoint.info
    tool.description
      .orElse(info.description)
      .orElse(info.summary)
      .getOrElse {
        val method = Inputs.method(tool.endpoint).getOrElse(Method.GET)
        s"$method ${tool.endpoint.showPathTemplate(showQueryParam = None)}"
      }
  }

  /** One tool's calls: arguments to a request, the request sent, the response to a result or a tool error. */
  final private class Call(
    tool: EndpointTool,
    steps: Vector[Step],
    baseUri: Uri,
    backend: SttpBackend[Identity, Any]
  ) {
    private val success: Option[Codec[String, ?, CodecFormat]] =
      Inputs.successBody(tool.endpoint).flatMap(stringCodec)

    def apply(args: ujson.Value): Either[String, ujson.Value] =
      Try(run(args)).toEither.left
        .map(e => s"the call failed: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}")
        .flatten

    private def run(args: ujson.Value): Either[String, ujson.Value] =
      for {
        values   <- arguments(args)
        request  <- assemble(values)
        response <- send(request)
        result   <- read(response)
      } yield result

    private def arguments(args: ujson.Value): Either[String, Map[String, ujson.Value]] = args match {
      case ujson.Obj(values) => Right(values.toMap.filter { case (_, v) => v != ujson.Null })
      case ujson.Null        => Right(Map.empty)
      case _                 => Left(s"the arguments must be a JSON object")
    }

    final private case class Parts(
      method: Method = Method.GET,
      segments: Vector[String] = Vector.empty,
      query: Vector[(String, String)] = Vector.empty,
      headers: Vector[(String, String)] = Vector.empty,
      body: Option[(String, String)] = None
    )

    private def assemble(values: Map[String, ujson.Value]): Either[String, Parts] = {
      val start: Either[List[String], Parts] = Right(Parts(headers = tool.headers.toVector))
      val built = steps.foldLeft(start) { (acc, step) =>
        val next: Either[String, Parts => Parts] = step match {
          case Step.Verb(m)           => Right(_.copy(method = m))
          case Step.Segment(s)        => Right(p => p.copy(segments = p.segments :+ s))
          case Step.FixedHeader(h, v) => Right(p => p.copy(headers = p.headers :+ (h -> v)))
          case Step.PathArg(param) =>
            param.encode(values.get(param.name)).map(v => p => p.copy(segments = p.segments ++ v))
          case Step.QueryArg(q, param) =>
            param.encode(values.get(param.name)).map(v => p => p.copy(query = p.query ++ v.toList.flatten.map(q -> _)))
          case Step.HeaderArg(h, param) =>
            param
              .encode(values.get(param.name))
              .map(v => p => p.copy(headers = p.headers ++ v.toList.flatten.map(h -> _)))
          case Step.BodyArg(param, ct) =>
            param.encode(values.get(param.name)).map(v => p => p.copy(body = v.map(_ -> ct)))
        }
        (acc, next) match {
          case (Right(parts), Right(f)) => Right(f(parts))
          case (Right(_), Left(e))      => Left(List(e))
          case (Left(es), Left(e))      => Left(es :+ e)
          case (Left(es), Right(_))     => Left(es)
        }
      }
      built.left.map(es => s"the arguments are invalid: ${es.mkString("; ")}")
    }

    private def send(parts: Parts): Either[String, Response[String]] = {
      val uri = baseUri.addPath(parts.segments).addParams(QueryParams.fromSeq(parts.query))
      val withHeaders = parts.headers.foldLeft(basicRequest.method(parts.method, uri).response(asStringAlways)) {
        case (request, (name, value)) => request.header(name, value)
      }
      val request = parts.body.fold(withHeaders) { case (body, contentType) =>
        withHeaders.contentType(contentType).body(body)
      }
      Try(request.send(backend)).toEither.left.map(e =>
        s"the request to ${parts.method} $uri could not be sent: " +
          Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
      )
    }

    private def read(response: Response[String]): Either[String, ujson.Value] = {
      val code   = response.code
      val body   = response.body
      val quoted = if (body.isEmpty) "" else s": ${Text.truncate(body)}"
      if (code.isSuccess) success match {
        case Some(codec) =>
          codec.decode(body) match {
            case DecodeResult.Value(_) =>
              Right(codec.format match {
                case _: CodecFormat.Json => json(body)
                case _                   => ujson.Str(body)
              })
            case failure: DecodeResult.Failure =>
              Left(
                s"the service answered HTTP ${code.code} with a body that does not match the endpoint's " +
                  s"output: it ${Failures.describe(failure)}"
              )
          }
        case None =>
          Right(if (body.isEmpty) ujson.Obj("status" -> code.code) else json(body))
      }
      else if (code.isServerError) Left(s"the service failed with HTTP ${code.code}$quoted")
      else Left(s"the service rejected the call with HTTP ${code.code}$quoted")
    }

    private def json(body: String): ujson.Value = Try(ujson.read(body)).getOrElse(ujson.Str(body))
  }

  private def stringCodec(body: EndpointIO.Body[?, ?]): Option[Codec[String, ?, CodecFormat]] = body match {
    case b: EndpointIO.Body[r, t] =>
      val raw: RawBodyType[r]             = b.bodyType
      val codec: Codec[r, t, CodecFormat] = b.codec
      raw match {
        case RawBodyType.StringBody(_) => Some(codec)
        case _                         => None
      }
  }
}
