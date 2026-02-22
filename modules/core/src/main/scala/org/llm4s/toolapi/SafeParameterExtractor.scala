package org.llm4s.toolapi

import scala.annotation.tailrec

/**
 * Safe parameter extraction with type checking and path navigation.
 *
 * This extractor provides two modes of operation:
 * 1. Simple mode: Returns Either[String, T] for backward compatibility
 * 2. Enhanced mode: Returns Either[ToolParameterError, T] for structured error reporting
 *
 * @param params The JSON parameters to extract from
 */
case class SafeParameterExtractor(params: ujson.Value) {
  // Helper case class to return both the value and available keys from parent
  private case class NavigationResult(value: Option[ujson.Value], availableKeys: List[String])
  // Simple mode - returns string errors for backward compatibility

  /**
   * Extract a required string parameter from the JSON params.
   *
   * @param path Dot-separated path to the parameter (e.g., `"user.name"`)
   * @return `Right(value)` on success, `Left(errorMessage)` if the parameter is absent or not a string
   */
  def getString(path: String): Either[String, String] =
    extract(path, _.strOpt, "string")

  /**
   * Extract a required integer parameter from the JSON params.
   *
   * @param path Dot-separated path to the parameter
   * @return `Right(value)` on success, `Left(errorMessage)` on failure
   */
  def getInt(path: String): Either[String, Int] =
    extract(path, _.numOpt.map(_.toInt), "integer")

  /**
   * Extract a required double (number) parameter from the JSON params.
   *
   * @param path Dot-separated path to the parameter
   * @return `Right(value)` on success, `Left(errorMessage)` on failure
   */
  def getDouble(path: String): Either[String, Double] =
    extract(path, _.numOpt, "number")

  /**
   * Extract a required boolean parameter from the JSON params.
   *
   * @param path Dot-separated path to the parameter
   * @return `Right(value)` on success, `Left(errorMessage)` on failure
   */
  def getBoolean(path: String): Either[String, Boolean] =
    extract(path, _.boolOpt, "boolean")

  /**
   * Extract a required JSON array parameter from the JSON params.
   *
   * @param path Dot-separated path to the parameter
   * @return `Right(ujson.Arr)` on success, `Left(errorMessage)` on failure
   */
  def getArray(path: String): Either[String, ujson.Arr] =
    extract(path, v => Option(v).collect { case arr: ujson.Arr => arr }, "array")

  /**
   * Extract a required JSON object parameter from the JSON params.
   *
   * @param path Dot-separated path to the parameter
   * @return `Right(ujson.Obj)` on success, `Left(errorMessage)` on failure
   */
  def getObject(path: String): Either[String, ujson.Obj] =
    extract(path, v => Option(v).collect { case obj: ujson.Obj => obj }, "object")

  // Enhanced mode - returns structured errors

  /**
   * Extract a required string parameter with structured error reporting.
   *
   * @param path Dot-separated path to the parameter
   * @return `Right(value)` on success, `Left(ToolParameterError)` on failure
   */
  def getStringEnhanced(path: String): Either[ToolParameterError, String] =
    extractEnhanced(path, _.strOpt, "string")

  /**
   * Extract a required integer parameter with structured error reporting.
   *
   * @param path Dot-separated path to the parameter
   * @return `Right(value)` on success, `Left(ToolParameterError)` on failure
   */
  def getIntEnhanced(path: String): Either[ToolParameterError, Int] =
    extractEnhanced(path, _.numOpt.map(_.toInt), "integer")

  /**
   * Extract a required double (number) parameter with structured error reporting.
   *
   * @param path Dot-separated path to the parameter
   * @return `Right(value)` on success, `Left(ToolParameterError)` on failure
   */
  def getDoubleEnhanced(path: String): Either[ToolParameterError, Double] =
    extractEnhanced(path, _.numOpt, "number")

  /**
   * Extract a required boolean parameter with structured error reporting.
   *
   * @param path Dot-separated path to the parameter
   * @return `Right(value)` on success, `Left(ToolParameterError)` on failure
   */
  def getBooleanEnhanced(path: String): Either[ToolParameterError, Boolean] =
    extractEnhanced(path, _.boolOpt, "boolean")

  /**
   * Extract a required JSON array parameter with structured error reporting.
   *
   * @param path Dot-separated path to the parameter
   * @return `Right(ujson.Arr)` on success, `Left(ToolParameterError)` on failure
   */
  def getArrayEnhanced(path: String): Either[ToolParameterError, ujson.Arr] =
    extractEnhanced(path, v => Option(v).collect { case arr: ujson.Arr => arr }, "array")

  /**
   * Extract a required JSON object parameter with structured error reporting.
   *
   * @param path Dot-separated path to the parameter
   * @return `Right(ujson.Obj)` on success, `Left(ToolParameterError)` on failure
   */
  def getObjectEnhanced(path: String): Either[ToolParameterError, ujson.Obj] =
    extractEnhanced(path, v => Option(v).collect { case obj: ujson.Obj => obj }, "object")

  // Optional parameter methods (enhanced mode only)

  /**
   * Extract an optional string parameter. Returns `Right(None)` when the parameter is absent.
   *
   * @param path Dot-separated path to the parameter
   * @return `Right(Some(value))` if present, `Right(None)` if absent, `Left` on type mismatch
   */
  def getOptionalString(path: String): Either[ToolParameterError, Option[String]] =
    extractOptional(path, _.strOpt, "string")

  /**
   * Extract an optional integer parameter. Returns `Right(None)` when the parameter is absent.
   *
   * @param path Dot-separated path to the parameter
   * @return `Right(Some(value))` if present, `Right(None)` if absent, `Left` on type mismatch
   */
  def getOptionalInt(path: String): Either[ToolParameterError, Option[Int]] =
    extractOptional(path, _.numOpt.map(_.toInt), "integer")

  /**
   * Extract an optional double (number) parameter. Returns `Right(None)` when the parameter is absent.
   *
   * @param path Dot-separated path to the parameter
   * @return `Right(Some(value))` if present, `Right(None)` if absent, `Left` on type mismatch
   */
  def getOptionalDouble(path: String): Either[ToolParameterError, Option[Double]] =
    extractOptional(path, _.numOpt, "number")

  /**
   * Extract an optional boolean parameter. Returns `Right(None)` when the parameter is absent.
   *
   * @param path Dot-separated path to the parameter
   * @return `Right(Some(value))` if present, `Right(None)` if absent, `Left` on type mismatch
   */
  def getOptionalBoolean(path: String): Either[ToolParameterError, Option[Boolean]] =
    extractOptional(path, _.boolOpt, "boolean")

  // Simple mode extractor - returns string errors
  private def extract[T](path: String, extractor: ujson.Value => Option[T], expectedType: String): Either[String, T] =
    extractEnhanced(path, extractor, expectedType).left.map(_.getMessage)

  // Enhanced mode extractor - returns structured errors
  private def extractEnhanced[T](
    path: String,
    extractor: ujson.Value => Option[T],
    expectedType: String
  ): Either[ToolParameterError, T] = {
    val pathParts = if (path.contains('.')) path.split('.').toList else List(path)

    navigateToValue(pathParts, params) match {
      case Left(error) => Left(error)
      case Right(NavigationResult(None, availableKeys)) =>
        Left(ToolParameterError.MissingParameter(path, expectedType, availableKeys))
      case Right(NavigationResult(Some(ujson.Null), _)) =>
        Left(ToolParameterError.NullParameter(path, expectedType))
      case Right(NavigationResult(Some(value), _)) =>
        extractor(value) match {
          case Some(result) => Right(result)
          case None =>
            val actualType = getValueType(value)
            Left(ToolParameterError.TypeMismatch(path, expectedType, actualType))
        }
    }
  }

  // Optional parameter extraction
  private def extractOptional[T](
    path: String,
    extractor: ujson.Value => Option[T],
    expectedType: String
  ): Either[ToolParameterError, Option[T]] = {
    val pathParts = if (path.contains('.')) path.split('.').toList else List(path)

    navigateToValue(pathParts, params) match {
      case Left(error)                                  => Left(error)
      case Right(NavigationResult(None, _))             => Right(None) // Optional parameter missing is OK
      case Right(NavigationResult(Some(ujson.Null), _)) => Right(None) // Null for optional is OK
      case Right(NavigationResult(Some(value), _)) =>
        extractor(value) match {
          case Some(result) => Right(Some(result))
          case None =>
            val actualType = getValueType(value)
            Left(ToolParameterError.TypeMismatch(path, expectedType, actualType))
        }
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
   * Validate all required parameters at once and collect all errors.
   *
   * Useful for upfront validation before any business logic runs.
   *
   * @param requirements Pairs of `(path, expectedType)` to validate
   * @return `Right(())` if all parameters are present and have the correct types,
   *         `Left(errors)` with the full list of validation failures otherwise
   * @example
   * {{{extractor.validateRequired("name" -> "string", "age" -> "integer")}}}
   */
  def validateRequired(
    requirements: (String, String)*
  ): Either[List[ToolParameterError], Unit] = {
    val errors = requirements.flatMap { case (path, expectedType) =>
      extractEnhanced(path, _ => Some(()), expectedType) match {
        case Left(error) => Some(error)
        case Right(_)    => None
      }
    }.toList

    if (errors.isEmpty) Right(())
    else Left(errors)
  }
}

object SafeParameterExtractor {

  /**
   * Create an extractor that uses enhanced error reporting by default.
   * This is a convenience method for code that wants to use structured errors.
   */
  def enhanced(params: ujson.Value): SafeParameterExtractor = SafeParameterExtractor(params)
}
