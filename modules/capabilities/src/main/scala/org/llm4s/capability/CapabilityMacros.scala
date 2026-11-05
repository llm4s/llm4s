package org.llm4s.capability

import scala.quoted.*

/** Builds the [[CapabilityCheck]] of a capability set at compile time. */
private[capability] object CapabilityMacros {

  private val Package = "org.llm4s.capability"

  def derive[C: Type](using Quotes): Expr[CapabilityCheck[C]] = {
    import quotes.reflect.*

    val markers = CapabilityCheck.Order.mkString(", ")

    def parts(tpe: TypeRepr): List[String] = tpe.dealias match {
      case AndType(left, right) => parts(left) ++ parts(right)
      case other =>
        val symbol = other.typeSymbol
        val name   = symbol.name
        if (other =:= TypeRepr.of[Nothing])
          report.errorAndAbort(
            "No capability set was given. Name the capabilities, for example " +
              "client.require[Vision & Tools](provider, model) or Capable.assume[Vision](client)."
          )
        else if (symbol.fullName == s"$Package.$name" && CapabilityCheck.SupportsKeys.contains(name)) List(name)
        else
          report.errorAndAbort(
            s"${other.show} is not a capability. A capability set is one or more of $markers, joined with &, " +
              "for example Vision & Tools."
          )
    }

    val names = Expr(parts(TypeRepr.of[C]).distinct.sortBy(CapabilityCheck.Order.indexOf))
    '{ CapabilityCheck.unsafeOf[C]($names) }
  }
}
