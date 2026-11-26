package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.SpecTools.{ call, calling }
import org.llm4s.agent.graph.{ GraphError, GraphRuntime, InMemoryCheckpointer, ThreadId }
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.graph.tool.{ ToolContent, ToolOutcome }
import org.llm4s.agent.guardrails.OutputGuardrail
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model._
import org.llm4s.testutil.TestTimeLimit
import org.llm4s.toolapi.{ AnyOfSchema, ArraySchema, ObjectSchema, PropertyDefinition, Schema, StringSchema }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

import java.util.concurrent.CopyOnWriteArrayList

/**
 * A union as an agent's answer (#2953): `withOutput` takes a schema whose root is an `anyOf` of objects. The model is
 * asked for the union inside an envelope object, `{"answer": <one alternative>}`, because a strict response format
 * needs an object at its root; the envelope is removed before the answer is checked, stored, guarded or decoded.
 */
class AgentUnionOutputSpec extends AnyFlatSpec with TestTimeLimit with Matchers {
  import AgentUnionOutputSpec._

  private def answers(texts: String*): ScriptedLLMClient = ScriptedLLMClient.of(texts.map(CompletionFixture.simple)*)

  private def withUnion(client: ScriptedLLMClient): AgentBuilder =
    Agent.builder("dm", client).withOutput(turnSchema)

  private def enveloped(value: String): String = s"""{"answer":$value}"""

  private val envelope: ujson.Value = ujson.Obj(
    "type"                 -> "object",
    "properties"           -> ujson.Obj("answer" -> turnSchema.toJsonSchema(strict = true)),
    "required"             -> ujson.Arr("answer"),
    "additionalProperties" -> false
  )

  "withOutput" should "accept a schema whose root is an anyOf of objects" in {
    withUnion(answers()).build().isRight shouldBe true
  }

  it should "send the union inside an envelope object as the response format, each alternative as strict as it was" in {
    val client = answers(enveloped(scene))
    built(withUnion(client)).run("look").value

    client.calls.map(_._2.responseFormat) shouldBe Vector(Some(ResponseFormat.JsonSchema(envelope)))
    val alternatives = envelope("properties")("answer")("anyOf").arr
    alternatives.map(_("additionalProperties")) shouldBe Seq(ujson.False, ujson.False)
    alternatives.map(_("required").arr.map(_.str).toSet) shouldBe
      Seq(Set("$type", "location", "exits"), Set("$type", "text"))
  }

  it should "state the envelope's schema in the system prompt, the one the response format carries" in {
    val client = answers(enveloped(scene))
    built(withUnion(client)).run("look").value

    client.sent.head.head.content should include(ujson.write(envelope))
  }

  "An answer in the envelope" should "decode as each alternative, and be stored without the envelope" in {
    val asScene = built(withUnion(answers(enveloped(scene)))).run("look").value
    asScene.answer shouldBe Some(scene)
    asScene.messages.last shouldBe AssistantMessage(scene)
    asScene.decodeOutput[Turn] shouldBe Right(Scene("cellar", List("north", "up")))

    val asReply = built(withUnion(answers(enveloped(reply)))).run("wave").value
    asReply.answer shouldBe Some(reply)
    asReply.decodeOutput[Turn] shouldBe Right(Reply("Nothing happens."))
  }

  it should "lose the fence or prose the model put round it too" in {
    val result = built(withUnion(answers(s"Here:\n```json\n${enveloped(reply)}\n```"))).run("wave").value
    result.messages.last shouldBe AssistantMessage(reply)
  }

  it should "be what a checkpointed thread holds, and what a later turn sends back to the model" in {
    val store  = new InMemoryCheckpointer()
    val client = answers(enveloped(scene), enveloped(reply))
    val agent  = built(withUnion(client).withRuntime(new GraphRuntime(store)))
    val thread = ThreadId("union")

    agent.run(thread, "look").value
    agent.run(thread, "wave").value.messages shouldBe Vector(
      UserMessage("look"),
      AssistantMessage(scene),
      UserMessage("wave"),
      AssistantMessage(reply)
    )
    client.sent(1).collect { case a: AssistantMessage => a.content } shouldBe Vector(scene)
    val stored =
      ujson.write(org.llm4s.agent.graph.Checkpoint.toJson(store.latest(thread).toOption.flatten.get.checkpoint))
    (stored should not).include("\\\"answer\\\"")
  }

