package org.llm4s.trace.model

import org.llm4s.error.ValidationError
import org.llm4s.types.{ Result, TraceId }

import java.time.Instant

import scala.util.Try

object TraceModelJson {

  private def parseError(field: String, reason: String): Left[ValidationError, Nothing] =
    Left(ValidationError(field, reason))

  private def nullableJson[T](opt: Option[T])(f: T => ujson.Value): ujson.Value =
    opt.map(f).getOrElse(ujson.Null)

  implicit class SpanIdJson(val spanId: SpanId) {
    def toJson: ujson.Value = ujson.Str(spanId.value)
  }

  implicit class TraceIdJson(val traceId: TraceId) {
    def toJson: ujson.Value = ujson.Str(traceId.value)
  }

  implicit class SpanValueJson(val sv: SpanValue) extends AnyVal {
    def toJson: ujson.Value = sv match {
      case SpanValue.StringValue(v)  => ujson.Obj("type" -> "String", "value" -> ujson.Str(v))
      case SpanValue.LongValue(v)    => ujson.Obj("type" -> "Long", "value" -> ujson.Str(v.toString))
      case SpanValue.DoubleValue(v)  => ujson.Obj("type" -> "Double", "value" -> ujson.Num(v))
      case SpanValue.BooleanValue(v) => ujson.Obj("type" -> "Boolean", "value" -> ujson.Bool(v))
      case SpanValue.StringListValue(v) =>
        ujson.Obj("type" -> "StringList", "value" -> ujson.Arr(v.map(ujson.Str(_)): _*))
    }
  }

  implicit class SpanStatusJson(val status: SpanStatus) extends AnyVal {
    def toJson: ujson.Value = status match {
      case SpanStatus.Ok         => ujson.Obj("type" -> "Ok")
      case SpanStatus.Error(msg) => ujson.Obj("type" -> "Error", "message" -> msg)
      case SpanStatus.Running    => ujson.Obj("type" -> "Running")
    }
  }

  implicit class SpanKindJson(val kind: SpanKind) extends AnyVal {
    def toJson: ujson.Value = kind match {
      case SpanKind.Internal      => ujson.Str("Internal")
      case SpanKind.LlmCall       => ujson.Str("LlmCall")
      case SpanKind.ToolCall      => ujson.Str("ToolCall")
      case SpanKind.AgentCall     => ujson.Str("AgentCall")
      case SpanKind.NodeExecution => ujson.Str("NodeExecution")
      case SpanKind.Embedding     => ujson.Str("Embedding")
      case SpanKind.Rag           => ujson.Str("Rag")
      case SpanKind.Cache         => ujson.Str("Cache")
    }
  }

  implicit class SpanEventJson(val event: SpanEvent) extends AnyVal {
    def toJson: ujson.Value = ujson.Obj(
      "name"       -> event.name,
      "timestamp"  -> event.timestamp.toString,
      "attributes" -> ujson.Obj.from(event.attributes.map { case (k, v) => k -> v.toJson })
    )
  }

  implicit class SpanJson(val span: Span) extends AnyVal {
    def toJson: ujson.Value = ujson.Obj(
      "spanId"       -> span.spanId.toJson,
      "traceId"      -> span.traceId.toJson,
      "parentSpanId" -> nullableJson(span.parentSpanId)(_.toJson),
      "name"         -> span.name,
      "kind"         -> span.kind.toJson,
      "startTime"    -> span.startTime.toString,
      "endTime"      -> nullableJson(span.endTime)(t => ujson.Str(t.toString)),
      "status"       -> span.status.toJson,
      "attributes"   -> ujson.Obj.from(span.attributes.map { case (k, v) => k -> v.toJson }),
      "events"       -> ujson.Arr(span.events.map(_.toJson): _*)
    )
  }

