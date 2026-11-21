package org.llm4s.tapir

import org.llm4s.error.ValidationError
import org.llm4s.tapir.Fixtures._
import org.llm4s.toolapi.ToolFunction
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.client3.UriContext
import sttp.tapir._

import java.time.{ Instant, LocalDate }

class EndpointSchemaSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val base = uri"https://orders.internal"

  private val binaryQueryCodec: Codec[List[String], Array[Byte], CodecFormat.TextPlain] =
    Codec.listHead(Codec.string.map(_.getBytes)(new String(_)).schema(Schema.schemaForByteArray))

  private def built(tools: EndpointTool*): Seq[ToolFunction[?, ?]] =
    EndpointTools.registry(base, new Recording().backend, tools*).value.tools

  private def schemaOf(tool: EndpointTool, strict: Boolean = false): ujson.Value =
    built(tool).head.schema.toJsonSchema(strict)

  private def failure(tools: EndpointTool*): List[String] =
    EndpointTools.registry(base, new Recording().backend, tools*).left.value match {
      case v: ValidationError => v.violations
      case other              => fail(s"expected a ValidationError, got $other")
    }

  "a path capture" should "be a required string parameter named after it, with its description" in {
    val schema = schemaOf(EndpointTools.tool("get_order", getOrder))
    schema("properties")("id")("type").str shouldBe "string"
    schema("properties")("id")("description").str shouldBe "The order's id"
    schema("required").arr.map(_.str) shouldBe Seq("id")
  }

  "the tool" should "take the endpoint's description, then its summary, then its method and path" in {
    built(EndpointTools.tool("get_order", getOrder)).head.description shouldBe "Look up an order by id"
    val summarised = endpoint.get.in("ping").summary("Check the service")
    built(EndpointTools.tool("ping", summarised)).head.description shouldBe "Check the service"
    val bare = endpoint.delete.in("orders" / path[Int]("id"))
    built(EndpointTools.tool("delete", bare)).head.description shouldBe "DELETE /orders/{id}"
    built(EndpointTools.tool("ping", summarised).withDescription("Mine")).head.description shouldBe "Mine"
    built(EndpointTools.tool("ping", summarised).withDescription(None)).head.description shouldBe "Check the service"
  }

  it should "name an unnamed path capture as tapir shows it" in {
    val e = endpoint.get.in("orders" / path[Int])
    schemaOf(EndpointTools.tool("t", e))("properties").obj.keys.toSeq shouldBe Seq("param1")
  }

  "a query parameter" should "be a parameter of its type, required unless it is an Option" in {
    val e = endpoint.get
      .in("orders")
      .in(query[Int]("page").description("Page number"))
      .in(query[Option[String]]("customer"))
      .in(query[Boolean]("archived"))
      .in(query[Double]("minTotal"))
      .in(query[List[String]]("tag"))
    val schema = schemaOf(EndpointTools.tool("list_orders", e))
    val props  = schema("properties")
    props("page")("type").str shouldBe "integer"
    props("page")("description").str shouldBe "Page number"
    props("customer")("type").arr.map(_.str) shouldBe Seq("string", "null")
    props("archived")("type").str shouldBe "boolean"
    props("minTotal")("type").str shouldBe "number"
    // tapir reads a repeated query parameter that is absent as an empty list, so it is optional
    props("tag")("type").arr.map(_.str) shouldBe Seq("array", "null")
    props("tag")("items")("type").str shouldBe "string"
    schema("required").arr.map(_.str).toSet shouldBe Set("page", "archived", "minTotal")
    // OpenAI's strict mode requires every property; the optional one stays nullable
    schemaOf(EndpointTools.tool("list_orders", e), strict = true)("required").arr should have size 5
  }

  it should "carry an enumeration's values, and its validators' bounds and lengths" in {
    val e = endpoint.get
      .in("orders")
      .in(query[Status]("status"))
      .in(query[Int]("limit").validate(Validator.min(1).and(Validator.max(100))))
      .in(query[Int]("offset").validate(Validator.min(0, exclusive = true)))
      .in(query[Long]("before").validate(Validator.max(Long.MaxValue, exclusive = true)))
      .in(query[Double]("ratio").validate(Validator.min(0.5).and(Validator.max(2.5, exclusive = true))))
      .in(query[String]("code").validate(Validator.minLength(2).and(Validator.maxLength(4))))
      .in(query[String]("sku").validate(Validator.pattern("[A-Z]+")))
      .in(query[List[String]]("ids").validate(Validator.minSize[String, List](1).and(Validator.maxSize(3))))
      .in(query[Int]("size").validate(Validator.enumeration(List(1, 2, 3))))
    val props = schemaOf(EndpointTools.tool("search", e))("properties")
    props("status")("enum").arr.map(_.str).toSet shouldBe Set("Open", "Shipped", "Cancelled")
    props("limit")("minimum").num shouldBe 1
    props("limit")("maximum").num shouldBe 100
    props("offset")("exclusiveMinimum").num shouldBe 0
    props("before")("type").str shouldBe "integer"
    props("before").obj.contains("exclusiveMaximum") shouldBe true
    props("ratio")("minimum").num shouldBe 0.5
    props("ratio")("exclusiveMaximum").num shouldBe 2.5
    props("code")("minLength").num shouldBe 2
    props("code")("maxLength").num shouldBe 4
    props("sku")("description").str should include("[A-Z]+")
    props("ids")("minItems").num shouldBe 1
    props("ids")("maxItems").num shouldBe 3
    props("size")("description").str should include("one of 1, 2, 3")
  }

  "a date or a timestamp" should "be a string whose description gives its format" in {
    val e     = endpoint.get.in("orders").in(query[LocalDate]("on")).in(query[Instant]("since"))
    val props = schemaOf(EndpointTools.tool("by_date", e))("properties")
    props("on")("type").str shouldBe "string"
    props("on")("description").str should include("ISO-8601 date")
    props("since")("description").str should include("date and time")
  }

  "a JSON body" should "be the body parameter, with its fields, their optionality and their validators" in {
    val e      = endpoint.post.in("orders").in(jsonIn[NewOrder].description("The order to place")).out(jsonIn[Order])
    val schema = schemaOf(EndpointTools.tool("place_order", e))
    val body   = schema("properties")("body")
    body("type").str shouldBe "object"
    body("description").str shouldBe "The order to place"
    body("properties")("item")("minLength").num shouldBe 1
    body("properties")("quantity")("minimum").num shouldBe 1
    body("properties")("quantity")("description").str shouldBe "How many to order"
    body("properties")("note")("type").arr.map(_.str) shouldBe Seq("string", "null")
    body("required").arr.map(_.str) shouldBe Seq("item", "quantity")
    schema("required").arr.map(_.str) shouldBe Seq("body")
  }

  "a text body" should "be a string body parameter" in {
    val e = endpoint.post.in("notes").in(stringBody)
    schemaOf(EndpointTools.tool("add_note", e))("properties")("body")("type").str shouldBe "string"
  }

  "a header" should "be a parameter only when the model may set it" in {
    val e      = endpoint.get.in("orders").in(header[Option[String]]("X-Request-Id")).in(header[String]("X-Tenant"))
    val hidden = EndpointTools.tool("list", e).withHeaders(Map("x-tenant" -> "acme"))
    schemaOf(hidden)("properties").obj.keySet shouldBe empty
    val shown = hidden.withModelHeaders("x-request-id")
    schemaOf(shown)("properties").obj.keySet shouldBe Set("X-Request-Id")
  }

  it should "fail the build when it is required and nobody supplies it" in {
    val e = endpoint.get.in("orders").in(header[String]("X-Tenant"))
    failure(EndpointTools.tool("list", e)).head should include("header 'X-Tenant' is required")
  }

  it should "fail the build when it is both an application header and one the model may set" in {
    val e    = endpoint.get.in("orders").in(header[String]("X-Tenant"))
    val both = EndpointTools.tool("list", e).withHeaders(Map("X-Tenant" -> "acme")).withModelHeaders("X-Tenant")
    failure(both).head should include("both an application header and one the model may set")
  }

  "an authentication input" should "never be a parameter, even when the model may set its header" in {
    val e = endpoint.get.securityIn(auth.bearer[String]()).in("orders")
    val tool = EndpointTools
      .tool("list", e)
      .withHeaders(Map("Authorization" -> "Bearer t"))
      .withModelHeaders(
        "Authorization"
      )
    // withModelHeaders does not reach an authentication input: the model is never offered it
    schemaOf(tool)("properties").obj.keySet shouldBe empty
    failure(EndpointTools.tool("list", e)).head should include("pass it with withHeaders")
  }

  it should "be refused when it is not a header" in {
    val e = endpoint.get.securityIn(auth.apiKey(query[String]("api_key"))).in("orders")
    failure(EndpointTools.tool("list", e)).head should include("only header authentication is")
  }

  "an input a model cannot supply" should "fail the build, naming the tool and the input" in {
    val cookieIn  = endpoint.get.in("a").in(cookie[String]("session"))
    val pathsIn   = endpoint.get.in("files").in(paths)
    val paramsIn  = endpoint.get.in("search").in(queryParams)
    val headersIn = endpoint.get.in("echo").in(headers)
    val binaryIn  = endpoint.post.in("upload").in(byteArrayBody)
    val errors = failure(
      EndpointTools.tool("cookie", cookieIn),
      EndpointTools.tool("paths", pathsIn),
      EndpointTools.tool("params", paramsIn),
      EndpointTools.tool("headers", headersIn),
      EndpointTools.tool("binary", binaryIn)
    )
    errors should have size 5
    errors(0) shouldBe "cookie: a cookie input is not supported"
    errors(1) shouldBe "paths: a `paths` input is not supported"
    errors(2) shouldBe "params: a `queryParams` input is not supported"
    errors(3) shouldBe "headers: a `headers` input is not supported"
    errors(4) should startWith("binary: a binary request body is not supported")
  }

  "a type a tool parameter cannot describe" should "fail the build, naming its path" in {
    failure(EndpointTools.tool("tree", endpoint.post.in(jsonIn[Tree]))).head should include("recursive type")
    failure(EndpointTools.tool("shape", endpoint.post.in(jsonIn[Shape]))).head should include("sealed hierarchy")
    failure(EndpointTools.tool("bag", endpoint.post.in(jsonIn[Bag]))).head should include("body.items is a map")
    failure(
      EndpointTools.tool("bin", endpoint.get.in(query[Array[Byte]]("b")(using binaryQueryCodec)))
    ).head should include("binary data")
  }

  "an output a model cannot read" should "fail the build" in {
    val e = endpoint.get.in("file").out(byteArrayBody)
    failure(EndpointTools.tool("file", e)).head shouldBe "file: a binary response body is not supported"
  }

  "the tool names" should "match the pattern providers accept and be unique" in {
    failure(EndpointTools.tool("get order", getOrder)).head should include("must match [a-zA-Z0-9_-]{1,64}")
    failure(EndpointTools.tool("a", getOrder), EndpointTools.tool("a", cancelOrder)).head should include(
      "used by more than one tool"
    )
  }

  "two inputs with one name" should "fail the build" in {
    val e = endpoint.get.in("orders" / path[String]("id")).in(query[String]("id"))
    failure(EndpointTools.tool("t", e)).head should include("both called 'id'")
  }

  "requiringApproval" should "gate an unsafe method unless told otherwise, and never a safe one" in {
    val get    = EndpointTools.tool("get_order", getOrder)
    val cancel = EndpointTools.tool("cancel_order", cancelOrder)
    get.requiresApproval shouldBe false
    cancel.requiresApproval shouldBe true
    EndpointTools.requiringApproval(get, cancel) shouldBe Set("cancel_order")
    EndpointTools.requiringApproval(
      EndpointTools.tool("get_order", getOrder, requiresApproval = true),
      cancel.withRequiresApproval(false)
    ) shouldBe Set("get_order")
    EndpointTools.tool("ping", endpoint.in("ping")).requiresApproval shouldBe false
  }
}
