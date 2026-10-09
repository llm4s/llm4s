package org.llm4s.llmconnect.smoke

import org.llm4s.error.{ AuthenticationError, ValidationError }
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient }
import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud
import org.llm4s.llmconnect.config.{ ContextWindowResolver, WatsonXConfig }
import org.llm4s.llmconnect.model.*
import org.llm4s.llmconnect.provider.WatsonXClient
import org.llm4s.model.ModelRegistryService
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction }
import org.scalatest.{ BeforeAndAfterAll, EitherValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ BufferedReader, InputStreamReader }
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.{ Try, Using }

/**
 * Assumption probe for `llm4s-watsonx`: the first run against a real IBM account.
 *
 * `WatsonXClient` has never talked to the real service. Its request and response shapes come from IBM's
 * public pages and from IBM's own open-source clients (see the client's Scaladoc), which shows what IBM's
 * clients send but is not a live check. Each test here checks one of those details against the service and
 * is named after it. The suite prints a table at the end saying which held, so the answer is mechanical:
 * `held` needs nothing, `NOT HELD` names the assumption in `WatsonXClient` to change. Rows marked `(info)`
 * check things the client does not depend on, to settle the docs; a failure there needs no client change.
 *
 * Without credentials every test is cancelled, so nothing is sent to IBM and the table is all `skipped`.
 *
 * == Running it ==
 *
 * {{{
 * export WATSONX_API_KEY=...          # IBM Cloud API key
 * export WATSONX_PROJECT_ID=...       # or WATSONX_SPACE_ID
 * export WATSONX_BASE_URL=https://eu-de.ml.cloud.ibm.com   # optional; default us-south
 * export WATSONX_MODEL=ibm/granite-4-h-small               # optional; must support tool calling
 * sbt "it/testOnly org.llm4s.llmconnect.smoke.WatsonXAssumptionProbeSpec"
 * }}}
 *
 * Cost: a few dozen requests of at most a few hundred tokens each. See `modules/it/README.md` for how to
 * read the report and what to send back.
 *
 * Tier: `@Cloud` - `sbt testSmoke`.
 */
@Cloud
class WatsonXAssumptionProbeSpec extends AnyFlatSpec with Matchers with EitherValues with BeforeAndAfterAll {

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private def env(name: String): Option[String] = Option(System.getenv(name)).filter(_.nonEmpty)

  private val apiKey    = env("WATSONX_API_KEY")
  private val projectId = env("WATSONX_PROJECT_ID")
  private val spaceId   = env("WATSONX_SPACE_ID").filter(_ => projectId.isEmpty)
  private val baseUrl   = env("WATSONX_BASE_URL").getOrElse(WatsonXConfig.DEFAULT_BASE_URL)
  // The model IBM's own tool-calling example uses (watsonx-ai-node-sdk, examples/src/sdk/example_chat_tools.ts).
  private val model = env("WATSONX_MODEL").getOrElse("ibm/granite-4-h-small")

  private val PlainCompletion     = "plain completion returns text and token usage"
  private val Roles               = "system and user messages are accepted in `messages`"
  private val TokenLimit          = "`max_tokens` limits the output length"
  private val FinishReasons       = "`finish_reason` is `stop` at a natural end and `length` at the token limit"
  private val MaxCompletionTokens = "(info) `max_completion_tokens`, the non-deprecated name, is accepted"
  private val ToolCallShape       = "a tool call has a server-sent `id` and `arguments` as a JSON string"
  private val ToolChoiceAuto      = "`tool_choice_option: \"auto\"` is accepted together with `tools`"
  private val StrictAccepted      = "(info) `strict` inside a tool definition is accepted"
  private val ToolRoundTrip       = "an assistant turn of only tool calls, then a `tool` message, is accepted"
  private val StreamShape         = "a stream delivers text, ends with a `finish_reason` and reports usage"
  private val StreamUsageChunk    = "(info) the stream's usage arrives in a chunk with no `choices`"
  private val BadKey              = "a wrong API key is an AuthenticationError that never contains the key"
  private val BadRequest          = "a malformed request is a 400 that the client reports as a ValidationError"