  implicit class TraceJson(val trace: Trace) extends AnyVal {
    def toJson: ujson.Value = ujson.Obj(
      "traceId"    -> trace.traceId.toJson,
      "rootSpanId" -> nullableJson(trace.rootSpanId)(_.toJson),
      "metadata"   -> ujson.Obj.from(trace.metadata.map { case (k, v) => k -> ujson.Str(v) }),
      "startTime"  -> trace.startTime.toString,
      "endTime"    -> nullableJson(trace.endTime)(t => ujson.Str(t.toString)),
      "status"     -> trace.status.toJson
    )
  }

  def parseSpanId(json: ujson.Value): Result[SpanId] = json match {
    case ujson.Str(v) => Right(SpanId(v))
    case _            => parseError("spanId", s"Expected string, got: ${json.getClass.getSimpleName}")
  }

  def parseTraceId(json: ujson.Value): Result[TraceId] = json match {
    case ujson.Str(v) => Right(TraceId(v))
    case _            => parseError("traceId", s"Expected string, got: ${json.getClass.getSimpleName}")
  }

  def parseSpanValue(json: ujson.Value): Result[SpanValue] =
    json match {
      case ujson.Obj(obj) =>
        obj.get("type") match {
          case Some(ujson.Str("String")) =>
            obj.get("value") match {
              case Some(ujson.Str(v)) => Right(SpanValue.StringValue(v))
              case _                  => parseError("SpanValue.value", s"Invalid or missing 'value' for String: $json")
            }
          case Some(ujson.Str("Long")) =>
            obj.get("value") match {
              case Some(ujson.Str(v)) =>
                Try(v.toLong).toEither.left
                  .map(_ => ValidationError("SpanValue.value", s"Invalid Long string value: $v"))
                  .map(SpanValue.LongValue(_))
              case Some(ujson.Num(v)) => Right(SpanValue.LongValue(v.toLong))
              case _                  => parseError("SpanValue.value", s"Invalid or missing 'value' for Long: $json")
            }
          case Some(ujson.Str("Double")) =>
            obj.get("value") match {
              case Some(ujson.Num(v)) => Right(SpanValue.DoubleValue(v))
              case _                  => parseError("SpanValue.value", s"Invalid or missing 'value' for Double: $json")
            }
          case Some(ujson.Str("Boolean")) =>
            obj.get("value") match {
              case Some(ujson.Bool(v)) => Right(SpanValue.BooleanValue(v))
              case _ => parseError("SpanValue.value", s"Invalid or missing 'value' for Boolean: $json")
            }
          case Some(ujson.Str("StringList")) =>
            obj.get("value") match {
              case Some(ujson.Arr(arr)) =>
                arr
                  .foldLeft[Result[List[String]]](Right(List.empty)) {
                    case (acc, ujson.Str(v)) => acc.map(_ :+ v)
                    case (_, elem) => parseError("SpanValue.value", s"StringList contains non-string element: $elem")
                  }
                  .map(SpanValue.StringListValue(_))
              case _ => parseError("SpanValue.value", s"Invalid or missing 'value' for StringList: $json")
            }
          case Some(ujson.Str(t)) => parseError("SpanValue.type", s"Unknown type: $t")
          case None               => parseError("SpanValue.type", s"Missing 'type' field: $json")
          case _                  => parseError("SpanValue.type", s"Invalid 'type' field: $json")
        }
      case ujson.Str(v)  => Right(SpanValue.StringValue(v))
      case ujson.Num(v)  => Right(SpanValue.DoubleValue(v))
      case ujson.Bool(v) => Right(SpanValue.BooleanValue(v))
      case ujson.Arr(arr) =>
        arr
          .foldLeft[Result[List[String]]](Right(List.empty)) {
            case (acc, ujson.Str(v)) => acc.map(_ :+ v)
            case (_, elem)           => parseError("SpanValue", s"Legacy StringList contains non-string element: $elem")
          }
          .map(SpanValue.StringListValue(_))
      case _ => parseError("SpanValue", s"Cannot parse from ${json.getClass.getSimpleName}")
    }

