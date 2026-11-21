package org.llm4s.schema

import org.llm4s.annotation.Experimental
import org.llm4s.toolapi.ToolFunction
import org.llm4s.types.Result

/**
 * Tools derived from a whole service: one per public method of a trait or class.
 *
 * {{{
 * trait CustomerService:
 *   /** Orders placed by a customer, newest first.
 *     * @param customerId the customer's id, for example C-1001
 *     */
 *   def findOrders(customerId: String): Result[List[Order]]
 *
 * val tools: Result[Seq[ToolFunction[?, ?]]] = Tools.derive[CustomerService](liveService)
 * }}}
 */
@Experimental
object Tools {

  /**
   * One tool per public method of `S`, each built as [[Tool.fromMethod]] builds one, and calling that method on
   * `service`; in the order of their tool names, ready for a `ToolRegistry`.
   *
   * Left out: methods `S` inherits from `Any`, `Object`, `Product`, `Equals` and `Serializable`, synthetic methods
   * (a case class's `copy`), private, protected and extension methods, `val`s, and every method marked [[notATool]].
   * A method with no parameter list (`def status: String`) is a tool without parameters.
   *
   * Refused at compile time, with a message naming the method: whatever [[Tool.fromMethod]] refuses, an overloaded
   * method (a model cannot tell two tools of one name apart: rename one, give it a [[toolName]], or mark it
   * [[notATool]]), and two methods whose tool names are the same.
   *
   * @param service the instance whose methods the tools call
   * @tparam S the trait or class whose methods are the tools; its Scaladoc and annotations describe them
   * @return the tools, or the first `ValidationError` a parameter's schema gave (see [[SchemaOf]])
   */
  inline def derive[S](service: S): Result[Seq[ToolFunction[?, ?]]] = ${ ToolMacros.deriveImpl[S]('service) }
}
