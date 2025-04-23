package org.llm4s.toolapi

import ujson._
import scala.util.boundary
import scala.util.boundary.break

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

  // Generic extractor with type validation
  private def extract[T](path: String, extractor: ujson.Value => Option[T], expectedType: String): Either[String, T] =
    try {
      boundary {
        val pathParts = path.split('.')
        var current = params

        // Navigate through nested path
        for (part <- pathParts.dropRight(1)) {
          current.obj.get(part) match {
            case Some(value) => current =
              value
            case None =>
              break(Left(s"Path '$path' not found: missing '$part' segment"))
          }
        }

        // Extract the final value
        val finalPart = pathParts.last
        current.obj.get(finalPart) match {
          case Some(value) =>
            extractor(value) match {
              case Some(result) => Right(result)
              case None => Left(s"Value at '$path' is not of expected type '$expectedType'")
            }
           case None => Left(s"Parameter '$finalPart' not found")
        }
      }
   } catch {
     case e: Exception => Left(s"Error extracting parameter at '$path': ${e.getMessage}")
   }
}