  private val report = new ProbeReport(
    s"watsonx ($model)",
    Seq(
      PlainCompletion,
      Roles,
      TokenLimit,
      FinishReasons,
      MaxCompletionTokens,
      ToolCallShape,
      ToolChoiceAuto,
      StrictAccepted,
      ToolRoundTrip,
      StreamShape,
      StreamUsageChunk,
      BadKey,
      BadRequest
    )
  )

  override def afterAll(): Unit = {
    println(report.render())
    super.afterAll()
  }

  private def requireAccount(): Unit =
    Tier.require(
      apiKey.isDefined && (projectId.isDefined || spaceId.isDefined),
      "WATSONX_API_KEY and WATSONX_PROJECT_ID (or WATSONX_SPACE_ID) not set"
    )

  private def config(key: String): WatsonXConfig =
    WatsonXConfig
      .fromValues(modelName = model, apiKey = key, projectId = projectId, spaceId = spaceId, baseUrl = baseUrl)
      .value

  private def withClient[A](key: String = apiKey.getOrElse(""))(f: WatsonXClient => A): A =
    Using.resource(new WatsonXClient(config(key)))(f)

  private def say(text: String): Conversation = Conversation(Seq(UserMessage(text)))

  private val addTool: ToolFunction[?, ?] = ToolBuilder[Map[String, Any], String](
    "add",
    "Adds two integers",
    Schema
      .`object`[Map[String, Any]]("params")
      .withProperty(Schema.property("a", Schema.integer("first")))
      .withProperty(Schema.property("b", Schema.integer("second")))
  ).withHandler(_ => Right("46")).buildSafe().fold(e => fail(e.toString), identity)

  private val askText  = "What is 12 + 34? Use the add tool."
  private val askToAdd = say(askText)

  // --- raw requests: for the wire details the client does not let a caller vary -------------------------------

  private def withRaw[A](f: Raw => A): A =
    Using.resource(Llm4sHttpClient.create())(http => f(new Raw(config(apiKey.get), http)))

  final private class Raw(cfg: WatsonXConfig, http: Llm4sHttpClient) {
    private lazy val token: String = {
      val form =
        s"grant_type=urn%3Aibm%3Aparams%3Aoauth%3Agrant-type%3Aapikey&apikey=${URLEncoder.encode(cfg.apiKey, "UTF-8")}"
      val headers  = Map("Content-Type" -> "application/x-www-form-urlencoded", "Accept" -> "application/json")
      val response = http.post(cfg.iamUrl, headers, form, 30.seconds).value
      withClue(s"IAM token exchange answered ${response.statusCode}")(response.statusCode shouldBe 200)
      ujson.read(response.body)("access_token").str
    }

    private def headers(accept: String): Map[String, String] =
      Map("Content-Type" -> "application/json", "Authorization" -> s"Bearer $token", "Accept" -> accept)

    private def url(path: String): String = s"${cfg.baseUrl}$path?version=${cfg.apiVersion}"

    /** The body every request starts from; `extra` adds or overrides fields. */
    def body(messages: ujson.Value, extra: (String, ujson.Value)*): ujson.Obj = {
      val b = ujson.Obj("model_id" -> cfg.model, "messages" -> messages)
      cfg.spaceId match {
        case Some(space) => b("space_id") = space
        case None        => b("project_id") = cfg.projectId
      }
      extra.foreach { case (name, value) => b(name) = value }
      b
    }

    /** Text safe to print: the API key and the bearer token removed. */
    def scrub(text: String): String = Seq(cfg.apiKey, token).foldLeft(text)(_.replace(_, "[REDACTED]")).take(600)

    def chat(request: ujson.Obj): HttpResponse =
      http.post(url("/ml/v1/text/chat"), headers("application/json"), request.render(), 90.seconds).value

    /** The `data:` events of a streamed reply, parsed; `[DONE]` is dropped. */
    def stream(request: ujson.Obj): (Int, Seq[ujson.Value]) = {
      val response =
        http.postStream(url("/ml/v1/text/chat_stream"), headers("text/event-stream"), request.render(), 2.minutes).value
      Using.resource(new BufferedReader(new InputStreamReader(response.body, StandardCharsets.UTF_8))) { reader =>
        val events = reader.lines().iterator().asScala.map(_.trim).filter(_.startsWith("data:")).map(_.drop(5).trim)
        (response.statusCode, events.filter(e => e.nonEmpty && e != "[DONE]").map(ujson.read(_)).toList)
      }
    }
  }

