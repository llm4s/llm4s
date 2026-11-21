package org.llm4s.schema

import org.llm4s.annotation.Experimental

import scala.annotation.StaticAnnotation

/**
 * Leaves a public method out of the tools [[Tools.derive]] makes from its trait or class: a helper the model should
 * not call, or a method whose parameters no tool could take.
 *
 * {{{
 * trait Orders:
 *   def findOrders(customerId: String): List[Order]
 *   @notATool
 *   def connection: java.sql.Connection
 * }}}
 */
@Experimental
final class notATool extends StaticAnnotation
