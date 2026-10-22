package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.llm4s.llmconnect.config.AnthropicConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters._

/**
 * Web search (#1216): with `webSearch` set, every request carries Anthropic's server-side `web_search` tool, and the
 * sources the answer's text blocks cite arrive on `Completion.citations`, from `complete` and from a stream.
 */
class AnthropicWebSearchSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val testConfig = AnthropicConfig(
    apiKey = "test-key",
    model = "claude-sonnet-4-5",
    baseUrl = "https://api.anthropic.com",
    contextWindow = 200000,
    reserveCompletion = 4096
  )

  private val conversation = Conversation(Seq(UserMessage("What is new in Scala?")))

  /** A fake `/v1/messages`, answering every request with `response` and recording its body. */
  private def withServer(response: (String, String))(test: (String, () => List[ujson.Value]) => Any): Unit = {
    val seen   = new ConcurrentLinkedQueue[ujson.Value]()
    val server = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    server.createContext(
      "/v1/messages",
      (exchange: HttpExchange) => {
        seen.add(ujson.read(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)))
        val bytes = response._2.getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.add("Content-Type", response._1)
        exchange.sendResponseHeaders(200, bytes.length)
        exchange.getResponseBody.write(bytes)
        exchange.close()
      }
    )
    server.start()
    try test(s"http://localhost:${server.getAddress.getPort}", () => seen.asScala.toList)
    finally server.stop(0)
  }

  private val webCitation =
    """{"type":"web_search_result_location","url":"https://scala-lang.org/news","title":"Scala News",
      |"cited_text":"Scala 3.8 is out.","encrypted_index":"enc-1"}""".stripMargin
  private val searchResultCitation =
    """{"type":"search_result_location","source":"https://docs.example.com/kb/1","title":"KB 1",
      |"cited_text":"From the knowledge base.","search_result_index":0,"start_block_index":0,"end_block_index":0}""".stripMargin
  private val documentCitation =
    """{"type":"char_location","cited_text":"A document.","document_index":0,"document_title":"Doc",
      |"start_char_index":0,"end_char_index":11}""".stripMargin

  private val searchedReply =
    "application/json" ->
      s"""{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-5",
         |"content":[
         |  {"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{"query":"Scala news"}},
         |  {"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[
         |    {"type":"web_search_result","url":"https://scala-lang.org/news","title":"Scala News",
         |     "encrypted_content":"enc","page_age":null}]},
         |  {"type":"text","text":"Here is what I found. "},
         |  {"type":"text","text":"Scala 3.8 is out.","citations":[$webCitation]},
         |  {"type":"text","text":" Also,","citations":[$searchResultCitation,$documentCitation]},
         |  {"type":"text","text":" again.","citations":[$webCitation]}
         |],
         |"stop_reason":"end_turn","stop_sequence":null,
         |"usage":{"input_tokens":10,"output_tokens":20,"server_tool_use":{"web_search_requests":1}}}""".stripMargin

  private val expected = List(
    Citation(url = "https://scala-lang.org/news", title = Some("Scala News"), citedText = Some("Scala 3.8 is out.")),
    Citation(url = "https://docs.example.com/kb/1", title = Some("KB 1"), citedText = Some("From the knowledge base."))
  )

  private val streamedReply = {
    val events = Seq(
      "message_start" ->
        """{"type":"message_start","message":{"id":"msg_s","type":"message","role":"assistant","content":[],"model":"claude-sonnet-4-5","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":8,"output_tokens":0}}}""",
      "content_block_start" ->
        """{"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{}}}""",
      "content_block_delta" ->
        """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"Scala news\"}"}}""",
      "content_block_stop" -> """{"type":"content_block_stop","index":0}""",
      "content_block_start" ->
        """{"type":"content_block_start","index":1,"content_block":{"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[]}}""",
      "content_block_stop" -> """{"type":"content_block_stop","index":1}""",
      "content_block_start" ->
        """{"type":"content_block_start","index":2,"content_block":{"type":"text","text":"","citations":[]}}""",
      "content_block_delta" ->
        s"""{"type":"content_block_delta","index":2,"delta":{"type":"citations_delta","citation":${webCitation
            .replace("\n", "")}}}""",
      "content_block_delta" ->
        """{"type":"content_block_delta","index":2,"delta":{"type":"text_delta","text":"Scala 3.8 is out."}}""",
      "content_block_delta" ->
        s"""{"type":"content_block_delta","index":2,"delta":{"type":"citations_delta","citation":${searchResultCitation
            .replace("\n", "")}}}""",
      "content_block_delta" ->
        s"""{"type":"content_block_delta","index":2,"delta":{"type":"citations_delta","citation":${webCitation
            .replace("\n", "")}}}""",
      "content_block_stop" -> """{"type":"content_block_stop","index":2}""",
      "message_delta" ->
        """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":20}}""",
      "message_stop" -> """{"type":"message_stop"}"""
    )
    "text/event-stream" -> events.map((event, data) => s"event: $event\ndata: $data\n\n").mkString
  }

  private def tools(body: ujson.Value): Seq[ujson.Value] = body.obj.get("tools").map(_.arr.toSeq).getOrElse(Nil)

  "a client with webSearch" should "send the web_search tool beside the caller's tools" in {
    withServer(searchedReply) { (baseUrl, seen) =>
      val tool = org.llm4s.toolapi
        .ToolBuilder[Map[String, Any], String](
          "lookup",
          "Looks up",
          org.llm4s.toolapi.Schema
            .`object`[Map[String, Any]]("args")
            .withProperty(org.llm4s.toolapi.Schema.property("q", org.llm4s.toolapi.Schema.string("Query")))
        )
        .withHandler(_ => Right("x"))
        .buildSafe()
        .fold(e => fail(e.toString), identity)
      new AnthropicClient(testConfig.withBaseUrl(baseUrl).withWebSearch(true))
        .complete(conversation, CompletionOptions().withTools(Seq(tool)))
        .isRight shouldBe true
      val sent = tools(seen().head)
      sent.map(_("name").str) shouldBe Seq("lookup", "web_search")
      sent.last("type").str shouldBe "web_search_20250305"
    }
  }

  it should "put each cited web or search-result source on Completion.citations once, skipping document citations" in {
    withServer(searchedReply) { (baseUrl, _) =>
      val completion = new AnthropicClient(testConfig.withBaseUrl(baseUrl).withWebSearch(true))
        .complete(conversation, CompletionOptions())
        .fold(e => fail(e.message), identity)
      completion.citations shouldBe expected
      completion.citations.foreach(_.hasSpan shouldBe false)
      completion.content shouldBe "Here is what I found. Scala 3.8 is out. Also, again."
      completion.toolCalls shouldBe empty
    }
  }

  it should "read the citations of a streamed answer, without turning the search into a tool call" in {
    withServer(streamedReply) { (baseUrl, _) =>
      val chunks = List.newBuilder[StreamedChunk]
      val completion = new AnthropicClient(testConfig.withBaseUrl(baseUrl).withWebSearch(true))
        .streamComplete(conversation, CompletionOptions(), c => chunks += c)
        .fold(e => fail(e.message), identity)
      completion.citations shouldBe expected
      completion.content shouldBe "Scala 3.8 is out."
      completion.toolCalls shouldBe empty
      chunks.result().flatMap(_.toolCall) shouldBe empty
    }
  }

  "a client without webSearch" should "send no web_search tool" in {
    withServer(searchedReply) { (baseUrl, seen) =>
      new AnthropicClient(testConfig.withBaseUrl(baseUrl))
        .complete(conversation, CompletionOptions())
        .isRight shouldBe true
      tools(seen().head) shouldBe empty
    }
  }

  "AnthropicConfig" should "keep web search off unless set, and show it in toString" in {
    testConfig.webSearch shouldBe false
    testConfig.withWebSearch(true).webSearch shouldBe true
    testConfig.withWebSearch(true).toString should include("webSearch=true")
  }
}
