package org.llm4s.agent.graph.tool

import org.llm4s.agent.graph._
import org.llm4s.error.ValidationError
import org.llm4s.toolapi.{ Schema, SchemaDefinition, ToolBuilder, ToolFunction }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

object AgentToolFixtures {
  final case class Search(query: String) derives ReadWriter
  final case class Confirm(prompt: String) derives ReadWriter
  final case class Reply(ok: Boolean) derives ReadWriter

  val searchSchema: SchemaDefinition[Search] =
    Schema.`object`[Search]("Search").withRequiredField("query", Schema.string("The query"))
}

class AgentToolContractSpec extends AnyFlatSpec with Matchers with EitherValues {
  import AgentToolFixtures._

  private def spec(name: String): AgentToolSpec[Search] = AgentToolSpec[Search](name, "Searches", searchSchema)

  private val context = ToolContext(
    new RunContext(
      RunConfig(),
      RunPosition(ThreadId("t"), RunId("r"), "", TaskId("task"), NodeId("node"), 0),
      NodeEventSink.none
    ),
    "call-1",
    ThreadState.empty(Map.empty),
    approved = false
  )

  "AgentToolSpec.apply" should "accept a valid name" in {
    spec("search_web-2").name shouldBe "search_web-2"
    spec("a" * 64).name shouldBe "a" * 64
  }

  it should "throw for an empty name, a 65-character name and a name with a space" in {
    an[IllegalArgumentException] should be thrownBy spec("")
    an[IllegalArgumentException] should be thrownBy spec("a" * 65)
    an[IllegalArgumentException] should be thrownBy spec("search web")
  }

  it should "default to no extra validation and no question" in {
    val s = spec("search")
    s.question shouldBe None
    s.validateDecoded(Search("x")) shouldBe Right(())
  }

  "providerSchema" should "be the schema rendered strict" in {
    spec("search").providerSchema shouldBe searchSchema.toJsonSchema(true)
  }

  "toolDefinition" should "have the shape of ToolFunction.toOpenAITool(true)" in {
    val function = ToolFunction[Search, String]("search", "Searches", searchSchema, _ => Right("ok"))
    spec("search").toolDefinition shouldBe function.toOpenAITool(true)
  }

  it should "hand out a copy of the schema, so mutating a definition leaves providerSchema intact" in {
    val s          = spec("search")
    val definition = s.toolDefinition
    definition("function")("parameters").obj.remove("additionalProperties")
    definition("function")("parameters")("properties").obj.remove("query")
    s.providerSchema shouldBe searchSchema.toJsonSchema(true)
    s.toolDefinition shouldBe ToolFunction[Search, String]("search", "Searches", searchSchema, _ => Right("ok"))
      .toOpenAITool(true)
  }

  "withValidation" should "store the check" in {
    val s = spec("search").withValidation(a => Either.cond(a.query.nonEmpty, (), ValidationError("query", "empty")))
    s.validateDecoded(Search("x")) shouldBe Right(())
    s.validateDecoded(Search("")).left.value.message should include("empty")
    s.name shouldBe "search"
  }

  "AgentTool.apply" should "run its function and declare its writes" in {
    val key  = StateKey.replace[Int]("count", 0)
    val tool = AgentTool(spec("search"), Set(key))((args, _) => ToolOutcome.Success(ujson.Str(args.query)))
    tool.writes shouldBe Set(key)
    tool.execute(Search("hi"), context) shouldBe ToolOutcome.Success(ujson.Str("hi"))
  }

  it should "refuse answers when it does not ask" in {
    val tool = AgentTool(spec("search"))((_, _) => ToolOutcome.Error("x"))
    tool.writes shouldBe Set.empty
    tool.resumeErased(Search("hi"), Confirm("?"), Reply(true), context) shouldBe
      ToolOutcome.Error("tool 'search' does not take answers")
  }

  "AgentTool.Asking" should "carry its question codecs on its spec and resume typed" in {
    val tool = new AgentTool.Asking[Search, Confirm, Reply](spec("search")) {
      def execute(args: Search, context: ToolContext): ToolOutcome = ask(Confirm(args.query))
      def resume(args: Search, question: Confirm, answer: Reply, context: ToolContext): ToolOutcome =
        ToolOutcome.Success(ujson.Str(s"${question.prompt}:${answer.ok}"))
    }
    tool.spec.question shouldBe Some(ToolQuestion(summon[ReadWriter[Confirm]], summon[ReadWriter[Reply]]))
    tool.spec.name shouldBe "search"
    tool.execute(Search("go"), context) shouldBe ToolOutcome.Ask(Confirm("go"))
    tool.resumeErased(Search("go"), Confirm("go"), Reply(true), context) shouldBe
      ToolOutcome.Success(ujson.Str("go:true"))
  }

  "AgentTool.fromToolFunction" should "use the function's name, description and schema, strict" in {
    val schema = Schema.`object`[Map[String, Any]]("Echo").withRequiredField("message", Schema.string("Message"))
    val function = ToolBuilder[Map[String, Any], Search]("echo", "Echoes", schema)
      .withHandler(extractor => extractor.getString("message").map(Search(_)))
      .buildSafe()
      .value
    val tool = AgentTool.fromToolFunction(function)
    tool.spec.name shouldBe "echo"
    tool.spec.description shouldBe "Echoes"
    tool.spec.providerSchema shouldBe schema.toJsonSchema(true)
    tool.spec.toolDefinition shouldBe function.toOpenAITool(true)
    tool.writes shouldBe Set.empty
    tool.execute(ujson.Obj("message" -> "hi"), context) shouldBe ToolOutcome.Success(ujson.Obj("query" -> "hi"))
    tool.execute(ujson.Obj(), context) match {
      case ToolOutcome.Error(message) => message should include("echo")
      case other                      => fail(s"expected an error, got $other")
    }
  }

  it should "not throw for a function whose name is invalid, leaving ToolSet.of to refuse it" in {
    val function = ToolFunction[Map[String, Any], String]("bad name", "d", Schema.`object`("o"), _ => Right("x"))
    AgentTool.fromToolFunction(function).spec.name shouldBe "bad name"
  }

  "GraphError.ToolFailed" should "name the tool, the call and the cause" in {
    val error = GraphError.ToolFailed("search", "call-1", ValidationError("x", "boom"))
    error.message should include("search")
    error.message should include("call-1")
    error.message should include("boom")
    error shouldBe a[org.llm4s.error.NonRecoverableError]
  }
}
