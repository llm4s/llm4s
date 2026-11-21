package org.llm4s.schema

import org.llm4s.annotation.Experimental

import scala.annotation.StaticAnnotation

/**
 * The name the model calls a tool derived from a method by ([[Tool.fromMethod]] or [[Tools.derive]]), instead of the
 * method's name in `snake_case`.
 *
 * The name must be a string literal of 1 to 64 letters, digits, `_` or `-` (what every provider accepts), because it
 * is read and checked at compile time.
 *
 * {{{
 * trait Orders:
 *   @toolName("orders_search")
 *   def findOrders(customerId: String): List[Order]
 * }}}
 */
@Experimental
final class toolName(val name: String) extends StaticAnnotation