  def parseSpanStatus(json: ujson.Value): Result[SpanStatus] =
    json match {
      case ujson.Obj(obj) =>
        obj.get("type") match {
          case Some(ujson.Str("Ok")) => Right(SpanStatus.Ok)
          case Some(ujson.Str("Error")) =>
            val message = obj.get("message").collect { case ujson.Str(msg) => msg }.getOrElse("")
            Right(SpanStatus.Error(message))
          case Some(ujson.Str("Running")) => Right(SpanStatus.Running)
          case Some(ujson.Str(t))         => parseError("SpanStatus.type", s"Unknown type: $t")
          case None                       => parseError("SpanStatus.type", s"Missing 'type' field: $json")
          case _                          => parseError("SpanStatus.type", s"Invalid 'type' field: $json")
        }
      case _ => parseError("SpanStatus", s"Expected object, got: ${json.getClass.getSimpleName}")
    }

  def parseSpanKind(json: ujson.Value): Result[SpanKind] = json match {
    case ujson.Str("Internal")      => Right(SpanKind.Internal)
    case ujson.Str("LlmCall")       => Right(SpanKind.LlmCall)
    case ujson.Str("ToolCall")      => Right(SpanKind.ToolCall)
    case ujson.Str("AgentCall")     => Right(SpanKind.AgentCall)
    case ujson.Str("NodeExecution") => Right(SpanKind.NodeExecution)
    case ujson.Str("Embedding")     => Right(SpanKind.Embedding)
    case ujson.Str("Rag")           => Right(SpanKind.Rag)
    case ujson.Str("Cache")         => Right(SpanKind.Cache)
    case ujson.Str(unknown)         => parseError("SpanKind", s"Unknown kind: $unknown")
    case _                          => parseError("SpanKind", s"Expected string, got: ${json.getClass.getSimpleName}")
  }

  def parseSpanEvent(json: ujson.Value): Result[SpanEvent] =
    json match {
      case ujson.Obj(obj) =>
        for {
          name <- obj.get("name") match {
            case Some(ujson.Str(v)) => Right(v)
            case _                  => parseError("SpanEvent.name", s"Missing or invalid: $json")
          }
          timestamp <- obj.get("timestamp") match {
            case Some(ujson.Str(v)) =>
              Try(Instant.parse(v)).toEither.left
                .map(_ => ValidationError("SpanEvent.timestamp", s"Invalid format: $v"))
            case _ => parseError("SpanEvent.timestamp", s"Missing or invalid: $json")
          }
          attributes <- obj.get("attributes") match {
            case Some(ujson.Obj(attrObj)) =>
              attrObj.foldLeft[Result[Map[String, SpanValue]]](Right(Map.empty)) { case (acc, (k, v)) =>
                for {
                  map   <- acc
                  value <- parseSpanValue(v)
                } yield map + (k -> value)
              }
            case _ => parseError("SpanEvent.attributes", s"Missing or invalid: $json")
          }
        } yield SpanEvent(name, timestamp, attributes)
      case _ => parseError("SpanEvent", s"Expected object, got: ${json.getClass.getSimpleName}")
    }

