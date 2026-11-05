package org.llm4s.tapir

import org.llm4s.tapir.Fixtures._
import org.llm4s.toolapi.{ ToolCallError, ToolCallRequest, ToolRegistry }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.client3.testing.SttpBackendStub
import sttp.client3.{ Identity, StringBody, SttpBackend, UriContext }
import sttp.model.Method
import sttp.tapir._

class EndpointCallSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val base = uri"https://orders.internal/api"

  private def registry(backend: SttpBackend[Identity, Any], tools: EndpointTool*): ToolRegistry =
    EndpointTools.registry(base, backend, tools*).value

  private def call(registry: ToolRegistry, name: String, args: String): Either[ToolCallError, ujson.Value] =
    registry.execute(ToolCallRequest(name, ujson.read(args)))

  private def message(result: Either[ToolCallError, ujson.Value]): String = result.left.value.getMessage

  private def body(recording: Recording): String = recording.last.body match {
    case StringBody(s, _, _) => s
    case other               => fail(s"expected a string body, got $other")
  }

  "a call" should "send the path, query, headers and body the endpoint declares, and return its JSON output" in {
    val e = endpoint.post
      .in("orders" / path[String]("id") / "items")
      .in(query[Int]("page"))
      .in(query[Option[String]]("note"))
      .in(query[List[String]]("tag"))
      .in(header[String]("X-Request-Id"))
      .in(header("X-Client", "llm4s"))
      .in(jsonIn[NewOrder])
      .out(jsonIn[Order])
    val recording = new Recording()
    val tool      = EndpointTools.tool("add_item", e).withModelHeaders("X-Request-Id")
    val result = call(
      registry(recording.backend, tool),
      "add_item",
      """{"id":"a b","page":2,"note":null,"tag":["x","y"],"X-Request-Id":"r-1",
        | "body":{"item":"pen","quantity":3}}""".stripMargin
    )
    result.value shouldBe ujson.Obj("id" -> "42", "total" -> 9.5)
    val request = recording.last
    request.method shouldBe Method.POST
    request.uri.toString shouldBe "https://orders.internal/api/orders/a%20b/items?page=2&tag=x&tag=y"
    request.headers.find(_.is("X-Request-Id")).map(_.value) shouldBe Some("r-1")
    request.headers.find(_.is("X-Client")).map(_.value) shouldBe Some("llm4s")
    request.headers.find(_.is("Content-Type")).map(_.value) shouldBe Some("application/json")
    val sent = ujson.read(body(recording))
    (sent("item").str, sent("quantity").num) shouldBe (("pen", 3.0))
  }

  it should "send numbers and booleans in a path or query as text" in {
    val e         = endpoint.get.in("orders" / path[Long]("id")).in(query[Boolean]("full")).in(query[Double]("min"))
    val recording = new Recording(body = "")
    call(
      registry(recording.backend, EndpointTools.tool("t", e)),
      "t",
      """{"id":7,"full":true,"min":1.5}"""
    ).value shouldBe ujson.Obj("status" -> 200)
    recording.last.uri.toString shouldBe "https://orders.internal/api/orders/7?full=true&min=1.5"
  }

  it should "send a text body as written" in {
    val recording = new Recording(body = "noted")
    val e         = endpoint.post.in("notes").in(stringBody).out(stringBody)
    call(
      registry(recording.backend, EndpointTools.tool("note", e)),
      "note",
      """{"body":"hello"}"""
    ).value shouldBe ujson.Str("noted")
    body(recording) shouldBe "hello"
  }

  it should "leave out an optional body the model does not give" in {
    val recording = new Recording()
    val e         = endpoint.post.in("orders").in(jsonIn[Option[NewOrder]](using summon, Schema.schemaForOption))
    call(registry(recording.backend, EndpointTools.tool("t", e)), "t", "{}").isRight shouldBe true
    recording.last.body shouldBe sttp.client3.NoBody
  }

  "the headers" should "be the application's and the allowed ones only, whatever else the model sends" in {
    val e = endpoint.get
      .securityIn(auth.bearer[String]())
      .in("orders")
      .in(header[Option[String]]("X-Trace"))
      .in(header[Option[String]]("X-Debug"))
    val recording = new Recording()
    val tool = EndpointTools
      .tool("list", e)
      .withHeaders(Map("Authorization" -> "Bearer secret", "X-Tenant" -> "acme"))
      .withModelHeaders("X-Trace", "Authorization")
    call(
      registry(recording.backend, tool),
      "list",
      """{"X-Trace":"t-1","X-Debug":"on","Authorization":"Bearer stolen"}"""
    ).isRight shouldBe true
    val sent = recording.last.headers
    sent.filter(_.is("Authorization")).map(_.value) shouldBe Seq("Bearer secret")
    sent.find(_.is("X-Tenant")).map(_.value) shouldBe Some("acme")
    sent.find(_.is("X-Trace")).map(_.value) shouldBe Some("t-1")
    sent.exists(_.is("X-Debug")) shouldBe false
  }

  "an argument the endpoint rejects" should "be a tool error naming it, and nothing is sent" in {
    val e = endpoint.get
      .in("orders" / path[Int]("id"))
      .in(query[Int]("limit").validate(Validator.max(10)))
      .in(query[Status]("status"))
    val recording = new Recording()
    val tools     = registry(recording.backend, EndpointTools.tool("t", e))
    message(call(tools, "t", """{"id":"abc","limit":5,"status":"Open"}""")) should include("'id' cannot be read")
    message(call(tools, "t", """{"id":1,"limit":50,"status":"Open"}""")) should include(
      "'limit' is invalid: expected <=10, got 50"
    )
    message(call(tools, "t", """{"id":1,"limit":5,"status":"Lost"}""")) should include("'status'")
    message(call(tools, "t", """{"limit":5,"status":"Open"}""")) should include("'id' is required")
    message(call(tools, "t", """{"id":1,"status":"Open"}""")) should include("'limit' is required")
    message(call(tools, "t", """{"id":{"n":1},"limit":5,"status":"Open"}""")) should include(
      "'id' must be a string, a number or a boolean"
    )
    message(call(tools, "t", """{"id":1,"limit":[5,6],"status":"Open"}""")) should include("'limit' takes one value")
    message(call(tools, "t", """{"id":1,"limit":[{"a":1}],"status":"Open"}""")) should include(
      "'limit' must be a string"
    )
    message(call(tools, "t", """[1]""")) should include("the arguments must be a JSON object")
    recording.requests shouldBe empty
  }

  it should "report every invalid argument at once, and a JSON body's field by its path" in {
    val e         = endpoint.post.in("orders" / path[Int]("id")).in(jsonIn[NewOrder])
    val recording = new Recording()
    val tools     = registry(recording.backend, EndpointTools.tool("t", e))
    val error     = message(call(tools, "t", """{"id":"x","body":{"item":"pen","quantity":0}}"""))
    error should include("'id' cannot be read")
    error should include("'body' is invalid at quantity: expected >=1, got 0")
    message(call(tools, "t", """{"id":1}""")) should include("'body' is required")
    message(call(tools, "t", """{"id":1,"body":{"item":3}}""")) should include("'body' cannot be read")
    recording.requests shouldBe empty
  }

  "an error response" should "be a tool error with the status and the endpoint's error output" in {
    val recording = new Recording(status = 404, body = """{"message":"no order 9"}""")
    val result =
      call(registry(recording.backend, EndpointTools.tool("get_order", getOrder)), "get_order", """{"id":"9"}""")
    message(result) should include("""the service rejected the call with HTTP 404: {"message":"no order 9"}""")
  }

  "a server error" should "be a tool error with the status and the body, shortened" in {
    val recording = new Recording(status = 503, body = "x" * 3000)
    val result =
      call(registry(recording.backend, EndpointTools.tool("get_order", getOrder)), "get_order", """{"id":"9"}""")
    message(result) should include("the service failed with HTTP 503: xxx")
    message(result) should include("(1000 more characters)")
    val empty = new Recording(status = 500, body = "")
    message(
      call(registry(empty.backend, EndpointTools.tool("get_order", getOrder)), "get_order", """{"id":"9"}""")
    ) should
      endWith("the service failed with HTTP 500")
  }

  "a request that cannot be sent" should "be a tool error, not an exception" in {
    val failing = SttpBackendStub.synchronous.whenAnyRequest.thenRespondF(_ => throw new RuntimeException("refused"))
    val result  = call(registry(failing, EndpointTools.tool("get_order", getOrder)), "get_order", """{"id":"9"}""")
    message(result) should include("the request to GET https://orders.internal/api/orders/9 could not be sent: refused")
  }

  "a response that does not match the endpoint's output" should "be a tool error" in {
    val recording = new Recording(body = """{"unexpected":true}""")
    val result =
      call(registry(recording.backend, EndpointTools.tool("get_order", getOrder)), "get_order", """{"id":"9"}""")
    message(result) should include("with a body that does not match the endpoint's output")
  }

  "a response with no single declared body" should "be returned as JSON when it is JSON, else as text" in {
    val e = endpoint.get
      .in("status")
      .out(
        sttp.tapir.oneOf[String](
          oneOfVariant(sttp.model.StatusCode.Ok, stringBody),
          oneOfVariant(sttp.model.StatusCode.Accepted, stringBody)
        )
      )
    val asJson = new Recording(body = """{"ok":true}""")
    call(registry(asJson.backend, EndpointTools.tool("s", e)), "s", "{}").value shouldBe ujson.Obj("ok" -> true)
    val asText = new Recording(body = "fine")
    call(registry(asText.backend, EndpointTools.tool("s", e)), "s", "{}").value shouldBe ujson.Str("fine")
  }

  "a call with no arguments" should "accept null for a tool with no required parameter" in {
    val recording = new Recording(body = "")
    registry(recording.backend, EndpointTools.tool("ping", endpoint.get.in("ping")))
      .execute(ToolCallRequest("ping", ujson.Null))
      .value shouldBe ujson.Obj("status" -> 200)
  }
}
