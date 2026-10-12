package org.llm4s.schema

import org.llm4s.annotation.Experimental

import scala.annotation.StaticAnnotation

/**
 * The description the model sees for a derived schema: on a case class parameter it describes the field, on a
 * case class, enum or sealed trait it describes the type.
 *
 * The text must be a string literal (`@description("Total amount")`), because the annotation is read at
 * compile time and a macro cannot evaluate an expression. Without it, a field is described by its name and a
 * type by its name.
 *
 * {{{
 * @description("An invoice extracted from text")
 * case class Invoice(
 *   @description("Name of the vendor or supplier") vendor: String,
 *   amount: Double
 * ) derives SchemaOf
 * }}}
 */
@Experimental
final class description(val text: String) extends StaticAnnotation