  private def ok(raw: Raw, response: HttpResponse): ujson.Value = {
    withClue(s"HTTP ${response.statusCode}: ${raw.scrub(response.body)}\n")(response.statusCode shouldBe 200)
    ujson.read(response.body)
  }

  private val addToolJson: ujson.Value = ujson.Arr(
    ujson.Obj(
      "type" -> "function",
      "function" -> ujson.Obj(
        "name"        -> "add",
        "description" -> "Adds two integers",
        "parameters" -> ujson.Obj(
          "type"       -> "object",
          "properties" -> ujson.Obj("a" -> ujson.Obj("type" -> "integer"), "b" -> ujson.Obj("type" -> "integer")),
          "required"   -> ujson.Arr("a", "b")
        )
      )
    )
  )

  private def userMessage(text: String): ujson.Value = ujson.Arr(ujson.Obj("role" -> "user", "content" -> text))

  // --- the probes ----------------------------------------------------------------------------------------------

  "watsonx" should s"probe: $PlainCompletion" in {
    report.probe(PlainCompletion, "WatsonXClient.parseCompletion / parseUsage") {
      requireAccount()
      val completion = withClient()(_.complete(say("Say hi in one word"), CompletionOptions().withMaxTokens(20))).value
      completion.content should not be empty
      completion.usage.map(_.promptTokens).getOrElse(0) should be > 0
      completion.usage.map(_.completionTokens).getOrElse(0) should be > 0
    }
  }

  it should s"probe: $Roles" in {
    report.probe(Roles, "WatsonXClient.encodeMessages: the roles it sends") {
      requireAccount()
      val conversation = Conversation(Seq(SystemMessage("Answer with the single word: pong"), UserMessage("ping")))
      withClient()(_.complete(conversation, CompletionOptions().withMaxTokens(10))).value.content should not be empty
    }
  }

  it should s"probe: $TokenLimit" in {
    report.probe(TokenLimit, "WatsonXClient.createRequestBody: the field it names the limit with (max_tokens)") {
      requireAccount()
      val completion = withClient()(_.complete(say("Count from 1 to 100"), CompletionOptions().withMaxTokens(5))).value
      withClue("the service ignored the limit, so the field name is probably wrong: ") {
        completion.usage.map(_.completionTokens).getOrElse(Int.MaxValue) should be <= 5
      }
    }
  }

  it should s"probe: $FinishReasons" in {
    report.probe(FinishReasons, "WatsonXClient.ErrorFinishReasons / normalizeFinishReason") {
      requireAccount()
      withRaw { raw =>
        def reason(request: ujson.Obj): String =
          ok(raw, raw.chat(request))("choices")(0)("finish_reason").str
        reason(raw.body(userMessage("Say hi in one word"), "max_tokens" -> ujson.Num(20))) shouldBe "stop"
        reason(raw.body(userMessage("Count from 1 to 100"), "max_tokens" -> ujson.Num(3))) shouldBe "length"
      }
    }
  }

  it should s"probe: $MaxCompletionTokens" in {
    report.probe(
      MaxCompletionTokens,
      "none needed: only decides whether a later change can switch to max_completion_tokens"
    ) {
      requireAccount()
      withRaw { raw =>
        val json =
          ok(raw, raw.chat(raw.body(userMessage("Count from 1 to 100"), "max_completion_tokens" -> ujson.Num(5))))
        json("usage")("completion_tokens").num should be <= 5.0
      }
    }
  }

  it should s"probe: $ToolCallShape" in {
    report.probe(ToolCallShape, "WatsonXClient.parseToolCall / requestArguments: the id and the arguments encoding") {
      requireAccount()
      withRaw { raw =>
        val json =
          ok(raw, raw.chat(raw.body(userMessage(askText), "tools" -> addToolJson, "max_tokens" -> ujson.Num(100))))
        val calls = Try(json("choices")(0)("message")("tool_calls").arr)
          .getOrElse(fail(s"no tool call in: ${raw.scrub(json.render())}"))
        calls should not be empty
        val call = calls.head
        withClue("the server sent no `id`, so WatsonXClient is inventing ids: ")(call("id").str should not be empty)
        withClue("`arguments` is not a JSON string: ")(call("function")("arguments") shouldBe a[ujson.Str])
        ujson.read(call("function")("arguments").str) shouldBe a[ujson.Obj]
      }
    }
  }