  def parseSpan(json: ujson.Value): Result[Span] =
    json match {
      case ujson.Obj(obj) =>
        for {
          spanId <- obj
            .get("spanId")
            .toRight(ValidationError("Span.spanId", "Missing field"))
            .flatMap(parseSpanId)
          traceId <- obj
            .get("traceId")
            .toRight(ValidationError("Span.traceId", "Missing field"))
            .flatMap(parseTraceId)
          parentSpanId <- obj.get("parentSpanId") match {
            case Some(ujson.Null) | None => Right(None)
            case Some(v)                 => parseSpanId(v).map(Some(_))
          }
          name <- obj.get("name") match {
            case Some(ujson.Str(v)) => Right(v)
            case _                  => parseError("Span.name", s"Missing or invalid: $json")
          }
          kind <- obj
            .get("kind")
            .toRight(ValidationError("Span.kind", "Missing field"))
            .flatMap(parseSpanKind)
          startTime <- obj.get("startTime") match {
            case Some(ujson.Str(v)) =>
              Try(Instant.parse(v)).toEither.left
                .map(_ => ValidationError("Span.startTime", s"Invalid format: $v"))
            case _ => parseError("Span.startTime", s"Missing field")
          }
          endTime <- obj.get("endTime") match {
            case Some(ujson.Null) | None => Right(None)
            case Some(ujson.Str(v)) =>
              Try(Instant.parse(v)).toEither.left
                .map(_ => ValidationError("Span.endTime", s"Invalid format: $v"))
                .map(Some(_))
            case Some(other) => parseError("Span.endTime", s"Invalid type: ${other.getClass.getSimpleName}")
          }
          status <- obj
            .get("status")
            .toRight(ValidationError("Span.status", "Missing field"))
            .flatMap(parseSpanStatus)
          attributes <- obj.get("attributes") match {
            case Some(ujson.Obj(attrObj)) =>
              attrObj.foldLeft[Result[Map[String, SpanValue]]](Right(Map.empty)) { case (acc, (k, v)) =>
                for {
                  map   <- acc
                  value <- parseSpanValue(v)
                } yield map + (k -> value)
              }
            case _ => parseError("Span.attributes", s"Missing or invalid: $json")
          }
          events <- obj.get("events") match {
            case Some(ujson.Arr(eventArr)) =>
              eventArr.foldLeft[Result[List[SpanEvent]]](Right(List.empty)) { case (acc, eventJson) =>
                for {
                  list  <- acc
                  event <- parseSpanEvent(eventJson)
                } yield list :+ event
              }
            case _ => parseError("Span.events", s"Missing or invalid: $json")
          }
        } yield Span(spanId, traceId, parentSpanId, name, kind, startTime, endTime, status, attributes, events)
      case _ => parseError("Span", s"Expected object, got: ${json.getClass.getSimpleName}")
    }

  def parseTrace(json: ujson.Value): Result[Trace] =
    json match {
      case ujson.Obj(obj) =>
        for {
          traceId <- obj
            .get("traceId")
            .toRight(ValidationError("Trace.traceId", "Missing field"))
            .flatMap(parseTraceId)
          rootSpanId <- obj.get("rootSpanId") match {
            case Some(ujson.Null) | None => Right(None)
            case Some(v)                 => parseSpanId(v).map(Some(_))
          }
          metadata <- obj.get("metadata") match {
            case Some(ujson.Obj(metaObj)) =>
              metaObj.foldLeft[Result[Map[String, String]]](Right(Map.empty)) {
                case (acc, (k, ujson.Str(v))) => acc.map(_ + (k -> v))
                case (_, (k, other)) =>
                  parseError(
                    "Trace.metadata",
                    s"Value for key '$k' must be string, got: ${other.getClass.getSimpleName}"
                  )
              }
            case _ => parseError("Trace.metadata", s"Missing or invalid: $json")
          }
          startTime <- obj.get("startTime") match {
            case Some(ujson.Str(v)) =>
              Try(Instant.parse(v)).toEither.left
                .map(_ => ValidationError("Trace.startTime", s"Invalid format: $v"))
            case _ => parseError("Trace.startTime", s"Missing field")
          }
          endTime <- obj.get("endTime") match {
            case Some(ujson.Null) | None => Right(None)
            case Some(ujson.Str(v)) =>
              Try(Instant.parse(v)).toEither.left
                .map(_ => ValidationError("Trace.endTime", s"Invalid format: $v"))
                .map(Some(_))
            case Some(other) => parseError("Trace.endTime", s"Invalid type: ${other.getClass.getSimpleName}")
          }
          status <- obj
            .get("status")
            .toRight(ValidationError("Trace.status", "Missing field"))
            .flatMap(parseSpanStatus)
        } yield Trace(traceId, rootSpanId, metadata, startTime, endTime, status)
      case _ => parseError("Trace", s"Expected object, got: ${json.getClass.getSimpleName}")
    }
}
