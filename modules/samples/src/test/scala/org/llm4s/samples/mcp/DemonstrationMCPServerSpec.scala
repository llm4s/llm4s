package org.llm4s.samples.mcp

import org.llm4s.mcp.{ MCPServer, MCPServerOptions }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }

/**
 * `DemonstrationMCPServer`'s replies on the wire (#3200), with the checks `MCPServerJsonRpcWireSpec` makes of
 * `MCPServer`: `jsonrpc` on every reply, each id as it was sent (a number stays a number), and only the
 * capabilities the server implements.
 */
class DemonstrationMCPServerSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val server = new MCPServer(
    MCPServerOptions(0, "/mcp", "Demo", "1.0"),
    DemonstrationMCPServer.demoTools.fold(e => fail(e.formatted), identity),
    DemonstrationMCPServer.annotations
  )
  private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  override def beforeAll(): Unit = server.start().fold(e => throw e, _ => ())
  override def afterAll(): Unit  = server.stop()

  private def post(body: ujson.Value, headers: (String, String)*): HttpResponse[String] = {
    val builder = HttpRequest
      .newBuilder(URI.create(s"http://127.0.0.1:${server.boundPort}/mcp"))
      .header("Content-Type", "application/json")
      .header("Accept", "application/json, text/event-stream")
    headers.foreach { case (k, v) => builder.header(k, v) }
    http.send(
      builder.POST(HttpRequest.BodyPublishers.ofString(body.render())).build(),
      HttpResponse.BodyHandlers.ofString()
    )
  }

  private def request(id: ujson.Value, method: String, params: ujson.Value): ujson.Value =
    ujson.Obj("jsonrpc" -> "2.0", "id" -> id, "method" -> method, "params" -> params)

  "DemonstrationMCPServer" should "answer with jsonrpc, the id as sent, and only the tools capability" in {
    val init = post(
      request(ujson.Num(1), "initialize", ujson.Obj("protocolVersion" -> "2025-06-18", "capabilities" -> ujson.Obj()))
    )
    val reply = ujson.read(init.body())
    reply("jsonrpc").str shouldBe "2.0"
    reply("id") shouldBe ujson.Num(1)
    reply("result")("capabilities") shouldBe ujson.Obj("tools" -> ujson.Obj("listChanged" -> false))

    val session = init.headers().firstValue("mcp-session-id").orElseThrow()
    val call = ujson.read(
      post(
        request(
          ujson.Num(2),
          "tools/call",
          ujson.Obj(
            "name"      -> "currency_convert",
            "arguments" -> ujson.Obj("amount" -> 10, "from_currency" -> "USD", "to_currency" -> "EUR")
          )
        ),
        "mcp-session-id"       -> session,
        "MCP-Protocol-Version" -> "2025-06-18"
      ).body()
    )
    call("jsonrpc").str shouldBe "2.0"
    call("id") shouldBe ujson.Num(2)
    call("result")("structuredContent")("converted_amount").num shouldBe 8.5

    val tools = ujson.read(
      post(
        request(ujson.Str("t"), "tools/list", ujson.Obj()),
        "mcp-session-id"       -> session,
        "MCP-Protocol-Version" -> "2025-06-18"
      ).body()
    )
    tools("id") shouldBe ujson.Str("t")
    tools("result")("tools").arr.map(_("name").str).toSet shouldBe Set("get_weather", "currency_convert")
    tools("result")("tools").arr.map(_("annotations")("readOnlyHint").bool).toSet shouldBe Set(true)
  }

  it should "answer an unreadable id with null" in {
    val reply = ujson.read(post(ujson.Obj("jsonrpc" -> "2.0", "id" -> ujson.Obj(), "method" -> "ping")).body())
    reply("jsonrpc").str shouldBe "2.0"
    reply("id") shouldBe ujson.Null
  }
}