  // an answer is stored only once it is accepted, so a run stopped mid-answer left nothing of the envelope behind
  it should "be stored without the envelope by a recover, after the corrective call of the first run failed" in {
    val wrong = enveloped("""{"$type":"song"}""")
    val client = new ScriptedLLMClient(
      Right(CompletionFixture.simple(wrong)),
      Left(ValidationError("provider", "unavailable")),
      Right(CompletionFixture.simple(wrong)),
      Right(CompletionFixture.simple(enveloped(reply)))
    )
    val store  = new InMemoryCheckpointer()
    val agent  = built(withUnion(client).withRuntime(new GraphRuntime(store)))
    val thread = ThreadId("union-recovered")

    cause(agent.run(thread, "wave").error) shouldBe ValidationError("provider", "unavailable")
    val recovered = agent.recover(thread).value
    recovered.messages shouldBe Vector(UserMessage("wave"), AssistantMessage(reply))
    recovered.decodeOutput[Turn] shouldBe Right(Reply("Nothing happens."))
  }

  "An answer without the envelope" should "be accepted when it is one of the alternatives itself" in {
    val client = answers(scene)
    val result = built(withUnion(client)).run("look").value

    client.callCount shouldBe 1
    result.answer shouldBe Some(scene)
    result.decodeOutput[Turn] shouldBe Right(Scene("cellar", List("north", "up")))
  }

  it should "be accepted from a call that offered tools, which carries no response format" in {
    val echo   = SpecTools.tool("echo")((a, _) => ToolOutcome.success(ToolContent.Json(ujson.Str(a.text))))
    val client = new ScriptedLLMClient(Right(calling(call("c1", "echo"))), Right(CompletionFixture.simple(reply)))
    val result = built(withUnion(client).withToolSet(SpecTools.set(echo))).run("wave").value

    client.calls.map(_._2.responseFormat) shouldBe Vector(None, None)
    result.decodeOutput[Turn] shouldBe Right(Reply("Nothing happens."))
  }

  "An answer that matches no alternative" should "be retried once with its problems, under the envelope's path" in {
    val wrong  = enveloped("""{"$type":"scene","location":"cellar"}""")
    val client = answers(wrong, enveloped(scene))
    val result = built(withUnion(client)).run("look").value

    result.decodeOutput[Turn] shouldBe Right(Scene("cellar", List("north", "up")))
    client.callCount shouldBe 2
    val retry = client.sent(1)
    retry(retry.size - 2) shouldBe AssistantMessage(wrong)
    retry.last.content should include("$.answer")
    retry.last.content should include("matches none of the 2 alternatives")
    retry.last.content should include("exits")
    result.messages shouldBe Vector(UserMessage("look"), AssistantMessage(scene))
  }

  it should "be reported from the answer's own root when it came without the envelope" in {
    val client = answers("""{"$type":"reply"}""", reply)
    built(withUnion(client)).run("wave").value.decodeOutput[Turn] shouldBe Right(Reply("Nothing happens."))

    client.sent(1).last.content should include("$: ")
    client.sent(1).last.content should include("text")
    (client.sent(1).last.content should not).include("$.answer")
  }

  it should "end the turn OutputInvalid when the second answer matches none either" in {
    val wrong = enveloped("""{"$type":"song"}""")
    val error = built(withUnion(answers(wrong, wrong))).run("sing").error

    cause(error) match
      case GraphError.OutputInvalid(problems) => problems.mkString should include("$.answer")
      case other                              => fail(s"expected OutputInvalid, got $other")
  }

  it should "be an envelope holding anything but the answer" in {
    val client = answers(s"""{"answer":$reply,"note":"extra"}""", enveloped(reply))
    built(withUnion(client)).run("wave").value.answer shouldBe Some(reply)

    client.callCount shouldBe 2
    client.sent(1).last.content should include("note")
  }

  "withOutputCheck" should "be given the decoded alternative, not the envelope, and share the retry" in {
    val seen   = new CopyOnWriteArrayList[Turn]()
    val client = answers(enveloped("""{"$type":"scene","location":"cellar","exits":[]}"""), enveloped(scene))
    val agent = withUnion(client).withOutputCheck[Turn] { turn =>
      seen.add(turn)
      turn match
        case Scene(_, exits) if exits.isEmpty => Seq("$.exits: a scene needs a way out")
        case _                                => Nil
    }
    built(agent).run("look").value.decodeOutput[Turn] shouldBe Right(Scene("cellar", List("north", "up")))

    seen.toArray.toVector shouldBe Vector(Scene("cellar", Nil), Scene("cellar", List("north", "up")))
    client.sent(1).last.content should include("a scene needs a way out")
  }

  "withOutputJsonCheck" should "be given the alternative's JSON, not the envelope" in {
    val seen = new CopyOnWriteArrayList[String]()
    val agent = withUnion(answers(enveloped(reply))).withOutputJsonCheck { json =>
      seen.add(ujson.write(json))
      Nil
    }
    built(agent).run("wave").value
    seen.toArray.toVector shouldBe Vector(reply)
  }

