package org.llm4s.toolapi

import ujson.Value
import scala.annotation.tailrec

/**
 * Safe parameter extraction with type checking and path navigation
 */
case class SafeParameterExtractor(params: ujson.Value) {
  def getString(path: String): Either[String, String] =
    extract(path, _.strOpt, "string")

  def getInt(path: String): Either[String, Int] =
    extract(path, _.numOpt.map(_.toInt), "integer")

  def getDouble(path: String): Either[String, Double] =
    extract(path, _.numOpt, "number")

  def getBoolean(path: String): Either[String, Boolean] =
    extract(path, _.boolOpt, "boolean")

  def getArray(path: String): Either[String, ujson.Arr] =
    extract(path, v => Option(v).collect { case arr: ujson.Arr => arr }, "array")

  def getObject(path: String): Either[String, ujson.Obj] =
    extract(path, v => Option(v).collect { case obj: ujson.Obj => obj }, "object")

  // Generic extractor with type validation - no more boundary usage
  private def extract[T](path: String, extractor: ujson.Value => Option[T], expectedType: String): Either[String, T] =
    try {
      val pathParts = path.split('.')

      // Navigate to the value using a recursive approach instead of boundary
      @tailrec
      def navigatePath(current: ujson.Value, remainingParts: List[String]): Either[String, ujson.Value] =
        if (remainingParts.isEmpty) {
          Right(current)
        } else {
          val part = remainingParts.head
          if (!current.isInstanceOf[ujson.Obj]) {
            Left(
              s"Path '$path': Expected object at '${pathParts.dropRight(remainingParts.size).mkString(".")}' but found ${current.getClass.getSimpleName}"
            )
          } else {
            current.obj.get(part) match {
              case Some(value) => navigatePath(value, remainingParts.tail)
              case None        => Left(s"Path '$path' not found: missing '$part' segment")
            }
          }
        }

      // Get the value at the path
      navigatePath(params, pathParts.toList).flatMap { value =>
        val finalPart = pathParts.last
        extractor(value) match {
          case Some(result) => Right(result)
          case None         => Left(s"Value at '$path' is not of expected type '$expectedType'")
        }
      }
    } catch {
      case e: Exception => Left(s"Error extracting parameter at '$path': ${e.getMessage}")
    }
}
