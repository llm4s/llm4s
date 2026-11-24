package org.llm4s.toolapi

import org.llm4s.annotation.Stable

import scala.annotation.tailrec

/**
 * Reads a tool call's arguments by name, with type checking and path navigation.
 *
 * Every getter returns `Either[ToolParameterError, A]`: the error says which parameter is missing, null or of the
 * wrong type, and a handler that returns it has the call refused as [[ToolCallError.InvalidArguments]], which
 * lists each one for the model to correct (see [[ToolHandlerError]]).
 *
 * A path is a property name, or several joined by dots to reach into nested objects (`"user.name"`). A property
 * whose own name contains a dot is found first: `"a.b"` reads the top-level property `a.b` when there is one, and
 * otherwise `b` inside `a`.
 *
 * @param params The JSON parameters to extract from
 * @param call   The tool call being run, when the caller knows it: an agent sets it for every call, with the call's
 *               id and idempotency key ([[ToolCallInfo]]); plain [[ToolRegistry]] tool calling leaves it `None`.
 */
@Stable
final case class SafeParameterExtractor private (params: ujson.Value, call: Option[ToolCallInfo]) {

  /** An extractor over other parameters, for the same call. */
  def withParams(params: ujson.Value): SafeParameterExtractor = copy(params = params)

  /** An extractor for the given call. */
  def withCall(call: ToolCallInfo): SafeParameterExtractor = copy(call = Some(call))

  /** An extractor for the given call, or for no call. */
  def withCall(call: Option[ToolCallInfo]): SafeParameterExtractor = copy(call = call)
  // Helper case class to return both the value and available keys from parent
  private case class NavigationResult(value: Option[ujson.Value], availableKeys: List[String])

  /**
   * Extract a required string parameter from the JSON params.
   *
   * @param path Dot-separated path to the parameter (e.g., `"user.name"`)
   * @return `Right(value)` on success, `Left(ToolParameterError)` if the parameter is absent, null or not a string
   */
  def getString(path: String): Either[ToolParameterError, String] =
    extract(path, _.strOpt, ToolParameterType.String)

  /**
   * Extract a required integer parameter from the JSON params.
   *
   * JSON numbers reach this class as doubles, so an integer is a number with no fractional part that fits in an
   * `Int`: `3`, `3.0`, `1e2` and `-0.0` are accepted; `3.14`, `2147483648`, `1e10` and anything too large are
   * refused as a type mismatch instead of being truncated or wrapped around, so a model that sends one gets an
   * error it can correct.
   *
   * @param path Dot-separated path to the parameter
   * @return `Right(value)` on success, `Left(ToolParameterError)` on failure
   */
  def getInt(path: String): Either[ToolParameterError, Int] =
    extract(path, SafeParameterExtractor.intOpt, ToolParameterType.Integer)

  /**
   * Extract a required double (number) parameter from the JSON params.
   *
   * A whole number above 2^53 (9007199254740992) has already been rounded to the nearest double when the tool call's
   * arguments were parsed, so it cannot be recovered here. Declare large identifiers as strings in the tool's schema.
   *
   * @param path Dot-separated path to the parameter
   * @return `Right(value)` on success, `Left(ToolParameterError)` on failure
   */
  def getDouble(path: String): Either[ToolParameterError, Double] =
    extract(path, _.numOpt, ToolParameterType.Number)

  /**
   * Extract a required boolean parameter from the JSON params.
   *
   * @param path Dot-separated path to the parameter
   * @return `Right(value)` on success, `Left(ToolParameterError)` on failure
   */
  def getBoolean(path: String): Either[ToolParameterError, Boolean] =
    extract(path, _.boolOpt, ToolParameterType.Boolean)

  /**
   * Extract a required JSON array parameter from the JSON params.
   *
   * @param path Dot-separated path to the parameter
   * @return `Right(ujson.Arr)` on success, `Left(ToolParameterError)` on failure
   */
  def getArray(path: String): Either[ToolParameterError, ujson.Arr] =
    extract(path, v => Option(v).collect { case arr: ujson.Arr => arr }, ToolParameterType.Array)

  /**
   * Extract a required JSON object parameter from the JSON params.
   *
   * @param path Dot-separated path to the parameter
   * @return `Right(ujson.Obj)` on success, `Left(ToolParameterError)` on failure
   */
  def getObject(path: String): Either[ToolParameterError, ujson.Obj] =
    extract(path, v => Option(v).collect { case obj: ujson.Obj => obj }, ToolParameterType.Object)

  // Optional parameters

  /**
   * Extract an optional string parameter. Returns `Right(None)` when the parameter is absent.
   *
   * @param path Dot-separated path to the parameter
   * @return `Right(Some(value))` if present, `Right(None)` if absent, `Left` on type mismatch
   */
  def getOptionalString(path: String): Either[ToolParameterError, Option[String]] =
    extractOptional(path, _.strOpt, ToolParameterType.String)

  /**
   * Extract an optional integer parameter. Returns `Right(None)` when the parameter is absent.
   *
   * @param path Dot-separated path to the parameter
   * @return `Right(Some(value))` if present, `Right(None)` if absent, `Left` on type mismatch
   */
  def getOptionalInt(path: String): Either[ToolParameterError, Option[Int]] =
    extractOptional(path, SafeParameterExtractor.intOpt, ToolParameterType.Integer)

  /**
   * Extract an optional double (number) parameter. Returns `Right(None)` when the parameter is absent.
   *
   * @param path Dot-separated path to the parameter
   * @return `Right(Some(value))` if present, `Right(None)` if absent, `Left` on type mismatch
   */
  def getOptionalDouble(path: String): Either[ToolParameterError, Option[Double]] =
    extractOptional(path, _.numOpt, ToolParameterType.Number)

  /**
   * Extract an optional boolean parameter. Returns `Right(None)` when the parameter is absent.
   *
   * @param path Dot-separated path to the parameter
   * @return `Right(Some(value))` if present, `Right(None)` if absent, `Left` on type mismatch
   */
  def getOptionalBoolean(path: String): Either[ToolParameterError, Option[Boolean]] =
    extractOptional(path, _.boolOpt, ToolParameterType.Boolean)

  // A property whose own name has a dot in it is read by that name; otherwise the dots are steps into nested objects
  private def steps(path: String): List[String] =
    params match {
      case obj: ujson.Obj if obj.value.contains(path) => List(path)
      case _ if path.contains('.')                    => path.split('.').toList
      case _                                          => List(path)
    }

  private def extract[T](
    path: String,
    extractor: ujson.Value => Option[T],
    expected: ToolParameterType
  ): Either[ToolParameterError, T] =
    navigateToValue(steps(path), params) match {
      case Left(error) => Left(error)
      case Right(NavigationResult(None, availableKeys)) =>
        Left(ToolParameterError.MissingParameter(path, expected.name, availableKeys))
      case Right(NavigationResult(Some(ujson.Null), _)) =>
        Left(ToolParameterError.NullParameter(path, expected.name))
      case Right(NavigationResult(Some(value), _)) =>
        extractor(value) match {
          case Some(result) => Right(result)
          case None         => Left(ToolParameterError.TypeMismatch(path, expected.name, getValueType(value)))
        }
    }

  // Optional parameter extraction
  private def extractOptional[T](
    path: String,
    extractor: ujson.Value => Option[T],
    expected: ToolParameterType
  ): Either[ToolParameterError, Option[T]] =
    navigateToValue(steps(path), params) match {
      case Left(error)                                  => Left(error)
      case Right(NavigationResult(None, _))             => Right(None) // Optional parameter missing is OK
      case Right(NavigationResult(Some(ujson.Null), _)) => Right(None) // Null for optional is OK
      case Right(NavigationResult(Some(value), _)) =>
        extractor(value) match {
          case Some(result) => Right(Some(result))
          case None         => Left(ToolParameterError.TypeMismatch(path, expected.name, getValueType(value)))
        }
    }

  // Navigate to a value in nested JSON
  private def navigateToValue(
    pathParts: List[String],
    current: ujson.Value
  ): Either[ToolParameterError, NavigationResult] = {

    @tailrec
    def navigate(
      parts: List[String],
      value: ujson.Value,
      traversedPath: List[String],
      parentKeys: List[String]
    ): Either[ToolParameterError, NavigationResult] =
      parts match {
        case Nil => Right(NavigationResult(Some(value), parentKeys))
        case head :: tail =>
          value match {
            case ujson.Null =>
              val parentPath = traversedPath.mkString(".")
              Left(
                ToolParameterError.InvalidNesting(
                  head,
                  if (parentPath.isEmpty) "root" else parentPath,
                  "null"
                )
              )
            case obj: ujson.Obj =>
              val currentKeys = obj.obj.keys.toList.sorted
              obj.obj.get(head) match {
                case Some(nextValue) =>
                  navigate(tail, nextValue, traversedPath :+ head, currentKeys)
                case None =>
                  if (tail.isEmpty) {
                    // This is the final parameter we're looking for
                    // Return the keys from the current object where the parameter is missing
                    Right(NavigationResult(None, currentKeys))
                  } else {
                    // We're trying to navigate deeper but intermediate path is missing
                    val fullPath = (traversedPath :+ head).mkString(".")
                    Left(
                      ToolParameterError.MissingParameter(
                        fullPath,
                        "object",
                        currentKeys
                      )
                    )
                  }
              }
            case other =>
              val parentPath = traversedPath.mkString(".")
              Left(
                ToolParameterError.InvalidNesting(
                  head,
                  if (parentPath.isEmpty) "root" else parentPath,
                  getValueType(other)
                )
              )
          }
      }

    current match {
      case ujson.Null if pathParts.nonEmpty =>
        Left(
          ToolParameterError.InvalidNesting(
            pathParts.head,
            "root",
            "null"
          )
        )
      case _ =>
        // Get the initial keys from the root object
        val rootKeys = current match {
          case obj: ujson.Obj => obj.obj.keys.toList.sorted
          case _              => Nil
        }
        navigate(pathParts, current, Nil, rootKeys)
    }
  }

  private def getValueType(value: ujson.Value): String = value match {
    case _: ujson.Str  => "string"
    case _: ujson.Num  => "number"
    case _: ujson.Bool => "boolean"
    case _: ujson.Arr  => "array"
    case _: ujson.Obj  => "object"
    case ujson.Null    => "null"
    case null          => "unknown"
  }

  /**
   * Checks that every listed parameter is present and of its type, and reports all that are not, rather than the
   * first: useful before any business logic runs.
   *
   * Each type is checked with the same rules as its getter ([[ToolParameterType.Integer]] is a whole number in the
   * `Int` range).
   *
   * @param requirements Pairs of `(path, type)` to validate
   * @return `Right(())` if all parameters are present and have the correct types; otherwise the one error, or a
   *         [[ToolParameterError.MultipleErrors]] holding every one, which a handler can return as it is
   * @example
   * {{{extractor.validateRequired("name" -> ToolParameterType.String, "age" -> ToolParameterType.Integer)}}}
   */
  def validateRequired(requirements: (String, ToolParameterType)*): Either[ToolParameterError, Unit] =
    requirements.flatMap { case (path, expected) =>
      extract(path, expected.read, expected).left.toOption
    }.toList match {
      case Nil           => Right(())
      case single :: Nil => Left(single)
      case several       => Left(ToolParameterError.MultipleErrors(several))
    }
}

