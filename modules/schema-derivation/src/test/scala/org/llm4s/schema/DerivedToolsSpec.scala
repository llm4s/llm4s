package org.llm4s.schema

import org.llm4s.context.tokens.TokenizerMapping
import org.llm4s.schema.MethodFixtures.*
import org.llm4s.toolapi.{ ToolCallError, ToolCallRequest, ToolFunction, ToolRegistry }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Tools made from methods (#2326): their schema, names, descriptions, argument errors and dispatch. */
class DerivedToolsSpec extends AnyFlatSpec with Matchers {

  private def built[T](tool: org.llm4s.types.Result[T]): T =
    tool.fold(e => sys.error(s"could not build the tool: ${e.formatted}"), identity)

  private lazy val tools: Map[String, ToolFunction[?, ?]] =
    built(Tools.derive[CustomerService](LiveCustomerService)).map(t => t.name -> t).toMap

  private def parameters(tool: ToolFunction[?, ?]): ujson.Value = tool.schema.toJsonSchema(true)

  "Tools.derive" should "make one tool per public method, in name order, leaving out @notATool methods" in {
    built(Tools.derive[CustomerService](LiveCustomerService)).map(_.name) shouldBe
      List("cancel", "find_orders", "open_orders", "ping", "status")
  }

  it should "derive the parameters' schema that Tool.of derives for the equivalent case class" in {
    val typed      = built(Tool.of[FindOrdersArgs, List[Order]]("find_orders", "unused")(_ => Right(Nil)))
    val fromMethod = parameters(tools("find_orders"))
    val fromClass  = parameters(typed)
    fromMethod("properties") shouldBe fromClass("properties")
    fromMethod("required") shouldBe fromClass("required")
    fromMethod("additionalProperties") shouldBe fromClass("additionalProperties")
    tools("find_orders").schema.toJsonSchema(false)("required") shouldBe typed.schema.toJsonSchema(false)("required")
  }

  it should "describe a tool by its Scaladoc's text, and a parameter by its @param, joining wrapped lines" in {
    tools(
      "find_orders"
    ).description shouldBe "Orders placed by a customer, newest first.\n\nOnly orders that were paid."
    parameters(tools("find_orders"))("properties")("customerId")("description").str shouldBe
      "the customer's id, for example C-1001"
  }

  it should "prefer @description to Scaladoc, for a method and for a parameter, and @toolName to the method name" in {
    tools("open_orders").description shouldBe "How many orders are open"
    tools("cancel").description shouldBe "Cancel an order that has not shipped yet."
    parameters(tools("cancel"))("properties")("orderId")("description").str shouldBe "The order to cancel"
  }

  it should "describe a method and a parameter with neither by their names" in {
    tools("ping").description shouldBe "ping"
    tools("cancel").schema.toJsonSchema(true)("properties")("reason")("description").str shouldBe "reason"
  }

  it should "dispatch a call to the right method, and write its result" in {
    val registry = new ToolRegistry(tools.values.toSeq)
    registry.execute(ToolCallRequest("find_orders", ujson.Obj("customerId" -> "C-7"))) shouldBe
      Right(ujson.Arr(ujson.Obj("id" -> "C-7-1", "total" -> 10.0)))
    registry.execute(ToolCallRequest("find_orders", ujson.Obj("customerId" -> "C-7", "since" -> "2026-01-01"))) shouldBe
      Right(ujson.Arr(ujson.Obj("id" -> "C-7-1", "total" -> 5.0)))
    registry.execute(ToolCallRequest("cancel", ujson.Obj("orderId" -> "O-1", "reason" -> "late"))) shouldBe
      Right(ujson.Obj("orderId" -> "O-1", "refunded" -> true))
    registry.execute(ToolCallRequest("open_orders", ujson.Obj("limit" -> 9))) shouldBe Right(ujson.Num(3))
    registry.execute(ToolCallRequest("ping", ujson.Obj())) shouldBe Right(ujson.Str("pong"))
    registry.execute(ToolCallRequest("status", ujson.Null)) shouldBe Right(ujson.Str("ok"))
  }

  it should "make a Left, a Result's error and a Try's failure the tool's error" in {
    tools("cancel").execute(ujson.Obj("orderId" -> "O-1", "reason" -> "")) shouldBe
      Left(ToolCallError.HandlerError("cancel", "a reason is required"))
    tools("open_orders").execute(ujson.Obj("limit" -> -1)) shouldBe
      Left(ToolCallError.HandlerError("open_orders", "limit must not be negative"))
    tools("find_orders").execute(ujson.Obj("customerId" -> "unknown")) match {
      case Left(ToolCallError.HandlerError("find_orders", message)) => message should include("no such customer")
      case other                                                    => fail(s"expected the Result's error, got $other")
    }
  }

  "Tool.fromMethod" should "build the tool of one method, typed by its result" in {
    val cancel: ToolFunction[?, Cancellation] = built(Tool.fromMethod(LiveCustomerService.cancelOrder))
    cancel.name shouldBe "cancel"
    cancel.execute(ujson.Obj("orderId" -> "O-2", "reason" -> "late")) shouldBe
      Right(ujson.Obj("orderId" -> "O-2", "refunded" -> true))
  }

  it should "take the Scaladoc and annotations of the method an undocumented override implements" in {
    // `LiveCustomerService`'s methods have neither Scaladoc nor annotations; the trait's have both
    val viaTrait = built(Tool.fromMethod((LiveCustomerService: CustomerService).findOrders))
    val viaClass = built(Tool.fromMethod(LiveCustomerService.findOrders))
    viaClass.description shouldBe viaTrait.description
    viaClass.description shouldBe tools("find_orders").description
    parameters(viaClass) shouldBe parameters(viaTrait)
    val cancel = built(Tool.fromMethod(LiveCustomerService.cancelOrder))
    parameters(cancel)("properties")("orderId")("description").str shouldBe "The order to cancel"
  }

  it should "take a method with an empty parameter list as a function" in {
    val ping = built(Tool.fromMethod(() => LiveCustomerService.ping()))
    ping.name shouldBe "ping"
    ping.execute(ujson.Obj()) shouldBe Right(ujson.Str("pong"))
  }

  it should "evaluate the qualifier once, when the tool is built" in {
    var made                       = 0
    def service(): CustomerService = { made += 1; LiveCustomerService }
    val cancel                     = built(Tool.fromMethod(service().cancelOrder))
    cancel.execute(ujson.Obj("orderId" -> "O-1", "reason" -> "x"))
    cancel.execute(ujson.Obj("orderId" -> "O-2", "reason" -> "y"))
    made shouldBe 1
  }

  it should "read Scaladoc from the TASTy of a class compiled in another run" in {
    val exact = built(Tool.fromMethod(TokenizerMapping.isExactMapping))
    exact.name shouldBe "is_exact_mapping"
    exact.description should startWith("Check if the tokenizer mapping is exact or approximate for a model.")
    parameters(exact)("properties")("modelName")("description").str shouldBe "The model identifier"
    exact.execute(ujson.Obj("modelName" -> "gpt-4o")) shouldBe Right(ujson.Bool(true))
  }

  "Scaladoc" should "be read as its main text and its @param tags, with links written as their text" in {
    val (text, params) = ToolMacros.scaladoc(
      """/** Looks up a [[Customer]] by [[org.llm4s.Id its id]].
        |  *
        |  * @param id   the id
        |  *             of the customer
        |  * @param
        |  * @return the customer
        |  */""".stripMargin
    )
    text shouldBe Some("Looks up a Customer by its id.")
    params shouldBe Map("id" -> "the id of the customer")
    ToolMacros.scaladoc("/** @param x only a tag */") shouldBe (None, Map("x" -> "only a tag"))
  }

  "A tool name" should "be the method name in snake_case" in {
    ToolMacros.snakeCase("findOrders") shouldBe "find_orders"
    ToolMacros.snakeCase("getHTTPStatus") shouldBe "get_http_status"
    ToolMacros.snakeCase("v2Lookup") shouldBe "v2_lookup"
    ToolMacros.snakeCase("ping") shouldBe "ping"
  }
}