  it should s"probe: $ToolChoiceAuto" in {
    report.probe(ToolChoiceAuto, "WatsonXClient.createRequestBody: stop sending tool_choice_option") {
      requireAccount()
      withRaw { raw =>
        ok(
          raw,
          raw.chat(
            raw.body(
              userMessage("Say hi"),
              "tools"              -> addToolJson,
              "tool_choice_option" -> ujson.Str("auto"),
              "max_tokens"         -> ujson.Num(20)
            )
          )
        )
      }
    }
  }

  it should s"probe: $StrictAccepted" in {
    report.probe(StrictAccepted, "none needed: the client never sends strict; only decides what the docs say") {
      requireAccount()
      withRaw { raw =>
        val withStrict = ujson.read(addToolJson.render())
        withStrict(0)("function")("strict") = false
        ok(raw, raw.chat(raw.body(userMessage("Say hi"), "tools" -> withStrict, "max_tokens" -> ujson.Num(20))))
      }
    }
  }

  it should s"probe: $ToolRoundTrip" in {
    report.probe(
      ToolRoundTrip,
      "WatsonXClient.encodeMessages: the assistant tool-call turn (content omitted) and the tool message"
    ) {
      requireAccount()
      withClient() { client =>
        val first = client.complete(askToAdd, CompletionOptions().withTools(Seq(addTool)).withMaxTokens(100)).value
        withClue("the model did not call the tool: set WATSONX_MODEL to a model that supports tool calling. ") {
          first.toolCalls should not be empty
        }
        val call     = first.toolCalls.head
        val followUp = Conversation(askToAdd.messages ++ Seq(first.message, ToolMessage("46", call.id)))
        val answer   = client.complete(followUp, CompletionOptions().withTools(Seq(addTool)).withMaxTokens(100)).value
        answer.content should not be empty
      }
    }
  }

  it should s"probe: $StreamShape" in {
    report.probe(StreamShape, "WatsonXClient.readStream: stream ending and usage") {
      requireAccount()
      val text = scala.collection.mutable.ListBuffer.empty[String]
      val result = withClient()(
        _.streamComplete(say("Say hi in one word"), CompletionOptions().withMaxTokens(20), _.content.foreach(text += _))
      )
      val completion = result.value
      text.mkString should not be empty
      completion.usage should not be empty
    }
  }

  it should s"probe: $StreamUsageChunk" in {
    report.probe(StreamUsageChunk, "none needed: WatsonXClient reads usage from any chunk; only correct the docs") {
      requireAccount()
      withRaw { raw =>
        val (status, events) = raw.stream(raw.body(userMessage("Say hi in one word"), "max_tokens" -> ujson.Num(20)))
        status shouldBe 200
        withClue(s"usage came with choices, or not at all: ${raw.scrub(events.map(_.render()).mkString("\n"))}\n") {
          events.exists(e => e.obj.get("usage").exists(!_.isNull) && e("choices").arr.isEmpty) shouldBe true
        }
      }
    }
  }

  it should s"probe: $BadKey" in {
    report.probe(BadKey, "WatsonXClient.fetchToken / scrub: the error for a rejected key") {
      requireAccount()
      val wrong = "wrong-key-for-the-probe-0000"
      val error = withClient(wrong)(_.complete(say("hi"), CompletionOptions().withMaxTokens(5))).swap.value
      error shouldBe an[AuthenticationError]
      (error.message should not).include(wrong)
    }
  }

  it should s"probe: $BadRequest" in {
    report.probe(BadRequest, "HttpErrorMapper or WatsonXClient: the status a bad request gets, and its mapping") {
      requireAccount()
      withRaw { raw =>
        val response = raw.chat(raw.body(ujson.Arr(), "max_tokens" -> ujson.Num(5)))
        withClue(s"HTTP ${response.statusCode}: ${raw.scrub(response.body)}\n")(response.statusCode shouldBe 400)
      }
      val error = withClient()(_.complete(say("hi"), CompletionOptions().withMaxTokens(-5))).swap.value
      withClue(s"the client reported ${error.getClass.getSimpleName}: ${error.message}\n") {
        error shouldBe a[ValidationError]
      }
    }
  }
}