  "An output guardrail" should "see the alternative's JSON, not the envelope" in {
    val seen = new CopyOnWriteArrayList[String]()
    val watch: OutputGuardrail = new OutputGuardrail {
      val name: String = "Watch"
      def validate(value: String): Result[String] =
        seen.add(value)
        Right(value)
    }
    val result =
      built(withUnion(answers(enveloped(scene))).withMiddleware(new GuardrailMiddleware(Nil, Seq(watch))))
        .run("look")
        .value

    seen.toArray.toVector shouldBe Vector(scene)
    result.answer shouldBe Some(scene)
  }

  "The envelope's property" should "take another name when an alternative has an `answer` of its own" in {
    val client = answers("""{"final_answer":{"answer":"42"}}""")
    val result = built(Agent.builder("oracle", client).withOutput(oracleSchema)).run("why").value

    result.answer shouldBe Some("""{"answer":"42"}""")
    result.decodeOutput[Oracle] shouldBe Right(Verdict("42"))
    val sent = client.calls.head._2.responseFormat.collect { case f: ResponseFormat.JsonSchema => f.schema }.get
    sent("required").arr.map(_.str) shouldBe Seq("final_answer")
    sent("properties").obj.keySet shouldBe Set("final_answer")
  }

  it should "leave such an alternative's own answer alone when it comes without the envelope" in {
    val result =
      built(Agent.builder("oracle", answers("""{"answer":"42"}""")).withOutput(oracleSchema)).run("why").value
    result.decodeOutput[Oracle] shouldBe Right(Verdict("42"))
  }

  "build" should "refuse an anyOf with an alternative that is not an object, saying what to do instead" in {
    val mixed   = Schema.anyOf[Turn]("A turn", sceneSchema, StringSchema("a kind", enumValues = Some(Seq("idle"))))
    val message = Agent.builder("dm", answers()).withOutput(mixed).build().left.map(_.message).swap.getOrElse("")

    message should include("response format refused")
    message should include("anyOf[1]")
    message should include("property of an object schema")
  }

  it should "refuse a root array or string with the same advice" in {
    val refusals = Seq(
      Agent.builder("dm", answers()).withOutput(StringSchema("a name")).build(),
      Agent.builder("dm", answers()).withOutput(ArraySchema("names", StringSchema("a name"))).build()
    )
    refusals.foreach { refused =>
      val message = refused.left.map(_.message).swap.getOrElse("")
      message should include("must be \"object\"")
      message should include("property of an object schema")
    }
  }

  "The graph version" should "change with the union, as with any response format" in {
    val client                   = answers()
    def version(b: AgentBuilder) = built(b).loop.graph.version

    version(withUnion(client)) should not be version(Agent.builder("dm", client))
    version(withUnion(client)) shouldBe version(withUnion(client))
  }
}

object AgentUnionOutputSpec {
  sealed trait Turn derives ReadWriter
  @upickle.implicits.key("scene")
  final case class Scene(location: String, exits: List[String]) extends Turn
  @upickle.implicits.key("reply")
  final case class Reply(text: String) extends Turn

  private def tag(name: String): PropertyDefinition[String] =
    PropertyDefinition("$type", StringSchema("which kind of turn", enumValues = Some(Seq(name))))

  val sceneSchema: ObjectSchema[Scene] = ObjectSchema[Scene](
    "A new scene",
    Seq(
      tag("scene"),
      PropertyDefinition("location", StringSchema("where the player is")),
      PropertyDefinition("exits", ArraySchema("the ways out", StringSchema("a direction")))
    )
  )

  val replySchema: ObjectSchema[Reply] =
    ObjectSchema[Reply]("What happened", Seq(tag("reply"), PropertyDefinition("text", StringSchema("the narration"))))

  val turnSchema: AnyOfSchema[Turn] = Schema.anyOf[Turn]("One turn of the game", sceneSchema, replySchema)

  val scene: String = """{"$type":"scene","location":"cellar","exits":["north","up"]}"""
  val reply: String = """{"$type":"reply","text":"Nothing happens."}"""

  /** A union one of whose alternatives has a property called `answer`, the envelope's usual name. */
  sealed trait Oracle
  final case class Verdict(answer: String) extends Oracle
  final case class Doubt(question: String) extends Oracle
  given ReadWriter[Oracle] = upickle.default
    .readwriter[ujson.Value]
    .bimap[Oracle](
      {
        case Verdict(answer) => ujson.Obj("answer" -> answer)
        case Doubt(question) => ujson.Obj("question" -> question)
      },
      json => json.obj.get("answer").map(a => Verdict(a.str)).getOrElse(Doubt(json("question").str))
    )

  val oracleSchema: AnyOfSchema[Oracle] = Schema.anyOf[Oracle](
    "An oracle's reply",
    ObjectSchema[Verdict]("A verdict", Seq(PropertyDefinition("answer", StringSchema("the verdict")))),
    ObjectSchema[Doubt]("A doubt", Seq(PropertyDefinition("question", StringSchema("what is unclear"))))
  )
}
