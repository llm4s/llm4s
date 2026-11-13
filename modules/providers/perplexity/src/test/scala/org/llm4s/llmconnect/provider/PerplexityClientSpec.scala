package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, ProcessingError, RateLimitError, ServiceError, ValidationError }
import org.llm4s.http.HttpResponse
import org.llm4s.llmconnect.model.*
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** The request `PerplexityClient.complete` sends, and how it reads a reply, against Perplexity's documented shapes. */
class PerplexityClientSpec extends AnyWordSpec with Matchers:

  import PerplexityFixtures.{ *, given }

  private val hello = Conversation(Seq(UserMessage("Hello")))

  private def answering(body: String): StubHttp = new StubHttp(respond = _ => ok(body))

  private def completeWith(
    body: String,
    conversation: Conversation = hello,
    options: CompletionOptions = CompletionOptions()
  ): (StubHttp, Either[org.llm4s.error.LLMError, Completion]) = {
    val http = answering(body)
    http -> client(http).complete(conversation, options)
  }

  private val weatherTool: ToolFunction[?, ?] = ToolBuilder[Map[String, Any], String](
    "get_weather",
    "Weather for a city",
    Schema.`object`[Map[String, Any]]("args").withProperty(Schema.property("city", Schema.string("the city")))
  ).withHandler(_ => Right("sunny")).buildSafe().fold(e => fail(e.toString), identity)

  "the request" should {

    "post to /v1/agent with the bearer key, the model, input items and the web_search tool" in {
      val (http, _) = completeWith(response(s"[${textMessage("ok")}]"))
      val seen      = http.requests.head
      seen.url shouldBe "https://api.perplexity.ai/v1/agent"
      seen.headers("Authorization") shouldBe "Bearer pplx-test-key"
      seen.headers("Accept") shouldBe "application/json"
      val body = http.lastBody
      body("model").str shouldBe "perplexity/sonar"
      body("input") shouldBe ujson.Arr(ujson.Obj("type" -> "message", "role" -> "user", "content" -> "Hello"))
      body("tools") shouldBe ujson.Arr(ujson.Obj("type" -> "web_search"))
      body("temperature").num shouldBe CompletionOptions().temperature
      body.obj.keySet should contain noneOf ("stream", "max_output_tokens", "top_p", "reasoning", "instructions")
    }

    "join a base URL that already ends in /v1, and send the section's headers without letting them replace the key" in {
      val http = answering(response(s"[${textMessage("ok")}]"))
      client(
        http,
        config(
          baseUrl = "https://proxy.example/v1/",
          headers = Map("X-Team" -> "research", "Authorization" -> "Bearer wrong")
        )
      ).complete(hello, CompletionOptions())
      http.requests.head.url shouldBe "https://proxy.example/v1/agent"
      http.requests.head.headers("X-Team") shouldBe "research"
      http.requests.head.headers("Authorization") shouldBe "Bearer pplx-test-key"
    }

    "send no tools when web search is off and none are given" in {
      val http = answering(response(s"[${textMessage("ok")}]"))
      client(http, config(webSearch = false)).complete(hello, CompletionOptions())
      http.lastBody.obj.keySet should not contain "tools"
    }

    "send system messages as instructions and the whole tool loop as input items" in {
      val call = ToolCall("call_1", "get_weather", ujson.Obj("city" -> "Paris"))
      val conversation = Conversation(
        Seq(
          SystemMessage("Be brief."),
          UserMessage("Weather?"),
          AssistantMessage("Checking.", Seq(call)),
          ToolMessage("""{"temp":20}""", "call_1"),
          SystemMessage("Cite sources."),
          UserMessage(Vector(ContentPart.Text("one"), ContentPart.Text("two")))
        )
      )
      val (http, _) = completeWith(response(s"[${textMessage("ok")}]"), conversation)
      val body      = http.lastBody
      body("instructions").str shouldBe "Be brief.\n\nCite sources."
      body("input") shouldBe ujson.Arr(
        ujson.Obj("type" -> "message", "role" -> "user", "content"      -> "Weather?"),
        ujson.Obj("type" -> "message", "role" -> "assistant", "content" -> "Checking."),
        ujson.Obj(
          "type"      -> "function_call",
          "call_id"   -> "call_1",
          "name"      -> "get_weather",
          "arguments" -> """{"city":"Paris"}"""
        ),
        ujson.Obj(
          "type"    -> "function_call_output",
          "call_id" -> "call_1",
          "output"  -> """{"temp":20}""",
          "name"    -> "get_weather"
        ),
        ujson.Obj(
          "type" -> "message",
          "role" -> "user",
          "content" -> ujson.Arr(
            ujson.Obj("type" -> "input_text", "text" -> "one"),
            ujson.Obj("type" -> "input_text", "text" -> "two")
          )
        )
      )
    }

    "send a tool result for an unknown call without a name, and drop an empty assistant turn" in {
      val conversation =
        Conversation(Seq(UserMessage("q"), AssistantMessage(contentOpt = None), ToolMessage("r", "call_x")))
      val (http, _) = completeWith(response(s"[${textMessage("ok")}]"), conversation)
      http.lastBody("input").arr.map(_("type").str) shouldBe Seq("message", "function_call_output")
      http.lastBody("input")(1).obj.keySet should not contain "name"
    }

    "send function tools in the flat Responses form beside web_search" in {
      val (http, _) =
        completeWith(response(s"[${textMessage("ok")}]"), options = CompletionOptions().withTools(Seq(weatherTool)))
      val tools = http.lastBody("tools").arr
      tools.head shouldBe ujson.Obj("type" -> "web_search")
      tools(1)("type").str shouldBe "function"
      tools(1)("name").str shouldBe "get_weather"
      tools(1)("description").str shouldBe "Weather for a city"
      tools(1)("parameters")("properties").obj.keySet shouldBe Set("city")
      tools(1).obj.keySet should not contain "function"
    }

    "send max tokens, top_p, reasoning and a JSON schema response format" in {
      val schema = ujson.Obj("type" -> "object")
      val options = CompletionOptions()
        .withMaxTokens(300)
        .withTopP(0.5)
        .withReasoning(ReasoningEffort.High)
        .withResponseFormat(ResponseFormat.JsonSchema(schema, name = "answer", strict = true))
      val (http, _) = completeWith(response(s"[${textMessage("{}")}]"), options = options)
      val body      = http.lastBody
      body("max_output_tokens").num shouldBe 300
      body("top_p").num shouldBe 0.5
      body("reasoning") shouldBe ujson.Obj("effort" -> "high")
      body("response_format") shouldBe ujson.Obj(
        "type"        -> "json_schema",
        "json_schema" -> ujson.Obj("name" -> "answer", "schema" -> schema, "strict" -> true)
      )
    }

    "map every reasoning effort to its own name, all of which the Agent API takes" in {
      PerplexityClient.reasoningEffortValue(ReasoningEffort.Minimal) shouldBe "minimal"
      PerplexityClient.reasoningEffortValue(ReasoningEffort.Low) shouldBe "low"
      PerplexityClient.reasoningEffortValue(ReasoningEffort.Medium) shouldBe "medium"
      PerplexityClient.reasoningEffortValue(ReasoningEffort.High) shouldBe "high"
      PerplexityClient.reasoningEffortValue(ReasoningEffort.XHigh) shouldBe "xhigh"
      PerplexityClient.reasoningEffortValue(ReasoningEffort.Max) shouldBe "max"
    }

    "refuse plain JSON mode before sending anything" in {
      val (http, result) =
        completeWith(
          response(s"[${textMessage("ok")}]"),
          options = CompletionOptions().withResponseFormat(ResponseFormat.Json)
        )
      result.left.toOption.get shouldBe a[ValidationError]
      result.left.toOption.get.message should include("JsonSchema")
      http.requests shouldBe empty
    }

    "send an anthropic/ model the reserve as max_output_tokens when the request sets none" in {
      val http = answering(response(s"[${textMessage("ok")}]"))
      client(http, config(model = "anthropic/claude-sonnet-5-5", reserveCompletion = 2048))
        .complete(hello, CompletionOptions())
      http.lastBody("max_output_tokens").num shouldBe 2048
      client(http, config(model = "anthropic/claude-sonnet-5-5", reserveCompletion = 0))
        .complete(hello, CompletionOptions())
      http.lastBody("max_output_tokens").num shouldBe 4096
      client(http, config(model = "anthropic/claude-sonnet-5-5")).complete(hello, CompletionOptions().withMaxTokens(77))
      http.lastBody("max_output_tokens").num shouldBe 77
    }

    "send arguments as a JSON string, whatever form a call holds them in" in {
      PerplexityClient.requestArguments(ujson.Obj("a" -> 1)) shouldBe """{"a":1}"""
      PerplexityClient.requestArguments(ujson.Str("""{"a":1}""")) shouldBe """{"a":1}"""
      PerplexityClient.requestArguments(ujson.Str("not json")) shouldBe "{}"
      PerplexityClient.requestArguments(ujson.Arr(1)) shouldBe "{}"
    }
  }

  "the reply" should {

    "give the text, citations from annotations then search results, usage and the reported cost" in {
      val annotations =
        """[{"type":"url_citation","url":"https://b.example/2","title":"B!","start_index":3,"end_index":9},{"type":"file_citation","url":"x"}]"""
      val usage =
        """{"input_tokens":100,"output_tokens":40,"total_tokens":140,"input_tokens_details":{"cached_tokens":64,"cache_creation_input_tokens":8},"output_tokens_details":{"reasoning_tokens":12},"cost":{"currency":"USD","input_cost":0.1,"output_cost":0.2,"total_cost":0.42}}"""
      val (_, result) =
        completeWith(response(s"[$searchResults,${textMessage("Answer [web:1]", annotations)}]", usage = usage))
      val completion = result.fold(e => fail(e.message), identity)
      completion.content shouldBe "Answer [web:1]"
      completion.message.content shouldBe "Answer [web:1]"
      completion.id shouldBe "resp_1"
      completion.model shouldBe "perplexity/sonar"
      completion.created.getEpochSecond shouldBe 1779391454L
      completion.citations shouldBe List(
        Citation(url = "https://b.example/2", title = Some("B!"), startIndex = Some(3), endIndex = Some(9)),
        Citation(url = "https://a.example/1", title = Some("A"), citedText = Some("alpha"))
      )
      completion.usage shouldBe Some(
        TokenUsage(100, 40, 140, thinkingTokens = Some(12), cachedTokens = Some(64), cacheCreationTokens = Some(8))
      )
      completion.estimatedCost shouldBe Some(0.42)
      completion.toolCalls shouldBe empty
      completion.message.thinking shouldBe empty
    }

    "read cache reads when cached_tokens is absent, and no cost in another currency or without one" in {
      PerplexityClient
        .parseUsage(
          ujson
            .read("""{"input_tokens":1,"output_tokens":2,"input_tokens_details":{"cache_read_input_tokens":5}}""")
            .obj
        )
        .flatMap(_.cachedTokens) shouldBe Some(5)
      PerplexityClient
        .parseUsage(ujson.read("""{"input_tokens":1,"output_tokens":2}""").obj)
        .map(_.totalTokens) shouldBe Some(3)
      PerplexityClient.parseUsage(ujson.read("""{"output_tokens":2}""").obj) shouldBe None
      PerplexityClient.parseCost(ujson.read("""{"cost":{"currency":"EUR","total_cost":1.0}}""").obj) shouldBe None
      PerplexityClient.parseCost(ujson.read("""{"input_tokens":1}""").obj) shouldBe None
    }

    "fall back to the configured model, a generated id and no usage when the response omits them" in {
      val (_, result) =
        completeWith(
          """{"object":"response","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"x"}]}]}"""
        )
      val completion = result.fold(e => fail(e.message), identity)
      completion.model shouldBe "perplexity/sonar"
      completion.id should not be empty
      completion.usage shouldBe None
      completion.citations shouldBe empty
    }

    "join the text of several messages and parts, and skip parts of another type" in {
      val two =
        """{"type":"message","content":[{"type":"output_text","text":"a"},{"type":"refusal","text":"no"},{"type":"output_text","text":"b"}]}"""
      val (_, result) = completeWith(response(s"[$two,${textMessage("c")}]"))
      result.map(_.content) shouldBe Right("abc")
    }

    "return function calls as tool calls, keeping a thought signature for the call it came with" in {
      val output =
        s"[${functionCall("call_a", "get_weather", """{"city":"Rome"}""", Some("c2lnbmF0dXJl"))},${functionCall("call_b", "get_weather", "")}]"
      val (_, result) = completeWith(response(output))
      val completion  = result.fold(e => fail(e.message), identity)
      completion.toolCalls shouldBe List(
        ToolCall("call_a", "get_weather", ujson.Obj("city" -> "Rome")),
        ToolCall("call_b", "get_weather", ujson.Obj())
      )
      completion.message.toolCalls shouldBe completion.toolCalls
      completion.message.thinking shouldBe Seq(
        ThinkingBlock.Opaque("perplexity", """{"call":"call_a","sig":"c2lnbmF0dXJl"}""")
      )
      completion.message.thinkingBinding should not be empty
    }

    "send a kept thought signature back on its call to the same model, and not to another" in {
      val http  = answering(response(s"[${functionCall("call_a", "get_weather", "{}", Some("SIG"))}]"))
      val first = client(http).complete(hello, CompletionOptions()).fold(e => fail(e.message), identity)
      val loop  = Conversation(Seq(UserMessage("Hello"), first.message, ToolMessage("sunny", "call_a")))
      client(http).complete(loop, CompletionOptions())
      http.lastBody("input")(1)("thought_signature").str shouldBe "SIG"
      client(http, config(model = "openai/gpt-5.6-sol")).complete(loop, CompletionOptions())
      http.lastBody("input")(1).obj.keySet should not contain "thought_signature"
    }

    "fail on a function call without a name or call_id, or with arguments that are not an object" in {
      def callError(item: String) =
        completeWith(response(s"[$item]"))._2.left.toOption.get
      callError("""{"type":"function_call","call_id":"c","arguments":"{}"}""") shouldBe a[ProcessingError]
      callError("""{"type":"function_call","name":"n","arguments":"{}"}""") shouldBe a[ProcessingError]
      callError("""{"type":"function_call","call_id":"c","name":"n","arguments":"[1]"}""") shouldBe a[ProcessingError]
      callError("""{"type":"function_call","call_id":"c","name":"n","arguments":7}""") shouldBe a[ProcessingError]
      completeWith(response("""[{"type":"function_call","call_id":"c","name":"n","arguments":{"k":1}}]"""))._2
        .map(_.toolCalls.head.arguments) shouldBe Right(ujson.Obj("k" -> 1))
      completeWith(response("""[{"type":"function_call","call_id":"c","name":"n"}]"""))._2
        .map(_.toolCalls.head.arguments) shouldBe Right(ujson.Obj())
    }

    "return an incomplete response as an answer cut short" in {
      completeWith(response(s"[${textMessage("partial")}]", status = "incomplete"))._2.map(_.content) shouldBe Right(
        "partial"
      )
    }

    "fail a failed response with Perplexity's code and message, and any other unfinished status" in {
      val failed =
        """{"id":"r","status":"failed","output":[],"error":{"code":"model_error","message":"upstream broke"}}"""
      val error = completeWith(failed)._2.left.toOption.get
      error shouldBe a[ServiceError]
      error.message should include("model_error: upstream broke")
      completeWith("""{"status":"failed","output":[]}""")._2.left.toOption.get.message should include(
        "the response failed"
      )
      completeWith(response("[]", status = "in_progress"))._2.left.toOption.get.message should include("in_progress")
    }

    "fail a body that is not a response object or has no output" in {
      completeWith("[1]")._2.left.toOption.get shouldBe a[ValidationError]
      completeWith("""{"status":"completed"}""")._2.left.toOption.get.message should include("output")
      completeWith("not json")._2.isLeft shouldBe true
    }
  }

  "an HTTP error" should {

    "map to a typed error with Perplexity's message" in {
      def statusError(status: Int) = {
        val http = new StubHttp(respond =
          _ => Right(HttpResponse(status, """{"error":{"message":"nope","type":"invalid_request_error"}}"""))
        )
        client(http).complete(hello, CompletionOptions()).left.toOption.get
      }
      statusError(401) shouldBe an[AuthenticationError]
      statusError(429) shouldBe a[RateLimitError]
      statusError(400).message should include("nope")
    }

    "keep the API key out of the error" in {
      val http =
        new StubHttp(respond = _ => Right(HttpResponse(400, """{"error":{"message":"bad key pplx-test-key"}}""")))
      (client(http).complete(hello, CompletionOptions()).left.toOption.get.message should not).include("pplx-test-key")
    }
  }

  "the client" should {

    "report its config's context window and reserve, and close its HTTP client" in {
      val http = new StubHttp()
      val c    = client(http)
      c.getContextWindow() shouldBe 128000
      c.getReserveCompletion() shouldBe 4096
      c.close()
      http.closed shouldBe true
    }

    "build through the companion" in {
      PerplexityClient(config()).isRight shouldBe true
    }
  }
