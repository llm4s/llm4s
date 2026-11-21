package org.llm4s.schema

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A method no tool can be made from is refused at compile time, with a message naming it (#2326). */
class DerivedToolsCompileErrorsSpec extends AnyFlatSpec with Matchers {

  private def messages(errors: List[scala.compiletime.testing.Error]): String = errors.map(_.message).mkString("\n")

  "Tools.derive" should "refuse a parameter type without a SchemaOf, naming the method and the parameter" in {
    val errors = scala.compiletime.testing.typeCheckErrors(
      "Tools.derive[MethodFixtures.TakesAMap](null.asInstanceOf[MethodFixtures.TakesAMap])"
    )
    val text = messages(errors)
    text should include("Cannot derive a tool from TakesAMap.score")
    text should include("parameter weights has type Map[String, Int], which has no SchemaOf")
  }

  it should "refuse an overloaded method, naming it" in {
    val text = messages(
      scala.compiletime.testing.typeCheckErrors(
        "Tools.derive[MethodFixtures.Overloaded](null.asInstanceOf[MethodFixtures.Overloaded])"
      )
    )
    text should include("Overloaded.find")
    text should include("overloaded")
  }

  it should "refuse a by-name parameter, naming the method" in {
    messages(
      scala.compiletime.testing.typeCheckErrors(
        "Tools.derive[MethodFixtures.ByName](null.asInstanceOf[MethodFixtures.ByName])"
      )
    ) should include("Cannot derive a tool from ByName.later: parameter value is by-name")
  }

  it should "refuse a context parameter, naming the method" in {
    messages(
      scala.compiletime.testing.typeCheckErrors(
        "Tools.derive[MethodFixtures.WithContext](null.asInstanceOf[MethodFixtures.WithContext])"
      )
    ) should include("Cannot derive a tool from WithContext.greet: it takes a context parameter")
  }

  it should "refuse several parameter lists, naming the method" in {
    messages(
      scala.compiletime.testing.typeCheckErrors(
        "Tools.derive[MethodFixtures.Curried](null.asInstanceOf[MethodFixtures.Curried])"
      )
    ) should include("Cannot derive a tool from Curried.add: it has 2 parameter lists")
  }

  it should "refuse a polymorphic method, naming it" in {
    messages(
      scala.compiletime.testing.typeCheckErrors(
        "Tools.derive[MethodFixtures.Polymorphic](null.asInstanceOf[MethodFixtures.Polymorphic])"
      )
    ) should include("Cannot derive a tool from Polymorphic.first: it is polymorphic")
  }

  it should "refuse a repeated parameter, naming the method" in {
    messages(
      scala.compiletime.testing.typeCheckErrors(
        "Tools.derive[MethodFixtures.Repeated](null.asInstanceOf[MethodFixtures.Repeated])"
      )
    ) should include("Cannot derive a tool from Repeated.sum: parameter values is repeated")
  }

  it should "refuse a result without a ReadWriter, naming the method" in {
    messages(
      scala.compiletime.testing.typeCheckErrors(
        "Tools.derive[MethodFixtures.Unwritable](null.asInstanceOf[MethodFixtures.Unwritable])"
      )
    ) should include("Cannot derive a tool from Unwritable.connection: its result InputStream")
  }

  it should "refuse two methods whose tool names are the same" in {
    messages(
      scala.compiletime.testing.typeCheckErrors(
        "Tools.derive[MethodFixtures.Clashing](null.asInstanceOf[MethodFixtures.Clashing])"
      )
    ) should include("findOrders and lookup would both be the tool find_orders")
  }

  it should "refuse a tool name that providers do not accept" in {
    messages(
      scala.compiletime.testing.typeCheckErrors(
        "Tools.derive[MethodFixtures.BadName](null.asInstanceOf[MethodFixtures.BadName])"
      )
    ) should include("its tool name 'find orders' is not 1 to 64 letters")
  }

  it should "refuse a type that is not a class or trait" in {
    messages(scala.compiletime.testing.typeCheckErrors("Tools.derive[Int | String](1)")) should include(
      "needs a trait or a class"
    )
  }

  "Tool.fromMethod" should "refuse a function that is not a method reference" in {
    messages(scala.compiletime.testing.typeCheckErrors("Tool.fromMethod((x: Int) => x + 1)")) should include(
      "Tool.fromMethod takes a method, eta-expanded"
    )
    messages(scala.compiletime.testing.typeCheckErrors("Tool.fromMethod(42)")) should include(
      "Tool.fromMethod takes a method, eta-expanded"
    )
  }

  it should "refuse a parameter type without a SchemaOf, naming the method and the parameter" in {
    messages(
      scala.compiletime.testing.typeCheckErrors("Tool.fromMethod(MethodFixtures.LiveCustomerService.internal)")
    ) should include("Cannot derive a tool from LiveCustomerService.internal: parameter weights has type Map")
  }

  it should "refuse a polymorphic method and a context parameter, naming the method" in {
    messages(
      scala.compiletime.testing.typeCheckErrors(
        "Tool.fromMethod(null.asInstanceOf[MethodFixtures.Polymorphic].first[Int])"
      )
    ) should include("Polymorphic.first: it is polymorphic")
    messages(
      scala.compiletime.testing.typeCheckErrors(
        "{ given String = \"Hi\"; Tool.fromMethod(null.asInstanceOf[MethodFixtures.WithContext].greet) }"
      )
    ) should include("WithContext.greet: it takes a context parameter")
  }
}