object SafeParameterExtractor {

  /** Creates an extractor over `params` for the call `call`, when the caller knows it. */
  def apply(params: ujson.Value, call: Option[ToolCallInfo]): SafeParameterExtractor =
    new SafeParameterExtractor(params, call)

  /** Creates an extractor over `params` with no call; Java calls `SafeParameterExtractor.apply(params)`. */
  def apply(params: ujson.Value): SafeParameterExtractor = new SafeParameterExtractor(params, None)

  /** A whole number that fits in an `Int`; `None` for a fraction, NaN, an infinity or a value out of range. */
  private[toolapi] def intOpt(value: ujson.Value): Option[Int] =
    value.numOpt.filter(d => d >= Int.MinValue && d <= Int.MaxValue && d == Math.rint(d)).map(_.toInt)
}

/**
 * The JSON type a tool parameter is read as: what [[SafeParameterExtractor.validateRequired]] checks, and the
 * `expectedType` a [[ToolParameterError]] names.
 *
 * @param name the JSON Schema type name, as it appears in an error message
 */
@Stable
enum ToolParameterType(val name: Predef.String) {

  /** A JSON string. */
  case String extends ToolParameterType("string")

  /** A JSON number with no fractional part that fits in an `Int`. */
  case Integer extends ToolParameterType("integer")

  /** A JSON number. */
  case Number extends ToolParameterType("number")

  /** `true` or `false`. */
  case Boolean extends ToolParameterType("boolean")

  /** A JSON array. */
  case Array extends ToolParameterType("array")

  /** A JSON object. */
  case Object extends ToolParameterType("object")

  /** `Some(())` when `value` is of this type, by the rule of the getter for it. */
  private[toolapi] def read(value: ujson.Value): Option[Unit] = {
    val ok = this match {
      case ToolParameterType.String  => value.strOpt.isDefined
      case ToolParameterType.Integer => SafeParameterExtractor.intOpt(value).isDefined
      case ToolParameterType.Number  => value.numOpt.isDefined
      case ToolParameterType.Boolean => value.boolOpt.isDefined
      case ToolParameterType.Array   => value.arrOpt.isDefined
      case ToolParameterType.Object  => value.objOpt.isDefined
    }
    if (ok) Some(()) else None
  }
}
