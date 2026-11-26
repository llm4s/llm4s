package org.llm4s.schema

import org.llm4s.annotation.Experimental

import scala.annotation.StaticAnnotation

/**
 * The description the model sees for a derived schema: on a case class parameter it describes the field, on a
 * case class, enum or sealed trait it describes the type.
 *
 * The annotation is read at compile time, so the text must be a constant string the compiler can work out: a string
 * literal (`@description("Total amount")`), a `final val` or `inline val` declared without a type
 * (`final val Text = "..."`, not `final val Text: String = "..."`), a `+` of such constants, numbers and other
 * primitives included (`"at most " + Limits.Max + " characters"`), or an `s"..."` interpolation whose arguments are
 * all such constants (`s"at most ${Limits.Max} characters"`). Anything else (a `def`, a `val` that is not `final`, an
 * `f"..."` or `raw"..."` interpolation, a method call) is a compile error where the schema or tool is derived, naming
 * the field; it is never silently ignored. Without the annotation, a field is described by its name and a type by its
 * name.
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
