package org.llm4s.schema

import org.llm4s.toolapi.ToolFunction
import org.llm4s.types.Result
import upickle.default.{ ReadWriter, Reader }

import scala.util.Try

/**
 * What the code [[ToolMacros]] generates calls at run time: everything about a method-made tool that does not need
 * the compiler. Internal to the module.
 */
private[schema] object MethodTools {

  /**
   * The tool of one method. `fields`, `schemas`, `readers` and `descriptions` are its parameters, in order;
   * `invoke` calls the method with the decoded arguments, in the same order.
   */
  def build[R](
    name: String,
    description: String,
    fields: List[String],
    schemas: List[SchemaOf[?]],
    readers: List[Reader[?]],
    descriptions: List[Option[String]],
    invoke: List[Any] => Either[String, R]
  )(using ReadWriter[R]): Result[ToolFunction[Any, R]] = {
    val arguments = SchemaOf.product[Any](name, Some(description), fields, schemas, descriptions, fields.map(_ => None))
    Tool.parametersOf(name, arguments).map { parameters =>
      ToolFunction[Any, R](
        name,
        description,
        parameters,
        extractor => Tool.decodeFields(parameters, extractor.params, readers).left.map(Tool.message).flatMap(invoke)
      )
    }
  }

  /** A method's `Result`: its error, formatted, is the tool's. */
  def fromResult[R](result: Result[R]): Either[String, R] = result.left.map(_.formatted)

  /** A method's `Try`: a failure's message is the tool's error. */
  def fromTry[R](result: Try[R]): Either[String, R] =
    result.toEither.left.map(e => Option(e.getMessage).getOrElse(e.getClass.getName))

  /** The tools of a service, or the first that could not be built. */
  def all(tools: List[Result[ToolFunction[?, ?]]]): Result[Seq[ToolFunction[?, ?]]] =
    tools.foldRight[Result[List[ToolFunction[?, ?]]]](Right(Nil)) { (tool, rest) =>
      for {
        t  <- tool
        ts <- rest
      } yield t :: ts
    }
}
